package dev.filmstudio.mixin;

import dev.filmstudio.rec.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	/** Lets the recorder see every arm swing (attacks, mining, placing, empty-air clicks). */
	@Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"))
	private void filmstudio$onSwing(InteractionHand hand, boolean updateSelf, CallbackInfo ci) {
		Hooks.onSwing((LivingEntity) (Object) this, hand);
	}

	/** Lets the recorder note hurt/killed mobs, and playback note knock-back so it can recover afterwards. */
	@Inject(method = "hurtServer", at = @At("RETURN"))
	private void filmstudio$onHurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) Hooks.onHurt((LivingEntity) (Object) this, source);
	}
}
