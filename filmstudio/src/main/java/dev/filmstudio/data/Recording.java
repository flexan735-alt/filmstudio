package dev.filmstudio.data;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One recorded take of one actor. Stored per tick: pose + movement flags (Frame),
 * discrete things that happened (Ev), mobs seen near the actor (MobTrack) and
 * block changes (Delta) so the world can be restored between takes.
 */
public final class Recording {
	public static final int VERSION = 1;

	public String dimension = "minecraft:overworld";
	public double ox, oy, oz;
	public float oyaw;
	public final List<Frame> frames = new ArrayList<>();
	public final List<Ev> events = new ArrayList<>();
	public final List<MobTrack> mobs = new ArrayList<>();
	public final List<Delta> deltas = new ArrayList<>();

	public int length() { return frames.size(); }

	// ---------------------------------------------------------------- Frame
	public static final class Frame {
		public static final int SNEAK = 1, SPRINT = 2, FLY = 4, GLIDE = 8, GROUND = 16, SWIM = 32, CRAWL = 64, USING = 128,
			JUMP = 256, FWD = 512, BACK = 1024, LEFT = 2048, RIGHT = 4096, UP = 8192, DOWN = 16384, SLEEP = 32768, WATER = 65536;
		public double x, y, z;
		public float yaw, pitch, head;
		public int flags;

		public boolean has(int f) { return (flags & f) != 0; }
	}

	// ---------------------------------------------------------------- Event
	public static final class Ev {
		public static final int SWING = 1, EQUIP = 2, USE_ITEM = 3, USE_RELEASE = 4, USE_BLOCK = 5, DIG_START = 6, BREAK = 7,
			ATTACK = 8, INTERACT_ENTITY = 9, DROP = 10, HURT = 11, DEATH = 12, CHAT = 13;
		/** Target kinds for ATTACK / INTERACT_ENTITY. */
		public static final int T_MOB = 1, T_ACTOR = 2, T_OTHER = 3;

		public int tick;
		public int type;
		public String a = "", b = "";
		public int i1, i2, i3;
		public long l;
		public double d1, d2, d3;
		public float f1;

		public Ev(int tick, int type) { this.tick = tick; this.type = type; }
	}

	// ---------------------------------------------------------------- Mobs
	public static final class MobTrack {
		public int id;
		public String uuid = "";
		public String entityType = "";
		public String customName = "";
		public boolean baby;
		/** Extra variant string (sheep colour etc.), optional. */
		public String variant = "";
		public int startTick;
		public boolean interacted;
		/** One entry per tick starting at startTick: x,y,z,yaw,pitch,head. */
		public final List<float[]> pos = new ArrayList<>();
		public final List<double[]> posD = new ArrayList<>();
		public final List<Integer> hurtTicks = new ArrayList<>();
		public int deathTick = -1;

		public int endTick() { return startTick + posD.size(); }
	}

	// ---------------------------------------------------------------- Block deltas
	public static final class Delta {
		public int tick;
		public long pos;
		public String before = "", after = "";
	}

