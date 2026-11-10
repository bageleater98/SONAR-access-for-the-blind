package com.sonar;

import com.sonar.Director.Lesson;
import com.sonar.Director.Step;
import com.sonar.Tutorial.Ev;

import java.util.List;

/**
 * THE LESSONS, in order. Each lesson's room and named spots are in TutorialRooms, its lines in assets/sonar/voice/script.json. Most are a few steps
 * (instruction, a real check, a reaction) plus reactions to what you actually do; the headphone check is its own small machine.
 *
 * Sonar runs as it really plays (the echo is on from the start, as it is by default); a lesson only switches on what it teaches. No lesson asks you to
 * change a setting: the only menu a lesson uses is Waypoints (there is no other way to save a place).
 */
final class Lessons {
	private Lessons() {}

	/** A plain step lesson. */
	static Lesson lesson(String id, TutorialRooms.Room room) { return new Lesson(id, room) {}; }

	/** The tutorial's sweep speed, always: 0.10 s a beat (sweepGap ticks). */
	static final int SWEEP_GAP = 2;

	/** Every lesson starts from this: the defaults a lesson relies on (auto scan off until it is taught; the echo on, as by default; the sweep speed fixed). */
	static void base(SonarConfig c, boolean beforeLedges) {
		c.autoScan = false;
		c.echoCue = true;
		c.sweepGap = SWEEP_GAP;
		c.entityCue = false;
		c.pathGuide = false; // the path bell and the ripple only where they are taught (the path bell lesson) and in the graduation
		c.floorEdge = false;
		if (beforeLedges) { c.autoLedge = false; c.edgeBrake = false; c.sideLedges = false; } // the ledge lesson teaches these
	}

	static List<Lesson> all() {
		return List.of(new Welcome(), turning(), new Ping(), sweep(), materials(), tree(), autoScan(), hedges(), moves(), ledges(), echo(), bend(), entrances(),
			caves(), pathBell(), water(), jingle(), waypoints(), graduation());
	}

	// ---------------------------------------------------------------- L0

	/**
	 * L0 WELCOME: hello, the controls, the headphone check: a chime on your left, then on your right; press the strafe key on that side (A / D) or turn
	 * toward it. Both right: on. Both the wrong way round: "your headphones may be on backwards". Anything else: "your sound may be mono". Then the repeat
	 * key tries again, and jumping carries on anyway.
	 */
	static final class Welcome extends Lesson {
		private static final int[] SIDES = {-1, 1};
		private int trial = -1, chimeTicks;
		private final int[] answers = new int[2];
		private boolean waiting, choosing, wasLeft, wasRight, wasJump;
		private float yaw0;

		Welcome() { super("l0", TutorialRooms.WELCOME); title = "Welcome and headphones"; done = "l0.done"; par = 60; }

		@Override boolean custom() { return true; }
		@Override String hint1(Director d) { return waiting ? "l0.phones.hint1" : null; }
		@Override String hint2(Director d) { return waiting ? "l0.phones.hint2" : null; }
		@Override String example(Director d) { return null; }

		@Override void begin(Director d) {
			boolean s = d.shortV();
			d.nar.say("l0.intro", s, null);
			d.nar.say("l0.controls", s, null);
			d.nar.say("l0.phones", s, () -> { armed = true; d.progressed(); startTrial(d, 0); });
		}

		private void startTrial(Director d, int i) {
			trial = i;
			waiting = false;
			d.nar.say(i == 0 ? "l0.phones.ready" : "l0.phones.again", () -> {
				yaw0 = d.host.yaw();
				wasLeft = d.host.down(Host.Key.LEFT);
				wasRight = d.host.down(Host.Key.RIGHT);
				waiting = true;
				chimeTicks = 0;
				d.nar.listen(8); // quiet while you listen for the chime
			});
		}

