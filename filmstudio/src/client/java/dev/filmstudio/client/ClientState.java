package dev.filmstudio.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.filmstudio.data.ActorSpec;
import dev.filmstudio.data.Film;

import java.util.ArrayList;
import java.util.List;

/** Everything the dashboard knows about the server: films, assets and live recording/playback status. */
public final class ClientState {
	private static final Gson GSON = new Gson();

	public static List<Film> films = new ArrayList<>();
	public static List<String> skins = new ArrayList<>();
	public static List<String> models = new ArrayList<>();

	public static String selFilm = "";
	public static String selActor = "";

	// live status
	public static String mode = "idle";
	public static int tick, length, countdown;
	public static boolean loop;
	public static String statusFilm = "";
	public static String statusActor = "";
	public static final List<Lane> lanes = new ArrayList<>();

	public static String message = "";
	public static long messageUntil;

	/** Called on the client thread when films/assets change (screens rebuild) or only live status changes. */
	public static Runnable onFilms;
	public static Runnable onStatus;

	public record Lane(String id, String state, int len, int delay) {}

	private ClientState() {}

	public static Film film() {
		for (Film f : films) if (f.id.equals(selFilm)) return f;
		return null;
	}

	public static ActorSpec actor() {
		Film f = film();
		return f == null ? null : f.actor(selActor);
	}

	public static void setFilms(JsonObject o) {
		List<Film> out = new ArrayList<>();
		for (JsonElement e : o.getAsJsonArray("films")) out.add(GSON.fromJson(e, Film.class));
		films = out;
		skins = strings(o.getAsJsonArray("skins"));
		models = strings(o.getAsJsonArray("models"));
		if (film() == null) { selFilm = films.isEmpty() ? "" : films.get(0).id; selActor = ""; }
		Film f = film();
		if (f != null && f.actor(selActor) == null) selActor = f.actors.isEmpty() ? "" : f.actors.get(0).id;
		if (onFilms != null) onFilms.run();
	}

	public static void setStatus(JsonObject o) {
		mode = o.get("mode").getAsString();
		tick = o.has("tick") ? o.get("tick").getAsInt() : 0;
		length = o.has("length") ? o.get("length").getAsInt() : 0;
		countdown = o.has("countdown") ? o.get("countdown").getAsInt() : 0;
		loop = o.has("loop") && o.get("loop").getAsBoolean();
		statusFilm = o.has("film") ? o.get("film").getAsString() : "";
		statusActor = o.has("actor") ? o.get("actor").getAsString() : "";
		lanes.clear();
		if (o.has("actors")) {
			for (JsonElement e : o.getAsJsonArray("actors")) {
				JsonObject a = e.getAsJsonObject();
				lanes.add(new Lane(a.get("id").getAsString(), a.get("state").getAsString(), a.get("len").getAsInt(), a.get("delay").getAsInt()));
			}
		}
		if (onStatus != null) onStatus.run();
	}

	public static void say(String text) {
		message = text;
		messageUntil = System.currentTimeMillis() + 6000;
	}

	private static List<String> strings(JsonArray a) {
		List<String> l = new ArrayList<>();
		if (a != null) for (JsonElement e : a) l.add(e.getAsString());
		return l;
	}
}
