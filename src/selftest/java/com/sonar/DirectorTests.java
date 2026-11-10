package com.sonar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The tutorial's Director on a scripted fake player and clock: lesson order, no instant completion, skip / restart / start over / resume / the lesson menu,
 * waiting for a room to load (and paused time not counting), hints and pacing, the safety resets, the lesson log and the "confusing" mark, and the real
 * lessons' lines all being in the script.
 */
final class DirectorTests {
	private DirectorTests() {}

	/** A player that does what the test says. Positions are world coordinates. */
	static final class FakeHost implements Host {
		long now;
		double x, y, z;
		float yaw;
		boolean ground = true, water, paused, neverLoad, left;
		int notReady, clicks, chimes, passChimes;
		final Set<Key> keys = EnumSet.noneOf(Key.class);
		final List<String> teleports = new ArrayList<>(), commands = new ArrayList<>();
		final SonarConfig cfg = new SonarConfig();
		String floor = "grass_block";

		@Override public long now() { return now; }
		@Override public boolean paused() { return paused; }
		@Override public void teleport(TutorialRooms.Room r, double rx, double ry, double rz, float yaw) {
			teleports.add(r.id() + "@" + rx + "," + ry + "," + rz);
			x = r.ox() + rx; y = r.oy() + ry; z = r.oz() + rz; this.yaw = yaw;
		}
		@Override public String notLoaded(TutorialRooms.Room r, double rx, double ry, double rz) {
			if (neverLoad) return "chunks not loaded";
			if (notReady > 0) { notReady--; return "chunks not drawn yet"; }
			return null;
		}
		@Override public double x() { return x; }
		@Override public double y() { return y; }
		@Override public double z() { return z; }
		@Override public float yaw() { return yaw; }
		@Override public boolean onGround() { return ground; }
		@Override public boolean inWater() { return water; }
		@Override public boolean down(Key k) { return keys.contains(k); }
		@Override public String floorBlock() { return floor; }
		@Override public void chime(int side) { chimes++; }
		@Override public void passChime() { passChimes++; }
		@Override public void click() { clicks++; }
		@Override public SonarConfig cfg() { return cfg; }
		@Override public void saveCfg() {}
		@Override public void leave() { left = true; }
		@Override public boolean timelineFree() { return true; }
		@Override public void serverCommand(String cmd) { commands.add(cmd); }
		@Override public void clearWaypoints() {}
		boolean screen, waypoints, axe, hurts;
		final java.util.Map<String, String> blocks = new java.util.HashMap<>();
		int fanfares;
		@Override public boolean screenOpen() { return screen || waypoints; }
		@Override public boolean waypointsOpen() { return waypoints; }
		@Override public boolean guiding() { return false; }
		@Override public void inventory(boolean axe) { this.axe = axe; }
		@Override public String blockAt(TutorialRooms.Room r, int rx, int ry, int rz) {
			String k = r.id() + ":" + rx + "," + ry + "," + rz;
			if (blocks.containsKey(k)) return blocks.get(k);
			String[] got = {"air"};
			r.buildScene((x, y, z, st) -> { if (x == r.ox() + rx && y == r.oy() + ry && z == r.oz() + rz) got[0] = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath(); });
			return got[0];
		}
		@Override public void fallDamage(boolean on) { hurts = on; }
		@Override public void fanfare() { fanfares++; }

		/** Moves to room coordinates of the lesson's room. */
		void at(TutorialRooms.Room r, double rx, double ry, double rz) { x = r.ox() + rx; y = r.oy() + ry; z = r.oz() + rz; }
	}

	/** A Director on a fake host: every line takes 1 s to say. */
	static final class Rig {
		final FakeHost host = new FakeHost();
		final List<String> said = new ArrayList<>();
		final Director d;
		Rig(List<Director.Lesson> lessons) {
			Narrator n = new Narrator(new Narrator.Speaker() {
				public double play(String id, boolean shortV) { said.add(id + (shortV ? "*" : "")); return 1.0; }
				public void stop() {}
			}, () -> host.now);
			d = new Director(host, n, lessons);
		}
		/** Runs the director for `s` seconds of ticks. */
		void run(double s) { for (int i = 0; i < Math.round(s * 20); i++) { host.now += 50_000_000L; d.tick(); } }
		boolean saidAny(String id) { return said.stream().anyMatch(x -> x.equals(id) || x.equals(id + "*")); }
	}

