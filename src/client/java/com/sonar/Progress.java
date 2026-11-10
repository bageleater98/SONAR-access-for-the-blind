package com.sonar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The player's tutorial progress (config/sonar-tutorial/progress.json): where to resume, which lessons were passed, the options of the tutorial itself. */
final class Progress {
	String lesson = "l0";
	List<String> done = new ArrayList<>();
	boolean completed;
	boolean skipIntros;
	/** The first-launch offer was made (it is never made again). */
	boolean offered;

	static Path file;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	static synchronized Progress load() {
		try {
			if (file != null && Files.exists(file)) {
				Progress p = GSON.fromJson(Files.readString(file), Progress.class);
				if (p != null) { if (p.done == null) p.done = new ArrayList<>(); return p; }
			}
		} catch (Exception e) { SonarClient.LOG.warn("Sonar tutorial: could not read progress, starting fresh: {}", e.toString()); }
		return new Progress();
	}

	synchronized void save() {
		if (file == null) return;
		try { Files.createDirectories(file.getParent()); Files.writeString(file, GSON.toJson(this)); } catch (Exception e) { SonarClient.LOG.error("Sonar tutorial: could not save progress", e); }
	}
}
