package dev.filmstudio.client.ui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.filmstudio.client.ClientNet;
import dev.filmstudio.client.ClientState;
import dev.filmstudio.data.ActorSpec;
import dev.filmstudio.data.Film;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The Film Studio dashboard. One screen, five tabs. The game keeps running while it is open (it never pauses),
 * so you can leave it open while an actor performs.
 */
public class DashboardScreen extends Screen {
	private static final Gson GSON = new Gson();
	private enum Tab {
		FILMS("Films"), ACTORS("Actors"), RECORD("Record"), PLAYBACK("Playback"), ASSETS("Assets");
		final String label;
		Tab(String l) { label = l; }
	}

	private static final String[] COLORS = {"white", "gray", "dark_gray", "black", "red", "dark_red", "gold", "yellow", "green", "dark_green",
		"aqua", "dark_aqua", "blue", "dark_blue", "light_purple", "dark_purple"};

	// state that survives rebuilds / reopening
	private static Tab tab = Tab.FILMS;
	private static int actorSub = 0;
	private static int listPage = 0;
	private static boolean relativePlay = false;
	private static int playFrom = 0;
	private static ActorSpec edit;
	private static boolean dirty;
	private static String confirmDelete = "";
	private static String importPathSkin = "", importPathModel = "", newFilmName = "", newActorName = "";
	private static String trimStart = "0", trimEnd = "";

	private record Label(int x, int y, String text, int color) {}
	private final List<Label> labels = new ArrayList<>();
	private String lastMode = "idle";

	// text fields that exist only on some tabs
	private EditBox fName, fPrefix, fSuffix, fSkinVal, fDelay, fFilmName, fFilmNotes, fNewFilm, fNewActor, fImportSkin, fImportModel,
		fCountdown, fRadius, fTrimStart, fTrimEnd;
	private final EditBox[] fEquip = new EditBox[6];

	private int cx, cy, cw; // content rectangle

	public DashboardScreen() {
		super(Component.literal("Film Studio"));
		ClientNet.send("sync", null);
	}

	@Override
	public boolean isPauseScreen() { return false; }

	// ------------------------------------------------------------------ lifecycle
	@Override
	protected void init() {
		ClientState.onFilms = () -> { if (!dirty) syncEdit(); rebuildWidgets(); };
		ClientState.onStatus = () -> {
			if (!ClientState.mode.equals(lastMode)) { lastMode = ClientState.mode; rebuildWidgets(); }
		};
		if (edit == null || ClientState.actor() == null || !edit.id.equals(ClientState.selActor)) syncEdit();
		lastMode = ClientState.mode;
		labels.clear();
		cx = 108; cy = 32; cw = width - cx - 8;

		// sidebar
		int y = 30;
		for (Tab t : Tab.values()) {
			final Tab target = t;
			Button b = btn(8, y, 92, 20, t.label, () -> { applyIfDirty(); tab = target; listPage = 0; confirmDelete = ""; rebuildWidgets(); });
			b.active = t != tab;
			y += 24;
		}
		btn(8, height - 52, 92, 20, "Refresh", () -> ClientNet.send("sync", null));
		btn(8, height - 28, 92, 20, "Close", () -> onClose());

		if (ClientState.films.isEmpty() && tab != Tab.FILMS) tab = Tab.FILMS;
		switch (tab) {
			case FILMS -> buildFilms();
			case ACTORS -> buildActors();
			case RECORD -> buildRecord();
			case PLAYBACK -> buildPlayback();
			case ASSETS -> buildAssets();
		}
	}

	@Override
	public void removed() {
		applyIfDirty();
		if (ClientState.onFilms != null) { ClientState.onFilms = null; ClientState.onStatus = null; }
	}

	private static void syncEdit() {
		ActorSpec a = ClientState.actor();
		edit = a == null ? null : a.copy();
		dirty = false;
	}

	private void applyIfDirty() {
		if (edit != null && dirty) apply();
	}

	private void commitBoxes() {
		if (edit == null) return;
		if (fName != null) edit.name = fName.getValue();
		if (fPrefix != null) edit.prefix = fPrefix.getValue();
		if (fSuffix != null) edit.suffix = fSuffix.getValue();
		if (fSkinVal != null && !"file".equals(edit.skinType)) edit.skinValue = fSkinVal.getValue();
		if (fDelay != null) edit.startDelay = parseInt(fDelay.getValue(), edit.startDelay);
		boolean any = false;
		String[] names = {"mainhand", "offhand", "head", "chest", "legs", "feet"};
		for (int i = 0; i < 6; i++) {
			if (fEquip[i] == null) continue;
			any = true;
			String v = fEquip[i].getValue().trim();
			if (v.isEmpty()) edit.equipOverride.remove(names[i]); else edit.equipOverride.put(names[i], v);
		}
		if (any || fName != null) dirty = true;
	}

