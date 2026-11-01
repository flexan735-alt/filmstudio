package dev.filmstudio.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.filmstudio.FilmStudio;
import dev.filmstudio.data.*;
import dev.filmstudio.model.ModelDef;
import dev.filmstudio.play.Playback;
import dev.filmstudio.play.PlaybackSession;
import dev.filmstudio.rec.Recorder;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Server side of the dashboard protocol: every button in the UI ends up in {@link #handle}. */
public final class Net {
	private Net() {}

	public static void init() {
		PayloadTypeRegistry.playC2S().registerLarge(StudioC2S.TYPE, StudioC2S.CODEC, StudioC2S.MAX_SIZE);
		PayloadTypeRegistry.playS2C().registerLarge(StudioS2C.TYPE, StudioS2C.CODEC, StudioS2C.MAX_SIZE);
		ServerPlayNetworking.registerGlobalReceiver(StudioC2S.TYPE, (payload, ctx) -> handle(ctx.player(), payload));
	}

	// ------------------------------------------------------------------ access control
	public static boolean allowed(ServerPlayer p) {
		MinecraftServer s = p.level().getServer();
		if (s == null) return false;
		if (!s.isDedicatedServer()) return true;
		return FilmStore.allowed().contains(p.getGameProfile().name().toLowerCase(Locale.ROOT));
	}

	// ------------------------------------------------------------------ outbound helpers
	public static void send(ServerPlayer p, String op, String json, byte[] data) {
		ServerPlayNetworking.send(p, new StudioS2C(op, json, data));
	}

	public static void msg(ServerPlayer p, String text) {
		JsonObject o = new JsonObject();
		o.addProperty("text", text);
		send(p, "msg", o.toString(), new byte[0]);
	}

	public static void sendFilms(ServerPlayer p) {
		JsonObject o = new JsonObject();
		o.add("films", FilmStore.COMPACT.toJsonTree(FilmStore.listFilms()));
		o.add("skins", FilmStore.COMPACT.toJsonTree(FilmStore.listSkins()));
		o.add("models", FilmStore.COMPACT.toJsonTree(FilmStore.listModels()));
		send(p, "films", o.toString(), new byte[0]);
	}

	public static void sendPlaybackStatus(ServerPlayer p, JsonObject o) {
		if (Recorder.ACTIVE.containsKey(p.getUUID())) return;
		send(p, "status", o.toString(), new byte[0]);
	}

	public static void sendRecStatus(ServerPlayer p, String actor, int tick, boolean live, int countdown) {
		JsonObject o = new JsonObject();
		o.addProperty("mode", live ? "recording" : "rec_countdown");
		o.addProperty("actor", actor);
		o.addProperty("tick", tick);
		o.addProperty("countdown", countdown);
		send(p, "status", o.toString(), new byte[0]);
	}

	public static void sendIdle(ServerPlayer p) {
		JsonObject o = new JsonObject();
		o.addProperty("mode", "idle");
		send(p, "status", o.toString(), new byte[0]);
	}

	public static void sendSkin(ServerPlayer p, String key, byte[] png) {
		JsonObject o = new JsonObject();
		o.addProperty("key", key);
		send(p, "skin.data", o.toString(), png);
	}

	// ------------------------------------------------------------------ inbound
	private static void handle(ServerPlayer p, StudioC2S in) {
		if (!allowed(p)) { msg(p, "\u00a7cYou are not allowed to use Film Studio on this server."); return; }
		try {
			JsonObject j = in.json().isBlank() ? new JsonObject() : JsonParser.parseString(in.json()).getAsJsonObject();
			MinecraftServer server = p.level().getServer();
			switch (in.op()) {
				case "sync" -> sendFilms(p);

				case "film.create" -> {
					FilmStore.createFilm(str(j, "name", "New Film"));
					sendFilms(p);
				}
				case "film.delete" -> {
					FilmStore.deleteFilm(FilmStore.sanitize(str(j, "film", "")));
					sendFilms(p);
				}
				case "film.update" -> {
					Film f = film(j);
					if (f == null) return;
					if (j.has("name")) f.name = str(j, "name", f.name);
					if (j.has("notes")) f.notes = str(j, "notes", f.notes);
					if (j.has("loop")) f.loop = j.get("loop").getAsBoolean();
					if (j.has("restoreWorld")) f.restoreWorld = j.get("restoreWorld").getAsBoolean();
					if (j.has("countdown")) f.countdown = Math.max(0, Math.min(30, j.get("countdown").getAsInt()));
					if (j.has("mobMode")) f.mobMode = str(j, "mobMode", f.mobMode);
					if (j.has("mobRadius")) f.mobRadius = Math.max(4, Math.min(64, j.get("mobRadius").getAsInt()));
					if (j.has("playOthers")) f.playOthersWhileRecording = j.get("playOthers").getAsBoolean();
					FilmStore.saveFilm(f);
					sendFilms(p);
				}

				case "actor.add" -> {
					Film f = film(j);
					if (f == null) return;
					ActorSpec a = new ActorSpec();
					a.id = Long.toString(System.nanoTime(), 36);
					a.name = uniqueName(f, null, str(j, "name", "Actor"));
					f.actors.add(a);
					FilmStore.saveFilm(f);
					sendFilms(p);
				}
				case "actor.update" -> {
					Film f = film(j);
					if (f == null) return;
					ActorSpec in2 = FilmStore.COMPACT.fromJson(j.get("actor"), ActorSpec.class);
					ActorSpec a = f.actor(in2.id);
					if (a == null) return;
					a.name = uniqueName(f, a, in2.name);
					a.prefix = clip(in2.prefix, 32); a.suffix = clip(in2.suffix, 32);
					a.nameColor = in2.nameColor == null ? "white" : in2.nameColor;
					a.showNametag = in2.showNametag;
					a.skinType = in2.skinType; a.skinValue = clip(in2.skinValue, 64); a.slim = in2.slim;
					a.scale = Math.max(0.0625f, Math.min(16f, in2.scale));
					a.modelType = in2.modelType; a.modelName = in2.modelName == null ? "" : in2.modelName;
					a.equipOverride = in2.equipOverride == null ? new LinkedHashMap<>() : in2.equipOverride;
					a.forcedPose = in2.forcedPose; a.damageMode = in2.damageMode; a.physics = in2.physics;
					a.glow = in2.glow; a.invisible = in2.invisible;
					a.startDelay = Math.max(0, Math.min(72000, in2.startDelay));
					a.enabled = in2.enabled;
					FilmStore.saveFilm(f);
					sendFilms(p);
				}
				case "actor.delete" -> {
					Film f = film(j);
					if (f == null) return;
					String id = str(j, "id", "");
					f.actors.removeIf(a -> a.id.equals(id));
					FilmStore.deleteRecording(f.id, id);
					FilmStore.saveFilm(f);
					sendFilms(p);
				}
				case "actor.dup" -> {
					Film f = film(j);
					if (f == null) return;
					ActorSpec a = f.actor(str(j, "id", ""));
					if (a == null) return;
					ActorSpec c = a.copy();
					c.id = Long.toString(System.nanoTime(), 36);
					c.name = uniqueName(f, null, a.name);
					c.hasRecording = false;
					Recording r = FilmStore.loadRecording(f.id, a.id);
					f.actors.add(c);
					if (r != null) FilmStore.saveRecording(f.id, c.id, r);
					FilmStore.saveFilm(f);
					sendFilms(p);
				}

				case "rec.start" -> {
					Film f = film(j);
					if (f == null) return;
					ActorSpec a = f.actor(str(j, "actor", ""));
					if (a == null) { msg(p, "\u00a7cChoose an actor first."); return; }
					Playback.stopFor(p);
					Recorder r = Recorder.start(p, f, a);
					if (f.playOthersWhileRecording) {
						Set<String> others = new HashSet<>();
						for (ActorSpec o : f.actors) if (o.enabled && !o.id.equals(a.id) && FilmStore.hasRecording(f.id, o.id)) others.add(o.id);
						if (!others.isEmpty()) {
							Playback.start(p, f, others, 0, false, false, true, s -> {
								if (s == null) return;
								if (Recorder.ACTIVE.get(p.getUUID()) == r) { r.companion = s; if (r.live) s.release(); }
								else Playback.stop(s);
							});
						}
					}
				}
				case "rec.stop" -> {
					boolean save = !j.has("save") || j.get("save").getAsBoolean();
					Recorder.stop(p, save);
					sendIdle(p);
					sendFilms(p);
				}
				case "rec.delete" -> {
					Film f = film(j);
					if (f == null) return;
					String id = str(j, "actor", "");
					FilmStore.deleteRecording(f.id, id);
					ActorSpec a = f.actor(id);
					if (a != null) FilmStore.refreshRecInfo(f.id, a);
					FilmStore.saveFilm(f);
					sendFilms(p);
				}
				case "rec.trim" -> {
					Film f = film(j);
					if (f == null) return;
					String id = str(j, "actor", "");
					Recording r = FilmStore.loadRecording(f.id, id);
					if (r == null) { msg(p, "\u00a7cNo recording to trim."); return; }
					RecEdit.trim(r, j.get("start").getAsInt(), j.get("end").getAsInt());
					FilmStore.saveRecording(f.id, id, r);
					ActorSpec a = f.actor(id);
					if (a != null) FilmStore.refreshRecInfo(f.id, a);
					FilmStore.saveFilm(f);
					sendFilms(p);
					msg(p, "\u00a7aTrimmed to " + r.length() + " ticks.");
				}

				case "play.start" -> {
					Film f = film(j);
					if (f == null) return;
					Set<String> ids = null;
					if (j.has("actors") && j.get("actors").isJsonArray()) {
						ids = new HashSet<>();
						for (JsonElement e : j.getAsJsonArray("actors")) ids.add(e.getAsString());
					}
					boolean loop = j.has("loop") ? j.get("loop").getAsBoolean() : f.loop;
					boolean rel = j.has("relative") && j.get("relative").getAsBoolean();
					int from = j.has("from") ? Math.max(0, j.get("from").getAsInt()) : 0;
					Recorder.stop(p, true);
					Playback.start(p, f, ids, from, loop, rel, false, null);
				}
				case "play.stop" -> {
					Playback.stopFor(p);
					sendIdle(p);
				}
				case "play.pause" -> {
					PlaybackSession s = Playback.of(p);
					if (s != null) { s.paused = !s.paused; s.status(true); }
				}

				case "world.restore" -> {
					Film f = film(j);
					if (f == null) return;
					boolean undo = !j.has("undo") || j.get("undo").getAsBoolean();
					List<ActorSpec> list = new ArrayList<>();
					String only = str(j, "actor", "");
					for (ActorSpec a : f.actors) if (only.isEmpty() || a.id.equals(only)) list.add(a);
					int n = Playback.restoreWorld(server, f, list, undo);
					msg(p, (undo ? "\u00a7aReverted " : "\u00a7aRe-applied ") + n + " block changes.");
				}

				case "skin.upload" -> {
					byte[] d = in.data();
					if (d.length < 8 || d.length > 4 * 1024 * 1024 || (d[0] & 0xFF) != 0x89 || d[1] != 'P' || d[2] != 'N' || d[3] != 'G') {
						msg(p, "\u00a7cThat does not look like a PNG skin (max 4 MB).");
						return;
					}
					String key = FilmStore.sanitize(str(j, "key", "skin"));
					FilmStore.writeSkin(key, d);
					msg(p, "\u00a7aSkin imported as \u00a7f" + key);
					sendFilms(p);
				}
				case "skin.delete" -> { FilmStore.deleteSkin(str(j, "key", "")); sendFilms(p); }
				case "skin.request" -> {
					String key = FilmStore.sanitize(str(j, "key", ""));
					byte[] png = FilmStore.readSkin(key);
					if (png != null) sendSkin(p, key, png);
				}

				case "model.upload" -> {
					String key = FilmStore.sanitize(str(j, "key", "model"));
					byte[] d = in.data();
					if (d.length < 16 || d.length > 24 * 1024 * 1024) { msg(p, "\u00a7cModel file is empty or too large (max 24 MB)."); return; }
					try {
						ModelDef def = ModelDef.parse(new String(d, StandardCharsets.UTF_8));
						FilmStore.writeModel(key, d);
						msg(p, "\u00a7aModel imported as \u00a7f" + key + "\u00a7a (" + def.cubeCount() + " cubes, " + def.animations.size() + " animations)");
						sendFilms(p);
					} catch (Exception e) {
						msg(p, "\u00a7cCould not read model: " + e.getMessage());
					}
				}
				case "model.delete" -> { FilmStore.deleteModel(str(j, "key", "")); sendFilms(p); }
				case "model.request" -> {
					String key = FilmStore.sanitize(str(j, "key", ""));
					byte[] d = FilmStore.readModel(key);
					if (d != null) {
						JsonObject o = new JsonObject();
						o.addProperty("key", key);
						send(p, "model.data", o.toString(), d);
					}
				}
				default -> FilmStudio.LOGGER.warn("Unknown Film Studio op '{}'", in.op());
			}
		} catch (Exception e) {
			FilmStudio.LOGGER.error("Film Studio op '{}' failed", in.op(), e);
			msg(p, "\u00a7cError: " + e);
		}
	}

	private static Film film(JsonObject j) {
		String id = FilmStore.sanitize(str(j, "film", ""));
		return id.isEmpty() ? null : FilmStore.loadFilm(id);
	}

	private static String str(JsonObject j, String k, String def) {
		return j.has(k) && !j.get(k).isJsonNull() ? j.get(k).getAsString() : def;
	}

	private static String clip(String s, int max) {
		if (s == null) return "";
		return s.length() > max ? s.substring(0, max) : s;
	}

	/** Names are Minecraft profile names (max 16 chars) and must be unique within a film. */
	private static String uniqueName(Film f, ActorSpec self, String wanted) {
		String base = wanted == null || wanted.isBlank() ? "Actor" : wanted.trim();
		if (base.length() > 16) base = base.substring(0, 16);
		String name = base; int n = 2;
		while (true) {
			boolean clash = false;
			for (ActorSpec a : f.actors) if (a != self && a.name.equalsIgnoreCase(name)) { clash = true; break; }
			if (!clash) return name;
			String suffix = String.valueOf(n++);
			name = (base.length() + suffix.length() > 16 ? base.substring(0, 16 - suffix.length()) : base) + suffix;
		}
	}
}
