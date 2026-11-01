package dev.filmstudio.play;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.world.damagesource.DamageSource;

/** A server-side player with no client. Driven by an {@link ActorRuntime}. */
public class ActorPlayer extends ServerPlayer {
	public final String actorId;
	/** 0 = normal, 1 = cannot die, 2 = takes knockback/hurt animation but practically no damage. */
	public int damageMode = 1;

	public ActorPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, String actorId) {
		super(server, level, profile, ClientInformation.createDefault());
		this.actorId = actorId;
		this.connection = new ActorListener(server, this, profile);
	}

	@Override
	public void tick() {
		super.tick();
		doTick();
		MinecraftServer s = level().getServer();
		if (s != null && s.getTickCount() % 10 == 0) {
			((ServerLevel) level()).getChunkSource().move(this);
		}
	}

	@Override
	@SuppressWarnings({"rawtypes"})
	public void awardStat(Stat stat, int amount) {
		// Actors do not earn statistics.
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (damageMode == 1) amount = Math.min(amount, Math.max(0.0f, getHealth() - 1.0f));
		else if (damageMode == 2) amount = Math.min(amount, 0.01f);
		return super.hurtServer(level, source, amount);
	}

	@Override
	public void die(DamageSource source) {
		// Equipment belongs to the recording, not to the world: do not spill copies of it.
		getInventory().clearContent();
		super.die(source);
	}
}