	private void apply() {
		commitBoxes();
		Film f = ClientState.film();
		if (f == null || edit == null) return;
		JsonObject o = new JsonObject();
		o.addProperty("film", f.id);
		o.add("actor", GSON.toJsonTree(edit));
		ClientNet.send("actor.update", o);
		dirty = false;
	}

	private static int parseInt(String s, int def) {
		try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
	}

	// ------------------------------------------------------------------ widget helpers
	private Button btn(int x, int y, int w, int h, String label, Runnable r) {
		return addRenderableWidget(Button.builder(Component.literal(label), b -> r.run()).bounds(x, y, w, h).build());
	}

	private EditBox box(int x, int y, int w, String value, String hint, int max) {
		EditBox e = new EditBox(font, x, y, w, 20, Component.literal(hint));
		e.setMaxLength(max);
		e.setHint(Component.literal(hint));
		e.setValue(value == null ? "" : value);
		return addRenderableWidget(e);
	}

	private void label(int x, int y, String t) { labels.add(new Label(x, y, t, 0xFFB0B0B0)); }

	private Button toggle(int x, int y, int w, String name, Supplier<Boolean> get, Consumer<Boolean> set) {
		final Button[] self = new Button[1];
		self[0] = btn(x, y, w, 20, name + ": " + (get.get() ? "ON" : "OFF"), () -> {
			boolean v = !get.get();
			set.accept(v);
			self[0].setMessage(Component.literal(name + ": " + (v ? "ON" : "OFF")));
		});
		return self[0];
	}

	private Button cycle(int x, int y, int w, String name, List<String> options, Supplier<String> get, Consumer<String> set) {
		final Button[] self = new Button[1];
		self[0] = btn(x, y, w, 20, name + ": " + shown(get.get()), () -> {
			int i = options.indexOf(get.get());
			String next = options.get((i + 1) % options.size());
			set.accept(next);
			self[0].setMessage(Component.literal(name + ": " + shown(next)));
		});
		return self[0];
	}

	private static String shown(String s) { return s == null || s.isEmpty() ? "(none)" : s.replace('_', ' '); }

	private static class IntSlider extends AbstractSliderButton {
		private final String name;
		private final int min, max;
		private final IntConsumer cb;

		IntSlider(int x, int y, int w, String name, int min, int max, int initial, IntConsumer cb) {
			super(x, y, w, 20, Component.empty(), max == min ? 0 : (initial - min) / (double) (max - min));
			this.name = name; this.min = min; this.max = max; this.cb = cb;
			updateMessage();
		}

		int current() { return min + (int) Math.round(this.value * (max - min)); }

		@Override
		protected void updateMessage() { setMessage(Component.literal(name + ": " + current())); }

		@Override
		protected void applyValue() { cb.accept(current()); }
	}

	private static class ScaleSlider extends AbstractSliderButton {
		private final Consumer<Float> cb;

		ScaleSlider(int x, int y, int w, float scale, Consumer<Float> cb) {
			super(x, y, w, 20, Component.empty(), Math.max(0, Math.min(1, (scale - 0.1) / 4.9)));
			this.cb = cb;
			updateMessage();
		}

		float current() { return Math.round((0.1f + (float) this.value * 4.9f) * 20f) / 20f; }

		@Override
		protected void updateMessage() { setMessage(Component.literal(String.format(Locale.ROOT, "Size: %.2fx", current()))); }

		@Override
		protected void applyValue() { cb.accept(current()); }
	}

