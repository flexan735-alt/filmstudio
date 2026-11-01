package dev.filmstudio.play;

import dev.filmstudio.data.Recording.MobTrack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Re-creates one recorded mob. If the original is still alive in the world it is "adopted" (its AI is paused
 * while it follows the recorded path); otherwise an identical mob is spawned. Real hits from actors still
 * hurt it and knock it back; afterwards it eases back onto its recorded path.
 */
public final class MobPuppet {
	public final MobTrack track;
	public Mob entity;
	private boolean adopted;
	private boolean wasNoAi;
	private boolean dead;
	private int disturbedUntil = -1;
	private boolean spawnFailed;

	public MobPuppet(MobTrack track) { this.track = track; }

	public void disturb(int untilTick) { disturbedUntil = untilTick; }

	public void tick(ServerLevel level, int lt, Vec3 off) {
		if (dead || spawnFailed) return;
		if (entity == null) {
			if (lt < track.startTick || lt >= track.endTick()) return;
			spawn(level, lt, off);
			if (entity == null) return;
		}
		if (entity.isRemoved() || !entity.isAlive()) { dead = true; return; }

		int i = lt - track.startTick;
		if (i >= 0 && i < track.posD.size()) {
			double[] p = track.posD.get(i);
			float[] r = track.pos.get(i);
			double tx = p[0] + off.x, ty = p[1] + off.y, tz = p[2] + off.z;
			if (lt > disturbedUntil) {
				Vec3 cur = entity.position();
				double d = cur.distanceTo(new Vec3(tx, ty, tz));
				if (d > 3.0 || lt > disturbedUntil + 12) {
					entity.setPos(tx, ty, tz);
				} else {
					// Ease back onto the path after a knock-back.
					entity.setPos(cur.x + (tx - cur.x) * 0.35, cur.y + (ty - cur.y) * 0.35, cur.z + (tz - cur.z) * 0.35);
				}
				entity.setYRot(r[0]); entity.setXRot(r[1]); entity.setYHeadRot(r[2]);
				entity.setYBodyRot(r[0]);
			}
		}
		if (track.hurtTicks.contains(lt)) level.broadcastEntityEvent(entity, (byte) 2);
		if (track.deathTick >= 0 && lt >= track.deathTick && entity.isAlive()) {
			entity.kill(level);
			dead = true;
		}
	}

	private void spawn(ServerLevel level, int lt, Vec3 off) {
		try {
			Entity ex = null;
			try { ex = level.getEntity(UUID.fromString(track.uuid)); } catch (Exception ignored) {}
			if (ex instanceof Mob m && m.isAlive()) {
				entity = m; adopted = true; wasNoAi = m.isNoAi();
			} else {
				EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(track.entityType));
				Entity e = type.create(level, EntitySpawnReason.COMMAND);
				if (!(e instanceof Mob m)) { spawnFailed = true; return; }
				if (track.baby) m.setBaby(true);
				if (!track.customName.isEmpty()) m.setCustomName(Component.literal(track.customName));
				if (m instanceof Sheep s && !track.variant.isEmpty()) s.setColor(DyeColor.byName(track.variant, DyeColor.WHITE));
				int i = Math.max(0, Math.min(track.posD.size() - 1, lt - track.startTick));
				double[] p = track.posD.get(i);
				m.setPos(p[0] + off.x, p[1] + off.y, p[2] + off.z);
				m.setPersistenceRequired();
				m.addTag(Playback.SPAWNED_TAG);
				level.addFreshEntity(m);
				entity = m; adopted = false;
			}
			entity.setNoAi(true);
			entity.addTag(Playback.PUPPET_TAG);
		} catch (Exception e) {
			spawnFailed = true;
		}
	}

	public void release() {
		if (entity == null || entity.isRemoved()) return;
		entity.removeTag(Playback.PUPPET_TAG);
		if (adopted) entity.setNoAi(wasNoAi);
		else if (entity.isAlive()) entity.discard();
	}
}
