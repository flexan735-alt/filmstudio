package dev.filmstudio.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.filmstudio.FilmStudio;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** All disk access for films, recordings, skins and models. Everything lives in &lt;gameDir&gt;/filmstudio. */
public final class FilmStore {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	public static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

	private FilmStore() {}

	public static Path root() {
		Path p = FabricLoader.getInstance().getGameDir().resolve("filmstudio");
		try { Files.createDirectories(p); } catch (IOException ignored) {}
		return p;
	}

	public static Path filmsDir() { return sub("films"); }
	public static Path skinsDir() { return sub("skins"); }
	public static Path modelsDir() { return sub("models"); }

	private static Path sub(String n) {
		Path p = root().resolve(n);
		try { Files.createDirectories(p); } catch (IOException ignored) {}
		return p;
	}

	/** Lower-case, filesystem-safe key. */
	public static String sanitize(String s) {
		if (s == null) return "";
		String k = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
		if (k.length() > 40) k = k.substring(0, 40);
		return k;
	}

	// ------------------------------------------------------------------ films
	public static List<Film> listFilms() {
		List<Film> out = new ArrayList<>();
		try (Stream<Path> s = Files.list(filmsDir())) {
			for (Path d : (Iterable<Path>) s.filter(Files::isDirectory).sorted()::iterator) {
				Film f = loadFilm(d.getFileName().toString());
				if (f != null) out.add(f);
			}
		} catch (IOException e) {
			FilmStudio.LOGGER.error("Could not list films", e);
		}
		out.sort(Comparator.comparingLong((Film f) -> f.created));
		return out;
	}

