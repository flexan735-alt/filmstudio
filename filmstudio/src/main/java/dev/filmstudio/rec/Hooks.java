package dev.filmstudio.rec;

import dev.filmstudio.play.Playback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Entry points called from mixins. Kept tiny and exception-safe so a bug here can never crash the game loop. */
public final class Hooks {
	private Hooks() {}

	public static void onSwing(LivingEntity e, InteractionHand hand) {
		try {
			if (e instanceof ServerPlayer sp && !Recorder.ACTIVE.isEmpty()) {
				Recorder r = Recorder.ACTIVE.get(sp.getUUID());
				if (r != null) r.onSwing(hand);
			}
		} catch (Throwable ignored) {}
	}

	public static void onHurt(LivingEntity e, DamageSource src) {
		try {
			if (!Recorder.ACTIVE.isEmpty()) {
				if (e instanceof ServerPlayer sp) {
					Recorder r = Recorder.ACTIVE.get(sp.getUUID());
					if (r != null) {
						r.onHurtSelf(src.getEntity() == null ? sp.getYRot() : sp.getYRot());
						if (sp.isDeadOrDying()) r.onDeathSelf();
					}
				}
				for (Recorder r : Recorder.ACTIVE.values()) r.onEntityHurt(e);
			}
			Playback.noteHurt(e);
		} catch (Throwable ignored) {}
	}

	public static void onDrop(Player p, ItemStack stack, boolean thrownFromHand) {
		try {
			if (thrownFromHand && p instanceof ServerPlayer sp && !Recorder.ACTIVE.isEmpty()) {
				Recorder r = Recorder.ACTIVE.get(sp.getUUID());
				if (r != null) r.onDrop(stack.copy());
			}
		} catch (Throwable ignored) {}
	}
}
