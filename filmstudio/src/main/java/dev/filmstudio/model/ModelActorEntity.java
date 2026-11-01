package dev.filmstudio.model;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * An actor that wears an imported 3D model (Blockbench .bbmodel) instead of the player model.
 * It has no AI of its own: the playback runtime feeds it the recorded inputs, so it still walks,
 * falls, jumps, takes knockback and can be hurt exactly like the player-based actors.
 */
public class ModelActorEntity extends PathfinderMob {
	public static final ResourceKey<EntityType<?>> KEY =
		ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("filmstudio", "model_actor"));
	public static EntityType<ModelActorEntity> TYPE;

	public static final EntityDataAccessor<String> MODEL = SynchedEntityData.defineId(ModelActorEntity.class, EntityDataSerializers.STRING);

	public String actorId = "";
	/** 0 = normal, 1 = cannot die, 2 = practically invulnerable. */
	public int damageMode = 1;

	public ModelActorEntity(EntityType<? extends ModelActorEntity> type, Level level) {
		super(type, level);
	}

	public static void register() {
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, KEY.identifier(),
			EntityType.Builder.<ModelActorEntity>of(ModelActorEntity::new, MobCategory.MISC)
				.sized(0.6f, 1.8f).eyeHeight(1.62f).clientTrackingRange(10).updateInterval(2).build(KEY));
		FabricDefaultAttributeRegistry.register(TYPE, PathfinderMob.createMobAttributes()
			.add(Attributes.MAX_HEALTH, 20.0)
			.add(Attributes.MOVEMENT_SPEED, 0.1)
			.add(Attributes.ATTACK_DAMAGE, 2.0)
			.add(Attributes.ATTACK_KNOCKBACK, 0.0)
			.add(Attributes.FOLLOW_RANGE, 16.0));
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(MODEL, "");
	}

	public String getModelName() { return this.entityData.get(MODEL); }
	public void setModelName(String n) { this.entityData.set(MODEL, n == null ? "" : n); }

	/** No goals, no wandering: inputs are set from outside every tick and must not be reset by the AI step. */
	@Override
	protected void serverAiStep() {
	}

	@Override
	public boolean removeWhenFarAway(double distanceSq) { return false; }

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (damageMode == 1) amount = Math.min(amount, Math.max(0.0f, getHealth() - 1.0f));
		else if (damageMode == 2) amount = Math.min(amount, 0.01f);
		return super.hurtServer(level, source, amount);
	}
}
