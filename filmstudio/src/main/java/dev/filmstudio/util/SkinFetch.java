package dev.filmstudio.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import dev.filmstudio.FilmStudio;
import dev.filmstudio.data.FilmStore;
import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Resolves a Minecraft account name to a signed "textures" property using Mojang's public web API,
 * off the server thread, with an on-disk cache so repeated plays (and offline use) work.
 */
public final class SkinFetch {
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
	private static final Map<String, String[]> CACHE = new ConcurrentHashMap<>();
	private static boolean loaded = false;

	private SkinFetch() {}

	private static Path cacheFile() { return FilmStore.root().resolve("skin_cache.json"); }

	private static synchronized void load() {
		if (loaded) return;
		loaded = true;
		try {
			Path p = cacheFile();
			if (Files.isRegularFile(p)) {
				JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
				for (var e : o.entrySet()) {
					JsonObject v = e.getValue().getAsJsonObject();
					CACHE.put(e.getKey(), new String[]{v.get("value").getAsString(), v.get("signature").getAsString()});
				}
			}
		} catch (Exception e) {
			FilmStudio.LOGGER.warn("Could not read skin cache", e);
		}
	}

	private static synchronized void save() {
		try {
			JsonObject o = new JsonObject();
			for (var e : CACHE.entrySet()) {
				JsonObject v = new JsonObject();
				v.addProperty("value", e.getValue()[0]);
				v.addProperty("signature", e.getValue()[1]);
				o.add(e.getKey(), v);
			}
			Files.writeString(cacheFile(), o.toString());
		} catch (Exception ignored) {}
	}

	/** Calls back on the server thread with the property, or null if the name could not be resolved. */
	public static void resolve(MinecraftServer server, String name, Consumer<Property> cb) {
		load();
		String key = name.toLowerCase(Locale.ROOT);
		String[] cached = CACHE.get(key);
		if (cached != null) { cb.accept(new Property("textures", cached[0], cached[1])); return; }
		CompletableFuture.supplyAsync(() -> fetch(key)).thenAccept(res -> server.execute(() -> {
			if (res != null) {
				CACHE.put(key, res);
				save();
				cb.accept(new Property("textures", res[0], res[1]));
			} else {
				cb.accept(null);
			}
		}));
	}

	private static String[] fetch(String name) {
		try {
			String b1 = get("https://api.mojang.com/users/profiles/minecraft/" + name);
			if (b1 == null) return null;
			String id = JsonParser.parseString(b1).getAsJsonObject().get("id").getAsString();
			String b2 = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
			if (b2 == null) return null;
			JsonArray props = JsonParser.parseString(b2).getAsJsonObject().getAsJsonArray("properties");
			for (JsonElement e : props) {
				JsonObject o = e.getAsJsonObject();
				if ("textures".equals(o.get("name").getAsString()) && o.has("signature")) {
					return new String[]{o.get("value").getAsString(), o.get("signature").getAsString()};
				}
			}
		} catch (Exception e) {
			FilmStudio.LOGGER.warn("Skin lookup for '{}' failed: {}", name, e.toString());
		}
		return null;
	}

	private static String get(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
		HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return r.statusCode() == 200 ? r.body() : null;
	}
}
