package dev.filmstudio.data;

import dev.filmstudio.data.Recording.*;

import java.util.*;

/** Non-destructive-ish editing helpers for recordings (the file is rewritten by the caller). */
public final class RecEdit {
	private RecEdit() {}

	/** Keep only ticks [start, end). Everything is shifted so the new recording begins at tick 0. */
	public static void trim(Recording r, int start, int end) {
		int len = r.length();
		start = Math.max(0, Math.min(start, len - 1));
		end = Math.max(start + 1, Math.min(end, len));
		final int s = start, e = end;

		// frames
		List<Frame> nf = new ArrayList<>(r.frames.subList(s, e));
		r.frames.clear(); r.frames.addAll(nf);
		if (!r.frames.isEmpty()) {
			Frame f0 = r.frames.get(0);
			r.ox = f0.x; r.oy = f0.y; r.oz = f0.z; r.oyaw = f0.yaw;
		}

		// events: keep the latest equipment state from before the cut, drop other early events
		Map<String, Ev> equipBefore = new LinkedHashMap<>();
		List<Ev> keep = new ArrayList<>();
		for (Ev ev : r.events) {
			if (ev.tick < s) {
				if (ev.type == Ev.EQUIP) equipBefore.put(ev.a, ev);
				continue;
			}
			if (ev.tick >= e) continue;
			keep.add(ev);
		}
		List<Ev> out = new ArrayList<>();
		for (Ev ev : equipBefore.values()) { ev.tick = 0; out.add(ev); }
		for (Ev ev : keep) { ev.tick -= s; out.add(ev); }
		r.events.clear(); r.events.addAll(out);

		// block deltas: keep them all (they really happened), clamp ticks
		for (Delta d : r.deltas) d.tick = Math.max(0, d.tick - s);

		// mobs
		List<MobTrack> mobs = new ArrayList<>();
		for (MobTrack m : r.mobs) {
			int ms = m.startTick, me = m.endTick();
			if (me <= s || ms >= e) continue;
			int from = Math.max(ms, s), to = Math.min(me, e);
			List<double[]> pd = new ArrayList<>(m.posD.subList(from - ms, to - ms));
			List<float[]> pf = new ArrayList<>(m.pos.subList(from - ms, to - ms));
			m.posD.clear(); m.posD.addAll(pd);
			m.pos.clear(); m.pos.addAll(pf);
			m.startTick = from - s;
			List<Integer> h = new ArrayList<>();
			for (int t : m.hurtTicks) if (t >= s && t < e) h.add(t - s);
			m.hurtTicks.clear(); m.hurtTicks.addAll(h);
			m.deathTick = (m.deathTick >= s && m.deathTick < e) ? m.deathTick - s : -1;
			mobs.add(m);
		}
		r.mobs.clear(); r.mobs.addAll(mobs);
	}
}