	// ------------------------------------------------------------------ FILMS
	private void buildFilms() {
		int perPage = 8;
		List<Film> films = ClientState.films;
		int pages = Math.max(1, (films.size() + perPage - 1) / perPage);
		listPage = Math.max(0, Math.min(listPage, pages - 1));
		label(cx, cy - 12, "Your films");
		int y = cy;
		for (int i = listPage * perPage; i < Math.min(films.size(), (listPage + 1) * perPage); i++) {
			Film f = films.get(i);
			boolean sel = f.id.equals(ClientState.selFilm);
			Button b = btn(cx, y, 160, 20, (sel ? "\u25b6 " : "") + f.name + " (" + f.actors.size() + ")", () -> {
				applyIfDirty();
				ClientState.selFilm = f.id;
				ClientState.selActor = f.actors.isEmpty() ? "" : f.actors.get(0).id;
				syncEdit();
				rebuildWidgets();
			});
			b.active = !sel;
			y += 22;
		}
		if (pages > 1) {
			btn(cx, y + 2, 78, 18, "< Prev", () -> { listPage--; rebuildWidgets(); }).active = listPage > 0;
			btn(cx + 82, y + 2, 78, 18, "Next >", () -> { listPage++; rebuildWidgets(); }).active = listPage < pages - 1;
		}

		int by = height - 54;
		label(cx, by - 12, "New film");
		fNewFilm = box(cx, by, 110, newFilmName, "Film name", 40);
		fNewFilm.setResponder(s -> newFilmName = s);
		btn(cx + 114, by, 46, 20, "Create", () -> {
			JsonObject o = new JsonObject();
			o.addProperty("name", newFilmName.isBlank() ? "New Film" : newFilmName);
			ClientNet.send("film.create", o);
			newFilmName = "";
		});

		Film f = ClientState.film();
		if (f == null) return;
		int rx = cx + 176, rw = cw - 176;
		label(rx, cy - 12, "Selected film");
		fFilmName = box(rx, cy, Math.min(200, rw), f.name, "Film name", 40);
		btn(rx + Math.min(200, rw) + 4, cy, 60, 20, "Rename", () -> {
			JsonObject o = new JsonObject();
			o.addProperty("film", f.id); o.addProperty("name", fFilmName.getValue());
			ClientNet.send("film.update", o);
		});
		label(rx, cy + 26, "Notes");
		fFilmNotes = box(rx, cy + 38, Math.min(264, rw), f.notes, "Notes for this film", 200);
		btn(rx, cy + 64, 100, 20, "Save notes", () -> {
			JsonObject o = new JsonObject();
			o.addProperty("film", f.id); o.addProperty("notes", fFilmNotes.getValue());
			ClientNet.send("film.update", o);
		});
		btn(rx, cy + 94, 130, 20, "Open actors \u2192", () -> { tab = Tab.ACTORS; rebuildWidgets(); });
		boolean confirm = confirmDelete.equals(f.id);
		btn(rx, cy + 122, 130, 20, confirm ? "Click again to DELETE" : "Delete film", () -> {
			if (confirm) {
				JsonObject o = new JsonObject();
				o.addProperty("film", f.id);
				ClientNet.send("film.delete", o);
				confirmDelete = "";
			} else { confirmDelete = f.id; rebuildWidgets(); }
		});
		int rec = 0;
		for (ActorSpec a : f.actors) if (a.hasRecording) rec++;
		label(rx, cy + 150, f.actors.size() + " actors, " + rec + " with recordings");
	}