	static Progress progress(Path dir, String lesson) {
		Progress.file = dir.resolve("progress-" + System.nanoTime() + ".json");
		Progress p = new Progress();
		p.lesson = lesson;
		return p;
	}

	/** A test lesson in a real room: one step with the given check. */
	static Director.Lesson lesson(String id, TutorialRooms.Room room, java.util.function.Predicate<Director> done) {
		Director.Lesson l = new Director.Lesson(id, room) {};
		l.step(Director.Step.of("l1.task", done, null, "l1.hint1", "l1.hint2").example("l3.example"));
		l.done = "l1.done";
		return l;
	}

	static void run(BiConsumer<Boolean, String> check) {
		try {
			Path dir = Files.createTempDirectory("sonar-director");
			LessonLog.dir = dir;
			order(check, dir);
			controls(check, dir);
			loading(check, dir);
			hints(check, dir);
			safety(check, dir);
			menus(check, dir);
			logs(check, dir);
			pacing(check);
			realLessons(check, dir);
		} catch (Exception e) {
			e.printStackTrace();
			check.accept(false, "director tests: " + e);
		}
	}

	static void order(BiConsumer<Boolean, String> check, Path dir) {
		Rig r = new Rig(List.of(lesson("t0", TutorialRooms.GARDEN, d -> true), lesson("t1", TutorialRooms.COURTYARD, d -> true)));
		r.d.begin(progress(dir, "t0"));
		check.accept(r.d.index == 0 && r.d.phase == Director.Phase.LOADING && r.host.teleports.size() == 1, "director: starts at the first lesson, teleporting to its room and waiting for it");
		r.run(0.5);
		check.accept(r.d.phase == Director.Phase.PLAY && r.saidAny("l1.task"), "director: once the room is ready, the step's instruction plays");
		r.run(1.2);
		check.accept(r.d.index == 0 && r.d.phase == Director.Phase.PLAY, "director: nothing passes in the first second after the instruction (a check true from the start waits)");
		r.run(1.0);
		check.accept(r.d.phase != Director.Phase.PLAY || r.d.index == 1, "director: then the step passes");
		r.run(4);
		check.accept(r.d.index == 1 && r.host.passChimes == 1 && r.saidAny("l1.done"), "director: a pass plays the chime and its line, then the next lesson starts");
		r.run(5);
		check.accept(r.host.left && r.d.progress.completed && r.saidAny("end.complete"), "director: after the last lesson: complete, saved, back to the title screen");
	}

	static void controls(BiConsumer<Boolean, String> check, Path dir) {
		var ls = List.of(lesson("t0", TutorialRooms.GARDEN, d -> false), lesson("t1", TutorialRooms.COURTYARD, d -> false), lesson("t2", TutorialRooms.SWEEP, d -> false));
		Rig r = new Rig(ls);
		r.d.begin(progress(dir, "t0"));
		r.run(2);
		r.d.skip();
		r.run(2);
		check.accept(r.d.index == 1 && r.d.log.lesson.equals("t1"), "director: skip goes to the next lesson");
		r.d.restart();
		r.run(2);
		check.accept(r.d.index == 1 && r.host.teleports.get(r.host.teleports.size() - 1).startsWith("l2@"), "director: restart starts the same lesson again, back at its spawn");
		r.d.progress.done.add("t0");
		r.d.startOver();
		r.run(2);
		check.accept(r.d.index == 0 && r.d.progress.done.isEmpty(), "director: start over goes back to the first lesson and clears progress");
		r.d.jump(2);
		r.run(1);
		check.accept(r.d.index == 2, "director: the lesson menu jumps straight to a lesson");
		// resume
		Rig q = new Rig(ls);
		Progress p = progress(dir, "t1");
		p.done.add("t0");
		q.d.begin(p);
		q.run(0.5);
		check.accept(q.d.index == 1 && q.saidAny("sys.resume"), "director: resumes at the saved lesson, saying so");
		check.accept(q.d.furthest() == 2, "director: every lesson is open in the lesson menu from the start");
	}