		@Override void tick(Director d) {
			if (waiting) {
				if (chimeTicks % 14 == 0 && chimeTicks <= 28) d.host.chime(SIDES[trial]);
				if (chimeTicks++ > 28 * 6) chimeTicks = 0; // still no answer after a while: chime again
				boolean l = d.host.down(Host.Key.LEFT), r = d.host.down(Host.Key.RIGHT);
				double turned = net.minecraft.util.Mth.wrapDegrees(d.host.yaw() - yaw0); // + = turned right
				int answer = (l && !wasLeft) ? -1 : (r && !wasRight) ? 1 : turned <= -30 ? -1 : turned >= 30 ? 1 : 0;
				wasLeft = l; wasRight = r;
				if (answer == 0) return;
				waiting = false;
				d.nar.endListening();
				answers[trial] = answer;
				d.progressed();
				SonarClient.LOG.info("SONAR tutorial: l0 headphone trial " + trial + " side " + SIDES[trial] + " answered " + answer);
				if (trial == 0) startTrial(d, 1); else judge(d);
				return;
			}
			if (choosing) {
				boolean j = d.host.down(Host.Key.JUMP);
				if (j && !wasJump) { choosing = false; d.pass("headphones unclear, carried on"); }
				wasJump = j;
			}
		}

		private void judge(Director d) {
			boolean right = answers[0] == SIDES[0] && answers[1] == SIDES[1];
			boolean swapped = answers[0] == -SIDES[0] && answers[1] == -SIDES[1];
			if (right) { d.nar.say("l0.phones.ok", () -> d.pass("headphones fine")); return; }
			SonarClient.LOG.info("SONAR tutorial: l0 headphones " + (swapped ? "swapped" : "mono or unclear"));
			d.nar.say(swapped ? "l0.phones.swapped" : "l0.phones.mono");
			wasJump = d.host.down(Host.Key.JUMP);
			d.nar.say("l0.phones.choice", () -> choosing = true);
		}

		@Override boolean onRepeat(Director d) {
			if (!choosing) return false;
			choosing = false;
			startTrial(d, 0);
			return true;
		}

		@Override void reset() { super.reset(); trial = -1; waiting = false; choosing = false; }
	}

	// ---------------------------------------------------------------- L1

	/** L1 TURNING: turn until "north", walk to the north wall and bump it. */
	static Lesson turning() {
		Lesson l = new Lesson("l1", TutorialRooms.GARDEN) {
			private boolean north;
			@Override void settings(SonarConfig c) { c.sayFacing = true; c.stuckCue = true; c.centerOnBump = true; }
			@Override void event(Director d, Ev e, double v, String s) {
				if (e != Ev.FACING) return;
				boolean n = v == 2; // the facing word: 0 south, 1 west, 2 north, 3 east
				if (n && !north) { d.progressed(); d.nar.say("l1.north"); }
				else if (!n && north) d.nar.say("l1.away");
				north = n;
			}
			@Override void reset() { super.reset(); north = false; }
		};
		l.title = "Walking and turning"; l.intro = "l1.intro"; l.done = "l1.done"; l.idle = "l1.idle"; l.par = 45;
		l.step(Step.of("l1.task", d -> d.rz() < 2.7 && d.count(Ev.BUMP) > 0 && d.off(180) <= 35, null, "l1.hint1", "l1.hint2"));
		l.once(Ev.BUMP, (d, v) -> v >= 2, "l1.thud.tall");
		l.once(Ev.SQUARED, (d, v) -> true, "l1.square");
		return l;
	}

	// ---------------------------------------------------------------- L2

	/** L2 PING: press R; walk toward the short wall ahead, pinging, until it is loud. Passed: a ping (the second or later) with the wall within 2.5 ahead. */
	static final class Ping extends Lesson {
		private int pings;
		private double lastDist = -1;
		private boolean saidAfter, bumped, close;
		private static final double WALL_Z = 7.0, WALL_X0 = 5, WALL_X1 = 10;

		Ping() {
			super("l2", TutorialRooms.COURTYARD);
			title = "Your first ping"; done = "l2.done"; idle = "l2.idle"; par = 45;
			step(Step.of("l2.intro", d -> close, null, "l2.hint1", "l2.hint2"));
		}

		@Override void event(Director d, Ev e, double v, String s) {
			if (!d.armed) return;
			double dist = d.rz() - WALL_Z;
			if (e == Ev.BUMP && dist < 1.2) bumped = true;
			if (e != Ev.PING || v != 0) return; // your own pings (not auto)
			pings++;
			d.progressed();
			boolean ahead = d.rx() >= WALL_X0 - 1 && d.rx() <= WALL_X1 + 1 && d.off(180) <= 45;
			if (!saidAfter) { saidAfter = true; lastDist = dist; d.nar.say("l2.after", d.shortV(), null); return; }
			if (ahead && dist <= 2.5 && pings >= 2) { close = true; return; }
			if (bumped) { bumped = false; d.nar.once("l2.bumped"); }
			else if (lastDist >= 0 && dist < lastDist - 2) d.nar.say("l2.closer");
			lastDist = dist;
		}

