package dev.filmstudio.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.filmstudio.client.ui.DashboardScreen;
import dev.filmstudio.model.ModelActorEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class FilmStudioClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientNet.init();
		EntityRendererRegistry.register(ModelActorEntity.TYPE, ModelActorRenderer::new);

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("filmstudio", "main"));
		KeyMapping open = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.filmstudio.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
		KeyMapping record = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.filmstudio.record", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
		KeyMapping stop = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.filmstudio.stop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, category));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (open.consumeClick()) {
				if (mc.player != null && mc.screen == null) mc.setScreen(new DashboardScreen());
			}
			while (record.consumeClick()) {
				if (mc.player == null) continue;
				boolean busy = ClientState.mode.equals("recording") || ClientState.mode.equals("rec_countdown");
				if (busy) {
					JsonObject o = new JsonObject();
					o.addProperty("save", true);
					ClientNet.send("rec.stop", o);
				} else if (ClientState.film() != null && ClientState.actor() != null) {
					JsonObject o = new JsonObject();
					o.addProperty("film", ClientState.selFilm);
					o.addProperty("actor", ClientState.selActor);
					ClientNet.send("rec.start", o);
				} else {
					ClientState.say("Open the studio (K) and pick a film and actor first.");
					ClientNet.send("sync", null);
				}
			}
			while (stop.consumeClick()) {
				if (mc.player == null) continue;
				ClientNet.send("play.stop", null);
			}
		});
	}
}
