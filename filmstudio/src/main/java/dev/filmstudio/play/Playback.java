package dev.filmstudio.play;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import dev.filmstudio.data.ActorSpec;
import dev.filmstudio.data.Film;
import dev.filmstudio.data.FilmStore;
import dev.filmstudio.data.Recording;
import dev.filmstudio.net.Net;
import dev.filmstudio.util.BlockStr;
import dev.filmstudio.util.SkinFetch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Global playback manager: starting/stopping sessions, ticking them and world restore. */
public final class Playback {
	public static final String PUPPET_TAG = "filmstudio_puppet";
	public static final String SPAWNED_TAG = "filmstudio_spawned";
	public static final Set<ActorPlayer> ACTOR_PLAYERS = Collections.newSetFromMap(new IdentityHashMap<>());
	public static final List<PlaybackSession> SESSIONS = new ArrayList<>();

	private Playback() {}

	public static PlaybackSession of(ServerPlayer owner) {
		for (PlaybackSession s : SESSIONS) if (s.owner == owner) return s;
		return null;
	}

	public static void tickAll() {
		if (SESSIONS.isEmpty()) return;
		for (PlaybackSession s : new ArrayList<>(SESSIONS)) s.tick();
	}

	public static void stop(PlaybackSession s) {
		SESSIONS.remove(s);
		s.close();
	}

	public static void stopFor(ServerPlayer owner) {
		PlaybackSession s = of(owner);
		if (s != null) stop(s);
	}

	public static void stopAll() {
		for (PlaybackSession s : new ArrayList<>(SESSIONS)) stop(s);
	}

	public static void noteHurt(LivingEntity e) {
		if (SESSIONS.isEmpty()) return;
		for (PlaybackSession s : SESSIONS) for (ActorRuntime r : s.runtimes) r.noteHurt(e, s.tick);
	}

