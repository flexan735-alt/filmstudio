package dev.filmstudio.rec;

import dev.filmstudio.FilmStudio;
import dev.filmstudio.data.ActorSpec;
import dev.filmstudio.data.Film;
import dev.filmstudio.data.FilmStore;
import dev.filmstudio.data.Recording;
import dev.filmstudio.data.Recording.Ev;
import dev.filmstudio.data.Recording.Frame;
import dev.filmstudio.data.Recording.MobTrack;
import dev.filmstudio.model.ModelActorEntity;
import dev.filmstudio.net.Net;
import dev.filmstudio.play.ActorPlayer;
import dev.filmstudio.play.Playback;
import dev.filmstudio.play.PlaybackSession;
import dev.filmstudio.util.BlockStr;
import dev.filmstudio.util.ItemJson;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.util.*;

/**
 * Records one player for one actor. Runs on the server so it captures exactly what the server sees
 * (positions, hits, block changes), which is also what playback has to reproduce.
 */
public final class Recorder {
	public static final Map<UUID, Recorder> ACTIVE = new HashMap<>();
	private static final EquipmentSlot[] SLOTS = {
		EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	public final ServerPlayer player;
	public final MinecraftServer server;
	public final Film film;
	public final ActorSpec actor;
	public final Recording rec = new Recording();
	public boolean live = false;
	public PlaybackSession companion;

	private int countdown;
	private Frame prev;
	private boolean wasUsing;
	private int useHand;
	private int useTicks;
	private final ItemStack[] lastEquip = new ItemStack[SLOTS.length];
	private final Map<Integer, MobTrack> open = new HashMap<>();
	private final List<MobTrack> all = new ArrayList<>();
	private int nextMobId = 1;
	private final List<Pending> pending = new ArrayList<>();

	private record Pending(Map<BlockPos, net.minecraft.world.level.block.state.BlockState> snap) {}

	public Recorder(ServerPlayer player, Film film, ActorSpec actor) {
		this.player = player;
		this.server = player.level().getServer();
		this.film = film;
		this.actor = actor;
		this.countdown = Math.max(0, film.countdown) * 20;
		Arrays.fill(lastEquip, ItemStack.EMPTY);
	}

	public int tickIndex() { return rec.frames.size(); }

	// ------------------------------------------------------------------ lifecycle
	public static Recorder start(ServerPlayer p, Film film, ActorSpec actor) {
		stop(p, false);
		Recorder r = new Recorder(p, film, actor);
		ACTIVE.put(p.getUUID(), r);
		if (r.countdown == 0) r.begin();
		return r;
	}

	public static void stop(ServerPlayer p, boolean save) {
		Recorder r = ACTIVE.remove(p.getUUID());
		if (r == null) return;
		r.finish(save);
	}

	private void begin() {
		live = true;
		rec.dimension = player.level().dimension().identifier().toString();
		rec.ox = player.getX(); rec.oy = player.getY(); rec.oz = player.getZ(); rec.oyaw = player.getYRot();
		if (companion != null) companion.release();
		player.displayClientMessage(Component.literal("\u00a7c\u25cf Recording \u00a7f" + actor.name), true);
	}

	private void finish(boolean save) {
		if (companion != null) { Playback.stop(companion); companion = null; }
		Net.sendIdle(player);
		if (!live || !save || rec.frames.isEmpty()) {
			player.displayClientMessage(Component.literal(live ? "\u00a7eRecording discarded." : "\u00a7eRecording cancelled."), true);
			return;
		}
		for (MobTrack m : all) {
			boolean keep = switch (film.mobMode) {
				case "nearby" -> true;
				case "interacted" -> m.interacted;
				default -> false;
			};
			if (keep && m.posD.size() > 0) rec.mobs.add(m);
		}
		try {
			FilmStore.saveRecording(film.id, actor.id, rec);
			FilmStore.refreshRecInfo(film.id, actor);
			FilmStore.saveFilm(film);
			Net.sendFilms(player);
			player.displayClientMessage(Component.literal("\u00a7aSaved recording for \u00a7f" + actor.name + "\u00a7a (" + rec.length() / 20 + "s, "
				+ rec.events.size() + " events, " + rec.mobs.size() + " mobs)"), true);
		} catch (Exception e) {
			FilmStudio.LOGGER.error("Could not save recording", e);
			player.displayClientMessage(Component.literal("\u00a7cCould not save recording: " + e.getMessage()), true);
		}
	}

	// ------------------------------------------------------------------ per tick (END of server tick)
	public static void tickAll() {
		if (ACTIVE.isEmpty()) return;
		for (Recorder r : new ArrayList<>(ACTIVE.values())) {
			if (r.player.isRemoved()) { ACTIVE.remove(r.player.getUUID()); r.finish(true); continue; }
			r.tick();
		}
	}

	private void tick() {
		if (!live) {
			if (countdown > 0) {
				if (countdown % 20 == 0) {
					player.displayClientMessage(Component.literal("\u00a7eRecording in \u00a7f" + (countdown / 20) + "\u00a7e ... get ready"), true);
				}
				countdown--;
				if (countdown % 4 == 0) Net.sendRecStatus(player, actor.id, 0, false, countdown);
				if (countdown == 0) begin();
			}
			return;
		}
		int t = tickIndex();
		checkPending();
		captureEquipment(t);
		captureUsing(t);
		trackMobs(t);
		captureFrame();
		if (t % 4 == 0) Net.sendRecStatus(player, actor.id, t, true, 0);
		if (t % 20 == 0) {
			player.displayClientMessage(Component.literal("\u00a7c\u25cf REC \u00a7f" + actor.name + " \u00a77" + (t / 20) + "s"), true);
		}
	}

	private void captureFrame() {
		Frame f = new Frame();
		f.x = player.getX(); f.y = player.getY(); f.z = player.getZ();
		f.yaw = player.getYRot(); f.pitch = player.getXRot(); f.head = player.getYHeadRot();
		int fl = 0;
		if (player.isShiftKeyDown()) fl |= Frame.SNEAK;
		if (player.isSprinting()) fl |= Frame.SPRINT;
		if (player.getAbilities().flying) fl |= Frame.FLY;
		if (player.isFallFlying()) fl |= Frame.GLIDE;
		if (player.onGround()) fl |= Frame.GROUND;
		if (player.isSwimming()) fl |= Frame.SWIM;
		else if (player.getPose() == Pose.SWIMMING) fl |= Frame.CRAWL;
		if (player.isUsingItem()) fl |= Frame.USING;
		if (player.isSleeping()) fl |= Frame.SLEEP;
		if (player.isInWater()) fl |= Frame.WATER;

		// Infer the player's key presses from how they moved relative to where they were looking.
		if (prev != null) {
			double dx = f.x - prev.x, dy = f.y - prev.y, dz = f.z - prev.z;
			double yr = Math.toRadians(f.yaw);
			double fwd = -dx * Math.sin(yr) + dz * Math.cos(yr);
			double left = dx * Math.cos(yr) + dz * Math.sin(yr);
			double thr = 0.02;
			if (fwd > thr) fl |= Frame.FWD; else if (fwd < -thr) fl |= Frame.BACK;
			if (left > thr) fl |= Frame.LEFT; else if (left < -thr) fl |= Frame.RIGHT;
			boolean prevGround = prev.has(Frame.GROUND);
			if (prevGround && !player.onGround() && dy > 0.2 && (fl & Frame.FLY) == 0) fl |= Frame.JUMP;
			if ((fl & Frame.WATER) != 0 && dy > 0.01 && !player.onGround()) fl |= Frame.JUMP;
			if ((fl & Frame.FLY) != 0) {
				if (dy > 0.05) fl |= Frame.UP; else if (dy < -0.05) fl |= Frame.DOWN;
			}
		}
		f.flags = fl;
		rec.frames.add(f);
		prev = f;
	}

	private void captureEquipment(int t) {
		for (int i = 0; i < SLOTS.length; i++) {
			ItemStack cur = player.getItemBySlot(SLOTS[i]);
			ItemStack old = lastEquip[i];
			boolean same = (cur.isEmpty() && old.isEmpty()) || (!cur.isEmpty() && !old.isEmpty() && ItemStack.isSameItemSameComponents(cur, old));
			if (!same) {
				Ev e = new Ev(t, Ev.EQUIP);
				e.a = SLOTS[i].getName();
				e.b = ItemJson.encode(server, cur);
				e.i1 = cur.getCount();
				e.i2 = player.getAbilities().instabuild ? 1 : 0;
				rec.events.add(e);
			}
			lastEquip[i] = cur.copy();
		}
	}

	private void captureUsing(int t) {
		boolean using = player.isUsingItem();
		if (using) {
			if (!wasUsing) { useTicks = 0; useHand = player.getUsedItemHand().ordinal(); }
			useTicks++;
		} else if (wasUsing) {
			ItemStack st = player.getItemInHand(useHand == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
			int dur = st.isEmpty() ? 0 : st.getUseDuration(player);
			// A use that ran its full length finishes by itself on playback; only record early releases.
			if (st.isEmpty() || useTicks < dur) {
				Ev e = new Ev(t, Ev.USE_RELEASE);
				e.i1 = useHand; e.i2 = useTicks;
				rec.events.add(e);
			}
		}
		wasUsing = using;
	}

	// ------------------------------------------------------------------ mobs
	private void trackMobs(int t) {
		if ("none".equals(film.mobMode)) return;
		ServerLevel level = (ServerLevel) player.level();
		AABB box = player.getBoundingBox().inflate(Math.max(4, film.mobRadius));
		List<Mob> mobs = level.getEntitiesOfClass(Mob.class, box,
			m -> m.isAlive() && !(m instanceof ModelActorEntity) && !m.getTags().contains(Playback.PUPPET_TAG));
		Set<Integer> seen = new HashSet<>();
		for (Mob m : mobs) {
			if (open.size() >= 48 && !open.containsKey(m.getId())) continue;
			MobTrack tr = open.get(m.getId());
			if (tr == null) tr = newTrack(m, t);
			seen.add(m.getId());
			tr.posD.add(new double[]{m.getX(), m.getY(), m.getZ()});
			tr.pos.add(new float[]{m.getYRot(), m.getXRot(), m.getYHeadRot()});
		}
		open.keySet().removeIf(id -> !seen.contains(id));
	}

	private MobTrack newTrack(Mob m, int t) {
		MobTrack tr = new MobTrack();
		tr.id = nextMobId++;
		tr.uuid = m.getUUID().toString();
		tr.entityType = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString();
		tr.customName = m.getCustomName() == null ? "" : m.getCustomName().getString();
		tr.baby = m.isBaby();
		if (m instanceof Sheep s) tr.variant = s.getColor().getName();
		tr.startTick = t;
		open.put(m.getId(), tr);
		all.add(tr);
		return tr;
	}

	private MobTrack trackFor(Mob m) {
		MobTrack tr = open.get(m.getId());
		if (tr == null && !"none".equals(film.mobMode)) tr = newTrack(m, tickIndex());
		return tr;
	}

	// ------------------------------------------------------------------ event hooks (called from Fabric events / mixins)
	private Ev ev(int type) {
		Ev e = new Ev(tickIndex(), type);
		rec.events.add(e);
		return e;
	}

	public void onSwing(InteractionHand hand) {
		if (live) ev(Ev.SWING).i1 = hand.ordinal();
	}

	public void onUseItem(InteractionHand hand) {
		if (live) ev(Ev.USE_ITEM).i1 = hand.ordinal();
	}

	public void onUseBlock(InteractionHand hand, BlockHitResult hit) {
		if (!live) return;
		Ev e = ev(Ev.USE_BLOCK);
		e.i1 = hand.ordinal(); e.i2 = hit.getDirection().ordinal(); e.i3 = hit.isInside() ? 1 : 0;
		e.l = hit.getBlockPos().asLong();
		e.d1 = hit.getLocation().x; e.d2 = hit.getLocation().y; e.d3 = hit.getLocation().z;

		// Snapshot the blocks this click could change; compared again at the end of the tick.
		ServerLevel level = (ServerLevel) player.level();
		Map<BlockPos, net.minecraft.world.level.block.state.BlockState> snap = new HashMap<>();
		BlockPos p = hit.getBlockPos();
		BlockPos q = p.relative(hit.getDirection());
		for (BlockPos c : new BlockPos[]{p, q, q.above(), q.north(), q.south(), q.east(), q.west(), p.above()}) {
			snap.put(c, level.getBlockState(c));
		}
		pending.add(new Pending(snap));
	}

	public void onDigStart(BlockPos pos, int dir) {
		if (!live) return;
		Ev e = ev(Ev.DIG_START);
		e.l = pos.asLong(); e.i1 = dir;
	}

	public void onBreak(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		if (!live) return;
		Ev e = ev(Ev.BREAK);
		e.l = pos.asLong(); e.a = BlockStr.encode(state);
		Map<BlockPos, net.minecraft.world.level.block.state.BlockState> snap = new HashMap<>();
		snap.put(pos, state);
		pending.add(new Pending(snap));
	}

	/** Compare snapshots with the world and store real differences as block deltas. */
	private void checkPending() {
		if (pending.isEmpty()) return;
		ServerLevel level = (ServerLevel) player.level();
		int t = tickIndex();
		for (Pending p : pending) {
			for (var en : p.snap.entrySet()) {
				var now = level.getBlockState(en.getKey());
				if (now != en.getValue()) {
					Recording.Delta d = new Recording.Delta();
					d.tick = t; d.pos = en.getKey().asLong();
					d.before = BlockStr.encode(en.getValue()); d.after = BlockStr.encode(now);
					rec.deltas.add(d);
				}
			}
		}
		pending.clear();
	}

	public void onAttack(Entity target, boolean interact, InteractionHand hand) {
		if (!live) return;
		Ev e = ev(interact ? Ev.INTERACT_ENTITY : Ev.ATTACK);
		e.i3 = hand.ordinal();
		if (target instanceof ActorPlayer ap) {
			e.i1 = Ev.T_ACTOR; e.a = ap.actorId;
		} else if (target instanceof Mob m) {
			MobTrack tr = trackFor(m);
			if (tr != null) { tr.interacted = true; e.i1 = Ev.T_MOB; e.i2 = tr.id; }
			else e.i1 = Ev.T_OTHER;
			e.a = BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()).toString();
		} else {
			e.i1 = Ev.T_OTHER;
			e.a = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
		}
		e.d1 = target.getX(); e.d2 = target.getY(); e.d3 = target.getZ();
	}

	public void onDrop(ItemStack stack) {
		if (!live) return;
		Ev e = ev(Ev.DROP);
		e.a = ItemJson.encode(server, stack);
		e.i1 = stack.getCount();
	}

	public void onHurtSelf(float yaw) {
		if (live) ev(Ev.HURT).f1 = yaw;
	}

	public void onDeathSelf() {
		if (live) ev(Ev.DEATH);
	}

	public void onChat(String msg) {
		if (live) ev(Ev.CHAT).a = msg;
	}

	/** Something near us was hurt or died: remember it on the mob's track. */
	public void onEntityHurt(LivingEntity e) {
		if (!live || !(e instanceof Mob m)) return;
		MobTrack tr = open.get(m.getId());
		if (tr == null) return;
		tr.hurtTicks.add(tickIndex());
		if (m.isDeadOrDying()) tr.deathTick = tickIndex();
	}
}
