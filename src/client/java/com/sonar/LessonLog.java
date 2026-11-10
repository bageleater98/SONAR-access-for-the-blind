package com.sonar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * THE LESSON LOG (local only, never sent anywhere): config/sonar-tutorial/log-YYYY-MM-DD.json, one entry per lesson played, so a playtest can be read back
 * afterwards to find the rooms that confused people: how long it took, which hints were heard, retries, skips, resets and why, wrong-way moments, and every
 * "this was confusing" mark (with where you were and the last line said).
 */
final class LessonLog {
	static final class Mark { long t; double x, y, z; String lastLine; }

	static final class Entry {
		String lesson;
		long start, end;
		double solveSec;
		List<String> hints = new ArrayList<>();
		int retries;
		boolean skipped;
		List<String> resets = new ArrayList<>();
		int wrongWay;
		List<Mark> confusing = new ArrayList<>();
		String passedHow;
	}

	static final class Day {
		int worldVersion = TutorialWorld.VERSION;
		String rooms = "";
		String voice = "kokoro af_heart";
		List<Entry> lessons = new ArrayList<>();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	static Path dir;

	/** The file for this day. */
	static Path file(long epochMillis) {
		var d = java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
		return dir.resolve("log-" + d + ".json");
	}

	static Day read(Path f) {
		try {
			if (Files.exists(f)) { Day d = GSON.fromJson(Files.readString(f), Day.class); if (d != null && d.lessons != null) return d; }
		} catch (Exception e) { SonarClient.LOG.warn("Sonar tutorial: lesson log unreadable, starting a new one: {}", e.toString()); }
		return new Day();
	}

	/** Writes this entry into today's log (replacing an earlier write of the same entry: same lesson and start). */
	static void write(Entry e, long nowMillis) {
		if (dir == null) return;
		try {
			Files.createDirectories(dir);
			Path f = file(nowMillis);
			Day d = read(f);
			d.rooms = TutorialRooms.hash();
			d.lessons.removeIf(x -> x.lesson.equals(e.lesson) && x.start == e.start);
			d.lessons.add(e);
			Files.writeString(f, GSON.toJson(d));
		} catch (Exception ex) { SonarClient.LOG.error("Sonar tutorial: could not write the lesson log", ex); }
	}
}
