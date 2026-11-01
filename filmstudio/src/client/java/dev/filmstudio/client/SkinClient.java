package dev.filmstudio.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.filmstudio.FilmStudio;
import dev.filmstudio.net.StudioC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Client-side registry of imported actor skins (PNG bytes sent by the server -&gt; dynamic textures). */
public final class SkinClient {
	private static final Map<String, Identifier> TEXTURES = new HashMap<>();
	private static final Set<String> REQUESTED = new HashSet<>();

	private SkinClient() {}

	public static Identifier id(String key) {
		return Identifier.fromNamespaceAndPath("filmstudio", "skins/" + key);
	}

	/** Returns the texture if we have it; otherwise asks the server for it once and returns null. */
	public static Identifier get(String key) {
		Identifier t = TEXTURES.get(key);
		if (t != null) return t;
		if (REQUESTED.add(key) && ClientPlayNetworking.canSend(StudioC2S.TYPE)) {
			ClientPlayNetworking.send(new StudioC2S("skin.request", "{\"key\":\"" + key + "\"}"));
		}
		return null;
	}

	public static void register(String key, byte[] png) {
		try {
			NativeImage image = NativeImage.read(new ByteArrayInputStream(png));
			Identifier id = id(key);
			Minecraft mc = Minecraft.getInstance();
			if (TEXTURES.containsKey(key)) mc.getTextureManager().release(id);
			mc.getTextureManager().register(id, new DynamicTexture(id::toString, image));
			TEXTURES.put(key, id);
		} catch (Exception e) {
			FilmStudio.LOGGER.warn("Could not load skin '{}': {}", key, e.toString());
		}
	}
}
