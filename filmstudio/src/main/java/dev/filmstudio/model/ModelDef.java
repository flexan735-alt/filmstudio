package dev.filmstudio.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * A parsed Blockbench (.bbmodel) model: bone hierarchy, cuboids with UVs, an embedded texture and keyframe
 * animations. Pure data + math (no Minecraft rendering classes) so the server can validate uploads and the
 * client can render it. Units are Blockbench pixels (16 = one block).
 */
public final class ModelDef {
	public static final int NORTH = 0, EAST = 1, SOUTH = 2, WEST = 3, UP = 4, DOWN = 5;
	private static final String[] FACE_NAMES = {"north", "east", "south", "west", "up", "down"};

	public static final class Cube {
		public final float[] from = new float[3], to = new float[3], origin = new float[3], rot = new float[3];
		public float inflate;
		/** Per face: u1,v1,u2,v2 in texture pixels; null = face not drawn. */
		public final float[][] uv = new float[6][];
	}

	public static final class Bone {
		public String name = "root", uuid = "";
		public final float[] origin = new float[3], rot = new float[3];
		public Bone parent;
		public final List<Bone> children = new ArrayList<>();
		public final List<Cube> cubes = new ArrayList<>();
	}

	public static final class Key {
		public float time;
		public final float[] v = new float[3];
	}

	public static final class Track {
		public final List<Key> rot = new ArrayList<>(), pos = new ArrayList<>(), scale = new ArrayList<>();
	}

	public static final class Anim {
		public String name = "";
		/** loop | once | hold */
		public String loop = "loop";
		public float length = 1f;
		public final Map<String, Track> tracks = new HashMap<>();
	}

	public int texW = 16, texH = 16;
	public byte[] texture = new byte[0];
	public final Bone root = new Bone();
	public final List<Bone> bones = new ArrayList<>();
	public final Map<String, Anim> animations = new LinkedHashMap<>();
	private int cubes;

	public int cubeCount() { return cubes; }

	// ------------------------------------------------------------------ parsing
	public static ModelDef parse(String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		ModelDef m = new ModelDef();
		m.bones.add(m.root);

		if (o.has("resolution")) {
			JsonObject r = o.getAsJsonObject("resolution");
			m.texW = Math.max(1, r.get("width").getAsInt());
			m.texH = Math.max(1, r.get("height").getAsInt());
		}
		boolean boxUv = o.has("meta") && o.getAsJsonObject("meta").has("box_uv") && o.getAsJsonObject("meta").get("box_uv").getAsBoolean();

		if (o.has("textures") && o.get("textures").isJsonArray() && !o.getAsJsonArray("textures").isEmpty()) {
			JsonObject t = o.getAsJsonArray("textures").get(0).getAsJsonObject();
			if (t.has("source")) {
				String src = t.get("source").getAsString();
				int c = src.indexOf("base64,");
				if (c >= 0) m.texture = Base64.getMimeDecoder().decode(src.substring(c + 7));
			}
			if (t.has("uv_width") && !o.has("resolution")) m.texW = Math.max(1, t.get("uv_width").getAsInt());
			if (t.has("uv_height") && !o.has("resolution")) m.texH = Math.max(1, t.get("uv_height").getAsInt());
		}

		Map<String, Cube> byUuid = new LinkedHashMap<>();
		Set<String> used = new HashSet<>();
		if (o.has("elements")) {
			for (JsonElement e : o.getAsJsonArray("elements")) {
				JsonObject el = e.getAsJsonObject();
				String type = el.has("type") ? el.get("type").getAsString() : "cube";
				if (!"cube".equals(type)) continue;
				Cube c = parseCube(el, boxUv);
				byUuid.put(el.get("uuid").getAsString(), c);
			}
		}

		Map<String, Bone> boneByUuid = new HashMap<>();
		if (o.has("outliner") && o.get("outliner").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("outliner")) m.parseNode(e, m.root, byUuid, used, boneByUuid);
		}
		for (var en : byUuid.entrySet()) if (!used.contains(en.getKey())) m.root.cubes.add(en.getValue());
		m.cubes = byUuid.size();
		if (m.cubes == 0) throw new IllegalArgumentException("model has no cubes");