	// ------------------------------------------------------------------ ACTORS
	private void buildActors() {
		Film f = ClientState.film();
		if (f == null) { label(cx, cy, "Create or select a film first."); return; }
		label(cx, cy - 12, "Actors in \"" + f.name + "\"");
		int perPage = 7;
		int y = cy;
		int pages = Math.max(1, (f.actors.size() + perPage - 1) / perPage);
		listPage = Math.max(0, Math.min(listPage, pages - 1));
		for (int i = listPage * perPage; i < Math.min(f.actors.size(), (listPage + 1) * perPage); i++) {
			ActorSpec a = f.actors.get(i);
			boolean sel = a.id.equals(ClientState.selActor);
			Button b = btn(cx, y, 130, 20, (sel ? "\u25b6 " : "") + (a.hasRecording ? "\u25cf " : "") + a.name, () -> {
				applyIfDirty();
				ClientState.selActor = a.id;
				syncEdit();
				rebuildWidgets();
			});
			b.active = !sel;
			y += 22;
		}
		if (pages > 1) {
			btn(cx, y, 63, 18, "<", () -> { listPage--; rebuildWidgets(); }).active = listPage > 0;
			btn(cx + 67, y, 63, 18, ">", () -> { listPage++; rebuildWidgets(); }).active = listPage < pages - 1;
		}
		int by = height - 86;
		fNewActor = box(cx, by, 130, newActorName, "New actor name", 16);
		fNewActor.setResponder(s -> newActorName = s);
		btn(cx, by + 22, 130, 20, "+ Add actor", () -> {
			applyIfDirty();
			JsonObject o = new JsonObject();
			o.addProperty("film", f.id); o.addProperty("name", newActorName.isBlank() ? "Actor" : newActorName);
			ClientNet.send("actor.add", o);
			newActorName = "";
		});

		if (edit == null) { label(cx + 146, cy, "Add an actor to get started."); return; }
		btn(cx, by + 44, 63, 20, "Copy", () -> {
			applyIfDirty();
			JsonObject o = new JsonObject();
			o.addProperty("film", f.id); o.addProperty("id", edit.id);
			ClientNet.send("actor.dup", o);
		});
		boolean confirm = confirmDelete.equals(edit.id);
		btn(cx + 67, by + 44, 63, 20, confirm ? "Sure?" : "Delete", () -> {
			if (confirm) {
				JsonObject o = new JsonObject();
				o.addProperty("film", f.id); o.addProperty("id", edit.id);
				ClientNet.send("actor.delete", o);
				confirmDelete = ""; dirty = false;
			} else { confirmDelete = edit.id; rebuildWidgets(); }
		});

		// editor
		int ex = cx + 146, ew = cw - 146;
		int colW = (ew - 8) / 2, c2 = ex + colW + 8;
		btn(ex, cy - 12 - 6, 70, 16, "Identity", () -> { commitBoxes(); actorSub = 0; rebuildWidgets(); }).active = actorSub != 0;
		btn(ex + 74, cy - 12 - 6, 70, 16, "Behavior", () -> { commitBoxes(); actorSub = 1; rebuildWidgets(); }).active = actorSub != 1;
		int ry = cy + 14;
		clearFieldRefs();
		if (actorSub == 0) {
			label(ex, ry - 10, "Name (max 16)"); label(c2, ry - 10, "Name color");
			fName = box(ex, ry, colW, edit.name, "Name", 16);
			cycle(c2, ry, colW, "Color", List.of(COLORS), () -> edit.nameColor, v -> { edit.nameColor = v; dirty = true; });
			ry += 32;
			label(ex, ry - 10, "Prefix (e.g. [Hero] )"); label(c2, ry - 10, "Suffix");
			fPrefix = box(ex, ry, colW, edit.prefix, "Prefix", 32);
			fSuffix = box(c2, ry, colW, edit.suffix, "Suffix", 32);
			ry += 32;
			toggle(ex, ry, colW, "Name tag", () -> edit.showNametag, v -> { edit.showNametag = v; dirty = true; });
			toggle(c2, ry, colW, "Slim arms", () -> edit.slim, v -> { edit.slim = v; dirty = true; });
			ry += 26;
			List<String> modelOpts = new ArrayList<>();
			modelOpts.add("Player");
			modelOpts.addAll(ClientState.models);
			cycle(ex, ry, colW, "Model", modelOpts,
				() -> "custom".equals(edit.modelType) && ClientState.models.contains(edit.modelName) ? edit.modelName : "Player",
				v -> { if (v.equals("Player")) { edit.modelType = "player"; edit.modelName = ""; } else { edit.modelType = "custom"; edit.modelName = v; } dirty = true; });
			addRenderableWidget(new ScaleSlider(c2, ry, colW, edit.scale, v -> { edit.scale = v; dirty = true; }));
			ry += 32;
			label(ex, ry - 10, "Skin (player models only)");
			cycle(ex, ry, colW, "Skin", List.of("default", "player", "file"), () -> edit.skinType, v -> { commitBoxes(); edit.skinType = v; dirty = true; rebuildWidgets(); });
			if ("player".equals(edit.skinType)) {
				fSkinVal = box(c2, ry, colW, edit.skinValue, "Minecraft username", 16);
			} else if ("file".equals(edit.skinType)) {
				List<String> opts = new ArrayList<>();
				opts.add("");
				opts.addAll(ClientState.skins);
				cycle(c2, ry, colW, "File", opts, () -> edit.skinValue, v -> { edit.skinValue = v; dirty = true; });
			}
			ry += 28;
			label(ex, ry, "Imported skins and models live in the Assets tab.");
		} else {
			String[] names = {"Main hand", "Off hand", "Head", "Chest", "Legs", "Feet"};
			String[] keys = {"mainhand", "offhand", "head", "chest", "legs", "feet"};
			label(ex, ry - 10, "Equipment override (item id, empty = as recorded)");
			ry += 6;
			for (int i = 0; i < 6; i++) {
				int x = i % 2 == 0 ? ex : c2;
				if (i % 2 == 0 && i > 0) ry += 30;
				label(x, ry - 10, names[i]);
				fEquip[i] = box(x, ry, colW, edit.equipOverride.getOrDefault(keys[i], ""), "minecraft:diamond_sword", 80);
			}
			ry += 32;
			cycle(ex, ry, colW, "Pose", List.of("auto", "crouch", "crawl", "glide"), () -> edit.forcedPose, v -> { edit.forcedPose = v; dirty = true; });
			cycle(c2, ry, colW, "Damage", List.of("normal", "no_death", "invulnerable"), () -> edit.damageMode, v -> { edit.damageMode = v; dirty = true; });
			ry += 24;
			cycle(ex, ry, colW, "Physics", List.of("realistic", "exact"), () -> edit.physics, v -> { edit.physics = v; dirty = true; });
			fDelay = box(c2, ry, colW, String.valueOf(edit.startDelay), "Start delay (ticks)", 6);
			ry += 24;
			toggle(ex, ry, colW, "Glow", () -> edit.glow, v -> { edit.glow = v; dirty = true; });
			toggle(c2, ry, colW, "Invisible", () -> edit.invisible, v -> { edit.invisible = v; dirty = true; });
			ry += 24;
			toggle(ex, ry, colW, "In playback", () -> edit.enabled, v -> { edit.enabled = v; dirty = true; });
		}
		btn(ex, height - 30, 100, 20, "Apply changes", this::apply);
		btn(ex + 104, height - 30, 80, 20, "Revert", () -> { syncEdit(); rebuildWidgets(); });
	}

