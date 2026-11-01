package dev.filmstudio.play;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * A network handler that is connected to nothing. Actors are real ServerPlayer objects (so they get real
 * physics, combat and block interaction), but there is no client behind them, so every packet is dropped.
 */
public class ActorListener extends ServerGamePacketListenerImpl {
	public ActorListener(MinecraftServer server, ServerPlayer player, GameProfile profile) {
		super(server, new Connection(PacketFlow.SERVERBOUND), player,
			new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false));
	}

	/** Required on 1.21.6+: players whose client has not "loaded" yet are treated as invulnerable. */
	@Override
	public boolean hasClientLoaded() {
		return true;
	}

	@Override
	public void resetPosition() {
	}

	@Override
	public void tick() {
		// No keep-alives, no timeouts, no movement validation.
	}

	@Override
	public void send(Packet<?> packet) {
		// Nobody is listening.
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener listener) {
		// Nobody is listening.
	}
}
