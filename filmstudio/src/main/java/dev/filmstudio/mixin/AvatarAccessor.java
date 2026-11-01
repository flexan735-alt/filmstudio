package dev.filmstudio.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives access to the "which skin layers are visible" flag so actors show their jacket, sleeves, hat and cape. */
@Mixin(Avatar.class)
public interface AvatarAccessor {
	@Accessor("DATA_PLAYER_MODE_CUSTOMISATION")
	static EntityDataAccessor<Byte> filmstudioSkinParts() {
		return null;
	}
}
