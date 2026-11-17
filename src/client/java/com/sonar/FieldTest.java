package com.sonar;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * TEST RUNS ONLY (environment variable SONAR_FIELD_TEST=<seed>): Sonar in a real, freshly generated survival world, played by a bot that can only do what a
 * blind player can: turn toward a sound and walk.
 *
 * Part 1, the waypoint guide: it saves "home" where it spawns, is put down some way off in each of several directions, starts the guide, and FOLLOWS THE
 * GUIDE'S SOUND (turning toward it at a human pace, walking, jumping what it bumps, swimming). Each trip is measured: arrived or not, time, distance walked
 * against the straight line, standstills, damage taken, and everything the guide said.
 *
 * Part 2, the things Sonar says: items in the hand, the helper key, low health, a monster in sight, death and the Last death waypoint.
 *
 * Everything goes to the log as "FIELD ..." lines; the game closes itself at the end. Never active in normal play.
 */
final class FieldTest {
	private FieldTest() {}

	static final String SEED = System.getenv("SONAR_FIELD_TEST");
	static final boolean ON = SEED != null;
	private static final String FOLDER = "sonar-field-test";
	/** Where each trip starts, in blocks east and south of home. */
	private static final int[][] TRIPS = {{230, -210}, {90, 20}, {-170, 110}, {-30, -140}};
	private static final int TIMEOUT = 20 * 300;

	private static boolean started;
	private static int phase, wait, trip, t, stillFor, standstills, speechAt;
	private static Waypoints.Wp home;
	private static double startDist, walked, lastX, lastZ, bestDist;
	private static float minHealth, lastHealth;
	private static int hits, deaths, step;
	private static final List<String> said = new ArrayList<>();
	private static final List<String> results = new ArrayList<>();

	static void log(String s) { SonarClient.LOG.info("FIELD " + s); }

	/** Everything Sonar says (Speech.say) is noted here during a test run. */
	static void heard(String text) { if (ON) { said.add(text); log("said: " + text); } }

