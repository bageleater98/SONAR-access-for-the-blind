package com.sonar;

import java.util.TreeSet;

/**
 * When sounds play, so they never land on top of each other. The sonar ping and the echo clicks BOOK the ticks they play on (they carry the most detail);
 * the steady guide sounds (the player jingle's beat, the path bell's ring) wait a tick or two for a free moment instead of playing over them. The ledge warning is a
 * continuous tone and never waits.
 */
final class Timeline {
	private Timeline() {}

	private static final TreeSet<Integer> booked = new TreeSet<>();
	private static int now;

	/** Called once per client tick with the mod's tick count. */
	static void tick(int t) {
		now = t;
		while (!booked.isEmpty() && booked.first() < now - 4) booked.pollFirst();
	}

	/** A detailed sound (a sonar ping, an echo click) plays `inTicks` from now. */
	static void book(int inTicks) {
		booked.add(now + inTicks);
	}

	/** Nothing booked from a tick ago to two ticks ahead: a guide sound may play now without covering one, or being covered. */
	static boolean free() {
		Integer n = booked.ceiling(now - 1);
		return n == null || n > now + 2;
	}

	/** A new world: nothing booked. */
	static void reset() { booked.clear(); }
}
