package dev.filmstudio.data;

import java.util.LinkedHashMap;
import java.util.Map;

/** Everything the user can configure about one actor inside a film. Plain Gson POJO (shared with the client UI). */
public class ActorSpec {
	public String id = "";
	/** Profile name shown as the name tag. Max 16 characters (Minecraft limit). */
	public String name = "Actor";
	public String prefix = "";
	public String suffix = "";
	public String nameColor = "white";
	public boolean showNametag = true;

	/** default | player | file */
	public String skinType = "default";
	/** player name (skinType=player) or imported skin key (skinType=file). */
	public String skinValue = "";
	public boolean slim = false;

	public float scale = 1.0f;
	/** player | custom */
	public String modelType = "player";
	public String modelName = "";

	/** slot name (mainhand, offhand, head, chest, legs, feet) -> item id. Empty/missing = use what was recorded. */
	public Map<String, String> equipOverride = new LinkedHashMap<>();

	/** auto | crouch | crawl | glide */
	public String forcedPose = "auto";
	/** normal | no_death | invulnerable */
	public String damageMode = "no_death";
	/** realistic | exact */
	public String physics = "realistic";
	public boolean glow = false;
	public boolean invisible = false;

	/** Timeline offset in ticks. */
	public int startDelay = 0;
	/** Whether this actor takes part in playback. */
	public boolean enabled = true;

	// Recording info (filled in by the server so the UI can show it)
	public boolean hasRecording = false;
	public int recLength = 0;
	public int recEvents = 0;
	public int recMobs = 0;
	public int recDeltas = 0;

	public ActorSpec copy() {
		ActorSpec c = new ActorSpec();
		c.id = id; c.name = name; c.prefix = prefix; c.suffix = suffix; c.nameColor = nameColor; c.showNametag = showNametag;
		c.skinType = skinType; c.skinValue = skinValue; c.slim = slim; c.scale = scale; c.modelType = modelType; c.modelName = modelName;
		c.equipOverride = new LinkedHashMap<>(equipOverride);
		c.forcedPose = forcedPose; c.damageMode = damageMode; c.physics = physics; c.glow = glow; c.invisible = invisible;
		c.startDelay = startDelay; c.enabled = enabled;
		c.hasRecording = hasRecording; c.recLength = recLength; c.recEvents = recEvents; c.recMobs = recMobs; c.recDeltas = recDeltas;
		return c;
	}
}
