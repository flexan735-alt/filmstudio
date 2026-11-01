package dev.filmstudio.mixin;

import dev.filmstudio.play.ActorPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
	/** Actors never vacuum up items: drops stay on the ground for real players to pick up. */
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void filmstudio$noActorPickup(Player player, CallbackInfo ci) {
		if (player instanceof ActorPlayer) ci.cancel();
	}
}
