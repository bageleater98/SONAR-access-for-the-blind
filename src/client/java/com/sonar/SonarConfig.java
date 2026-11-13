package com.sonar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;

public class SonarConfig {
	public int range = 45;
	public int frontRange = 48;
	public int maxSounds = 32;
	public float volume = 1.0f;
	public float autoPing = 1.5f; // seconds between auto sonar pings; the echo follows a fraction of a second later
	public float echoVolume = 1.0f, ledgeVolume = 1.0f, chimeVolume = 0.8f; // multipliers set by the volume sliders (the jingle's 100% is 0.8: what was 200% before 1.4.2)
	public boolean playerChime = true; // constant amethyst jingle from other players
	public int playerChimeRange = 128; // fixed (no slider): longer than name-tag distance (64)
	public boolean echoCue = true;
	public String ledgeSound = "sonar:ledge_loop"; // dry synthesized ratchet buzz, looped
	public boolean autoScan = false; // hold R for 2 seconds to toggle
	public int speechRate = 3; // -10 slow .. 10 fast (Windows system voice)
	public boolean floorSounds = false;
	public boolean pathGuide = true; // bell that leads you down a built path
	public float guideVolume = 1.0f;
	public boolean waypoints = true; // waypoints: save places, be guided back to them (Pause menu > Waypoints)
	public float waypointVolume = 1.0f;
	public float lavaVolume = 1.0f; // multiplier for every lava sound (the passive loop is already quiet)
	public boolean stagger = true; // a ping's sounds start a tick or two apart, front first, so they blur less
	public boolean echoReplacesSonar = true; // inside a cave or building (where the echo plays) the block sounds are turned off
	public int echoCount = -1; // echoes per ping: -1 = auto (every way out / route found, up to 8), 0 = none, 1-8 fixed
	public int caveRange = 175; // how far the cave echo listens for routes (blocks); slider up to 300
	public boolean centerOnBump = true; // two thuds in a row against a wall: center on your block and face the nearest straight direction
	public boolean edgeBrake = true; // sprinting straight at a drop: briefly slow you by ~80% so you can stop
	public boolean sideLedges = true; // quieter tones for edges to your left and right
	public boolean ignoreSteps = true; // slabs and stairs you can walk onto count as floor, not as walls
	public boolean ignoreFloorBumps = false; // blocks of the same material as your floor, at or below foot level and within 8 blocks, stay silent
	public boolean lineOfSight = true; // a block only sounds if you could see it (no leaking through thin walls)
	public boolean ceilingCue = true; // soft tone from above when the ceiling is low
	public int sweepGap = 3; // ticks between a ping's four beats (front, right, left, behind): 1 (0.05 s) to 5 (0.25 s)
	public float directionSpread = 1.5f; // 1 = true angles, up to 2 = sideways angles exaggerated
	public boolean floorEdge = true; // sound where the floor material changes (stone path vs dirt)
	public int maxMobCues = 4;
	public boolean autoLedge = true;
	public float ledgeWarnRange = 5f;
	public int ledgeMinDrop = 4;
	public boolean behindPitch = true; // sounds behind you are slightly lower in pitch
	public boolean directionalAudioDone = false; // set once: turns on Minecraft's Directional Audio the first time
	public int slopeMinDrop = 3; // steep continuous slopes count as ledges from this total drop
	public int maxPerMaterial = 6;
	public boolean entityCue = false;
	public int entityRange = 24;
	public boolean holdToRepeat = false;
	public int repeatTicks = 12;
	public boolean materialSounds = true;
	public boolean ledgeCue = true;
	public boolean hazardCue = true;
	public boolean tactileCue = true;
	public boolean gapCue = true;
	public boolean emptyCue = false;
	public boolean stuckCue = true;
	public boolean waterLavaLoop = true;
	public int tactileRange = 3;
	public int ledgeRange = 12;
	public int layersPerTick = 8;
	public boolean debug = false;
	public int speechVolume = 100, facingVolume = 45; // loudness of the mod's spoken messages, and of the facing direction (percent of the voice)
	public boolean sayFacing = true; // quietly says north / east / south / west when you settle on a new direction
	public boolean guideIcons = true; // a floating bell / nether star leads the way the guide sounds go, for sighted friends
	public boolean debugChat = false; // debug lines also shown in chat (they always go to the log)
	public boolean trace = false; // (no button) the per-ping "played:" line and the waypoint trace: very chatty, only for test sessions; needs debug too
	public boolean enabled = true; // master switch: false turns every Sonar sound and effect off
	/** Which settings layout this file was written by. A file from an older version is upgraded step by step on loading (see upgrade()). Missing in old files = 0. */
	public int configVersion = 0; // (0 by default, so an old file without this field is recognised as old and upgraded; a fresh file is upgraded once, harmlessly)
	static final int CURRENT = 3;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** The player's own settings file. */
	static Path playerFile() { return FabricLoader.getInstance().getConfigDir().resolve("sonar.json"); }
	/** The settings file in use: the player's, or the tutorial's own copy while the tutorial runs (Sandbox), so the tutorial never writes the player's file.
	 *  Found when first needed (a class load must not need the game's folders). */
	private static Path FILE;
	private static long stamp = -1;

