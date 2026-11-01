package dev.filmstudio.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.filmstudio.client.ui.DashboardScreen;
import dev.filmstudio.model.ModelDef;
import dev.filmstudio.net.StudioC2S;
import dev.filmstudio.net.StudioS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;

public final class ClientNet {
	private ClientNet() {}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(StudioS2C.TYPE, (payload, ctx) -> handle(payload));
	}

	public static void send(String op, JsonObject json) {
		send(op, json, new byte[0]);
	}

	public static void send(String op, JsonObject json, byte[] data) {
		if (!ClientPlayNetworking.canSend(StudioC2S.TYPE)) {
			ClientState.say("Film Studio is not installed on this server.");
			return;
		}
		ClientPlayNetworking.send(new StudioC2S(op, json == null ? "{}" : json.toString(), data));
	}

	public static JsonObject obj() { return new JsonObject(); }

	private static void handle(StudioS2C p) {
		try {
			JsonObject j = p.json().isBlank() ? new JsonObject() : JsonParser.parseString(p.json()).getAsJsonObject();
			switch (p.op()) {
				case "open" -> Minecraft.getInstance().setScreen(new DashboardScreen());
				case "films" -> ClientState.setFilms(j);
				case "status" -> ClientState.setStatus(j);
				case "msg" -> {
					String t = j.get("text").getAsString();
					ClientState.say(t.replaceAll("\u00a7.", ""));
					Minecraft mc = Minecraft.getInstance();
					if (mc.player != null) mc.player.displayClientMessage(Component.literal(t), true);
				}
				case "skin.data" -> SkinClient.register(j.get("key").getAsString(), p.data());
				case "model.data" -> ModelCache.put(j.get("key").getAsString(), ModelDef.parse(new String(p.data(), StandardCharsets.UTF_8)));
				default -> { }
			}
		} catch (Exception e) {
			dev.filmstudio.FilmStudio.LOGGER.warn("Bad Film Studio message '{}': {}", p.op(), e.toString());
		}
	}
}
