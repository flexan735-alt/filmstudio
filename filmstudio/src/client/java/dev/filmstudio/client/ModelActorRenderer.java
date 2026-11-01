package dev.filmstudio.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.filmstudio.model.ModelActorEntity;
import dev.filmstudio.model.ModelDef;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Draws a {@link ModelActorEntity} from its imported Blockbench model: every cuboid is emitted as six textured
 * quads through the bone hierarchy, with the model's own animations (idle / walk / attack / death) when present
 * and a simple limb-swing fallback when not.
 */
public class ModelActorRenderer extends EntityRenderer<ModelActorEntity, ModelActorRenderer.State> {

	public static class State extends EntityRenderState {
		public String model = "";
		public float bodyYaw, walkPos, walkSpeed, attack, scale = 1f;
		public int deathTime;
		public boolean moving;
	}

	// corner indices (bit0 = x max, bit1 = y max, bit2 = z max) for the four corners of each face: TL, TR, BR, BL
	private static final int[][] FACE_CORNERS = {
		/* north */ {1 | 2, 0 | 2, 0, 1},
		/* east  */ {1 | 2 | 4, 1 | 2, 1, 1 | 4},
		/* south */ {0 | 2 | 4, 1 | 2 | 4, 1 | 4, 4},
		/* west  */ {2, 2 | 4, 4, 0},
		/* up    */ {2, 1 | 2, 1 | 2 | 4, 2 | 4},
		/* down  */ {4, 1 | 4, 1, 0},
	};
	private static final float[][] FACE_NORMALS = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

	public ModelActorRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() { return new State(); }

	@Override
	public void extractRenderState(ModelActorEntity e, State s, float partialTick) {
		super.extractRenderState(e, s, partialTick);
		s.model = e.getModelName();
		s.bodyYaw = Mth.rotLerp(partialTick, e.yBodyRotO, e.yBodyRot);
		s.walkPos = e.walkAnimation.position(partialTick);
		s.walkSpeed = e.walkAnimation.speed(partialTick);
		s.attack = e.getAttackAnim(partialTick);
		s.scale = e.getScale();
		s.deathTime = e.deathTime;
		s.moving = s.walkSpeed > 0.04f;
	}

	@Override
	public void submit(State s, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		ModelCache.Entry entry = ModelCache.get(s.model);
		if (entry != null) {
			ModelDef def = entry.def;
			Map<String, ModelDef.Pose> poses = new HashMap<>();
			float time = s.ageInTicks / 20f;

			ModelDef.Anim idle = def.anim("idle", "stand");
			ModelDef.Anim walk = def.anim("walk", "run", "move");
			ModelDef.Anim attack = def.anim("attack", "swing", "hit");
			ModelDef.Anim death = def.anim("death", "die");

			if (s.deathTime > 0 && death != null) def.sample(death, s.deathTime / 20f, poses);
			else if (s.attack > 0 && attack != null) def.sample(attack, s.attack * attack.length, poses);
			else if (s.moving && walk != null) def.sample(walk, time, poses);
			else if (idle != null) def.sample(idle, time, poses);
			else if (s.moving) limbSwing(def, s.walkPos, Math.min(1f, s.walkSpeed * 2f), poses);

			poseStack.pushPose();
			poseStack.mulPose(Axis.YP.rotationDegrees(180f - s.bodyYaw));
			if (s.deathTime > 0 && death == null) {
				float t = Math.min(1f, s.deathTime / 20f);
				poseStack.mulPose(Axis.ZP.rotationDegrees(90f * t));
			}
			float k = s.scale / 16f;
			poseStack.scale(k, k, k);

			final int light = s.lightCoords;
			collector.submitCustomGeometry(poseStack, RenderTypes.entityCutoutNoCull(entry.texture), (pose, vc) -> {
				Matrix4f m = new Matrix4f();
				Vector3f p = new Vector3f(), n = new Vector3f();
				for (ModelDef.Bone bone : def.bones) {
					if (bone.cubes.isEmpty()) continue;
					def.boneMatrix(bone, poses, m);
					for (ModelDef.Cube cube : bone.cubes) emitCube(vc, pose, m, entry, cube, light, p, n);
				}
			});
			poseStack.popPose();
		}
		super.submit(s, poseStack, collector, camera);
	}

	private void emitCube(VertexConsumer vc, PoseStack.Pose pose, Matrix4f m, ModelCache.Entry entry, ModelDef.Cube cube,
						  int light, Vector3f p, Vector3f n) {
		Vector3f[] corners = entry.corners(cube);
		ModelDef def = entry.def;
		for (int face = 0; face < 6; face++) {
			float[] uv = cube.uv[face];
			if (uv == null) continue;
			float u1 = uv[0] / def.texW, v1 = uv[1] / def.texH, u2 = uv[2] / def.texW, v2 = uv[3] / def.texH;
			// TL, BL, BR, TR (counter-clockwise seen from outside)
			int[] c = FACE_CORNERS[face];
			int[] order = {0, 3, 2, 1};
			float[][] uvs = {{u1, v1}, {u2, v1}, {u2, v2}, {u1, v2}};
			n.set(FACE_NORMALS[face][0], FACE_NORMALS[face][1], FACE_NORMALS[face][2]);
			m.transformDirection(n);
			if (n.lengthSquared() > 1e-6f) n.normalize();
			for (int idx : order) {
				p.set(corners[c[idx]]);
				m.transformPosition(p);
				vc.addVertex(pose, p.x, p.y, p.z)
					.setColor(255, 255, 255, 255)
					.setUv(uvs[idx][0], uvs[idx][1])
					.setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(light)
					.setNormal(pose, n.x, n.y, n.z);
			}
		}
	}

	/** Fallback walk cycle for models without animations: swing bones whose names contain "arm" or "leg". */
	private static void limbSwing(ModelDef def, float pos, float amount, Map<String, ModelDef.Pose> out) {
		for (ModelDef.Bone b : def.bones) {
			String name = b.name.toLowerCase(Locale.ROOT);
			boolean arm = name.contains("arm"), leg = name.contains("leg");
			if (!arm && !leg) continue;
			boolean left = name.contains("left") || name.endsWith("_l") || name.startsWith("l_") || name.contains("leftarm");
			float swing = (float) Math.cos(pos * 0.6662f) * 1.4f * amount * (180f / (float) Math.PI) * 0.5f;
			float dir = (left ? 1f : -1f) * (arm ? -1f : 1f);
			ModelDef.Pose pose = out.computeIfAbsent(name, k -> new ModelDef.Pose());
			pose.rot[0] += swing * dir;
		}
	}
}