	// ---------------------------------------------------------------- IO
	public void write(OutputStream os) throws IOException {
		try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(os)))) {
			o.writeInt(VERSION);
			o.writeUTF(dimension);
			o.writeDouble(ox); o.writeDouble(oy); o.writeDouble(oz); o.writeFloat(oyaw);

			o.writeInt(frames.size());
			for (Frame f : frames) {
				o.writeDouble(f.x); o.writeDouble(f.y); o.writeDouble(f.z);
				o.writeFloat(f.yaw); o.writeFloat(f.pitch); o.writeFloat(f.head);
				o.writeInt(f.flags);
			}

			o.writeInt(events.size());
			for (Ev e : events) {
				o.writeInt(e.tick); o.writeInt(e.type);
				writeLong(o, e.a); writeLong(o, e.b);
				o.writeInt(e.i1); o.writeInt(e.i2); o.writeInt(e.i3);
				o.writeLong(e.l);
				o.writeDouble(e.d1); o.writeDouble(e.d2); o.writeDouble(e.d3);
				o.writeFloat(e.f1);
			}

			o.writeInt(mobs.size());
			for (MobTrack m : mobs) {
				o.writeInt(m.id); o.writeUTF(m.uuid); o.writeUTF(m.entityType); writeLong(o, m.customName);
				o.writeBoolean(m.baby); writeLong(o, m.variant);
				o.writeInt(m.startTick); o.writeBoolean(m.interacted);
				o.writeInt(m.posD.size());
				for (int i = 0; i < m.posD.size(); i++) {
					double[] d = m.posD.get(i); float[] f = m.pos.get(i);
					o.writeDouble(d[0]); o.writeDouble(d[1]); o.writeDouble(d[2]);
					o.writeFloat(f[0]); o.writeFloat(f[1]); o.writeFloat(f[2]);
				}
				o.writeInt(m.hurtTicks.size());
				for (int t : m.hurtTicks) o.writeInt(t);
				o.writeInt(m.deathTick);
			}

			o.writeInt(deltas.size());
			for (Delta d : deltas) {
				o.writeInt(d.tick); o.writeLong(d.pos); writeLong(o, d.before); writeLong(o, d.after);
			}
		}
	}

	public static Recording read(InputStream is) throws IOException {
		Recording r = new Recording();
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(is)))) {
			int ver = in.readInt();
			if (ver != VERSION) throw new IOException("Unsupported recording version " + ver);
			r.dimension = in.readUTF();
			r.ox = in.readDouble(); r.oy = in.readDouble(); r.oz = in.readDouble(); r.oyaw = in.readFloat();

			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				Frame f = new Frame();
				f.x = in.readDouble(); f.y = in.readDouble(); f.z = in.readDouble();
				f.yaw = in.readFloat(); f.pitch = in.readFloat(); f.head = in.readFloat();
				f.flags = in.readInt();
				r.frames.add(f);
			}

			n = in.readInt();
			for (int i = 0; i < n; i++) {
				Ev e = new Ev(in.readInt(), in.readInt());
				e.a = readLong(in); e.b = readLong(in);
				e.i1 = in.readInt(); e.i2 = in.readInt(); e.i3 = in.readInt();
				e.l = in.readLong();
				e.d1 = in.readDouble(); e.d2 = in.readDouble(); e.d3 = in.readDouble();
				e.f1 = in.readFloat();
				r.events.add(e);
			}

			n = in.readInt();
			for (int i = 0; i < n; i++) {
				MobTrack m = new MobTrack();
				m.id = in.readInt(); m.uuid = in.readUTF(); m.entityType = in.readUTF(); m.customName = readLong(in);
				m.baby = in.readBoolean(); m.variant = readLong(in);
				m.startTick = in.readInt(); m.interacted = in.readBoolean();
				int c = in.readInt();
				for (int k = 0; k < c; k++) {
					m.posD.add(new double[]{in.readDouble(), in.readDouble(), in.readDouble()});
					m.pos.add(new float[]{in.readFloat(), in.readFloat(), in.readFloat()});
				}
				c = in.readInt();
				for (int k = 0; k < c; k++) m.hurtTicks.add(in.readInt());
				m.deathTick = in.readInt();
				r.mobs.add(m);
			}

			n = in.readInt();
			for (int i = 0; i < n; i++) {
				Delta d = new Delta();
				d.tick = in.readInt(); d.pos = in.readLong(); d.before = readLong(in); d.after = readLong(in);
				r.deltas.add(d);
			}
		}
		return r;
	}

	/** writeUTF is limited to 64 KB; item stacks with big components can exceed that, so use length-prefixed bytes. */
	private static void writeLong(DataOutputStream o, String s) throws IOException {
		byte[] b = (s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8);
		o.writeInt(b.length); o.write(b);
	}

	private static String readLong(DataInputStream in) throws IOException {
		int len = in.readInt();
		if (len < 0 || len > 64 * 1024 * 1024) throw new IOException("Corrupt string length");
		byte[] b = new byte[len]; in.readFully(b);
		return new String(b, java.nio.charset.StandardCharsets.UTF_8);
	}
}