	/** Switches to another settings file (and loads it, writing defaults if it is missing). Screens open on the old object keep working on it harmlessly. */
	static void useFile(Path file) {
		FILE = file;
		stamp = -1;
		cur = new SonarConfig();
		try { Files.createDirectories(file.getParent()); } catch (Exception ignored) {}
		reloadIfChanged();
	}

	static Path file() { if (FILE == null) FILE = playerFile(); return FILE; }
	public static SonarConfig cur = new SonarConfig();

	/** Loads (or writes defaults); call periodically to hot-reload. */
	public static void reloadIfChanged() {
		try {
			Path FILE = file();
			if (!Files.exists(FILE)) { save(); }
			long m = Files.getLastModifiedTime(FILE).toMillis();
			if (m == stamp) return;
			SonarConfig c = GSON.fromJson(Files.readString(FILE), SonarConfig.class);
			if (c != null) { boolean old = c.upgrade(); c.clamp(); cur = c; if (old) save(); }
			stamp = m;
		} catch (Exception e) {
			// unreadable or hand-edited badly: keep the settings already loaded and say so once, instead of retrying forever
			try { stamp = Files.getLastModifiedTime(FILE).toMillis(); } catch (Exception ignored) { stamp = -1; }
			org.slf4j.LoggerFactory.getLogger("sonar").warn("Could not read sonar.json, keeping current settings: {}", e.toString());
		}
	}

	public static void save() throws java.io.IOException {
		Path FILE = file();
		Files.writeString(FILE, GSON.toJson(cur));
		try { stamp = Files.getLastModifiedTime(FILE).toMillis(); } catch (Exception ignored) {} // our own save must not trigger a reload that swaps the object screens hold
	}

	/**
	 * Brings settings written by an older version up to date, one step per version, so an update never leaves odd old values behind. True when anything changed
	 * (the file is then saved again in the new layout).
	 */
	boolean upgrade() {
		if (configVersion >= CURRENT) return false;
		if (configVersion < 1) { // up to 1.4.1: the jingle hearing distance was a slider (old files hold 90 or the slider value); it is now the edge of name-tag sight, 128
			playerChimeRange = 128;
		}
		if (configVersion < 2) { // 1.4.2: the jingle's old 200% is the new 100% (it was too quiet at 100%), and mob sounds are off by default (they crowded the sonar)
			chimeVolume = 0.8f;
			entityCue = false;
		}
		if (configVersion < 3) sweepGap = 3; // 1.5.2: the sweep speed is 0.15 s a beat for everyone (the tutorial had left 0.10 s in some files)
		configVersion = CURRENT;
		return true;
	}

	private void clamp() {
		range = Math.max(4, Math.min(range, 64));
		frontRange = Math.max(4, Math.min(frontRange, 96));
		maxSounds = Math.max(1, Math.min(maxSounds, 64));
		volume = Math.max(0f, Math.min(volume, 2f));
		echoVolume = Math.max(0f, Math.min(echoVolume, 2f));
		ledgeVolume = Math.max(0f, Math.min(ledgeVolume, 2f));
		chimeVolume = Math.max(0f, Math.min(chimeVolume, 1.6f));
		speechVolume = Math.max(0, Math.min(speechVolume, 100));
		facingVolume = Math.max(0, Math.min(facingVolume, 100));
		guideVolume = Math.max(0f, Math.min(guideVolume, 2f));
		waypointVolume = Math.max(0f, Math.min(waypointVolume, 2f));
		lavaVolume = Math.max(0f, Math.min(lavaVolume, 2f));
		directionSpread = Math.max(1f, Math.min(directionSpread, 2f));
		sweepGap = Math.max(1, Math.min(sweepGap, 5));
		autoPing = Math.max(0.3f, Math.min(autoPing, 30f));
		ledgeWarnRange = Math.max(1f, Math.min(ledgeWarnRange, 16f));
		ledgeRange = Math.max(4, Math.min(ledgeRange, 24));
		speechRate = Math.max(-10, Math.min(speechRate, 10));
		echoCount = Math.max(-1, Math.min(echoCount, 8));
		caveRange = Math.max(32, Math.min(caveRange, 300));
		repeatTicks = Math.max(4, repeatTicks);
		layersPerTick = Math.max(1, Math.min(layersPerTick, 64));
	}
}
