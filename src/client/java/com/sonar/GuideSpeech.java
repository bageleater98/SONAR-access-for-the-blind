package com.sonar;

/**
 * What the waypoint guide SAYS about itself, in one place. The rules (round 18, from the log of a cave that took 14 s to find a first route and then called itself
 * lost a moment before finding one):
 *  - It speaks about a STATE, never about an event: the state (WaypointRoute.Status) has to last a while before a word is said, so one that flips back within a
 *    moment is never mentioned (no "can't find a way" a second before a route turns up).
 *  - Each line is said once per spell of its state; the spell is over only when you have been guided along a route for two seconds.
 *  - At most one line every six seconds, none while swimming, never over another of the guide's own lines.
 *
 *  FINDING for 1 s (and not before 3 s after the start, for the "Guiding to..." line): "Finding a route." (the first time) or "Recalculating route." (after having had one); after 10 s more: "Hold on, I'm finding another way."
 *  OFF_ROUTE for 2 s: "You've fallen off the route..."   BLOCKED for 3 s: the closest-point line.   STUCK for 4 s: the nothing-found line.
 *  All are fixed lines in the narrator's own voice (Voice.phrase); none says a name.
 */
final class GuideSpeech {
	static final String FINDING = "Finding a route.", RECALC = "Recalculating route.", HOLD = "Hold on, I'm finding another way.",
		OFF = "You've fallen off the route. Let me find you another way.",
		BLOCKED = "I can't find a walking way on from here. I'll lead you to the closest spot I can, and you may need to dig or build from there.",
		STUCK = "I can't find a way on from here yet. Try turning back, or dig through.";

	/** At the closest point: the rock to dig through (1 to 6 blocks), or else how far up the open ground is (a rough size; the lines are pre-rendered). */
	static String hint(int dig, int up) {
		if (dig > 0) return "The way on is through " + new String[]{"", "one block", "two blocks", "three blocks", "four blocks", "five blocks", "six blocks"}[dig] + " of rock, straight ahead. Dig forward.";
		return "Open ground is " + (up <= 3 ? "a few blocks" : up <= 6 ? "about five blocks" : up <= 10 ? "about eight blocks" : up <= 16 ? "about a dozen blocks" : "well") + " above you. Look for a way up.";
	}

	/** Ticks a state must last before it is spoken, and the least gap between two lines. */
	static final int FINDING_AFTER = 20, HOLD_AFTER = 230, OFF_AFTER = 40, BLOCKED_AFTER = 60, STUCK_AFTER = 80, GUIDED_TO_REARM = 40, GAP = 120, FIRST_GAP = 60;

	private WaypointRoute.Status status = WaypointRoute.Status.FINDING;
	private int inState, now, lastSpoke;
	private boolean hadRoute, saidFinding, saidHold, saidOff, saidBlocked, saidStuck;

	/** A guide starts (or the world changes): nothing said yet, and the first line waits for the "Guiding to..." announcement. */
	void reset(int tick) {
		status = WaypointRoute.Status.FINDING; inState = 0; now = tick; lastSpoke = tick - GAP + FIRST_GAP;
		hadRoute = saidFinding = saidHold = saidOff = saidBlocked = saidStuck = false;
	}

	/** Every tick: the state the guide is in, and whether it must be quiet (swimming). `out` speaks a line (Speech.say in the game, a list in the tests). Returns the line said this tick, or null. */
	String update(java.util.function.Consumer<String> out, WaypointRoute.Status s, boolean quiet, int tick) {
		now = tick;
		if (s != status) { status = s; inState = 0; } else inState++;
		if (s == WaypointRoute.Status.GUIDING) { // a spell is over once you have been guided for a moment
			if (inState >= GUIDED_TO_REARM) { hadRoute = true; saidFinding = saidHold = saidOff = saidBlocked = saidStuck = false; }
			return null;
		}
		if (quiet || now - lastSpoke < GAP) return null;
		String line = null;
		switch (s) {
			case FINDING -> {
				if (!saidFinding && inState >= FINDING_AFTER) { saidFinding = true; line = hadRoute ? RECALC : FINDING; }
				else if (saidFinding && !saidHold && inState >= HOLD_AFTER) { saidHold = true; line = HOLD; }
			}
			case OFF_ROUTE -> { if (!saidOff && inState >= OFF_AFTER) { saidOff = true; line = OFF; } }
			case BLOCKED -> { if (!saidBlocked && inState >= BLOCKED_AFTER) { saidBlocked = true; line = BLOCKED; } }
			case STUCK -> { if (!saidStuck && inState >= STUCK_AFTER) { saidStuck = true; line = STUCK; } }
			default -> { }
		}
		if (line != null) say(out, line);
		return line;
	}

	/** Says a line of the guide's own (the hint at the closest point goes through here too, so it keeps the gap). False when it is too soon. */
	boolean say(java.util.function.Consumer<String> out, String text) {
		if (now - lastSpoke < GAP) return false;
		lastSpoke = now;
		out.accept(text);
		return true;
	}
}