	static void loading(BiConsumer<Boolean, String> check, Path dir) {
		Rig r = new Rig(List.of(lesson("t0", TutorialRooms.GARDEN, d -> false)));
		r.host.neverLoad = true;
		r.d.begin(progress(dir, "t0"));
		r.run(4);
		check.accept(r.saidAny("sys.loading") && r.d.phase == Director.Phase.LOADING, "director: a room slow to load gets a word after 3 s (never a long silence), nothing else");
		r.run(7);
		check.accept(r.host.teleports.size() == 2, "director: not ready after 10 s: teleports again");
		r.host.paused = true;
		r.run(30);
		check.accept(r.host.teleports.size() == 2, "director: paused time does not count (no retries while the game is paused)");
		r.host.paused = false;
		r.host.neverLoad = false;
		r.host.notReady = 3;
		r.run(1);
		check.accept(r.d.phase == Director.Phase.PLAY, "director: plays as soon as the room is ready");
	}

	static void hints(BiConsumer<Boolean, String> check, Path dir) {
		Rig r = new Rig(List.of(lesson("t0", TutorialRooms.GARDEN, d -> false)));
		r.d.begin(progress(dir, "t0"));
		r.run(2);
		r.run(16);
		boolean early = r.saidAny("l1.hint1");
		r.run(6);
		check.accept(!early && r.saidAny("l1.hint1"), "director: the first hint after about 20 s without progress");
		r.run(25);
		check.accept(r.saidAny("l1.hint2"), "director: the second hint after about 50 s");
		r.run(45);
		check.accept(r.saidAny("hint.skip"), "director: then the offer to skip");
		check.accept(r.d.log.hints.size() >= 3, "director: the hints heard go into the lesson log");
		// struggling (a retry): hints sooner, and the worked example after the first
		Rig s = new Rig(List.of(lesson("t0", TutorialRooms.GARDEN, d -> false)));
		s.d.begin(progress(dir, "t0"));
		s.run(2);
		s.d.retriesThisLesson = 1;
		s.run(16);
		check.accept(s.saidAny("l1.hint1"), "director: struggling, the first hint comes sooner (by 16 s)");
		s.run(3);
		check.accept(s.saidAny("l3.example"), "director: struggling, a worked example follows the first hint");
		// ahead: hints later
		Rig a = new Rig(List.of(lesson("t0", TutorialRooms.GARDEN, d -> false)));
		a.d.pacing.add(new Pacing.Result("x", 10, 60, 0, 0, false));
		a.d.pacing.add(new Pacing.Result("y", 10, 60, 0, 0, false));
		a.d.begin(progress(dir, "t0"));
		a.run(2);
		a.run(24);
		check.accept(!a.saidAny("l1.hint1"), "director: clearly ahead, hints come later (none yet at 24 s)");
		check.accept(a.said.stream().anyMatch(x -> x.equals("l1.task*")), "director: clearly ahead, the short instructions");
	}

	static void safety(BiConsumer<Boolean, String> check, Path dir) {
		Rig r = new Rig(List.of(lesson("t0", TutorialRooms.GARDEN, d -> false)));
		r.d.begin(progress(dir, "t0"));
		r.run(2);
		r.host.keys.add(Host.Key.FORWARD); // pushing, going nowhere
		r.run(14);
		check.accept(r.saidAny("l1.hint1") && r.host.teleports.size() == 1, "director: pushing at something for 12 s: a hint, not a teleport (they are trying)");
		r.run(28);
		r.host.keys.clear();
		check.accept(r.saidAny("sys.reset.stuck") && r.host.teleports.size() == 2 && r.d.log.resets.contains("stuck"), "director: still stuck after 40 s: back to the spawn, with the reason said and logged");
		r.run(4);
		check.accept(r.d.step == 0 && r.said.stream().anyMatch(x -> x.equals("l1.task*")), "director: after a reset the same step carries on, with the short instruction");
		r.run(2);
		r.host.x += 100; // out of the room's box
		r.run(0.2);
		check.accept(r.saidAny("sys.reset.outside") && r.host.teleports.size() == 3, "director: out of the room's box: back to the spawn");
		r.run(2);
		r.host.y -= 20; // fallen below the room
		r.run(0.2);
		check.accept(r.saidAny("sys.reset.fell"), "director: fallen below the room: back to the spawn");
	}

