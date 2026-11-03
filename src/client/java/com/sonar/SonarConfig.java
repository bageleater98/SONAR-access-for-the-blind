package com.sonar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;

public class SonarConfig {
	public int range = 30;
	public int frontRange = 48;
	public int maxSounds = 24;
	public float volume = 1.0f;
	public float autoPing = 1.5f; // seconds between auto sonar pings; the echo follows a fraction of a second later
	public float echoVolume = 1.0f, ledgeVolume = 1.0f, chimeVolume = 0.4f; // multipliers set by the volume sliders
	public boolean playerChime = true; // constant amethyst jingle from other players
	public int playerChimeRange = 90;
	public boolean echoCue = true;
	public String ledgeSound = "sonar:ledge_loop"; // dry synthesized ratchet buzz, looped
	public boolean autoScan = false; // hold R for 2 seconds to toggle
	public int speechRate = 3; // -10 slow .. 10 fast (Windows system voice)
	public boolean floorSounds = false;
	public boolean pathGuide = true; // bell that leads you down a built path
	public float guideVolume = 1.0f;
	public float lavaVolume = 1.0f; // multiplier for every lava sound (the passive loop is already quiet)
	public boolean stagger = true; // a ping's sounds start a tick or two apart, front first, so they blur less
	public boolean echoReplacesSonar = true; // inside a cave or building (where the echo plays) the block sounds are turned off
	public int echoCount = -1; // echoes per ping: -1 = auto (more for big spaces, more entrances, irregular shapes), 0 = none, 1-4 fixed
	public boolean edgeBrake = true; // sprinting straight at a drop: briefly slow you by ~80% so you can stop
	public boolean sideLedges = true; // quieter tones for edges to your left and right
	public boolean ignoreSteps = true; // slabs and stairs you can walk onto count as floor, not as walls
	public boolean ignoreFloorBumps = false; // blocks of the same material as your floor, at or below foot level and within 8 blocks, stay silent
	public boolean lineOfSight = true; // a block only sounds if you could see it (no leaking through thin walls)
	public boolean ceilingCue = true; // soft tone from above when the ceiling is low
	public float directionSpread = 1.5f; // 1 = true angles, up to 2 = sideways angles exaggerated
	public boolean floorEdge = true; // sound where the floor material changes (stone path vs dirt)
	public int maxMobCues = 4;
	public boolean autoLedge = true;
	public float ledgeWarnRange = 5f;
	public int ledgeMinDrop = 4;
	public boolean behindPitch = false; // sounds behind you are slightly lower in pitch
	public boolean directionalAudioDone = false; // set once: turns on Minecraft's Directional Audio the first time
	public int slopeMinDrop = 3; // steep continuous slopes count as ledges from this total drop
	public int maxPerMaterial = 6;
	public boolean entityCue = true;
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

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("sonar.json");
	private static long stamp = -1;
	public static SonarConfig cur = new SonarConfig();

	/** Loads (or writes defaults); call periodically to hot-reload. */
	public static void reloadIfChanged() {
		try {
			if (!Files.exists(FILE)) { save(); }
			long m = Files.getLastModifiedTime(FILE).toMillis();
			if (m == stamp) return;
			SonarConfig c = GSON.fromJson(Files.readString(FILE), SonarConfig.class);
			if (c != null) { c.clamp(); cur = c; }
			stamp = m;
		} catch (Exception e) {
			// unreadable or hand-edited badly: keep the settings already loaded and say so once, instead of retrying forever
			try { stamp = Files.getLastModifiedTime(FILE).toMillis(); } catch (Exception ignored) { stamp = -1; }
			org.slf4j.LoggerFactory.getLogger("sonar").warn("Could not read sonar.json, keeping current settings: {}", e.toString());
		}
	}

	public static void save() throws java.io.IOException {
		Files.writeString(FILE, GSON.toJson(cur));
	}

	private void clamp() {
		range = Math.max(4, Math.min(range, 64));
		frontRange = Math.max(4, Math.min(frontRange, 96));
		maxSounds = Math.max(1, Math.min(maxSounds, 64));
		volume = Math.max(0f, Math.min(volume, 2f));
		echoVolume = Math.max(0f, Math.min(echoVolume, 2f));
		ledgeVolume = Math.max(0f, Math.min(ledgeVolume, 2f));
		chimeVolume = Math.max(0f, Math.min(chimeVolume, 2f));
		guideVolume = Math.max(0f, Math.min(guideVolume, 2f));
		lavaVolume = Math.max(0f, Math.min(lavaVolume, 2f));
		directionSpread = Math.max(1f, Math.min(directionSpread, 2f));
		autoPing = Math.max(0.3f, Math.min(autoPing, 30f));
		ledgeWarnRange = Math.max(1f, Math.min(ledgeWarnRange, 16f));
		playerChimeRange = Math.max(8, Math.min(playerChimeRange, 256));
		ledgeRange = Math.max(4, Math.min(ledgeRange, 24));
		speechRate = Math.max(-10, Math.min(speechRate, 10));
		echoCount = Math.max(-1, Math.min(echoCount, 4));
		repeatTicks = Math.max(4, repeatTicks);
		layersPerTick = Math.max(1, Math.min(layersPerTick, 64));
	}
}