		if (o.has("animations") && o.get("animations").isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray("animations")) {
				try { Anim a = parseAnim(e.getAsJsonObject(), boneByUuid); m.animations.put(a.name.toLowerCase(Locale.ROOT), a); }
				catch (Exception ignored) {}
			}
		}
		return m;
	}

	private void parseNode(JsonElement e, Bone parent, Map<String, Cube> byUuid, Set<String> used, Map<String, Bone> boneByUuid) {
		if (e.isJsonPrimitive()) {
			Cube c = byUuid.get(e.getAsString());
			if (c != null) { parent.cubes.add(c); used.add(e.getAsString()); }
			return;
		}
		JsonObject g = e.getAsJsonObject();
		Bone b = new Bone();
		b.name = g.has("name") ? g.get("name").getAsString() : "bone";
		b.uuid = g.has("uuid") ? g.get("uuid").getAsString() : "";
		read3(g, "origin", b.origin, 0);
		read3(g, "rotation", b.rot, 0);
		b.parent = parent;
		parent.children.add(b);
		bones.add(b);
		if (!b.uuid.isEmpty()) boneByUuid.put(b.uuid, b);
		if (g.has("children")) for (JsonElement c : g.getAsJsonArray("children")) parseNode(c, b, byUuid, used, boneByUuid);
	}

	private static Cube parseCube(JsonObject el, boolean boxUv) {
		Cube c = new Cube();
		read3(el, "from", c.from, 0);
		read3(el, "to", c.to, 0);
		read3(el, "origin", c.origin, 0);
		read3(el, "rotation", c.rot, 0);
		c.inflate = el.has("inflate") ? el.get("inflate").getAsFloat() : 0f;
		boolean box = el.has("box_uv") ? el.get("box_uv").getAsBoolean() : boxUv;
		float w = c.to[0] - c.from[0], h = c.to[1] - c.from[1], d = c.to[2] - c.from[2];
		if (box && el.has("uv_offset")) {
			float u = el.getAsJsonArray("uv_offset").get(0).getAsFloat(), v = el.getAsJsonArray("uv_offset").get(1).getAsFloat();
			float wi = Math.abs(w), hi = Math.abs(h), di = Math.abs(d);
			c.uv[UP] = new float[]{u + di, v, u + di + wi, v + di};
			c.uv[DOWN] = new float[]{u + di + wi, v + di, u + di + wi + wi, v};
			c.uv[WEST] = new float[]{u, v + di, u + di, v + di + hi};
			c.uv[NORTH] = new float[]{u + di, v + di, u + di + wi, v + di + hi};
			c.uv[EAST] = new float[]{u + di + wi, v + di, u + di + wi + di, v + di + hi};
			c.uv[SOUTH] = new float[]{u + di + wi + di, v + di, u + di + wi + di + wi, v + di + hi};
		} else if (el.has("faces")) {
			JsonObject faces = el.getAsJsonObject("faces");
			for (int i = 0; i < 6; i++) {
				if (!faces.has(FACE_NAMES[i])) continue;
				JsonObject f = faces.getAsJsonObject(FACE_NAMES[i]);
				if (f.has("texture") && f.get("texture").isJsonNull()) continue;
				if (!f.has("uv")) continue;
				JsonArray a = f.getAsJsonArray("uv");
				c.uv[i] = new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()};
			}
		}
		return c;
	}

	private static void read3(JsonObject o, String key, float[] out, float def) {
		for (int i = 0; i < 3; i++) out[i] = def;
		if (o.has(key) && o.get(key).isJsonArray()) {
			JsonArray a = o.getAsJsonArray(key);
			for (int i = 0; i < 3 && i < a.size(); i++) out[i] = num(a.get(i), def);
		}
	}

	private static float num(JsonElement e, float def) {
		try {
			if (e.isJsonPrimitive()) {
				if (e.getAsJsonPrimitive().isNumber()) return e.getAsFloat();
				String s = e.getAsString().trim();
				if (s.isEmpty()) return def;
				return Float.parseFloat(s);
			}
		} catch (Exception ignored) {}
		return def; // molang expressions are not supported
	}

	private static Anim parseAnim(JsonObject o, Map<String, Bone> boneByUuid) {
		Anim a = new Anim();
		a.name = o.has("name") ? o.get("name").getAsString() : "anim";
		if (a.name.contains(".")) a.name = a.name.substring(a.name.lastIndexOf('.') + 1);
		a.loop = o.has("loop") ? o.get("loop").getAsString() : "once";
		a.length = o.has("length") ? Math.max(0.05f, o.get("length").getAsFloat()) : 1f;
		if (!o.has("animators")) return a;
		for (var en : o.getAsJsonObject("animators").entrySet()) {
			JsonObject an = en.getValue().getAsJsonObject();
			Bone b = boneByUuid.get(en.getKey());
			String bn = b != null ? b.name : (an.has("name") ? an.get("name").getAsString() : null);
			if (bn == null || !an.has("keyframes")) continue;
			Track t = a.tracks.computeIfAbsent(bn.toLowerCase(Locale.ROOT), k -> new Track());
			for (JsonElement ke : an.getAsJsonArray("keyframes")) {
				JsonObject k = ke.getAsJsonObject();
				if (!k.has("data_points") || k.getAsJsonArray("data_points").isEmpty()) continue;
				JsonObject dp = k.getAsJsonArray("data_points").get(0).getAsJsonObject();
				Key key = new Key();
				key.time = k.has("time") ? k.get("time").getAsFloat() : 0f;
				key.v[0] = dp.has("x") ? num(dp.get("x"), 0f) : 0f;
				key.v[1] = dp.has("y") ? num(dp.get("y"), 0f) : 0f;
				key.v[2] = dp.has("z") ? num(dp.get("z"), 0f) : 0f;
				String ch = k.has("channel") ? k.get("channel").getAsString() : "";
				switch (ch) {
					case "rotation" -> t.rot.add(key);
					case "position" -> t.pos.add(key);
					case "scale" -> { if (!dp.has("x")) key.v[0] = key.v[1] = key.v[2] = 1f; t.scale.add(key); }
					default -> { }
				}
			}
			for (List<Key> l : List.of(t.rot, t.pos, t.scale)) l.sort(Comparator.comparingDouble(x -> x.time));
		}
		return a;
	}

	// ------------------------------------------------------------------ animation sampling
	/** Pose for one bone: rotation (deg), position offset (px), scale. */
	public static final class Pose {
		public final float[] rot = new float[3], pos = new float[3], scale = {1, 1, 1};
	}

	private static float[] sampleTrack(List<Key> keys, float t, float[] def) {
		if (keys.isEmpty()) return def;
		if (keys.size() == 1 || t <= keys.get(0).time) return keys.get(0).v;
		for (int i = 0; i < keys.size() - 1; i++) {
			Key a = keys.get(i), b = keys.get(i + 1);
			if (t <= b.time) {
				float f = (t - a.time) / Math.max(1e-4f, b.time - a.time);
				return new float[]{a.v[0] + (b.v[0] - a.v[0]) * f, a.v[1] + (b.v[1] - a.v[1]) * f, a.v[2] + (b.v[2] - a.v[2]) * f};
			}
		}
		return keys.get(keys.size() - 1).v;
	}

	/** Fills poses (by lower-case bone name) for animation {@code a} at time {@code t} seconds. */
	public void sample(Anim a, float t, Map<String, Pose> out) {
		if (a == null) return;
		float time = t;
		if ("loop".equals(a.loop)) time = a.length > 0 ? (t % a.length) : 0;
		else time = Math.min(t, a.length);
		for (var en : a.tracks.entrySet()) {
			Pose p = out.computeIfAbsent(en.getKey(), k -> new Pose());
			Track tr = en.getValue();
			float[] r = sampleTrack(tr.rot, time, new float[3]);
			float[] po = sampleTrack(tr.pos, time, new float[3]);
			float[] s = sampleTrack(tr.scale, time, new float[]{1, 1, 1});
			for (int i = 0; i < 3; i++) { p.rot[i] += r[i]; p.pos[i] += po[i]; p.scale[i] *= s[i]; }
		}
	}

	// ------------------------------------------------------------------ geometry
	/** World-less matrix for a bone (chain from the root), including animation. Result is in pixel space. */
	public Matrix4f boneMatrix(Bone b, Map<String, Pose> poses, Matrix4f dest) {
		Deque<Bone> chain = new ArrayDeque<>();
		for (Bone x = b; x != null; x = x.parent) chain.push(x);
		dest.identity();
		for (Bone x : chain) {
			Pose p = poses == null ? null : poses.get(x.name.toLowerCase(Locale.ROOT));
			dest.translate(x.origin[0], x.origin[1], x.origin[2]);
			if (p != null) dest.translate(p.pos[0], p.pos[1], p.pos[2]);
			dest.rotateZYX((float) Math.toRadians(x.rot[2] + (p == null ? 0 : p.rot[2])),
				(float) Math.toRadians(x.rot[1] + (p == null ? 0 : p.rot[1])),
				(float) Math.toRadians(x.rot[0] + (p == null ? 0 : p.rot[0])));
			if (p != null) dest.scale(p.scale[0], p.scale[1], p.scale[2]);
			dest.translate(-x.origin[0], -x.origin[1], -x.origin[2]);
		}
		return dest;
	}

	/**
	 * Eight corners of a cube in pixel space after its own rotation (about its origin) but before bone transforms.
	 * Corner order: index bits (x=1,y=2,z=4) where 0 = min and 1 = max.
	 */
	public static Vector3f[] cubeCorners(Cube c) {
		Vector3f[] out = new Vector3f[8];
		float inf = c.inflate;
		Matrix4f rot = new Matrix4f();
		boolean rotated = c.rot[0] != 0 || c.rot[1] != 0 || c.rot[2] != 0;
		if (rotated) {
			rot.translate(c.origin[0], c.origin[1], c.origin[2])
				.rotateZYX((float) Math.toRadians(c.rot[2]), (float) Math.toRadians(c.rot[1]), (float) Math.toRadians(c.rot[0]))
				.translate(-c.origin[0], -c.origin[1], -c.origin[2]);
		}
		for (int i = 0; i < 8; i++) {
			float x = (i & 1) == 0 ? c.from[0] - inf : c.to[0] + inf;
			float y = (i & 2) == 0 ? c.from[1] - inf : c.to[1] + inf;
			float z = (i & 4) == 0 ? c.from[2] - inf : c.to[2] + inf;
			Vector3f v = new Vector3f(x, y, z);
			if (rotated) rot.transformPosition(v);
			out[i] = v;
		}
		return out;
	}

	public Anim anim(String... names) {
		for (String n : names) { Anim a = animations.get(n); if (a != null) return a; }
		return null;
	}
}
