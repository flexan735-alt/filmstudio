package dev.filmstudio.play;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import dev.filmstudio.data.ActorSpec;
import dev.filmstudio.data.Film;
import dev.filmstudio.data.FilmStore;
import dev.filmstudio.data.Recording;
import dev.filmstudio.net.Net;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import java.util.*;

/** One running playback of a film: a shared timeline and one {@link ActorRuntime} per actor. */
public final class PlaybackSession {
	public final MinecraftServer server;
	public final ServerPlayer owner;
	public final Film film;
	public final Map<String, GameProfile> profiles;
	public final Vec3 offset;
	public final int from;
	public boolean loop;
	public boolean paused;
	/** True while waiting for the recorder's countdown to finish. */
	public boolean held;
	public int tick;
	public final List<ActorRuntime> runtimes = new ArrayList<>();
	private final List<ActorSpec> specs;
	private final Map<String, PlayerTeam> teams = new HashMap<>();
	private int lastStatus = -100;
	private boolean closed;
	private int doneTicks;

	public PlaybackSession(MinecraftServer server, ServerPlayer owner, Film film, List<ActorSpec> specs,
						   Map<String, GameProfile> profiles, Vec3 offset, int from, boolean loop, boolean held) {
		this.server = server; this.owner = owner; this.film = film; this.specs = specs; this.profiles = profiles;
		this.offset = offset; this.from = from; this.loop = loop; this.held = held;
		this.tick = from;
		build();
	}

	private void build() {
		for (ActorSpec s : specs) {
			Recording r = FilmStore.loadRecording(film.id, s.id);
			if (r == null || r.frames.isEmpty()) continue;
			ServerLevel lvl = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(r.dimension)));
			if (lvl == null) lvl = (ServerLevel) owner.level();
			runtimes.add(new ActorRuntime(this, s, r, lvl));
		}
	}

	public ActorRuntime runtime(String actorId) {
		for (ActorRuntime r : runtimes) if (r.spec.id.equals(actorId)) return r;
		return null;
	}

	public void release() { held = false; }

	public int totalLength() {
		int m = 0;
		for (ActorRuntime r : runtimes) m = Math.max(m, r.spec.startDelay + r.length());
		return m;
	}

	// ------------------------------------------------------------------ ticking
	public void tick() {
		if (closed) return;
		if (held || paused) { status(false); return; }
		for (ActorRuntime r : runtimes) {
			try {
				r.tick(tick);
			} catch (Exception e) {
				dev.filmstudio.FilmStudio.LOGGER.error("Playback error for actor {}", r.spec.name, e);
				r.state = ActorRuntime.State.DEAD;
			}
		}
		tick++;
		boolean allDone = true;
		for (ActorRuntime r : runtimes) {
			if (r.state != ActorRuntime.State.DONE && r.state != ActorRuntime.State.DEAD) { allDone = false; break; }
		}
		if (allDone && tick > from + 2 && ++doneTicks > 40) {
			if (loop) { restart(); doneTicks = 0; }
			else { Playback.stop(this); if (owner != null) owner.displayClientMessage(Component.literal("\u00a7aPlayback finished."), true); }
			return;
		}
		if (!allDone) doneTicks = 0;
		status(false);
	}

	private void restart() {
		for (ActorRuntime r : runtimes) r.despawn();
		runtimes.clear();
		build();
		tick = from;
	}

	public void close() {
		if (closed) return;
		closed = true;
		for (ActorRuntime r : runtimes) r.despawn();
		Scoreboard sb = server.getScoreboard();
		for (PlayerTeam t : teams.values()) {
			try { sb.removePlayerTeam(t); } catch (Exception ignored) {}
		}
		teams.clear();
		status(true);
	}

	// ------------------------------------------------------------------ name tags (via scoreboard teams)
	public void applyTeam(ActorSpec spec, Entity body) {
		try {
			Scoreboard sb = server.getScoreboard();
			String tn = ("fs_" + spec.id);
			if (tn.length() > 16) tn = tn.substring(0, 16);
			PlayerTeam t = teams.get(tn);
			if (t == null) {
				t = sb.getPlayerTeam(tn);
				if (t == null) t = sb.addPlayerTeam(tn);
				teams.put(tn, t);
			}
			ChatFormatting c = ChatFormatting.getByName(spec.nameColor);
			if (c != null && c.isColor()) t.setColor(c);
			t.setPlayerPrefix(Component.literal(spec.prefix == null ? "" : spec.prefix));
			t.setPlayerSuffix(Component.literal(spec.suffix == null ? "" : spec.suffix));
			t.setNameTagVisibility(spec.showNametag ? Team.Visibility.ALWAYS : Team.Visibility.NEVER);
			t.setSeeFriendlyInvisibles(true);
			sb.addPlayerToTeam(body.getScoreboardName(), t);
		} catch (Exception e) {
			dev.filmstudio.FilmStudio.LOGGER.warn("Could not set name tag team: {}", e.toString());
		}
	}

	public void removeFromTeam(Entity body) {
		try {
			Scoreboard sb = server.getScoreboard();
			PlayerTeam t = sb.getPlayersTeam(body.getScoreboardName());
			if (t != null) sb.removePlayerFromTeam(body.getScoreboardName(), t);
		} catch (Exception ignored) {}
	}

	// ------------------------------------------------------------------ status to the dashboard
	public void status(boolean force) {
		if (owner == null) return;
		if (!force && tick - lastStatus < 4 && tick >= lastStatus) return;
		lastStatus = tick;
		JsonObject o = new JsonObject();
		o.addProperty("mode", closed ? "idle" : (held ? "countdown" : (paused ? "paused" : "playing")));
		o.addProperty("film", film.id);
		o.addProperty("tick", tick);
		o.addProperty("length", totalLength());
		o.addProperty("loop", loop);
		JsonArray arr = new JsonArray();
		for (ActorRuntime r : runtimes) {
			JsonObject a = new JsonObject();
			a.addProperty("id", r.spec.id);
			a.addProperty("state", r.state.name());
			a.addProperty("len", r.length());
			a.addProperty("delay", r.spec.startDelay);
			arr.add(a);
		}
		o.add("actors", arr);
		Net.sendPlaybackStatus(owner, o);
	}
}
