package dev.filmstudio.client.mixin;

import com.mojang.authlib.properties.Property;
import dev.filmstudio.client.SkinClient;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/** Swaps in an imported skin for actors whose profile carries the "filmstudio_skin" property. */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	@Shadow
	protected abstract PlayerInfo getPlayerInfo();

	@Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
	private void filmstudio$customSkin(CallbackInfoReturnable<PlayerSkin> cir) {
		PlayerInfo info = getPlayerInfo();
		if (info == null) return;
		Collection<Property> props = info.getProfile().properties().get("filmstudio_skin");
		if (props == null || props.isEmpty()) return;

		String value = props.iterator().next().value();
		String[] parts = value.split(":");
		String key = parts[0];
		boolean slim = parts.length > 1 && parts[1].equals("slim");

		Identifier tex = SkinClient.get(key);
		if (tex == null) return; // not downloaded yet: fall back to the default skin for now

		PlayerSkin original = info.getSkin();
		ClientAsset.Texture body = new ClientAsset.ResourceTexture(tex, tex);
		cir.setReturnValue(new PlayerSkin(body, original.cape(), original.elytra(),
			slim ? PlayerModelType.SLIM : PlayerModelType.WIDE, original.secure()));
	}
}
