package dev.filmstudio.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

/** Server -&gt; client: a named message with a JSON body and an optional binary blob. */
public record StudioS2C(String op, String json, byte[] data) implements CustomPacketPayload {
	public static final Type<StudioS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath("filmstudio", "s2c"));
	public static final StreamCodec<FriendlyByteBuf, StudioS2C> CODEC = StreamCodec.ofMember(StudioS2C::write, StudioS2C::read);
	public static final int MAX_SIZE = 32 * 1024 * 1024;

	public StudioS2C(String op, String json) { this(op, json, new byte[0]); }

	private static StudioS2C read(FriendlyByteBuf buf) {
		String op = buf.readUtf(128);
		String json = new String(buf.readByteArray(), StandardCharsets.UTF_8);
		byte[] data = buf.readByteArray();
		return new StudioS2C(op, json, data);
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeUtf(op, 128);
		buf.writeByteArray(json.getBytes(StandardCharsets.UTF_8));
		buf.writeByteArray(data);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
