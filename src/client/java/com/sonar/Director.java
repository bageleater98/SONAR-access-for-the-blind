package com.sonar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * THE TUTORIAL'S DIRECTOR: one lesson at a time, in its own room.
 *   teleport to the room's spawn -> WAIT until the room is ready around you (built, chunks loaded and drawn, on the ground; paused time does not count;
 *   a word after 3 s; teleport again after 10 s) -> the lesson's intro -> its STEPS, each: instruction -> a real check (positions, keys, events, settings;
 *   never timers, never in its first second) -> a reaction -> the next step -> success (a chime and a line) -> the next room.
 * Hints come by themselves when a step has not moved for a while (about 25 s, 50 s, then the offer to skip; sooner when struggling, later when ahead:
 * Pacing). Lesson-wide REACTIONS answer what you actually do (bumping the wrong wall, the first time you hear the edge tone...). Safety: out of the room's
 * box, a fall, or stuck for 10 s puts you back at the spawn, with the reason said. Skip, restart, start over, the lesson menu and leave are always there.
 *
 * Runs on a Host (the game, or the self-test's fake player) and a Narrator, so all of it is tested without the game.
 */
final class Director {
	enum Phase { IDLE, LOADING, PLAY, DONE }

	/** One step of a lesson: what to say, when it is done, what to say then, its hints and worked example, and anything to do as it starts. */
	record Step(String say, Predicate<Director> done, String react, String hint1, String hint2, String example, Consumer<Director> onStart, boolean menu) {
		static Step of(String say, Predicate<Director> done, String react, String hint1, String hint2) { return new Step(say, done, react, hint1, hint2, null, null, false); }
		Step example(String ex) { return new Step(say, done, react, hint1, hint2, ex, onStart, menu); }
		Step onStart(Consumer<Director> a) { return new Step(say, done, react, hint1, hint2, example, a, menu); }
		/** This step may start while a menu is open (its instruction is about the menu). Others wait until menus are closed. */
		Step inMenu() { return new Step(say, done, react, hint1, hint2, example, onStart, true); }
	}

	/** A lesson-wide reaction: on event `ev` (null: checked every tick), when `when` holds, say `line` (once ever, or by its cooldown). */
	record Reaction(Tutorial.Ev ev, BiPredicate<Director, Double> when, String line, boolean once) {}

	static final double HINT1_S = 20, HINT2_S = 40, SKIP_OFFER_S = 75, IDLE_S = 40, LOAD_TIMEOUT_S = 10, LOADING_WORD_S = 3, WRONG_WAY_S = 8;
	/** Pushing without getting anywhere: a hint after STUCK_HINT_S (they are trying, not stuck), and only after STUCK_S put back at the spawn. */
	static final double STUCK_HINT_S = 8, STUCK_S = 30;
	/** Waiting for you to close a menu before the next instruction: a nudge after this long. */
	static final double MENU_NUDGE_S = 4;
	/** A step can not be done in its first second (nothing completes by accident as it starts). */
	static final double ARM_DELAY_S = 1.0;
	/** After a fall that is allowed to hurt: this long on the ground before you are put back. */
	static final double LANDED_S = 1.2;
	private long landedAt;

	final Host host;
	final Narrator nar;
	final List<Lesson> lessons;
	Progress progress;
	final Pacing pacing = new Pacing();

	Phase phase = Phase.IDLE;
	int index = -1;
	Lesson lesson;
	int step;
	boolean armed;
	private long armedAt, phaseStart, lastProgress, lastInput, loadReady, lastLoadingLine, lessonStart;
	private int hintLevel, loadTries;
	private boolean resumed, exampleSaid;
	private final Map<Tutorial.Ev, Integer> counts = new HashMap<>();
	private final Set<String> changed = new HashSet<>();
	private final Set<String> onceSaid = new HashSet<>();
	private boolean wasInWater;
	private double anchorX, anchorZ, wrongD = -1;
	private long anchorAt, wrongAt;
	LessonLog.Entry log;
	/** The last time each line was used as a hint in this lesson (so a reset does not repeat them at once). */
	int hintsThisLesson, retriesThisLesson;

	Director(Host host, Narrator nar, List<Lesson> lessons) { this.host = host; this.nar = nar; this.lessons = lessons; }

	double since(long t) { return (host.now() - t) / 1e9; }

	// ---------------------------------------------------------------- the player, in room coordinates

	double rx() { return lesson.room.rx(host.x()); }
	double ry() { return lesson.room.ry(host.y()); }
	double rz() { return lesson.room.rz(host.z()); }
	SonarConfig cfg() { return host.cfg(); }

	/** Within `r` blocks across (and 1.5 up or down) of the room's named spot. */
	boolean at(String spot, double r) {
		var s = lesson.room.spot(spot);
		return Math.hypot(rx() - s.x(), rz() - s.z()) <= r && Math.abs(ry() - s.y()) <= 1.5;
	}

	/** Inside the room-coordinate box x0..x1, z0..z1 (any height). */
	boolean inBox(double x0, double z0, double x1, double z1) { double x = rx(), z = rz(); return x >= x0 && x < x1 && z >= z0 && z < z1; }

	/** How many times this event came since the current step began. */
	int count(Tutorial.Ev e) { return counts.getOrDefault(e, 0); }
	/** This setting (its label) was changed since the current step began. */
	boolean changed(String setting) { return changed.contains(setting); }

	/** Degrees between where you face and yaw `target`. */
	double off(double target) { return Math.abs(net.minecraft.util.Mth.wrapDegrees(host.yaw() - target)); }

	/** The short variants: the player asked to skip intros, is clearly ahead, or passed this lesson before. */
	boolean shortV() { return progress.skipIntros || pacing.ahead() || progress.done.contains(lesson.id) || retriesThisLesson > 0; }

	// ---------------------------------------------------------------- the run

	/** Starts (or resumes) the tutorial. */
	void begin(Progress p) {
		progress = p;
		int i = 0;
		for (int k = 0; k < lessons.size(); k++) if (lessons.get(k).id.equals(p.lesson)) i = k;
		resumed = i > 0 || !p.done.isEmpty();
		start(i);
	}

	/** Lesson i: its settings, then the trip to its room. */
	void start(int i) {
		nar.clear();
		index = i;
		lesson = lessons.get(i);
		progress.lesson = lesson.id;
		progress.save();
		lesson.reset();
		resumeStep = -1;
		pendingStep = -1;
		pendingNext = false;
		Lessons.base(host.cfg(), index < ledgesAt());
		lesson.settings(host.cfg());
		host.saveCfg();
		host.clearWaypoints(); // (a waypoint guide from the waypoints level must not play on into the next)
		host.inventory(lesson.axe);   // empty hands, or the tree lesson's axe
		host.fallDamage(lesson.hurt); // a fall is felt only where the lesson is about falling
		hintsThisLesson = 0;
		retriesThisLesson = 0;
		onceSaid.clear();
		loadTries = 0;
		log = new LessonLog.Entry();
		log.lesson = lesson.id;
		log.start = System.currentTimeMillis();
		lessonStart = host.now();
		teleport(lesson.room.sx(), lesson.room.sy(), lesson.room.sz(), lesson.room.yaw());
		SonarClient.LOG.info("SONAR tutorial: " + lesson.id + " started");
	}

	private double tx, ty, tz;

	/** Moves you within the lesson's room (room coordinates) and waits for it to be ready before anything else happens. */
	void teleport(double x, double y, double z, float yaw) {
		tx = x; ty = y; tz = z;
		phase = Phase.LOADING;
		phaseStart = host.now();
		loadReady = 0;
		lastLoadingLine = host.now() - 60_000_000_000L; // the first "one moment" may come after LOADING_WORD_S; then at most every 15 s
		loadTries++;
		host.teleport(lesson.room, x, y, z, yaw);
	}

	/** Moves you to a named spot of this room mid-lesson (the waypoints lesson), keeping the step. */
	void moveTo(String spot, float yaw) {
		var s = lesson.room.spot(spot);
		int keep = step;
		teleport(s.x(), s.y(), s.z(), yaw);
		resumeStep = keep;
		resumeRun = false;
	}

	private int resumeStep = -1, pendingStep = -1;
	private boolean resumeRun, pendingRun, pendingNext, stuckHinted;
	private long pendingSince;

	/** Before the ping is taught (L0, L1) the barrier lines must not mention it. */
	private String noPing(String id) { return index < 2 || host.cfg().autoScan || lesson.id.equals("l7") ? id + ".np" : id; } // (the ledge level has no walls to ping)

	/** The index of the ledge lesson: the ledge warning, its brake and its side tones are off before it (it is where they are taught). */
	private int ledgesAt() {
		for (int k = 0; k < lessons.size(); k++) if (lessons.get(k).id.equals("l7")) return k;
		return lessons.size();
	}

	boolean holding() { return phase == Phase.LOADING; }

	/** Every client tick. */
	void tick() {
		if (phase == Phase.IDLE) return;
		nar.tick(host.timelineFree(), host.paused());
		if (phase == Phase.LOADING) { loading(); return; }
		if (phase == Phase.DONE && pendingNext) { waitMenu(this::next); return; }
		if (phase != Phase.PLAY) return;
		if (pendingStep >= 0) { int i = pendingStep; boolean run = pendingRun; waitMenu(() -> startStep(i, run)); return; }
		if (host.screenOpen()) { progressed(); lastInput = host.now(); } // time in a menu is not time stuck: hints and idle lines wait
		if (safety()) return;
		if (anyInput()) lastInput = host.now();
		boolean water = host.inWater();
		if (water != wasInWater) { wasInWater = water; event(water ? Tutorial.Ev.WATER_IN : Tutorial.Ev.WATER_OUT, 0, null); }
		if (phase != Phase.PLAY) return;
		if (open() && !nar.busy()) for (Reaction r : lesson.reactions) if (r.ev() == null && r.when().test(this, 0.0)) react(r);
		lesson.tick(this);
		if (!lesson.custom()) stepTick();
		if (phase != Phase.PLAY) return;
		wrongWay();
		hints();
	}

	private void loading() {
		if (host.paused()) { phaseStart += 50_000_000L; lastLoadingLine += 50_000_000L; return; } // a paused game loads nothing: the wait does not count
		String why = host.notLoaded(lesson.room, tx, ty, tz);
		if (since(phaseStart) > LOADING_WORD_S && !nar.busy() && since(lastLoadingLine) > 15) { // never a long silence while the room comes
			lastLoadingLine = host.now();
			nar.say("room still being built".equals(why) ? "sys.building" : "sys.loading");
		}
		if (why == null) {
			if (loadReady == 0) loadReady = host.now();
			if (since(loadReady) >= 0.3) ready();
			return;
		}
		loadReady = 0;
		if (since(phaseStart) > LOAD_TIMEOUT_S && !"room still being built".equals(why)) {
			SonarClient.LOG.info("SONAR tutorial: " + lesson.id + " reset (room not ready after " + LOAD_TIMEOUT_S + " s: " + why + "; teleporting again)");
			teleport(tx, ty, tz, lesson.room.yaw());
		}
	}

	private void ready() {
		phase = Phase.PLAY;
		lastProgress = lastInput = host.now();
		anchorAt = host.now(); anchorX = host.x(); anchorZ = host.z();
		wrongD = -1;
		wasInWater = host.inWater();
		SonarClient.LOG.info("SONAR tutorial: " + lesson.id + " room ready");
		if (resumeStep >= 0) { int s = resumeStep; resumeStep = -1; startStep(s, resumeRun); return; } // after a reset or a move mid-lesson: carry on with the same step
		if (resumed) { resumed = false; nar.say("sys.resume", progress.skipIntros, null); }
		if (lesson.custom()) { lesson.begin(this); return; }
		if (lesson.intro != null) nar.say(lesson.intro, shortV(), null);
		startStep(0, true);
	}

	/** Starts step i of the lesson: its start action (unless resuming it), its instruction, then it is armed. */
	private boolean advancing; // a finished step's reaction is playing; the next step starts when it ends

	void startStep(int i, boolean runStart) {
		advancing = false;
		if (host.screenOpen() && !lesson.steps.get(i).menu()) { // the next instruction waits until you have closed the menu
			pendingStep = i; pendingRun = runStart; pendingSince = host.now();
			step = i; armed = false;
			return;
		}
		pendingStep = -1;
		step = i;
		armed = false;
		exampleSaid = false;
		hintLevel = 0;
		counts.clear();
		changed.clear();
		Step s = lesson.steps.get(i);
		if (runStart && s.onStart() != null) s.onStart().accept(this);
		if (phase != Phase.PLAY) return; // (onStart moved you: the step resumes once you are there)
		if (s.say() == null) arm();
		else if (s.menu()) nar.sayMenu(s.say(), shortV(), this::arm); // (an instruction about the menu you are in: heard there)
		else nar.say(s.say(), shortV(), this::arm);
	}

	private void arm() {
		armed = true;
		armedAt = host.now();
		progressed();
	}

	private void stepTick() {
		if (lesson.goal != null && !advancing && step < lesson.steps.size() && lesson.goal.test(this)) { nar.clear(); pass("goal reached early", null); return; }
		if (!armed && !advancing && step < lesson.steps.size() && pendingStep < 0 && (lesson.early || lesson.steps.get(step).menu()) && lesson.steps.get(step).done().test(this)) {
			nar.clear(); // you did it before she finished asking: she stops, and moves on
			armed = true;
			armedAt = host.now() - (long) (ARM_DELAY_S * 2e9);
		}
		if (!armed || since(armedAt) < ARM_DELAY_S || step >= lesson.steps.size()) return;
		Step s = lesson.steps.get(step);
		if (!s.done().test(this)) return;
		SonarClient.LOG.info("SONAR tutorial: " + lesson.id + " step " + (step + 1) + " done");
		if (step + 1 < lesson.steps.size()) {
			int next = step + 1;
			if (s.react() != null) { armed = false; advancing = true; nar.say(s.react(), () -> startStep(next, true)); } // (the reaction first, then the next instruction: "at the top, well climbed" never lands after "open the door")
			else startStep(next, true);
		} else pass("all " + lesson.steps.size() + " steps", s.react()); // the last step's reaction first, then the success line
	}

	/** Something moved forward: the hints wait again. */
	void progressed() { lastProgress = host.now(); }

	/** The current challenge is open (its instruction has been said). */
	boolean open() { return lesson.custom() ? lesson.armed : armed; }

	/** Waits for menus to close before `then`; after MENU_NUDGE_S, a nudge to close it. */
	private void waitMenu(Runnable then) {
		if (host.screenOpen()) {
			if (since(pendingSince) > MENU_NUDGE_S && !nar.busy()) { nar.say("sys.closemenu"); pendingSince = host.now() + 20_000_000_000L; }
			return;
		}
		pendingNext = false;
		pendingStep = -1;
		then.run();
	}

	private void hints() {
		boolean open = open();
		if (!open || nar.busy()) return;
		double f = pacing.hintFactor(hintsThisLesson, retriesThisLesson) * lesson.hintScale;
		double stuck = since(lastProgress);
		String h1 = lesson.hint1(this), h2 = lesson.hint2(this);
		if (hintLevel == 0 && stuck > HINT1_S * f && h1 != null) { hintLevel = 1; hint(h1); }
		else if (hintLevel == 1 && !exampleSaid && pacing.struggling(hintsThisLesson, retriesThisLesson) && lesson.example(this) != null) { exampleSaid = true; hint(lesson.example(this)); }
		else if (hintLevel == 1 && stuck > HINT2_S * f && h2 != null) { hintLevel = 2; hint(h2); }
		else if (since(lastInput) > IDLE_S && lesson.idle != null && !host.screenOpen()) nar.say(lesson.idle);
	}

	private void hint(String id) {
		hintsThisLesson++;
		log.hints.add(id);
		SonarClient.LOG.info("SONAR tutorial: " + lesson.id + " hint " + hintLevel + " (" + id + ")");
		nar.say(id);
	}

	private boolean anyInput() {
		for (Host.Key k : Host.Key.values()) if (host.down(k)) return true;
		return false;
	}

	private boolean moving() { return host.down(Host.Key.FORWARD) || host.down(Host.Key.BACK) || host.down(Host.Key.LEFT) || host.down(Host.Key.RIGHT); }

	/** Out of the room's box, a fall, or stuck: back to the spawn with the reason said. True when it reset. */
	private boolean safety() {
		var r = lesson.room;
		if (host.y() < r.oy() - 4) return reset("fell below the room", "sys.reset.fell");
		if (!r.inside(host.x(), host.y(), host.z())) return reset("left the room's box", "sys.reset.outside");
		if (lesson.fallBelow != null && ry() < lesson.fallBelow && !host.inWater()) {
			if (!lesson.hurt) return reset("fell off", lesson.fallLine != null ? lesson.fallLine : "sys.reset.fell");
			// a lesson about falling lets the fall be felt: you land (and it hurts a little), a moment on the ground, then back up
			if (!host.onGround()) landedAt = 0;
			else if (landedAt == 0) landedAt = host.now();
			else if (since(landedAt) > LANDED_S) { landedAt = 0; return reset("fell off", lesson.fallLine != null ? lesson.fallLine : "sys.reset.fell"); }
		} else landedAt = 0;
		// stuck: holding a movement key for STUCK_S without getting anywhere (not while a menu is open)
		if (!moving() || host.screenOpen() || Math.hypot(host.x() - anchorX, host.z() - anchorZ) > 0.3) { anchorAt = host.now(); anchorX = host.x(); anchorZ = host.z(); stuckHinted = false; }
		else if (since(anchorAt) > STUCK_S) return reset("stuck", "sys.reset.stuck");
		else if (since(anchorAt) > STUCK_HINT_S && !stuckHinted && !nar.busy()) { // pushing against something: they are trying; help, do not move them
			stuckHinted = true;
			String h = lesson.hint1(this);
			if (h != null && open()) hint(h); else nar.say(noPing("sys.pushing"));
		}
		return false;
	}

	private boolean reset(String why, String line) {
		SonarClient.LOG.info("SONAR tutorial: " + lesson.id + " reset (" + why + ")");
		log.resets.add(why);
		retriesThisLesson++;
		log.retries = retriesThisLesson;
		nar.clear();
		nar.say(line);
		if (!lesson.custom()) { resumeStep = step; resumeRun = true; } // back to the spawn, carrying on with the same step (not the whole lesson)
		else lesson.reset();
		pendingStep = -1;
		teleport(lesson.room.sx(), lesson.room.sy(), lesson.room.sz(), lesson.room.yaw());
		return true;
	}

	/** Moving away from the goal for a while (only where the straight line is the way: Lesson.wrongWay). */
	private void wrongWay() {
		if (!lesson.wrongWay || !armed) return;
		var g = lesson.room.goal();
		double d = Math.hypot(rx() - g.x(), rz() - g.z());
		if (wrongD < 0 || d < wrongD - 0.5 || !moving()) { wrongD = d; wrongAt = host.now(); return; }
		if (d > wrongD + 3 && since(wrongAt) <= WRONG_WAY_S) {
			log.wrongWay++;
			SonarClient.LOG.info("SONAR tutorial: " + lesson.id + " wrong way");
			nar.say(lesson.wrongLine);
			wrongD = d;
			wrongAt = host.now();
		} else if (since(wrongAt) > WRONG_WAY_S) { wrongD = d; wrongAt = host.now(); }
	}

	/** Says a reaction now, only if it can be heard now (the challenge is open, nothing else is being said); a once-line counts as said only when it is. */
	private void react(Reaction r) {
		if (!open() || nar.busy()) return;
		if (r.once() && onceSaid.contains(r.line())) return;
		if (r.once()) onceSaid.add(r.line());
		nar.say(r.line());
	}

	/** An event from Sonar's own code. */
	void event(Tutorial.Ev e, double v, String s) {
		if (phase != Phase.PLAY || lesson == null) return;
		counts.merge(e, 1, Integer::sum);
		if (e == Tutorial.Ev.SETTING && s != null) changed.add(s);
		for (Reaction r : lesson.reactions) if (r.ev() == e && r.when().test(this, v)) react(r);
		if (e == Tutorial.Ev.GUIDE_PUSH && open() && !nar.busy()) nar.say(noPing("sys.guide")); // pushing into an invisible guide wall: not that way
		lesson.event(this, e, v, s);
	}

	/** The lesson is passed: a chime, its success line, then the next room. */
	void pass(String how) { pass(how, null); }

	/** The lesson is passed; `first` (a last reaction) is said before the success line. */
	void pass(String how, String first) {
		if (phase != Phase.PLAY) return;
		phase = Phase.DONE;
		SonarClient.LOG.info("SONAR tutorial: " + lesson.id + " passed (" + how + ")");
		if (!progress.done.contains(lesson.id)) progress.done.add(lesson.id);
		progress.save();
		finishLog(false, how);
		host.passChime();
		nar.dropReactions();
		if (index == lessons.size() - 1) host.fanfare(); // the graduation
		String doneLine = lesson.done != null ? lesson.done : "pass." + (1 + (int) (Math.random() * 3));
		if (first != null) nar.say(first, () -> nar.say(doneLine, this::next)); // (a reaction ranks below a success line: chained, so it is never lost)
		else nar.say(doneLine, this::next);
	}

	private void finishLog(boolean skipped, String how) {
		double secs = since(lessonStart);
		log.end = System.currentTimeMillis();
		log.solveSec = Math.round(secs * 10) / 10.0;
		log.skipped = skipped;
		log.passedHow = how;
		log.retries = retriesThisLesson;
		LessonLog.write(log, log.end);
		pacing.add(new Pacing.Result(lesson.id, secs, lesson.par, hintsThisLesson, retriesThisLesson, skipped));
	}

	private void next() {
		if (host.screenOpen()) { pendingNext = true; pendingSince = host.now(); return; } // the next lesson starts once you have closed the menu
		if (index + 1 < lessons.size()) nar.say("next." + (1 + (int) (Math.random() * 2)), () -> start(index + 1));
		else complete();
	}

	private void complete() {
		progress.completed = true;
		progress.lesson = lessons.get(0).id;
		progress.save();
		SonarClient.LOG.info("SONAR tutorial: complete");
		nar.say("end.complete", host::leave);
	}

	void skip() {
		if (phase == Phase.IDLE || lesson == null) return;
		SonarClient.LOG.info("SONAR tutorial: " + lesson.id + " skipped");
		finishLog(true, "skipped");
		nar.clear();
		phase = Phase.DONE;
		nar.say("sys.skip", () -> { if (index + 1 < lessons.size()) start(index + 1); else complete(); });
	}

	void restart() {
		if (phase == Phase.IDLE || lesson == null) return;
		SonarClient.LOG.info("SONAR tutorial: " + lesson.id + " restarted");
		nar.clear();
		int i = index;
		phase = Phase.DONE;
		nar.say("sys.restart", () -> start(i));
	}

	void startOver() {
		if (phase == Phase.IDLE) return;
		SonarClient.LOG.info("SONAR tutorial: start over");
		nar.clear();
		phase = Phase.DONE;
		progress.done.clear();
		nar.say("sys.over", () -> start(0));
	}

	/** The lesson menu: straight to lesson i. */
	void jump(int i) {
		if (i < 0 || i >= lessons.size()) return;
		SonarClient.LOG.info("SONAR tutorial: lesson menu jump to " + lessons.get(i).id);
		nar.clear();
		start(i);
	}

	/** The furthest lesson you may jump to from the lesson menu. */
	int furthest() { return lessons.size() - 1; } // every lesson is open from the start

	/** The repeat key: the lesson may want it (the headphone check's "try again"), else the last instruction. */
	void repeat() {
		if (phase == Phase.PLAY && lesson.onRepeat(this)) return;
		if (!nar.repeat()) nar.say("sys.nothing");
	}

	/** The "this was confusing" key: a mark in the lesson log and the game log, a quiet click, nothing else. */
	void confusing() {
		var m = new LessonLog.Mark();
		m.t = System.currentTimeMillis();
		m.x = host.x(); m.y = host.y(); m.z = host.z();
		m.lastLine = nar.current() != null ? nar.current() : nar.lastInstruction;
		SonarClient.LOG.info(String.format("SONAR tutorial: confusing (lesson %s, at %.1f %.1f %.1f, last line %s)", lesson == null ? "-" : lesson.id, m.x, m.y, m.z, m.lastLine));
		if (log != null) { log.confusing.add(m); LessonLog.write(log, m.t); }
		host.click();
	}

	void stop() {
		nar.clear();
		phase = Phase.IDLE;
		lesson = null;
	}

	// ---------------------------------------------------------------- lessons

	/**
	 * One lesson: its room, settings, intro, steps, reactions and lines. Most lessons are just steps and reactions; a custom one (the headphone check)
	 * overrides begin / tick.
	 */
	abstract static class Lesson {
		final String id;
		final TutorialRooms.Room room;
		String intro, done, idle;
		final List<Step> steps = new ArrayList<>();
		final List<Reaction> reactions = new ArrayList<>();
		/** A confident player's time for this lesson (seconds): pacing. */
		double par = 60;
		/** Hints a little later (the graduation: you are on your own). */
		double hintScale = 1;
		/** Below this room height (and not in water): fallen off; back to the spawn with fallLine. */
		Double fallBelow;
		String fallLine;
		/** Moving away from the goal says so (only where the straight line is the way). */
		boolean wrongWay;
		/** What is said when you walk the wrong way (default: wrong.1). */
		String wrongLine = "wrong.1";
		/** For custom lessons: the challenge is open. */
		boolean armed;
		/** Its name in the lesson menu. */
		String title;
		/** Falls hurt here (and you are put back only once you have landed). */
		boolean hurt;
		/** You are given an iron axe that can chop logs. */
		boolean axe, early;
		/** Reaching this ends the level at once, whatever step you are on (you did the last thing early). */
		Predicate<Director> goal;

		Lesson(String id, TutorialRooms.Room room) { this.id = id; this.room = room; }

		void settings(SonarConfig c) {}
		boolean custom() { return false; }
		void begin(Director d) {}
		void tick(Director d) {}
		void event(Director d, Tutorial.Ev e, double v, String s) {}
		boolean onRepeat(Director d) { return false; }
		void reset() { armed = false; }

		String hint1(Director d) { return d.step < steps.size() ? steps.get(d.step).hint1() : null; }
		String hint2(Director d) { return d.step < steps.size() ? steps.get(d.step).hint2() : null; }
		String example(Director d) { return d.step < steps.size() ? steps.get(d.step).example() : null; }

		Lesson step(Step s) { steps.add(s); return this; }
		Lesson on(Tutorial.Ev ev, BiPredicate<Director, Double> when, String line) { reactions.add(new Reaction(ev, when, line, false)); return this; }
		Lesson once(Tutorial.Ev ev, BiPredicate<Director, Double> when, String line) { reactions.add(new Reaction(ev, when, line, true)); return this; }
		Lesson where(Predicate<Director> when, String line) { reactions.add(new Reaction(null, (d, v) -> when.test(d), line, true)); return this; }
	}
}
