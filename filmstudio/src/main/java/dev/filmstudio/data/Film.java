package dev.filmstudio.data;

import java.util.ArrayList;
import java.util.List;

/** A film project: a named collection of actors and playback settings. */
public class Film {
	public String id = "";
	public String name = "Untitled";
	public String notes = "";
	public long created = 0;
	public List<ActorSpec> actors = new ArrayList<>();

	// Playback settings
	public boolean loop = false;
	/** Revert blocks changed by recordings before every playback so actors can break/place them again. */
	public boolean restoreWorld = true;
	/** Seconds of countdown before a recording starts. */
	public int countdown = 3;
	/** none | interacted | nearby */
	public String mobMode = "interacted";
	public int mobRadius = 20;
	/** While recording one actor, play back the other enabled actors in sync. */
	public boolean playOthersWhileRecording = true;

	public ActorSpec actor(String id) {
		for (ActorSpec a : actors) if (a.id.equals(id)) return a;
		return null;
	}
}