		@Override void reset() { super.reset(); pings = 0; lastDist = -1; saidAfter = false; bumped = false; close = false; }
	}

	// ---------------------------------------------------------------- L3, L4

	/** L3 THE SWEEP: ping, find the open side (your right), walk out through it. Its click (the echo) comes last: the way into the covered yard. */
	static Lesson sweep() {
		Lesson l = lesson("l3", TutorialRooms.SWEEP);
		l.title = "The sweep"; l.intro = "l3.intro"; l.done = "l3.done"; l.wrongWay = true; l.par = 60;
		l.step(Step.of("l3.task", d -> d.rx() >= 12.5, null, "l3.hint1", "l3.hint2").example("l3.example"));
		l.once(Ev.PING, (d, v) -> true, "l3.gap");
		l.once(Ev.ECHO, (d, v) -> true, "l3.click");
		l.on(Ev.BUMP, (d, v) -> d.rx() < 3, "l3.left");
		return l;
	}

	/** L4 MATERIALS: find the wooden wall and walk into it. */
	static Lesson materials() {
		Lesson l = lesson("l4", TutorialRooms.MATERIALS);
		l.title = "Materials"; l.intro = "l4.intro"; l.done = "l4.done"; l.wrongWay = true; l.par = 60;
		l.step(Step.of("l4.task", d -> d.count(Ev.BUMP) > 0 && d.rz() < 2.7 && d.rx() >= 2 && d.rx() < 11, null, "l4.hint1", "l4.hint2").example("l4.example"));
		l.once(Ev.PING, (d, v) -> true, "l4.groups");
		l.on(Ev.BUMP, (d, v) -> !(d.rz() < 2.7 && d.rx() >= 2 && d.rx() < 11), "l4.stone");
		l.where(d -> d.host.floorBlock().endsWith("carpet"), "l4.carpet");
		return l;
	}

	// ---------------------------------------------------------------- the tree

	/** How many of the tree's trunk logs are still standing. */
	static int trunkLeft(Director d) {
		int n = 0;
		for (int[] p : TutorialRooms.TRUNK) if (d.host.blockAt(d.lesson.room, p[0], p[1], p[2]).endsWith("_log")) n++;
		return n;
	}

	/** THE TREE: you are given an iron axe; find the tree by its sound (leaves and a wooden trunk, not the bush) and chop its trunk down. */
	static Lesson tree() {
		Lesson l = new Lesson("tree", TutorialRooms.TREE) {
			private int left = -1;
			@Override void tick(Director d) {
				int n = trunkLeft(d);
				if (left >= 0 && n < left && n > 0) { d.progressed(); d.nar.say("tree.one"); } // the first log down: the next one is lower
				left = n;
			}
			@Override void reset() { super.reset(); left = -1; }
		};
		l.title = "Chopping a tree"; l.intro = "tree.intro"; l.done = "tree.done"; l.par = 90; l.axe = true;
		l.step(Step.of("tree.task", d -> trunkLeft(d) == 0, null, "tree.hint1", "tree.hint2"));
		l.once(Ev.BUMP, (d, v) -> d.rx() < 5 && d.rz() < 6, "tree.bush");
		l.where(d -> d.at("by the tree", 1.6), "tree.at");
		return l;
	}

	// ---------------------------------------------------------------- L5 auto scan, and the hedge walk

	/** The player is up against the L5 pillar (blocks x 2..4, z 8..10) from any side: a bump, wherever it is. */
	static boolean touchingPillar(Director d) {
		double dx = Math.max(Math.max(2 - d.rx(), d.rx() - 5), 0), dz = Math.max(Math.max(8 - d.rz(), d.rz() - 11), 0), r = Math.hypot(dx, dz);
		return Math.abs(d.ry() - 2) <= 1.5 && (r <= 0.65 || (d.count(Ev.BUMP) > 0 && r <= 1.6));
	}

	/** L5 AUTO SCAN: hold R for auto scan; then walk to the pillar while it pings by itself. */
	static Lesson autoScan() {
		Lesson l = lesson("l5", TutorialRooms.AUTO);
		l.title = "Auto scan"; l.intro = "l5.intro"; l.done = "l5.done"; l.par = 60;
		l.step(Step.of("l5.task1", d -> d.cfg().autoScan && d.count(Ev.AUTO_TOGGLED) > 0, "l5.on", "l5.hint.r", null));
		l.step(Step.of("l5.task2", d -> d.cfg().autoScan && touchingPillar(d), null, "l5.hint.pillar", null));
		return l;
	}

