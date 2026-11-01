package dev.filmstudio.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.filmstudio.FilmStudio;
import dev.filmstudio.model.ModelDef;
import dev.filmstudio.net.StudioC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Client cache of imported 3D models and their textures. Models are requested from the server on first sight. */
public final class ModelCache {
	public static final class Entry {
		public final ModelDef def;
		public final Identifier texture;
		public final Map<ModelDef.Cube, Vector3f[]> corners = new IdentityHashMap<>();

		Entry(ModelDef def, Identifier texture) { this.def = def; this.texture = texture; }

		public Vector3f[] corners(ModelDef.Cube c) {
			return corners.computeIfAbsent(c, ModelDef::cubeCorners);
		}
	}

	private static final Map<String, Entry> ENTRIES = new HashMap<>();
	private static final Set<String> REQUESTED = new HashSet<>();

	private ModelCache() {}

	public static Entry get(String key) {
		if (key == null || key.isEmpty()) return null;
		Entry e = ENTRIES.get(key);
		if (e == null && REQUESTED.add(key) && ClientPlayNetworking.canSend(StudioC2S.TYPE)) {
			ClientPlayNetworking.send(new StudioC2S("model.request", "{\"key\":\"" + key + "\"}"));
		}
		return e;
	}

	public static void put(String key, ModelDef def) {
		Identifier tex = MissingTextureAtlasSprite.getLocation();
		if (def.texture.length > 0) {
			try {
				NativeImage img = NativeImage.read(new ByteArrayInputStream(def.texture));
				Identifier id = Identifier.fromNamespaceAndPath("filmstudio", "models/" + key);
				Minecraft mc = Minecraft.getInstance();
				if (ENTRIES.containsKey(key)) mc.getTextureManager().release(id);
				mc.getTextureManager().register(id, new DynamicTexture(id::toString, img));
				tex = id;
			} catch (Exception ex) {
				FilmStudio.LOGGER.warn("Model '{}' has an unreadable texture: {}", key, ex.toString());
			}
		}
		ENTRIES.put(key, new Entry(def, tex));
	}
}