	private void clearFieldRefs() {
		fName = fPrefix = fSuffix = fSkinVal = fDelay = null;
		java.util.Arrays.fill(fEquip, null);
	}

	// ------------------------------------------------------------------ RECORD
	private void buildRecord() {
		Film f = ClientState.film();
		if (f == null) { label(cx, cy, "Create or select a film first."); return; }
		label(cx, cy - 12, "Choose the actor to record");
		int x = cx, y = cy;
		for (ActorSpec a : f.actors) {
			boolean sel = a.id.equals(ClientState.selActor);
			int w = Math.max(54, font.width(a.name) + 16);
			if (x + w > cx + cw) { x = cx; y += 22; }
			Button b = btn(x, y, w, 20, (a.hasRecording ? "\u25cf " : "") + a.name, () -> { ClientState.selActor = a.id; syncEdit(); rebuildWidgets(); });
			b.active = !sel;
			x += w + 4;
		}
		y += 30;
		ActorSpec a = f.actor(ClientState.selActor);
		boolean busy = ClientState.mode.equals("recording") || ClientState.mode.equals("rec_countdown");

		// film recording options
		label(cx, y - 10, "Countdown (s)"); label(cx + 84, y - 10, "Mob tracking"); label(cx + 214, y - 10, "Radius");
		fCountdown = box(cx, y, 78, String.valueOf(f.countdown), "3", 2);
		cycle(cx + 84, y, 124, "Mobs", List.of("none", "interacted", "nearby"), () -> f.mobMode, v -> { f.mobMode = v; sendFilmSettings(f); });
		fRadius = box(cx + 214, y, 50, String.valueOf(f.mobRadius), "20", 2);
		toggle(cx + 270, y, 130, "Play others", () -> f.playOthersWhileRecording, v -> { f.playOthersWhileRecording = v; sendFilmSettings(f); });
		btn(cx + 404, y, 50, 20, "Save", () -> sendFilmSettings(f));
		y += 32;

		Button rec = btn(cx, y, 150, 24, "\u25cf Start recording", () -> {
			if (a == null) return;
			applyIfDirty(); sendFilmSettings(f);
			JsonObject o = new JsonObject();
			o.addProperty("film", f.id); o.addProperty("actor", a.id);
			ClientNet.send("rec.start", o);
			onClose();
		});
		rec.active = a != null && !busy;
		Button stop = btn(cx + 154, y, 120, 24, "\u25a0 Stop & save", () -> {
			JsonObject o = new JsonObject(); o.addProperty("save", true);
			ClientNet.send("rec.stop", o);
		});
		stop.active = busy;
		Button cancel = btn(cx + 278, y, 100, 24, "\u2716 Discard", () -> {
			JsonObject o = new JsonObject(); o.addProperty("save", false);
			ClientNet.send("rec.stop", o);
		});
		cancel.active = busy;
		y += 34;

		if (a != null) {
			label(cx, y, a.hasRecording
				? "Recording: " + a.recLength + " ticks (" + String.format(Locale.ROOT, "%.1f", a.recLength / 20.0) + " s), " + a.recEvents + " actions, " + a.recMobs + " mobs, " + a.recDeltas + " block changes"
				: "No recording yet for " + a.name);
			y += 14;
			if (a.hasRecording) {
				label(cx, y + 2, "Trim from"); fTrimStart = box(cx + 56, y - 3, 60, trimStart, "0", 6);
				label(cx + 124, y + 2, "to"); fTrimEnd = box(cx + 140, y - 3, 60, trimEnd.isEmpty() ? String.valueOf(a.recLength) : trimEnd, "end", 6);
				fTrimStart.setResponder(s -> trimStart = s);
				fTrimEnd.setResponder(s -> trimEnd = s);
				btn(cx + 206, y - 3, 60, 20, "Trim", () -> {
					JsonObject o = new JsonObject();
					o.addProperty("film", f.id); o.addProperty("actor", a.id);
					o.addProperty("start", parseInt(trimStart, 0)); o.addProperty("end", parseInt(trimEnd, a.recLength));
					ClientNet.send("rec.trim", o);
					trimEnd = "";
				});
				y += 26;
				btn(cx, y, 130, 20, "Revert world", () -> restore(f, a, true));
				btn(cx + 134, y, 130, 20, "Re-apply world", () -> restore(f, a, false));
				boolean confirm = confirmDelete.equals("rec" + a.id);
				btn(cx + 268, y, 140, 20, confirm ? "Click again to DELETE" : "Delete recording", () -> {
					if (confirm) {
						JsonObject o = new JsonObject();
						o.addProperty("film", f.id); o.addProperty("actor", a.id);
						ClientNet.send("rec.delete", o);
						confirmDelete = "";
					} else { confirmDelete = "rec" + a.id; rebuildWidgets(); }
				});
			}
		}
	}

