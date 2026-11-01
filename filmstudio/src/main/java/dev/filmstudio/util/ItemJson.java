package dev.filmstudio.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/** Lossless ItemStack &lt;-&gt; JSON text (keeps enchantments, names, custom data, etc.) using the vanilla codec. */
public final class ItemJson {
	private ItemJson() {}

	public static String encode(MinecraftServer server, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return "";
		try {
			RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
			return ItemStack.CODEC.encodeStart(ops, stack).result().map(JsonElement::toString).orElse("");
		} catch (Exception e) {
			return "";
		}
	}

	public static ItemStack decode(MinecraftServer server, String json) {
		if (json == null || json.isEmpty()) return ItemStack.EMPTY;
		try {
			RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
			return ItemStack.CODEC.parse(ops, JsonParser.parseString(json)).result().orElse(ItemStack.EMPTY);
		} catch (Exception e) {
			return ItemStack.EMPTY;
		}
	}
}
