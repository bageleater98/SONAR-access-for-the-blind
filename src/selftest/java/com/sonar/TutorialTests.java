package com.sonar;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** The tutorial: guide blocks (barriers) are silent railings, its rooms, the narrator's queue, the sandbox, the voice files. */
final class TutorialTests {
	private TutorialTests() {}

	static final BlockState STONE = Blocks.STONE.defaultBlockState(), BARRIER = Blocks.BARRIER.defaultBlockState(), AIR = Blocks.AIR.defaultBlockState();

	static void run(BiConsumer<Boolean, String> check) {
		guides(check);
		rooms(check);
		narrator(check);
		sandbox(check);
		voiceFiles(check);
		progress(check);
	}

	// ---------------------------------------------------------------- guide blocks

	static void guides(BiConsumer<Boolean, String> check) {
		var pos = new BlockPos.MutableBlockPos();
		// a stone floor, a barrier wall across it at x 5 (2 high), with the floor running on behind it
		World w = new World().fill(-10, 63, -10, 20, 63, 10, STONE).fill(5, 64, -10, 5, 65, 10, BARRIER);
		var heard = Sense.hear(w);
		check.accept(heard.getBlockState(new BlockPos(5, 64, 0)).isAir() && !w.getBlockState(new BlockPos(5, 64, 0)).isAir(), "guide blocks: Sonar hears a barrier as air");
		var hit = heard.clip(new ClipContext(new Vec3(0.5, 65.6, 0.5), new Vec3(15.5, 65.6, 0.5), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
		check.accept(hit.getType() == HitResult.Type.MISS, "guide blocks: a ray (line of sight, echo, room test) passes straight through a barrier");
		check.accept(BlockRules.stepTo(w, 5, 0, 63, pos) == BlockRules.NONE && BlockRules.stepTo(heard, 5, 0, 63, pos) == BlockRules.NONE,
			"guide blocks: never stepped into, in the real world or as Sonar hears it (a silent railing)");
		check.accept(!BlockRules.passable(heard, 5, 64, 0, pos), "guide blocks: not passable for any walk");
		// a cliff (floor ends at x 8, drop to y 50) with a barrier railing along the edge: not a drop you can walk off
		World cliff = new World().fill(-10, 63, -10, 8, 63, 10, STONE).fill(9, 50, -10, 20, 50, 10, STONE).fill(9, 64, -10, 9, 65, 10, BARRIER);
		check.accept(BlockRules.dropBottom(Sense.hear(cliff), 9, 0, 63, pos) == BlockRules.NONE, "guide blocks: a drop behind a barrier railing is not a drop (no edge, no hole)");
		World open = new World().fill(-10, 63, -10, 8, 63, 10, STONE).fill(9, 50, -10, 20, 50, 10, STONE);
		check.accept(BlockRules.dropBottom(Sense.hear(open), 9, 0, 63, pos) != BlockRules.NONE, "guide blocks: (the same cliff without the railing is a drop)");
		// a route round a barrier wall with a gap at one end: it goes round, never through
		World gap = new World().fill(-10, 63, -10, 20, 63, 10, STONE).fill(5, 64, -10, 5, 65, 6, BARRIER);
		List<double[]> route = route(gap, 0.5, 0.5, 10.5, 0.5);
		boolean through = false;
		if (route != null) for (double[] c : route) if (Sense.guide(gap.getBlockState(BlockPos.containing(c[0], c[1] + 1, c[2])))) through = true;
		boolean round = route != null && route.stream().anyMatch(c -> c[2] > 6.5);
		check.accept(route != null && !through && round, "guide blocks: a walking route leads round a barrier wall, never through it");
		World sealed = new World().fill(-10, 63, -10, 20, 63, 10, STONE).fill(5, 64, -10, 5, 65, 10, BARRIER).fill(-11, 64, -10, 21, 65, -11, BARRIER).fill(-11, 64, 11, 21, 65, 11, BARRIER);
		List<double[]> none = route(sealed, 0.5, 0.5, 10.5, 0.5);
		boolean reached = none != null && !none.isEmpty() && Math.abs(none.get(none.size() - 1)[0] - 10.5) < 1;
		check.accept(!reached, "guide blocks: no route at all through a barrier wall with no gap");
	}

	private static List<double[]> route(World w, double mx, double mz, double gx, double gz) {
		var s = new PlayerChime.Search(w, mx, 64, mz, new PlayerChime.Tgt(null, gx, 64, gz, 65.6), false, true);
		while (!s.run(200000)) {}
		return s.path();
	}

	// ---------------------------------------------------------------- the world's fingerprint (the rooms themselves: RoomTests)

	static void rooms(BiConsumer<Boolean, String> check) {
		String h1 = TutorialRooms.hash(), h2 = TutorialRooms.hash();
		check.accept(h1.equals(h2) && h1.length() == 40, "tutorial world: the rooms have one stable fingerprint (a changed room rebuilds the save)");
		var m = new TutorialWorld.Marker();
		m.rooms = h1;
		check.accept(m.current(h1) && !m.current("changed"), "tutorial world: a save is used only when its marker matches these rooms");
		java.util.Set<Integer> idx = new java.util.HashSet<>();
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (var r : TutorialRooms.ALL) { idx.add(r.index()); ids.add(r.id()); }
		check.accept(idx.size() == TutorialRooms.ALL.size() && ids.size() == TutorialRooms.ALL.size(), "tutorial world: every room has its own place and name (" + TutorialRooms.ALL.size() + " rooms)");
	}

	// ---------------------------------------------------------------- the narrator's queue

	static void narrator(BiConsumer<Boolean, String> check) {
		long[] now = {0};
		List<String> played = new ArrayList<>();
		int[] stops = {0};
		Narrator n = new Narrator(new Narrator.Speaker() {
			public double play(String id, boolean shortV) { played.add(id + (shortV ? "*" : "")); return 2.0; }
			public void stop() { stops[0]++; }
		}, () -> now[0]);
		Runnable step = () -> { now[0] += 50_000_000L; n.tick(true); };
		n.say("l1.idle");        // idle, priority 4
		n.say("l1.task");        // instruction, priority 1
		step.run();
		check.accept(played.equals(List.of("l1.task")), "narrator: an instruction goes before idle chatter");
		for (int i = 0; i < 50; i++) step.run(); // 2.5 s: the instruction ends; the idle line is stale by now
		check.accept(played.size() == 1, "narrator: a stale idle line is dropped, not played late (" + played + ")");
		n.say("l1.north");
		step.run();
		for (int i = 0; i < 50; i++) step.run();
		n.say("l1.north"); // its cooldown is 20 s
		for (int i = 0; i < 50; i++) step.run();
		check.accept(played.stream().filter(s -> s.equals("l1.north")).count() == 1, "narrator: a line's cooldown stops it repeating too soon");
		boolean first = n.once("l1.square"), again = n.once("l1.square");
		for (int i = 0; i < 50; i++) step.run();
		check.accept(first && !again && played.contains("l1.square"), "narrator: a first-time line plays once");
		played.clear();
		n.repeat();
		step.run();
		check.accept(played.equals(List.of("l1.task")), "narrator: repeat replays the last INSTRUCTION, not the last reaction");
		// safety cuts in
		for (int i = 0; i < 50; i++) step.run();
		played.clear();
		boolean[] afterRan = {false};
		n.say("l2.after", () -> afterRan[0] = true);
		step.run();
		n.say("sys.reset.fell");
		step.run();
		check.accept(played.equals(List.of("l2.after", "sys.reset.fell")) && stops[0] >= 1, "narrator: a safety line cuts in on an instruction");
		for (int i = 0; i < 120; i++) step.run();
		check.accept(played.size() == 3 && played.get(2).equals("l2.after") && afterRan[0], "narrator: the interrupted instruction comes back afterwards, and its follow-up runs");
		// a listening window: quiet (but not for safety)
		played.clear();
		n.listen(3);
		n.say("l2.hint1");
		for (int i = 0; i < 20; i++) step.run();
		boolean quiet = played.isEmpty();
		for (int i = 0; i < 60; i++) step.run();
		check.accept(quiet && played.contains("l2.hint1"), "narrator: quiet while you listen for a sound, then speaks");
		// never on top of a ping: waits for the timeline, but not forever
		for (int i = 0; i < 60; i++) step.run();
		played.clear();
		n.say("l1.task");
		now[0] += 50_000_000L; n.tick(false);
		boolean waited = played.isEmpty();
		for (int i = 0; i < 15; i++) { now[0] += 50_000_000L; n.tick(false); }
		check.accept(waited && played.size() == 1, "narrator: waits for a ping's sounds to pass, at most a moment");
	}

	// ---------------------------------------------------------------- the sandbox

	static void sandbox(BiConsumer<Boolean, String> check) {
		try {
			Path dir = Files.createTempDirectory("sonar-sandbox");
			double[] g = {0.4};
			boolean[] saved = {false};
			String[] using = {"player"};
			SonarConfig player = new SonarConfig();
			player.autoScan = true;
			player.debug = true;
			SonarConfig[] tut = {null};
			Sandbox.dir = dir;
			Sandbox.on = false;
			Sandbox.gamma = new Sandbox.Gamma() {
				public double get() { return g[0]; }
				public void setRaw(double v) { g[0] = v; }
				public void setNormal(double v) { g[0] = Math.max(0, Math.min(1, v)); }
				public void save() { saved[0] = true; }
			};
			Sandbox.settings = new Sandbox.Settings() {
				public void useTutorial(Path f, SonarConfig start) { using[0] = "tutorial"; tut[0] = start; }
				public void usePlayer() { using[0] = "player"; }
				public SonarConfig player() { return player; }
			};
			Sandbox.enter();
			check.accept(g[0] == 100.0 && using[0].equals("tutorial") && Files.exists(Sandbox.session()), "sandbox: entering forces brightness 100, uses the tutorial's settings, keeps the player's brightness on disk");
			check.accept(tut[0] != null && !tut[0].autoScan && tut[0].debug, "sandbox: the tutorial starts from the defaults (keeping only the debug switches)");
			g[0] = 0.7; // the player moves the brightness slider
			Sandbox.tick();
			check.accept(g[0] == 100.0, "sandbox: the brightness stays locked");
			Sandbox.leave();
			check.accept(g[0] == 0.4 && saved[0] && using[0].equals("player") && !Files.exists(Sandbox.session()), "sandbox: leaving puts the player's brightness and settings back exactly");
			// the game stops inside the tutorial (no leave), then starts again
			Sandbox.enter();
			Sandbox.on = false; // (a crash: nothing ran)
			g[0] = 100.0;
			saved[0] = false;
			Sandbox.recover();
			check.accept(g[0] == 0.4 && saved[0] && !Files.exists(Sandbox.session()), "sandbox: after a crash inside the tutorial, the next start restores the brightness");
		} catch (Exception e) {
			check.accept(false, "sandbox: " + e);
		}
	}

	// ---------------------------------------------------------------- the voice files

	static void voiceFiles(BiConsumer<Boolean, String> check) {
		try {
			Path assets = Path.of("src/client/resources/assets/sonar");
			var sounds = com.google.gson.JsonParser.parseString(Files.readString(assets.resolve("sounds.json"))).getAsJsonObject();
			List<String> missing = new ArrayList<>();
			long total = 0;
			for (var l : Voice.LINES.values()) {
				for (String id : l.shortText() == null ? List.of(l.id()) : List.of(l.id(), l.id() + ".short")) {
					Path f = assets.resolve("sounds/voice/" + id + ".ogg");
					if (!Files.exists(f) || !sounds.has("voice." + id) || !Voice.SECONDS.containsKey(id)) missing.add(id);
					else total += Files.size(f);
				}
			}
			check.accept(!Voice.LINES.isEmpty() && missing.isEmpty(), "voice: every line and short variant has its file, its sounds.json entry and its length" + (missing.isEmpty() ? "" : " (missing " + missing + ")"));
			check.accept(total <= 12L * 1024 * 1024, "voice: all files together " + total / 1024 + " KB, within the 12 MB budget");
			check.accept(Voice.withKeys("Press {key:scan}, then {key:confusing}.", null).equals("Press R, then K."), "voice: key names in the text are the default keys the files say");
			check.accept(Voice.estimate("one two three four five") > 1, "voice: the system-voice fallback has a length to wait for");
			boolean known = true;
			for (String id : List.of("l0.intro", "l1.task", "l2.after", "sys.reset.fell", "hint.skip", "end.complete", "menu.open")) known &= Voice.LINES.containsKey(id);
			check.accept(known, "voice: the lines the lessons use are in the script");
		} catch (Exception e) {
			check.accept(false, "voice: " + e);
		}
	}

	static void progress(BiConsumer<Boolean, String> check) {
		try {
			Path f = Files.createTempDirectory("sonar-progress").resolve("progress.json");
			Progress.file = f;
			check.accept(Progress.load().lesson.equals("l0"), "progress: a new player starts at the first lesson");
			Progress p = Progress.load();
			p.lesson = "l2";
			p.done.add("l0");
			p.save();
			Progress q = Progress.load();
			check.accept(q.lesson.equals("l2") && q.done.contains("l0"), "progress: saved and resumed where you left off");
		} catch (Exception e) {
			check.accept(false, "progress: " + e);
		}
	}
}