	/** Menus: the next instruction and the next lesson wait until you close the menu (with a nudge); the legend is never talked over. */
	static void menus(BiConsumer<Boolean, String> check, Path dir) {
		Director.Lesson two = new Director.Lesson("t0", TutorialRooms.GARDEN) {};
		two.step(Director.Step.of("l1.task", d -> d.host.screenOpen(), null, null, null));
		two.step(Director.Step.of("l2.after", d -> false, null, "l2.hint1", null));
		Rig r = new Rig(List.of(two, lesson("t1", TutorialRooms.COURTYARD, d -> false)));
		r.d.begin(progress(dir, "t0"));
		r.run(2.5);
		r.host.screen = true; // step 1 is done in a menu
		r.run(3);
		check.accept(r.d.step == 1 && !r.saidAny("l2.after"), "director: the next instruction waits while a menu is open");
		r.run(3);
		check.accept(r.saidAny("sys.closemenu"), "director: after a few seconds, a nudge to close the menu");
		r.host.screen = false;
		r.run(2);
		check.accept(r.saidAny("l2.after"), "director: menu closed: the instruction plays");
		// the next lesson waits too
		Rig q = new Rig(List.of(lesson("t0", TutorialRooms.GARDEN, d -> d.host.screenOpen()), lesson("t1", TutorialRooms.COURTYARD, d -> false)));
		q.d.begin(progress(dir, "t0"));
		q.run(2.5);
		q.host.screen = true;
		q.run(8);
		check.accept(q.d.index == 0 && q.host.teleports.size() == 1, "director: a lesson passed in a menu: the next one waits for the menu to close");
		q.host.screen = false;
		q.run(3);
		check.accept(q.d.index == 1, "director: then the next lesson starts");
		// a menu (the game paused): no hints while you are in it, and Wren's line waits with the game
		Rig g = new Rig(List.of(lesson("t0", TutorialRooms.GARDEN, d -> false)));
		g.d.begin(progress(dir, "t0"));
		g.run(2);
		g.host.screen = true; g.host.paused = true;
		g.run(60);
		check.accept(!g.saidAny("l1.hint1"), "director: time in a menu is not time stuck: no hints while it is open");
		g.host.screen = false; g.host.paused = false;
		g.run(2);
		check.accept(!g.saidAny("l1.hint1"), "director: back from the menu, the hint clock starts again (no hint at once)");
		// reactions only when they can be heard
		Director.Lesson re = lesson("t0", TutorialRooms.GARDEN, d -> false);
		re.once(Tutorial.Ev.PING, (d, v) -> true, "l3.gap");
		Rig x = new Rig(List.of(re));
		x.d.begin(progress(dir, "t0"));
		x.run(0.5);
		x.d.event(Tutorial.Ev.PING, 0, null); // while the instruction is still being said
		x.run(2);
		boolean early = x.saidAny("l3.gap");
		x.d.event(Tutorial.Ev.PING, 0, null);
		x.run(1);
		check.accept(!early && x.saidAny("l3.gap"), "director: a reaction waits until it can be heard (and a first-time line is not lost)");
		x.run(2);
		x.d.event(Tutorial.Ev.GUIDE_PUSH, 0, null);
		x.run(1);
		check.accept(x.saidAny("sys.guide") || x.saidAny("sys.guide.np"), "director: pushing into an invisible guide wall: Wren says that way is closed");
	}

	static void logs(BiConsumer<Boolean, String> check, Path dir) {
		Rig r = new Rig(List.of(lesson("t0", TutorialRooms.GARDEN, d -> false), lesson("t1", TutorialRooms.COURTYARD, d -> false)));
		r.d.begin(progress(dir, "t0"));
		r.run(2);
		r.d.confusing();
		check.accept(r.host.clicks == 1, "director: the confusing key: a quiet click, nothing else");
		r.d.skip();
		r.run(2);
		var day = LessonLog.read(LessonLog.file(System.currentTimeMillis()));
		var t0 = day.lessons.stream().filter(e -> e.lesson.equals("t0")).reduce((a, b) -> b).orElse(null);
		check.accept(t0 != null && t0.skipped && t0.confusing.size() == 1 && t0.confusing.get(0).lastLine != null && day.voice.contains("af_heart") && day.rooms.length() == 40,
			"lesson log: written locally with the lesson, skip, confusing mark (with the last line), world rooms and voice");
		Progress p = progress(dir, "l0");
		p.offered = true;
		p.save();
		check.accept(Progress.load().offered, "first launch: the offer is made once and remembered");
	}