	private void sendFilmSettings(Film f) {
		JsonObject o = new JsonObject();
		o.addProperty("film", f.id);
		if (fCountdown != null) o.addProperty("countdown", parseInt(fCountdown.getValue(), f.countdown));
		if (fRadius != null) o.addProperty("mobRadius", parseInt(fRadius.getValue(), f.mobRadius));
		o.addProperty("mobMode", f.mobMode);
		o.addProperty("playOthers", f.playOthersWhileRecording);
		o.addProperty("loop", f.loop);
		o.addProperty("restoreWorld", f.restoreWorld);
		ClientNet.send("film.update", o);
	}

	private void restore(Film f, ActorSpec a, boolean undo) {
		JsonObject o = new JsonObject();
		o.addProperty("film", f.id); o.addProperty("actor", a == null ? "" : a.id); o.addProperty("undo", undo);
		ClientNet.send("world.restore", o);
	}

	// ------------------------------------------------------------------ PLAYBACK
	private void buildPlayback() {
		Film f = ClientState.film();
		if (f == null) { label(cx, cy, "Create or select a film first."); return; }
		label(cx, cy - 12, "Timeline: tick the actors to include and shift them in time");
		int y = cy + 2, max = 0;
		for (ActorSpec a : f.actors) max = Math.max(max, a.startDelay + a.recLength);
		int perPage = 6;
		int pages = Math.max(1, (f.actors.size() + perPage - 1) / perPage);
		listPage = Math.max(0, Math.min(listPage, pages - 1));
		for (int i = listPage * perPage; i < Math.min(f.actors.size(), (listPage + 1) * perPage); i++) {
			ActorSpec a = f.actors.get(i);
			Button t = toggle(cx, y, 150, a.name, () -> a.enabled, v -> { a.enabled = v; pushActor(f, a); });
			t.active = a.hasRecording;
			label(cx + 156, y + 6, "delay " + a.startDelay);
			int bx = cx + 214;
			btn(bx, y, 30, 20, "-20", () -> shift(f, a, -20));
			btn(bx + 32, y, 30, 20, "-5", () -> shift(f, a, -5));
			btn(bx + 64, y, 30, 20, "+5", () -> shift(f, a, 5));
			btn(bx + 96, y, 30, 20, "+20", () -> shift(f, a, 20));
			y += 24;
		}
		if (pages > 1) {
			btn(cx, y, 70, 16, "< Prev", () -> { listPage--; rebuildWidgets(); }).active = listPage > 0;
			btn(cx + 74, y, 70, 16, "Next >", () -> { listPage++; rebuildWidgets(); }).active = listPage < pages - 1;
			y += 20;
		}
		y = Math.max(y + 6, cy + 6 * 24 + 8);
		toggle(cx, y, 100, "Loop", () -> f.loop, v -> { f.loop = v; sendFilmSettings(f); });
		toggle(cx + 104, y, 140, "Restore world", () -> f.restoreWorld, v -> { f.restoreWorld = v; sendFilmSettings(f); });
		toggle(cx + 248, y, 160, "At my position", () -> relativePlay, v -> relativePlay = v);
		y += 24;
		final int maxTicks = Math.max(1, max);
		addRenderableWidget(new IntSlider(cx, y, 300, "Start at tick", 0, maxTicks, Math.min(playFrom, maxTicks), v -> playFrom = v));
		y += 26;
		boolean playing = ClientState.mode.equals("playing") || ClientState.mode.equals("paused") || ClientState.mode.equals("countdown");
		btn(cx, y, 110, 24, "\u25b6 Play", () -> {
			applyIfDirty();
			JsonObject o = new JsonObject();
			o.addProperty("film", f.id); o.addProperty("from", playFrom); o.addProperty("loop", f.loop); o.addProperty("relative", relativePlay);
			ClientNet.send("play.start", o);
		});
		Button pause = btn(cx + 114, y, 100, 24, ClientState.mode.equals("paused") ? "Resume" : "\u23f8 Pause", () -> ClientNet.send("play.pause", null));
		pause.active = playing;
		Button stop = btn(cx + 218, y, 90, 24, "\u25a0 Stop", () -> ClientNet.send("play.stop", null));
		stop.active = playing;
		btn(cx + 312, y, 140, 24, "Revert world", () -> restore(f, null, true));
	}