	public static Film loadFilm(String id) {
		Path f = filmsDir().resolve(id).resolve("film.json");
		if (!Files.isRegularFile(f)) return null;
		try {
			Film film = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Film.class);
			if (film == null) return null;
			film.id = id;
			if (film.actors == null) film.actors = new ArrayList<>();
			for (ActorSpec a : film.actors) {
				if (a.equipOverride == null) a.equipOverride = new LinkedHashMap<>();
				ensureRecInfo(film.id, a);
			}
			return film;
		} catch (Exception e) {
			FilmStudio.LOGGER.error("Could not load film {}", id, e);
			return null;
		}
	}

	public static void saveFilm(Film film) {
		try {
			Path d = filmsDir().resolve(film.id);
			Files.createDirectories(d);
			Files.writeString(d.resolve("film.json"), GSON.toJson(film), StandardCharsets.UTF_8);
		} catch (IOException e) {
			FilmStudio.LOGGER.error("Could not save film {}", film.id, e);
		}
	}

	public static Film createFilm(String name) {
		String base = sanitize(name);
		if (base.isEmpty()) base = "film";
		String id = base; int n = 2;
		while (Files.exists(filmsDir().resolve(id))) id = base + "_" + (n++);
		Film f = new Film();
		f.id = id; f.name = name; f.created = System.currentTimeMillis();
		saveFilm(f);
		return f;
	}

	public static void deleteFilm(String id) {
		deleteRecursive(filmsDir().resolve(id));
	}

	public static void deleteRecursive(Path p) {
		try {
			if (!Files.exists(p)) return;
			try (Stream<Path> s = Files.walk(p)) {
				s.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (IOException ignored) {} });
			}
		} catch (IOException ignored) {}
	}

	// ------------------------------------------------------------------ recordings
	private static Path recPath(String filmId, String actorId) {
		return filmsDir().resolve(filmId).resolve(sanitize(actorId) + ".fsr");
	}

	public static boolean hasRecording(String filmId, String actorId) {
		return Files.isRegularFile(recPath(filmId, actorId));
	}

	public static Recording loadRecording(String filmId, String actorId) {
		Path p = recPath(filmId, actorId);
		if (!Files.isRegularFile(p)) return null;
		try (InputStream in = Files.newInputStream(p)) {
			return Recording.read(in);
		} catch (Exception e) {
			FilmStudio.LOGGER.error("Could not read recording {}/{}", filmId, actorId, e);
			return null;
		}
	}

	public static void saveRecording(String filmId, String actorId, Recording r) throws IOException {
		Path p = recPath(filmId, actorId);
		Files.createDirectories(p.getParent());
		try (OutputStream os = Files.newOutputStream(p)) {
			r.write(os);
		}
	}

	public static void deleteRecording(String filmId, String actorId) {
		try { Files.deleteIfExists(recPath(filmId, actorId)); } catch (IOException ignored) {}
	}

	/** Cheap version for listing films: only opens the recording file if its summary was never stored. */
	public static void ensureRecInfo(String filmId, ActorSpec a) {
		boolean exists = hasRecording(filmId, a.id);
		if (!exists) { a.hasRecording = false; a.recLength = 0; a.recEvents = 0; a.recMobs = 0; a.recDeltas = 0; return; }
		if (a.hasRecording && a.recLength > 0) return;
		refreshRecInfo(filmId, a);
	}

	public static void refreshRecInfo(String filmId, ActorSpec a) {
		a.hasRecording = hasRecording(filmId, a.id);
		a.recLength = 0; a.recEvents = 0; a.recMobs = 0; a.recDeltas = 0;
		if (a.hasRecording) {
			Recording r = loadRecording(filmId, a.id);
			if (r != null) {
				a.recLength = r.length(); a.recEvents = r.events.size(); a.recMobs = r.mobs.size(); a.recDeltas = r.deltas.size();
			} else {
				a.hasRecording = false;
			}
		}
	}

	// ------------------------------------------------------------------ skins / models
	public static List<String> listSkins() { return listKeys(skinsDir(), ".png"); }
	public static List<String> listModels() { return listKeys(modelsDir(), ".bbmodel"); }

	private static List<String> listKeys(Path dir, String ext) {
		List<String> out = new ArrayList<>();
		try (Stream<Path> s = Files.list(dir)) {
			s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(ext)).sorted()
				.forEach(n -> out.add(n.substring(0, n.length() - ext.length())));
		} catch (IOException ignored) {}
		return out;
	}

	public static byte[] readSkin(String key) { return readBytes(skinsDir().resolve(sanitize(key) + ".png")); }
	public static byte[] readModel(String key) { return readBytes(modelsDir().resolve(sanitize(key) + ".bbmodel")); }

	public static void writeSkin(String key, byte[] data) throws IOException { Files.write(skinsDir().resolve(sanitize(key) + ".png"), data); }
	public static void writeModel(String key, byte[] data) throws IOException { Files.write(modelsDir().resolve(sanitize(key) + ".bbmodel"), data); }
	public static void deleteSkin(String key) { try { Files.deleteIfExists(skinsDir().resolve(sanitize(key) + ".png")); } catch (IOException ignored) {} }
	public static void deleteModel(String key) { try { Files.deleteIfExists(modelsDir().resolve(sanitize(key) + ".bbmodel")); } catch (IOException ignored) {} }

	private static byte[] readBytes(Path p) {
		try { return Files.isRegularFile(p) ? Files.readAllBytes(p) : null; } catch (IOException e) { return null; }
	}

	// ------------------------------------------------------------------ access list (dedicated servers)
	public static Set<String> allowed() {
		Set<String> out = new HashSet<>();
		Path p = root().resolve("allowed.txt");
		try {
			if (Files.isRegularFile(p)) for (String l : Files.readAllLines(p)) if (!l.isBlank()) out.add(l.trim().toLowerCase(Locale.ROOT));
		} catch (IOException ignored) {}
		return out;
	}

	public static void allow(String name) {
		Set<String> s = allowed(); s.add(name.toLowerCase(Locale.ROOT));
		try { Files.write(root().resolve("allowed.txt"), s); } catch (IOException ignored) {}
	}
}