	/** THE HEDGE WALK: auto scan on; walk between the hedges, round the bend, to the gate. */
	static Lesson hedges() {
		Lesson l = new Lesson("hedges", TutorialRooms.HEDGES) {
			@Override void settings(SonarConfig c) { c.autoScan = true; }
		};
		l.title = "The hedge walk"; l.intro = "hedges.intro"; l.done = "hedges.done"; l.par = 75;
		l.step(Step.of("hedges.task", d -> d.at("at the gate", 2.0), null, "hedges.hint.walk", "hedges.hint.turn"));
		return l;
	}

	// ---------------------------------------------------------------- L6, L7

	/** L6 MOVES: jump onto a block, climb the stairs, climb the ladder up the wall at the landing, open the door of the cottage on top. */
	static Lesson moves() {
		Lesson l = lesson("l6", TutorialRooms.MOVES);
		l.title = "Jumps, stairs, ladders and doors"; l.intro = "l6.intro"; l.done = "l6.done"; l.par = 120;
		l.goal = d -> d.rx() >= 26 && d.ry() >= 16.9; // (in the cottage by any route: the level is done)
		l.step(Step.of("l6.task1", d -> d.ry() >= 2.9 && d.rx() >= 9 && d.rx() < 12, "l6.up", "l6.hint.jump", null));
		l.step(Step.of("l6.task2", d -> d.ry() >= 8.9 && d.rx() >= 18 && d.rx() < 21, "l6.stairs", "l6.hint.stairs", null));
		l.step(Step.of("l6.task3", d -> d.rx() >= 21 && d.ry() >= 16.9, "l6.ladder", "l6.hint.ladder", null));
		l.step(Step.of("l6.task4", d -> d.rx() >= 26 && d.ry() >= 16.9, null, "l6.hint.door", null));
		l.once(Ev.BUMP, (d, v) -> v == 1, "l6.one");
		return l;
	}

	/** L7 LEDGES: switch the ledge warning on (J); the edge tone; sneak to the lip; the ridge to your right, along to its end. A fall is felt (a little damage), then you go back up. */
	static Lesson ledges() {
		Lesson l = new Lesson("l7", TutorialRooms.LEDGES) {
			@Override void settings(SonarConfig c) { c.autoLedge = false; c.ledgeCue = true; c.edgeBrake = true; c.sideLedges = true; } // (the warning starts off: the first step is turning it on)
		};
		l.title = "Ledges"; l.intro = "l7.intro"; l.done = "l7.done"; l.par = 150;
		l.goal = d -> d.at("ridge end", 1.6);
		l.fallBelow = 5.0; l.fallLine = "l7.fell"; l.hurt = true;
		l.step(Step.of("l7.task0", d -> d.cfg().autoLedge && d.count(Ev.LEDGE_TOGGLED) > 0, "l7.on", "l7.hint.j", null));
		l.step(Step.of("l7.task1", d -> d.count(Ev.LEDGE_TONE) > 0, "l7.tone", "l7.hint.tone", null));
		l.step(Step.of("l7.task2", d -> d.host.down(Host.Key.SNEAK) && d.rz() >= 10 && d.rz() < 10.9 && d.ry() >= 6.9, "l7.lip", "l7.hint.lip", null));
		l.step(Step.of("l7.task3", d -> d.at("ridge end", 1.6), "l7.ridge", "l7.hint.ridge", null));
		l.once(Ev.BRAKE, (d, v) -> true, "l7.brake");
		return l;
	}

	// ---------------------------------------------------------------- L8 - L11: the echo

	/** L8 THE ECHO INDOORS: the last click is the nearest way out; through the door; out to the garden, under the low ceiling on the way. */
	static Lesson echo() {
		Lesson l = new Lesson("l8", TutorialRooms.HOUSE) {
			@Override void settings(SonarConfig c) { c.autoScan = true; c.ceilingCue = true; }
		};
		l.title = "The echo indoors"; l.intro = "l8.intro"; l.done = "l8.done"; l.par = 120;
		l.goal = d -> d.rz() >= 13;
		l.step(Step.of("l8.task2", d -> d.rx() >= 7, null, "l8.hint.door", null));
		l.step(Step.of("l8.task4", d -> d.rz() >= 13, null, "l8.hint.out", null));
		l.once(Ev.ECHO, (d, v) -> true, "l8.last");
		l.where(d -> d.inBox(8, 1, 17, 11), "l8.low");
		l.once(Ev.HEAD_BONK, (d, v) -> true, "l8.bonk");
		return l;
	}

