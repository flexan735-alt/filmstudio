package dev.filmstudio.play;

import com.mojang.authlib.GameProfile;
import dev.filmstudio.data.ActorSpec;
import dev.filmstudio.data.Recording;
import dev.filmstudio.data.Recording.Ev;
import dev.filmstudio.data.Recording.Frame;
import dev.filmstudio.data.Recording.MobTrack;
import dev.filmstudio.model.ModelActorEntity;
import dev.filmstudio.util.ItemJson;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Plays one recording on one body (a real server-side player, or a model actor).
 *
 * <p>The body is driven by the recorded <i>inputs</i> (forward/strafe/jump/sneak/sprint + look direction),
 * not by teleporting it, so gravity, collisions, water, knockback and holes in the floor all work like they do
 * for a real player. A gentle correction pulls the body back onto the recorded path; if it is thrown far off
 * (knock-back, a hole, an obstacle) it switches to a "recover" mode and walks back to where it should be.
 */
public final class ActorRuntime {
	public enum State { WAITING, RUNNING, DONE, DEAD }

	public final PlaybackSession session;
	public final ActorSpec spec;
	public final Recording rec;
	public final ServerLevel level;
	public final Map<Integer, MobPuppet> puppets = new LinkedHashMap<>();

	public LivingEntity body;
	public ServerPlayer player;
	public State state = State.WAITING;
	public int localTick;

