package dev.filmstudio.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

/** Client -&gt; server: one named operation with a JSON argument and an optional binary blob (skins, models). */
public record StudioC2S(String op, String json, byte[] data) implements CustomPacketPayload {
	public static final Type<StudioC2S> TYPE = new Type<>(Identifier.fromNamespaceAndPath("filmstudio", "c2s"));
	public static final StreamCodec<FriendlyByteBuf, StudioC2S> CODEC = StreamCodec.ofMember(StudioC2S::write, StudioC2S::read);
	public static final int MAX_SIZE = 32 * 1024 * 1024;

	public StudioC2S(String op, String json) { this(op, json, new byte[0]); }

	private static StudioC2S read(FriendlyByteBuf buf) {
		String op = buf.readUtf(128);
		String json = new String(buf.readByteArray(), StandardCharsets.UTF_8);
		byte[] data = buf.readByteArray();
		return new StudioC2S(op, json, data);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(op, 128);
		buf.writeByteArray(json.getBytes(StandardCharsets.UTF_8));
		buf.writeByteArray(data);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