	/** L9 ROUND THE CORNER: follow the muffled click out. */
	static Lesson bend() {
		Lesson l = new Lesson("l9", TutorialRooms.BEND) {
			@Override void settings(SonarConfig c) { c.autoScan = true; }
		};
		l.title = "Round the corner"; l.intro = "l9.intro"; l.done = "l9.done"; l.par = 60;
		l.step(Step.of("l9.task", d -> d.rx() >= 15, null, "l9.hint1", "l9.hint2"));
		l.where(d -> d.rz() < 4 && d.rx() < 5, "l9.clear");
		return l;
	}

	/** L10 ENTRANCES: the shed (no door: no click), the gazebo (a roof, no walls: no click), then follow the muffled click round into the cottage. */
	static Lesson entrances() {
		Lesson l = new Lesson("l10", TutorialRooms.LAWN) {
			@Override void settings(SonarConfig c) { c.autoScan = true; }
		};
		l.title = "Finding entrances"; l.intro = "l10.intro"; l.done = "l10.done"; l.par = 150;
		l.goal = d -> d.inBox(6, 4, 11, 9); // (into the cottage before the shed and the gazebo: the level is done)
		l.step(Step.of("l10.task1", d -> d.inBox(12, 17, 21, 20) && d.count(Ev.BUMP) > 0, "l10.shed", "l10.hint.shed", null));
		l.step(Step.of("l10.task2", d -> d.inBox(24, 14, 30, 20), "l10.gazebo", "l10.hint.gazebo", null));
		l.step(Step.of("l10.task3", d -> d.inBox(6, 4, 11, 9), null, "l10.hint1", "l10.hint2"));
		l.where(d -> d.at("by the doorway", 2.5), "l10.round");
		return l;
	}

	/** L11 CAVES: the big tunnel, the passage round the corner, down the shaft into the lower cave. */
	static Lesson caves() {
		Lesson l = new Lesson("l11", TutorialRooms.CAVES) {
			@Override void settings(SonarConfig c) { c.autoScan = true; }
		};
		l.title = "Caves"; l.intro = "l11.intro"; l.done = "l11.done"; l.par = 180;
		l.goal = d -> d.ry() < 3;
		l.step(Step.of("l11.task1", d -> d.rx() >= 22, "l11.tunnel", "l11.hint.big", null));
		l.step(Step.of("l11.task2", d -> d.rx() >= 28 && d.rz() >= 22, "l11.corner", "l11.hint.corner", null));
		l.step(Step.of("l11.task3", d -> d.ry() < 3, null, "l11.hint.shaft", null));
		l.where(d -> d.rx() < 12, "l11.alcove");
		return l;
	}

	// ---------------------------------------------------------------- L12 - L15

	/** L12 THE PATH BELL: onto the path just ahead, follow the bell to the well. */
	static Lesson pathBell() {
		Lesson l = new Lesson("l12", TutorialRooms.MEADOW) {
			@Override void settings(SonarConfig c) { c.pathGuide = true; c.floorEdge = true; }
		};
		l.title = "The path bell"; l.intro = "l12.intro"; l.done = "l12.done"; l.par = 90;
		l.step(Step.of("l12.task", d -> d.rx() >= 27 && d.rz() <= 6.5, null, "l12.hint1", "l12.hint2"));
		l.where(d -> d.host.floorBlock().equals("dirt_path"), "l12.ripple");
		l.where(d -> d.inBox(8, 18, 15, 25), "l12.plaza");
		return l;
	}