	private void shift(Film f, ActorSpec a, int d) {
		a.startDelay = Math.max(0, a.startDelay + d);
		pushActor(f, a);
	}

	private void pushActor(Film f, ActorSpec a) {
		JsonObject o = new JsonObject();
		o.addProperty("film", f.id);
		o.add("actor", GSON.toJsonTree(a));
		ClientNet.send("actor.update", o);
	}

	// ------------------------------------------------------------------ ASSETS
	private void buildAssets() {
		int half = (cw - 12) / 2, x2 = cx + half + 12;
		label(cx, cy - 12, "Skins (PNG, 64x64)");
		label(x2, cy - 12, "3D models (Blockbench .bbmodel)");
		int y = cy;
		for (String s : ClientState.skins) {
			if (y > cy + 5 * 22) break;
			label(cx + 4, y + 6, s);
			btn(cx + half - 24, y, 24, 20, "x", () -> { JsonObject o = new JsonObject(); o.addProperty("key", s); ClientNet.send("skin.delete", o); });
			y += 22;
		}
		if (ClientState.skins.isEmpty()) label(cx + 4, y + 4, "(none imported yet)");
		int y2 = cy;
		for (String m : ClientState.models) {
			if (y2 > cy + 5 * 22) break;
			label(x2 + 4, y2 + 6, m);
			btn(x2 + half - 24, y2, 24, 20, "x", () -> { JsonObject o = new JsonObject(); o.addProperty("key", m); ClientNet.send("model.delete", o); });
			y2 += 22;
		}
		if (ClientState.models.isEmpty()) label(x2 + 4, y2 + 4, "(none imported yet)");

		int by = cy + 6 * 22 + 20;
		label(cx, by - 10, "Path to a .png file (or drag & drop onto this window)");
		fImportSkin = box(cx, by, half - 64, importPathSkin, "C:\\skins\\hero.png", 260);
		fImportSkin.setResponder(s -> importPathSkin = s);
		btn(cx + half - 60, by, 60, 20, "Import", () -> importFile(importPathSkin, true));
		label(x2, by - 10, "Path to a .bbmodel file (animations + texture are read from it)");
		fImportModel = box(x2, by, half - 64, importPathModel, "C:\\models\\robot.bbmodel", 260);
		fImportModel.setResponder(s -> importPathModel = s);
		btn(x2 + half - 60, by, 60, 20, "Import", () -> importFile(importPathModel, false));
		label(cx, by + 34, "Tip: models also support .json exported from Blockbench as 'Project (.bbmodel)'.");
	}