	private int evIdx;
	private boolean recovering;
	private int recoverTicks;
	private int disturbedUntil = -1;
	private int deadTicks;
	private int closeContainerAt = -1;
	private BlockPos digPos;
	private int digStart, digEnd;
	private boolean creativeHand;
	private final ItemStack[] lastEquip = new ItemStack[6];
	private static final EquipmentSlot[] SLOTS = {
		EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	public ActorRuntime(PlaybackSession session, ActorSpec spec, Recording rec, ServerLevel level) {
		this.session = session; this.spec = spec; this.rec = rec; this.level = level;
		Arrays.fill(lastEquip, ItemStack.EMPTY);
		for (MobTrack t : rec.mobs) puppets.put(t.id, new MobPuppet(t));
	}

	private Vec3 off() { return session.offset; }

	private Vec3 pos(Frame f) { return new Vec3(f.x + off().x, f.y + off().y, f.z + off().z); }

	public int length() { return rec.length(); }

	// ------------------------------------------------------------------ spawn / despawn
	private void spawn(int lt) {
		Frame f = rec.frames.get(Math.max(0, Math.min(lt, rec.frames.size() - 1)));
		Vec3 p = pos(f);
		MinecraftServer server = level.getServer();
		boolean custom = "custom".equals(spec.modelType) && !spec.modelName.isEmpty() && dev.filmstudio.data.FilmStore.readModel(spec.modelName) != null;
		if (custom) {
			ModelActorEntity e = new ModelActorEntity(ModelActorEntity.TYPE, level);
			e.actorId = spec.id;
			e.setModelName(spec.modelName);
			e.damageMode = damageMode();
			e.setPos(p.x, p.y, p.z);
			e.setYRot(f.yaw); e.setXRot(f.pitch); e.setYHeadRot(f.head);
			e.addTag(Playback.PUPPET_TAG);
			e.addTag(Playback.SPAWNED_TAG);
			level.addFreshEntity(e);
			body = e;
		} else {
			GameProfile profile = session.profiles.get(spec.id);
			ActorPlayer ap = new ActorPlayer(server, level, profile, spec.id);
			ap.damageMode = damageMode();
			ap.setPos(p.x, p.y, p.z);
			ap.setYRot(f.yaw); ap.setXRot(f.pitch); ap.setYHeadRot(f.head); ap.setYBodyRot(f.yaw);
			server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ap));
			ap.getEntityData().set(dev.filmstudio.mixin.AvatarAccessor.filmstudioSkinParts(), (byte) 0x7F);
			level.addNewPlayer(ap);
			ap.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
			ap.getAbilities().mayfly = true;
			body = ap; player = ap;
			Playback.ACTOR_PLAYERS.add(ap);
		}
		applyAppearance();
		session.applyTeam(spec, body);
		state = State.RUNNING;
	}

	private int damageMode() {
		return switch (spec.damageMode) { case "normal" -> 0; case "invulnerable" -> 2; default -> 1; };
	}

	private void applyAppearance() {
		var scale = body.getAttribute(Attributes.SCALE);
		if (scale != null) scale.setBaseValue(Math.max(0.0625, Math.min(16.0, spec.scale)));
		body.setGlowingTag(spec.glow);
		body.setInvisible(spec.invisible);
		if (body instanceof ModelActorEntity) {
			body.setCustomName(net.minecraft.network.chat.Component.literal(spec.prefix + spec.name + spec.suffix));
			body.setCustomNameVisible(spec.showNametag);
		}
		// Apply whatever equipment the recording had at tick 0, plus overrides.
		for (Ev e : rec.events) {
			if (e.tick > 0) break;
			if (e.type == Ev.EQUIP) applyEquip(e);
		}
		for (var en : spec.equipOverride.entrySet()) {
			if (en.getValue() == null || en.getValue().isBlank()) continue;
			EquipmentSlot s = slot(en.getKey());
			ItemStack st = parseItem(en.getValue());
			if (s != null && !st.isEmpty()) setSlot(s, st);
		}
	}

	public void despawn() {
		for (MobPuppet p : puppets.values()) p.release();
		if (digPos != null) level.destroyBlockProgress(body == null ? 0 : body.getId(), digPos, -1);
		if (body != null && !body.isRemoved()) {
			if (body instanceof ActorPlayer ap) {
				level.getServer().getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(ap.getUUID())));
				Playback.ACTOR_PLAYERS.remove(ap);
			}
			session.removeFromTeam(body);
			body.remove(Entity.RemovalReason.DISCARDED);
		}
		body = null; player = null;
	}

	// ------------------------------------------------------------------ tick
	/** Called once per server tick with the session's global tick (the timeline position). */
	public void tick(int sessionTick) {
		localTick = sessionTick - spec.startDelay;
		if (localTick < 0 || state == State.DONE || state == State.DEAD) return;
		if (rec.frames.isEmpty()) { state = State.DONE; return; }
		if (body == null) {
			spawn(localTick);
			// Jumping into the middle of a film: catch the equipment/event pointer up.
			while (evIdx < rec.events.size() && rec.events.get(evIdx).tick < localTick) {
				Ev e = rec.events.get(evIdx++);
				if (e.type == Ev.EQUIP) applyEquip(e);
			}
		}
		if (body == null || body.isRemoved()) { state = State.DEAD; return; }

		if (body.isDeadOrDying()) {
			if (++deadTicks > 25) { despawn(); state = State.DEAD; }
			return;
		}
		if (localTick >= rec.length()) {
			for (MobPuppet p : puppets.values()) p.tick(level, localTick, off());
			state = State.DONE;
			return;
		}

		Frame f = rec.frames.get(localTick);
		applyLook(f);
		applyFlags(f);
		dispatchEvents();
		updateDig();
		movement(f);
		for (MobPuppet p : puppets.values()) p.tick(level, localTick, off());
		maintenance();
	}

	public void noteHurt(Entity e, int sessionTick) {
		if (e == body) disturbedUntil = localTick + 12;
		for (MobPuppet p : puppets.values()) if (p.entity == e) p.disturb(localTick + 12);
	}

	private void applyLook(Frame f) {
		body.setYRot(f.yaw); body.setXRot(f.pitch); body.setYHeadRot(f.head);
	}

	private void applyFlags(Frame f) {
		boolean crouch = f.has(Frame.SNEAK) || "crouch".equals(spec.forcedPose);
		body.setShiftKeyDown(crouch);
		body.setSprinting(f.has(Frame.SPRINT));
		if (player != null) {
			var ab = player.getAbilities();
			boolean fly = f.has(Frame.FLY);
			if (!ab.mayfly || ab.flying != fly) {
				ab.mayfly = true; ab.flying = fly;
				player.onUpdateAbilities();
			}
			boolean glide = f.has(Frame.GLIDE) || "glide".equals(spec.forcedPose);
			if (glide && !player.isFallFlying()) player.startFallFlying();
			else if (!glide && player.isFallFlying()) player.stopFallFlying();
			if ("crawl".equals(spec.forcedPose)) player.setPose(Pose.SWIMMING);
		}
	}

	// ------------------------------------------------------------------ movement / physics
	private void movement(Frame f) {
		Vec3 target = pos(f);
		boolean exact = "exact".equals(spec.physics) || f.has(Frame.FLY) || f.has(Frame.GLIDE) || f.has(Frame.SLEEP) || body.isPassenger();
		if (exact) {
			body.setPos(target.x, target.y, target.z);
			body.setDeltaMovement(Vec3.ZERO);
			body.setOnGround(f.has(Frame.GROUND));
			body.resetFallDistance();
			body.setZza(0); body.setXxa(0); body.setJumping(false);
			recovering = false;
			return;
		}

		Frame prev = rec.frames.get(Math.max(0, localTick - 1));
		Vec3 ref = pos(prev);
		Vec3 cur = body.position();
		double ex = ref.x - cur.x, ey = ref.y - cur.y, ez = ref.z - cur.z;
		double eh = Math.sqrt(ex * ex + ez * ez);
		boolean disturbed = localTick <= disturbedUntil;
		boolean grounded = body.onGround() || body.isInWater();

		float fwd = (f.has(Frame.FWD) ? 1 : 0) - (f.has(Frame.BACK) ? 1 : 0);
		float str = (f.has(Frame.LEFT) ? 1 : 0) - (f.has(Frame.RIGHT) ? 1 : 0);
		boolean jump = f.has(Frame.JUMP);
		boolean sprint = f.has(Frame.SPRINT);

		if (!recovering) {
			if (!disturbed && grounded && (eh > 1.4 || Math.abs(ey) > 1.3)) {
				recovering = true; recoverTicks = 0;
			} else if (!disturbed && eh > 0.06 && eh < 1.4) {
				Vec3 dm = body.getDeltaMovement();
				body.setDeltaMovement(dm.x + ex * 0.25, dm.y, dm.z + ez * 0.25);
			}
		}

		if (recovering) {
			recoverTicks++;
			if (eh < 0.5 && Math.abs(ey) < 1.0) {
				recovering = false;
			} else if (recoverTicks > 120) {
				// Hopelessly stuck (walled in, trapped): put it back on the path.
				body.setPos(ref.x, ref.y, ref.z);
				body.setDeltaMovement(Vec3.ZERO);
				recovering = false;
			} else if (!disturbed) {
				// Walk toward where the actor should be, keeping the recorded look direction (strafe-style).
				double yr = Math.toRadians(body.getYRot());
				double nx = eh < 1e-4 ? 0 : ex / eh, nz = eh < 1e-4 ? 0 : ez / eh;
				fwd = (float) (-nx * Math.sin(yr) + nz * Math.cos(yr));
				str = (float) (nx * Math.cos(yr) + nz * Math.sin(yr));
				sprint = eh > 3.0;
				body.setSprinting(sprint);
				jump = body.onGround() && (body.horizontalCollision || (ey > 0.6 && eh < 2.5));
			}
		}

		float mult = 1.0f;
		if (body.isShiftKeyDown()) mult *= 0.3f;
		if (body.isUsingItem() && !body.isPassenger()) mult *= 0.2f;
		body.setZza(fwd * mult);
		body.setXxa(str * mult);
		body.setJumping(jump);
	}

	// ------------------------------------------------------------------ events
	private void dispatchEvents() {
		while (evIdx < rec.events.size() && rec.events.get(evIdx).tick <= localTick) {
			Ev e = rec.events.get(evIdx++);
			if (e.tick < localTick) { if (e.type == Ev.EQUIP) applyEquip(e); continue; }
			try {
				handle(e);
			} catch (Exception ex) {
				dev.filmstudio.FilmStudio.LOGGER.warn("Actor '{}': could not replay event type {} at tick {}: {}", spec.name, e.type, e.tick, ex.toString());
			}
		}
		if (closeContainerAt >= 0 && localTick >= closeContainerAt) {
			closeContainerAt = -1;
			if (player != null && player.containerMenu != player.inventoryMenu) player.closeContainer();
		}
	}

	private static InteractionHand hand(int i) { return i == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND; }

	private void handle(Ev e) {
		switch (e.type) {
			case Ev.SWING -> body.swing(hand(e.i1));
			case Ev.EQUIP -> applyEquip(e);
			case Ev.USE_ITEM -> {
				if (player == null) break;
				InteractionHand h = hand(e.i1);
				ItemStack st = player.getItemInHand(h);
				if (!st.isEmpty()) player.gameMode.useItem(player, level, st, h);
				topUp();
			}
			case Ev.USE_RELEASE -> {
				if (body.isUsingItem()) body.releaseUsingItem();
			}
			case Ev.USE_BLOCK -> {
				if (player == null) break;
				InteractionHand h = hand(e.i1);
				BlockPos bo = BlockPos.containing(off());
				BlockPos pos = BlockPos.of(e.l).offset(bo);
				Vec3 loc = new Vec3(e.d1, e.d2, e.d3).add(off());
				BlockHitResult hit = new BlockHitResult(loc, Direction.values()[e.i2], pos, e.i3 == 1);
				player.gameMode.useItemOn(player, level, player.getItemInHand(h), h, hit);
				topUp();
				if (player.containerMenu != player.inventoryMenu) closeContainerAt = localTick + 30;
			}
			case Ev.DIG_START -> {
				BlockPos pos = BlockPos.of(e.l).offset(BlockPos.containing(off()));
				int end = -1;
				for (int i = evIdx; i < rec.events.size(); i++) {
					Ev n = rec.events.get(i);
					if (n.tick - localTick > 600) break;
					if (n.type == Ev.BREAK && n.l == e.l) { end = n.tick; break; }
					if (n.type == Ev.DIG_START && n.l != e.l) break;
				}
				if (end >= 0) { digPos = pos; digStart = localTick; digEnd = Math.max(end, localTick + 1); }
			}
			case Ev.BREAK -> {
				BlockPos pos = BlockPos.of(e.l).offset(BlockPos.containing(off()));
				level.destroyBlockProgress(body.getId(), pos, -1);
				digPos = null;
				if (!level.getBlockState(pos).isAir()) {
					if (player != null) player.gameMode.destroyBlock(pos);
					else level.destroyBlock(pos, true, body);
				}
			}
			case Ev.ATTACK, Ev.INTERACT_ENTITY -> {
				Entity t = resolveTarget(e);
				if (t == null || !t.isAlive() || body.distanceToSqr(t) > 36.0) break;
				if (e.type == Ev.ATTACK) {
					if (player != null) player.attack(t);
					else if (body instanceof Mob m) m.doHurtTarget(level, t);
				} else if (player != null) {
					player.interactOn(t, hand(e.i3));
				}
			}
			case Ev.DROP -> {
				if (player == null) break;
				ItemStack st = ItemJson.decode(level.getServer(), e.a);
				if (!st.isEmpty()) player.drop(st, false, true);
			}
			case Ev.HURT -> {
				body.animateHurt(e.f1);
				level.playSound((net.minecraft.world.entity.player.Player) null, body.getX(), body.getY(), body.getZ(), SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0f, 1.0f);
			}
			case Ev.DEATH -> {
				if ("normal".equals(spec.damageMode)) body.kill(level);
			}
			case Ev.CHAT -> level.getServer().getPlayerList()
				.broadcastSystemMessage(net.minecraft.network.chat.Component.literal("<" + spec.name + "> " + e.a), false);
			default -> { }
		}
	}

	private void updateDig() {
		if (digPos == null) return;
		int span = Math.max(1, digEnd - digStart);
		int stage = Math.min(9, (int) (10.0 * (localTick - digStart) / span));
		level.destroyBlockProgress(body.getId(), digPos, stage);
	}

	/** Creative-mode recordings do not use up blocks; neither should the actor. */
	private void topUp() {
		if (!creativeHand) return;
		ItemStack cur = body.getMainHandItem();
		if (!cur.isEmpty() && cur.getCount() < cur.getMaxStackSize()) cur.setCount(cur.getMaxStackSize());
	}

	private Entity resolveTarget(Ev e) {
		switch (e.i1) {
			case Ev.T_MOB -> {
				MobPuppet p = puppets.get(e.i2);
				if (p != null && p.entity != null) return p.entity;
			}
			case Ev.T_ACTOR -> {
				ActorRuntime other = session.runtime(e.a);
				if (other != null && other.body != null && other != this) return other.body;
				return null;
			}
			default -> { }
		}
		// Fallback: nearest entity of the same type around where the target was.
		if (e.a == null || e.a.isEmpty() || "minecraft:player".equals(e.a)) return null;
		Vec3 c = new Vec3(e.d1, e.d2, e.d3).add(off());
		Entity best = null; double bd = 9.0;
		for (Entity x : level.getEntitiesOfClass(Entity.class, new AABB(c, c).inflate(3.0))) {
			if (x == body || !x.isAlive()) continue;
			if (!BuiltInRegistries.ENTITY_TYPE.getKey(x.getType()).toString().equals(e.a)) continue;
			double d = x.position().distanceToSqr(c);
			if (d < bd) { bd = d; best = x; }
		}
		return best;
	}

	// ------------------------------------------------------------------ equipment
	private static EquipmentSlot slot(String name) {
		for (EquipmentSlot s : SLOTS) if (s.getName().equals(name)) return s;
		return null;
	}

	private ItemStack parseItem(String id) {
		try {
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id.trim()));
			return new ItemStack(item);
		} catch (Exception e) {
			return ItemStack.EMPTY;
		}
	}

	private void applyEquip(Ev e) {
		EquipmentSlot s = slot(e.a);
		if (s == null || body == null) return;
		ItemStack st = ItemJson.decode(level.getServer(), e.b);
		if (s == EquipmentSlot.MAINHAND) creativeHand = e.i2 == 1;
		if (!st.isEmpty() && e.i2 == 1) st.setCount(st.getMaxStackSize());
		String over = spec.equipOverride.get(e.a);
		if (over != null && !over.isBlank()) {
			ItemStack o = parseItem(over);
			if (!o.isEmpty()) st = o;
		}
		setSlot(s, st);
	}

	private void setSlot(EquipmentSlot s, ItemStack st) {
		body.setItemSlot(s, st);
	}

	// ------------------------------------------------------------------ upkeep
	private void maintenance() {
		if (body.isDeadOrDying()) return;
		if (player != null) {
			player.getFoodData().setFoodLevel(20);
			player.getFoodData().setSaturation(5.0f);
		}
		if ("invulnerable".equals(spec.damageMode)) {
			if (body.getHealth() < body.getMaxHealth()) body.setHealth(body.getMaxHealth());
		} else if ("no_death".equals(spec.damageMode)) {
			if (body.getHealth() < 4.0f) body.setHealth(body.getMaxHealth());
		}
		if (body.getY() < level.getMinY() - 16) {
			// Fell out of the world: back to the recorded spot.
			Frame f = rec.frames.get(Math.min(localTick, rec.frames.size() - 1));
			Vec3 p = pos(f);
			body.setPos(p.x, p.y, p.z);
			body.setDeltaMovement(Vec3.ZERO);
			body.resetFallDistance();
		}
	}
}
