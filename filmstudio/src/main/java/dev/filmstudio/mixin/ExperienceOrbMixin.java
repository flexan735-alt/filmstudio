package dev.filmstudio.mixin;

import dev.filmstudio.play.ActorPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void filmstudio$noActorXp(Player player, CallbackInfo ci) {
		if (player instanceof ActorPlayer) ci.cancel();
	}
}