	/** Players who join mid-playback must learn about the actors, or they will not render them. */
	public static void onJoin(ServerPlayer p) {
		if (ACTOR_PLAYERS.isEmpty()) return;
		for (ActorPlayer ap : ACTOR_PLAYERS) {
			p.connection.send(new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ap));
		}
		for (PlaybackSession s : SESSIONS) pushSkins(p.level().getServer(), s.film, new ArrayList<>(s.film.actors), p);
	}

	// ------------------------------------------------------------------ start
	/**
	 * @param ids   actors to play (null = every enabled actor that has a recording)
	 * @param hold  create the session but wait until {@link PlaybackSession#release()} (used for recording alongside)
	 */
	public static void start(ServerPlayer owner, Film film, Set<String> ids, int from, boolean loop, boolean relative,
							 boolean hold, Consumer<PlaybackSession> callback) {
		MinecraftServer server = owner.level().getServer();
		stopFor(owner);
		List<ActorSpec> chosen = new ArrayList<>();
		for (ActorSpec a : film.actors) {
			if (ids != null ? !ids.contains(a.id) : !a.enabled) continue;
			if (!FilmStore.hasRecording(film.id, a.id)) continue;
			chosen.add(a);
		}
		if (chosen.isEmpty()) {
			owner.displayClientMessage(Component.literal("\u00a7cNothing to play: no enabled actor has a recording."), true);
			if (callback != null) callback.accept(null);
			return;
		}

		Vec3 offset = Vec3.ZERO;
		if (relative) {
			Recording first = FilmStore.loadRecording(film.id, chosen.get(0).id);
			if (first != null) {
				BlockPos o = BlockPos.containing(first.ox, first.oy, first.oz);
				BlockPos here = owner.blockPosition();
				offset = new Vec3(here.getX() - o.getX(), here.getY() - o.getY(), here.getZ() - o.getZ());
			}
		} else if (film.restoreWorld) {
			restoreWorld(server, film, chosen, true);
		}

		final Vec3 off = offset;
		pushSkins(server, film, chosen, null);
		resolveProfiles(server, film, chosen, profiles -> {
			if (owner.isRemoved()) return;
			stopFor(owner);
			PlaybackSession s = new PlaybackSession(server, owner, film, chosen, profiles, off, from, loop, hold);
			if (s.runtimes.isEmpty()) {
				owner.displayClientMessage(Component.literal("\u00a7cRecordings could not be loaded."), true);
				if (callback != null) callback.accept(null);
				return;
			}
			SESSIONS.add(s);
			if (callback != null) callback.accept(s);
		});
	}

	// ------------------------------------------------------------------ profiles & skins
	private static void resolveProfiles(MinecraftServer server, Film film, List<ActorSpec> specs, Consumer<Map<String, GameProfile>> done) {
		Map<String, Property> textures = new HashMap<>();
		int[] pending = {0};
		for (ActorSpec s : specs) if ("player".equals(s.skinType) && !s.skinValue.isBlank()) pending[0]++;
		Runnable finish = () -> {
			Map<String, GameProfile> out = new HashMap<>();
			for (ActorSpec s : specs) out.put(s.id, buildProfile(film, s, textures.get(s.id)));
			done.accept(out);
		};
		if (pending[0] == 0) { finish.run(); return; }
		for (ActorSpec s : specs) {
			if (!"player".equals(s.skinType) || s.skinValue.isBlank()) continue;
			SkinFetch.resolve(server, s.skinValue.trim(), prop -> {
				if (prop != null) textures.put(s.id, prop);
				if (--pending[0] == 0) finish.run();
			});
		}
	}

	private static GameProfile buildProfile(Film film, ActorSpec s, Property textures) {
		UUID id = UUID.nameUUIDFromBytes(("filmstudio:" + film.id + ":" + s.id).getBytes(StandardCharsets.UTF_8));
		String name = (s.name == null || s.name.isBlank()) ? "Actor" : s.name;
		if (name.length() > 16) name = name.substring(0, 16);
		Multimap<String, Property> mm = HashMultimap.create();
		if (textures != null) mm.put("textures", textures);
		if ("file".equals(s.skinType) && !s.skinValue.isBlank()) {
			mm.put("filmstudio_skin", new Property("filmstudio_skin", FilmStore.sanitize(s.skinValue) + (s.slim ? ":slim" : ":wide")));
		}
		return new GameProfile(id, name, new PropertyMap(mm));
	}

	/** Sends imported skin images to everyone (or one player) so clients can build the textures. */
	public static void pushSkins(MinecraftServer server, Film film, List<ActorSpec> specs, ServerPlayer only) {
		Set<String> sent = new HashSet<>();
		for (ActorSpec s : specs) {
			if (!"file".equals(s.skinType) || s.skinValue.isBlank()) continue;
			String key = FilmStore.sanitize(s.skinValue);
			if (!sent.add(key)) continue;
			byte[] png = FilmStore.readSkin(key);
			if (png == null) continue;
			if (only != null) Net.sendSkin(only, key, png);
			else for (ServerPlayer p : server.getPlayerList().getPlayers()) Net.sendSkin(p, key, png);
		}
	}

	// ------------------------------------------------------------------ world restore
	/** Puts blocks back the way they were before the recordings changed them (undo = true) or re-applies the changes. */
	public static int restoreWorld(MinecraftServer server, Film film, List<ActorSpec> specs, boolean undo) {
		int count = 0;
		for (ActorSpec s : specs) {
			Recording r = FilmStore.loadRecording(film.id, s.id);
			if (r == null || r.deltas.isEmpty()) continue;
			ServerLevel lvl = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(r.dimension)));
			if (lvl == null) continue;
			List<Recording.Delta> list = new ArrayList<>(r.deltas);
			if (undo) Collections.reverse(list);
			for (Recording.Delta d : list) {
				BlockState st = BlockStr.decode(undo ? d.before : d.after);
				if (st == null) continue;
				lvl.setBlock(BlockPos.of(d.pos), st, 3);
				count++;
			}
		}
		return count;
	}
}