	private void importFile(String pathStr, boolean skin) {
		try {
			Path p = Path.of(pathStr.trim().replace("\"", ""));
			sendAsset(p, skin);
		} catch (Exception e) {
			ClientState.say("Could not read file: " + e.getMessage());
		}
	}

	private void sendAsset(Path p, boolean skin) throws Exception {
		byte[] data = Files.readAllBytes(p);
		String name = p.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String key = (dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "_");
		JsonObject o = new JsonObject();
		o.addProperty("key", key);
		ClientNet.send(skin ? "skin.upload" : "model.upload", o, data);
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		for (Path p : paths) {
			try {
				String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
				if (n.endsWith(".png")) sendAsset(p, true);
				else if (n.endsWith(".bbmodel") || n.endsWith(".json")) sendAsset(p, false);
				else ClientState.say("Unsupported file: " + n);
			} catch (Exception e) {
				ClientState.say("Could not import: " + e.getMessage());
			}
		}
	}

	// ------------------------------------------------------------------ render
	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		renderBackground(g, mouseX, mouseY, delta);
		g.fill(4, 4, width - 4, height - 4, 0xC0101018);
		g.fill(4, 4, 104, height - 4, 0xC0181824);
		g.drawString(font, "FILM STUDIO", 10, 10, 0xFFFFD84A);
		Film f = ClientState.film();
		g.drawString(font, f == null ? "no film selected" : f.name, cx, 10, 0xFFFFFFFF);

		for (Label l : labels) g.drawString(font, l.text, l.x, l.y, l.color);
		if (tab == Tab.PLAYBACK) drawTimeline(g, f);

		super.render(g, mouseX, mouseY, delta);

		// status bar
		int sy = height - 14;
		String live = switch (ClientState.mode) {
			case "recording" -> "\u25cf RECORDING  " + ClientState.tick / 20 + "s";
			case "rec_countdown" -> "Recording starts in " + (ClientState.countdown / 20 + 1) + " ...";
			case "playing" -> "\u25b6 PLAYING  " + ClientState.tick + " / " + ClientState.length;
			case "paused" -> "\u23f8 PAUSED  " + ClientState.tick + " / " + ClientState.length;
			case "countdown" -> "Waiting for the recording to start ...";
			default -> "Idle";
		};
		int color = ClientState.mode.equals("recording") ? 0xFFFF5555 : ClientState.mode.equals("idle") ? 0xFF909090 : 0xFF55FF55;
		g.drawString(font, live, cx, sy, color);
		if (System.currentTimeMillis() < ClientState.messageUntil) {
			g.drawString(font, ClientState.message, cx + 170, sy, 0xFFFFFFA0);
		}
		if (dirty) g.drawString(font, "\u25cf unsaved actor changes", width - 130, sy, 0xFFFFAA00);
	}

	private void drawTimeline(GuiGraphics g, Film f) {
		if (f == null) return;
		int top = height - 14 - 6 - Math.max(1, f.actors.size()) * 12 - 6;
		int right = cx + cw - 8, left = cx + 60;
		int max = 1;
		for (ActorSpec a : f.actors) max = Math.max(max, a.startDelay + a.recLength);
		g.fill(cx, top - 4, cx + cw, height - 18, 0x60000000);
		int y = top;
		for (ActorSpec a : f.actors) {
			g.drawString(font, a.name.length() > 8 ? a.name.substring(0, 8) : a.name, cx + 3, y + 1, 0xFFC0C0C0);
			g.fill(left, y + 2, right, y + 9, 0x40FFFFFF);
			if (a.hasRecording) {
				int x1 = left + (int) ((long) (right - left) * a.startDelay / max);
				int x2 = left + (int) ((long) (right - left) * (a.startDelay + a.recLength) / max);
				g.fill(x1, y + 2, Math.max(x1 + 1, x2), y + 9, a.enabled ? 0xFF3C9DFF : 0xFF505050);
			}
			y += 12;
		}
		if (ClientState.mode.equals("playing") || ClientState.mode.equals("paused")) {
			int px = left + (int) ((long) (right - left) * Math.min(ClientState.tick, max) / max);
			g.fill(px, top - 2, px + 1, y, 0xFFFF5555);
		}
	}
}