	static void pacing(BiConsumer<Boolean, String> check) {
		Pacing p = new Pacing();
		check.accept(!p.ahead() && !p.struggling(0, 0) && p.hintFactor(0, 0) == 1.0, "pacing: a new player is neither ahead nor struggling");
		p.add(new Pacing.Result("a", 20, 60, 0, 0, false));
		p.add(new Pacing.Result("b", 30, 60, 0, 0, false));
		check.accept(p.ahead() && p.hintFactor(0, 0) > 1, "pacing: two fast lessons in a row: ahead (later hints, short intros)");
		p.add(new Pacing.Result("c", 200, 60, 2, 0, false));
		check.accept(!p.ahead() && p.struggling(0, 0) && p.hintFactor(0, 0) < 1, "pacing: a lesson that needed two hints: struggling (sooner hints)");
		p.add(new Pacing.Result("d", 5, 60, 0, 0, true));
		check.accept(p.struggling(0, 0), "pacing: a skipped lesson does not count either way");
	}

	/** The real lessons: their order and rooms, every line they use is in the script, and two of them played through. */
	static void realLessons(BiConsumer<Boolean, String> check, Path dir) {
		var ls = Lessons.all();
		boolean rooms = ls.size() == TutorialRooms.ALL.size();
		for (int i = 0; i < ls.size() && rooms; i++) rooms = ls.get(i).room == TutorialRooms.ALL.get(i) && ls.get(i).id.equals(TutorialRooms.ALL.get(i).id());
		check.accept(rooms && ls.size() == 19, "lessons: 19 lessons, each in its own room, in order");
		boolean titled = ls.stream().allMatch(l -> l.title != null && Voice.LINES.containsKey("title." + l.id));
		check.accept(titled, "lessons: each has a title and a spoken title line");
		List<String> missing = new ArrayList<>();
		for (var l : ls) {
			List<String> ids = new ArrayList<>(java.util.Arrays.asList(l.intro, l.done, l.idle, l.fallLine, "title." + l.id));
			for (var s : l.steps) ids.addAll(java.util.Arrays.asList(s.say(), s.react(), s.hint1(), s.hint2(), s.example()));
			for (var rc : l.reactions) ids.add(rc.line());
			for (String id : ids) if (id != null && !Voice.LINES.containsKey(id)) missing.add(id);
		}
		check.accept(missing.isEmpty(), "lessons: every line they use is in the script" + (missing.isEmpty() ? "" : " (missing " + missing + ")"));
		// L5, played through by the fake player: hold R, walk to the pillar; no settings to change
		Rig r = new Rig(Lessons.all());
		Progress p = progress(dir, "l5");
		r.d.begin(p);
		r.run(12);
		check.accept(r.d.lesson.id.equals("l5") && r.d.step == 0 && r.host.cfg.sweepGap == Lessons.SWEEP_GAP && !r.host.cfg.autoScan && r.host.cfg.echoCue,
			"l5 played: starts at its first step; sweep speed fixed at 0.10 s a beat, auto scan off, the echo on");
		r.host.cfg.autoScan = true;
		r.d.event(Tutorial.Ev.AUTO_TOGGLED, 1, null);
		r.run(8);
		check.accept(r.d.step == 1, "l5 played: holding R for auto scan moves on");
		var pil = TutorialRooms.AUTO.spot("by the pillar");
		r.host.at(TutorialRooms.AUTO, pil.x(), pil.y(), pil.z());
		r.run(12);
		check.accept(r.d.lesson.id.equals("hedges"), "l5 played: reaching the pillar with auto scan on passes; the hedge walk is next, its own lesson");
		r.run(12);
		check.accept(r.host.cfg.autoScan && r.host.cfg.sweepGap == Lessons.SWEEP_GAP, "hedges: auto scan already on, the sweep speed still fixed");
		var gate = TutorialRooms.HEDGES.spot("at the gate");
		r.host.at(TutorialRooms.HEDGES, gate.x(), gate.y(), gate.z());
		r.run(12);
		check.accept(r.d.progress.done.contains("hedges"), "hedges played: reaching the gate passes");
		// the tree: an axe, then the trunk chopped down (both logs)
		Rig t = new Rig(Lessons.all());
		t.d.begin(progress(dir, "tree"));
		t.run(14);
		check.accept(t.host.axe && t.d.step == 0, "tree played: you are given the axe");
		t.host.blocks.put("tree:9,3,4", "air");
		t.run(3);
		check.accept(t.saidAny("tree.one") && !t.d.progress.done.contains("tree"), "tree played: one log down: Wren says where the rest of the trunk is");
		t.host.blocks.put("tree:9,2,4", "air");
		t.run(3);
		check.accept(!t.d.progress.done.contains("tree"), "tree played: a log still standing: not yet");
		t.host.blocks.put("tree:9,4,4", "air");
		t.run(4);
		check.accept(t.d.progress.done.contains("tree"), "tree played: the whole trunk down passes");
		t.run(12);
		check.accept(!t.host.axe, "tree played: the next lesson takes the axe away");
		// L7: a fall off the terrace puts you back with its own line
		Rig f = new Rig(Lessons.all());
		f.d.begin(progress(dir, "l7"));
		f.run(12);
		check.accept(f.host.hurts, "l7 played: falls hurt in the ledges lesson");
		f.host.ground = false;
		f.host.at(TutorialRooms.LEDGES, 6.5, 4, 5.5); // falling
		f.run(0.5);
		f.host.ground = true;
		f.host.at(TutorialRooms.LEDGES, 6.5, 2, 5.5); // landed on the lower ground
		f.run(0.5);
		boolean early = f.saidAny("l7.fell");
		f.run(1.5);
		check.accept(!early && f.saidAny("l7.fell"), "l7 played: a fall is felt: you land, a moment on the ground, then back up with its own line");
		Rig m6 = new Rig(Lessons.all());
		m6.d.begin(progress(dir, "l6"));
		m6.run(14);
		for (String nm : new String[]{"on the step", "top of the stairs", "top of the ladder", "in the cottage"}) {
			var sp6 = TutorialRooms.MOVES.spot(nm);
			m6.host.at(TutorialRooms.MOVES, sp6.x(), sp6.y(), sp6.z());
			m6.run(14);
		}
		check.accept(m6.d.progress.done.contains("l6"), "l6 played: on the step, the stairs, the ladder, then in the cottage: the level passes");
		Rig h = new Rig(Lessons.all());
		h.d.begin(progress(dir, "l6"));
		h.run(3);
		check.accept(!h.host.hurts, "lessons: falls do not hurt outside the ledges lesson");
		// L15: the waypoint lesson's menu instructions are said in the menu (the only lesson with a menu)
		Rig w = new Rig(Lessons.all());
		w.d.begin(progress(dir, "l15"));
		w.run(14);
		w.host.waypoints = true; w.host.paused = true;
		w.run(6);
		check.accept(w.saidAny("l15.task1b"), "l15 played: in the Waypoints menu, Wren explains it there (a menu line plays while paused)");
		w.d.event(Tutorial.Ev.WAYPOINT_ADDED, 0, null);
		w.run(3);
		check.accept(!w.saidAny("l15.added"), "l15 played: 'saved, close the menu' waits... ");
		w.host.waypoints = false; w.host.paused = false;
		w.run(14);
		check.accept(w.saidAny("l15.added") && w.saidAny("l15.task2") && w.host.teleports.stream().anyMatch(x -> x.startsWith("l15@3.5")), "l15 played: menu closed: saved, moved to the far corner, next instruction");
		// graduation: a fanfare
		Rig e = new Rig(Lessons.all());
		e.d.begin(progress(dir, "l17"));
		e.run(14);
		var fin = TutorialRooms.COURSE.spot("finish");
		e.host.at(TutorialRooms.COURSE, fin.x(), fin.y(), fin.z());
		e.run(20);
		check.accept(e.host.fanfares == 1 && e.saidAny("l17.done") && e.saidAny("end.complete") && e.host.left, "graduation: a fanfare, congratulations, the course complete, back to the title screen");
		// the fixed phrases are in the narrator's voice
		check.accept("say.north".equals(Voice.phraseId("north")) && "say.auto.on".equals(Voice.phraseId("Auto scan on"))
			&& Voice.phraseId("Arrived at home") == null, "voice: fixed phrases (facing words, on/off) have the narrator's voice; names stay with the system voice");
	}
}