	/** L13 WATER AND LAVA: walk up to the lava pit's fence by its sound; then swim across the pond and out on the far bank. */
	static Lesson water() {
		Lesson l = new Lesson("l13", TutorialRooms.POND) {
			@Override void settings(SonarConfig c) { c.autoScan = true; c.waterLavaLoop = true; c.hazardCue = true; }
		};
		l.title = "Water and lava"; l.intro = "l13.intro"; l.done = "l13.done"; l.par = 120; l.early = true; // (already at the lava while she talks: she stops explaining it)
		l.goal = d -> d.rz() >= 15 && d.host.onGround() && !d.host.inWater();
		l.step(Step.of("l13.task1", d -> d.rx() >= 17 && d.rz() < 7, "l13.lava", "l13.hint.lava", null));
		l.step(Step.of("l13.task", d -> d.rz() >= 15 && d.host.onGround() && !d.host.inWater(), null, "l13.hint1", "l13.hint2"));
		l.once(Ev.WATER_IN, (d, v) -> d.step == 1, "l13.in");
		return l;
	}

	/** L14 THE PLAYER JINGLE: what it is; follow it round the wall to the dummy. */
	static Lesson jingle() {
		Lesson l = new Lesson("l14", TutorialRooms.DUMMY) {
			@Override void settings(SonarConfig c) { c.playerChime = true; }
		};
		l.title = "Following a friend"; l.intro = "l14.intro"; l.done = "l14.done"; l.par = 75; l.early = true; // (already at the friend while she talks: straight to the last line)
		l.step(Step.of("l14.task", d -> d.at("by the dummy", 2.5) && d.ry() >= 5.5, null, "l14.hint1", "l14.hint2"));
		l.where(d -> d.rx() >= 17 && d.rz() >= 9 && d.rz() < 12, "l14.gap");
		l.where(d -> d.ry() >= 5.5, "l14.climb");
		return l;
	}

	/**
	 * L15 WAYPOINTS (the one lesson with a menu: there is no other way to save a place): open Waypoints, add one at the fountain; from the far corner, behind
	 * a wall, open Waypoints, pick it, and be guided round the wall back to it. The instructions about the menu are said in the menu.
	 */
	static Lesson waypoints() {
		Lesson l = lesson("l15", TutorialRooms.SQUARE);
		l.early = true; // acting while she is still talking is fine: she stops and moves on
		l.title = "Waypoints"; l.intro = "l15.intro"; l.done = "l15.done"; l.par = 180;
		// (a step is done by what happened, not only by the state at one instant: a menu you opened while Wren was still talking has closed by the time she stops)
		l.step(Step.of("l15.task1", d -> d.host.waypointsOpen() || d.count(Ev.WAYPOINTS_OPENED) > 0, null, "l15.hint.open", null).onStart(d -> d.host.clearWaypoints()));
		l.step(Step.of("l15.task1b", d -> d.count(Ev.WAYPOINT_ADDED) > 0, "l15.added", "l15.hint.add", null).inMenu());
		l.step(Step.of("l15.task2", d -> d.host.waypointsOpen() || d.count(Ev.WAYPOINTS_OPENED) > 0 || d.host.guiding(), null, "l15.hint.open", null).onStart(d -> d.moveTo("far corner", 0f)));
		l.step(Step.of("l15.task2b", d -> d.count(Ev.WAYPOINT_GUIDING) > 0 || d.host.guiding(), "l15.guiding", "l15.hint.guide", null).inMenu());
		l.step(Step.of(null, d -> d.count(Ev.WAYPOINT_ARRIVED) > 0 || d.at("by the fountain", 2.0), null, "l15.hint.follow", null));
		return l;
	}

	// ---------------------------------------------------------------- graduation

	/** L17 GRADUATION: path, raised walk, cottage, cave, friend. Hints only after a long while; a fanfare and congratulations at the end. */
	static Lesson graduation() {
		Lesson l = new Lesson("l17", TutorialRooms.COURSE) {
			@Override void settings(SonarConfig c) {
				c.autoScan = true; c.pathGuide = true; c.floorEdge = true; c.playerChime = true; c.autoLedge = true; c.ledgeCue = true; c.edgeBrake = true; c.sideLedges = true;
			}
		};
		l.title = "Graduation"; l.intro = "l17.intro"; l.done = "l17.done"; l.par = 300; l.hintScale = 2.0;
		l.step(Step.of("l17.task", d -> d.at("finish", 2.5), null, "l17.hint1", "l17.hint2"));
		l.where(d -> d.ry() >= 2.4 && d.inBox(18, 26, 22, 44), "l17.walk"); // (from the first stair of the raised walk)
		l.where(d -> d.inBox(16, 19, 24, 25), "l17.house");
		l.where(d -> d.inBox(14, 4, 28, 10), "l17.cave");
		return l;
	}
}