	static void init() {
		if (!ON) return;
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (!(screen instanceof TitleScreen) || started) return;
			started = true;
			mc.execute(() -> create(mc));
		});
	}

	private static void create(Minecraft mc) {
		var src = mc.getLevelSource();
		if (src.levelExists(FOLDER)) {
			try (var access = src.createAccess(FOLDER)) { access.deleteLevel(); } catch (Exception e) { log("could not delete the old test world: " + e); }
		}
		long seed;
		try { seed = Long.parseLong(SEED.trim()); } catch (NumberFormatException e) { seed = SEED.hashCode(); }
		log("creating a survival world, seed " + seed);
		var settings = new LevelSettings(FOLDER, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, true), true, WorldDataConfiguration.DEFAULT);
		mc.createWorldOpenFlows().createFreshLevel(FOLDER, settings, new WorldOptions(seed, true, false), WorldPresets::createNormalWorldDimensions, new TitleScreen());
	}

	private static void command(Minecraft mc, String cmd) {
		var s = mc.getSingleplayerServer();
		if (s != null) s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd));
	}

	/** Puts the player on the surface at x, z (the chunk is made first, on the server, so the height is the real one). */
	private static void putDown(Minecraft mc, int x, int z) {
		var s = mc.getSingleplayerServer();
		if (s == null) return;
		s.execute(() -> {
			var lvl = s.overworld();
			int y = lvl.getChunk(x >> 4, z >> 4).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
			s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "tp @a " + x + " " + y + " " + z);
		});
	}

	private static void keys(Minecraft mc, boolean fwd, boolean jump) {
		mc.options.keyUp.setDown(fwd);
		mc.options.keyJump.setDown(jump);
	}

	static void tick(Minecraft mc) {
		if (!ON || mc.player == null || mc.level == null || Tutorial.active()) return;
		var p = mc.player;
		t++;
		if (mc.gui.screen() != null && phase < 100) { // a death screen: come back, as a player would; any other menu: closed
			if (p.isDeadOrDying()) { deaths++; log("died (trip " + trip + ")"); p.respawn(); }
			mc.gui.setScreen(null);
			return;
		}
		if (wait > 0) { wait--; return; }
		switch (phase) {
			case 0 -> { // settle after spawning: daylight, clear weather, the settings as shipped (with the debug log on)
				SonarConfig c = SonarConfig.cur;
				AdvancedScreen.reset(mc);
				c.debug = true; c.autoScan = true;
				command(mc, "time set day"); command(mc, "weather clear"); command(mc, "gamerule doDaylightCycle false");
				wait = 100; phase = 1;
			}
			case 1 -> {
				if (!p.onGround()) return;
				Waypoints.list(mc).clear();
				home = Waypoints.add(mc, "home");
				log(String.format("home saved at %d %d %d", home.x, home.y, home.z));
				phase = 2;
			}
			case 2 -> { // the next trip: put down some way off
				if (trip >= TRIPS.length) { phase = 10; step = 0; return; }
				keys(mc, false, false);
				WaypointGuide.stop();
				putDown(mc, home.x + TRIPS[trip][0], home.z + TRIPS[trip][1]);
				wait = 120; phase = 3;
			}
			case 3 -> {
				if (!p.onGround() && !p.isInWater()) return;
				said.clear(); speechAt = 0;
				WaypointGuide.start(mc, home);
				startDist = Waypoints.distance(mc, home); bestDist = startDist;
				walked = 0; lastX = p.getX(); lastZ = p.getZ(); stillFor = 0; standstills = 0; hits = 0;
				minHealth = lastHealth = p.getHealth();
				t = 0;
				log(String.format("trip %d: start %d blocks from home, at %.0f %.0f %.0f", trip, (int) startDist, p.getX(), p.getY(), p.getZ()));
				phase = 4;
			}
			case 4 -> follow(mc);
			case 10 -> speech(mc);
			case 100 -> { log("done"); for (String r : results) log("RESULT " + r); phase = 101; mc.stop(); }
			default -> { }
		}
	}

	/** One tick of following the guide's sound, as a player who hears where it is. */
	private static void follow(Minecraft mc) {
		var p = mc.player;
		double step = Math.hypot(p.getX() - lastX, p.getZ() - lastZ);
		if (step < 5) walked += step; // (not a respawn or teleport)
		lastX = p.getX(); lastZ = p.getZ();
		float h = p.getHealth();
		if (h < lastHealth - 0.1f) { hits++; log(String.format("trip %d: hurt, health %.1f -> %.1f at %.0f %.0f %.0f", trip, lastHealth, h, p.getX(), p.getY(), p.getZ())); }
		lastHealth = h; minHealth = Math.min(minHealth, h);
		double d = Waypoints.distance(mc, home);
		if (d < bestDist - 1) { bestDist = d; stillFor = 0; } else if (++stillFor == 200) { standstills++; if (standstills == 1) diagnose(mc); log(String.format("trip %d: no progress for 10 s at %.0f %.0f %.0f, %d blocks to go", trip, p.getX(), p.getY(), p.getZ(), (int) d)); stillFor = 0; }
		boolean arrived = !WaypointGuide.active();
		boolean stuck = standstills >= 2; // 20 s without getting nearer: a player would be lost by now
		if (arrived || stuck || t > TIMEOUT) {
			keys(mc, false, false);
			String r = String.format("trip %d: %s, %d blocks straight, walked %.0f (x%.2f), %.0f s, %.1f blocks/s, standstills %d, hurt %d times (lowest health %.0f), deaths so far %d, guide said %s",
				trip, arrived ? "ARRIVED" : (stuck ? "STUCK " : "TIMED OUT ") + (int) d + " blocks short", (int) startDist, walked, walked / Math.max(1, startDist), t / 20.0, walked / (t / 20.0), standstills, hits, minHealth, deaths, said);
			log(r); results.add(r);
			trip++; phase = 2; wait = 20;
			return;
		}
		Vec3 s = WaypointGuide.soundAt();
		if (s == null) { keys(mc, false, p.isInWater()); return; } // no sound yet: wait, as you would
		float want = (float) Math.toDegrees(Math.atan2(-(s.x - p.getX()), s.z - p.getZ()));
		float diff = net.minecraft.util.Mth.wrapDegrees(want - p.getYRot());
		p.setYRot(p.getYRot() + Math.max(-10, Math.min(10, diff))); // turns like a person, not a compass needle
		boolean fwd = Math.abs(diff) < 50;
		keys(mc, fwd, p.isInWater() || (fwd && p.horizontalCollision && p.onGround()));
	}

	/** The first standstill of a trip: what the bot and the guide see, and the blocks around (config/sonar-spots, loadable into the self-test). */
	private static void diagnose(Minecraft mc) {
		var p = mc.player;
		Vec3 s = WaypointGuide.soundAt();
		log(String.format("trip %d STUCK at %.1f %.1f %.1f yaw %.0f: onGround %b, inWater %b, pushing a wall %b, standing on %s, sound at %s, guide: %s",
			trip, p.getX(), p.getY(), p.getZ(), p.getYRot(), p.onGround(), p.isInWater(), p.horizontalCollision, p.getBlockStateOn().getBlock().getName().getString(),
			s == null ? "none" : String.format("%.1f %.1f %.1f", s.x, s.y, s.z), String.valueOf(WaypointGuide.report()).lines().limit(2).reduce("", (x, y) -> x + " | " + y)));
		Spot.save(mc);
	}

	/** Part 2: a scripted minute of the things Sonar says by itself and on the helper key; what was said is in the log. */
	private static void speech(Minecraft mc) {
		var p = mc.player;
		keys(mc, false, false);
		switch (step++) {
			case 0 -> { said.clear(); command(mc, "clear @a"); command(mc, "give @a torch 12"); command(mc, "give @a stone_pickaxe"); p.getInventory().setSelectedSlot(8); wait = 40; }
			case 1 -> { p.getInventory().setSelectedSlot(0); wait = 40; }
			case 2 -> { expect("Torch, 12", "hand: a stack"); p.getInventory().setSelectedSlot(1); wait = 40; }
			case 3 -> { expect("Stone Pickaxe", "hand: a tool"); p.getInventory().setSelectedSlot(5); wait = 40; }
			case 4 -> { expect("Empty hand", "hand: empty"); net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(SonarClient.helpKey.saveString())); wait = 40; }
			case 5 -> { expectAny(" block", "helper tap: names what you face"); SonarClient.helpKey.setDown(true); wait = 30; }
			case 6 -> { SonarClient.helpKey.setDown(false); wait = 60; }
			case 7 -> { expectAny("Facing ", "helper hold: where am I"); command(mc, "difficulty easy"); command(mc, "damage @p 15"); wait = 40; }
			case 8 -> { expect("Health low", "health low"); command(mc, "damage @p 3"); wait = 40; }
			case 9 -> { expect("Health very low", "health very low"); command(mc, "effect give @a instant_health 1 10"); command(mc, "difficulty normal"); wait = 40; }
			case 10 -> { var f = p.getLookAngle(); command(mc, String.format(java.util.Locale.ROOT, "summon zombie %.1f %.1f %.1f {NoAI:1b}", p.getX() + f.x * 5, p.getY(), p.getZ() + f.z * 5)); wait = 80; }
			case 11 -> { expectAny("Zombie, ", "threat: the monster is named with its side"); command(mc, "kill @e[type=zombie]"); command(mc, "difficulty peaceful"); wait = 20; }
			case 12 -> { command(mc, "kill @a"); wait = 60; }
			case 13 -> { wait = 80; }
			case 14 -> {
				expectAny("Last death", "death: the place is saved and said");
				boolean kept = Waypoints.list(mc).stream().anyMatch(w -> w.auto && w.name.equals(Waypoints.DEATH));
				String r = "speech: Last death waypoint " + (kept ? "kept" : "MISSING");
				log(r); results.add(r);
				phase = 100;
			}
			default -> phase = 100;
		}
	}

	private static void expect(String exact, String what) {
		boolean ok = said.stream().anyMatch(s -> s.equalsIgnoreCase(exact));
		String r = "speech: " + what + (ok ? " ok" : " MISSING (wanted \"" + exact + "\", heard " + said + ")");
		log(r); results.add(r);
	}

	private static void expectAny(String part, String what) {
		boolean ok = said.stream().anyMatch(s -> s.contains(part));
		String r = "speech: " + what + (ok ? " ok (" + said.stream().filter(s -> s.contains(part)).findFirst().orElse("") + ")" : " MISSING (wanted \"" + part + "\", heard " + said + ")");
		log(r); results.add(r);
	}
}
