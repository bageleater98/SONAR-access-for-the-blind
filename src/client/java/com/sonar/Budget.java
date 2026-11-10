package com.sonar;

/**
 * One time budget for the mod's work in a tick, so all its features together never take a noticeable part of a frame. The work that must happen in its tick (the
 * sonar scan step, an echo stage) runs first; the work that can pause and carry on next tick (the route searches) gets what is left, between a small floor and its
 * own cap.
 *
 * Round 16 test (Nov 8): the sonar scan step alone took 5 to 7 ms a tick with auto scan on, so the route searches only ever got the 0.3 ms floor and a waypoint
 * detour of 7000 spots took 12 seconds. Now the route searches have their OWN share of every tick (SEARCH_SHARE_NS, shared by all of them), whatever the scan used;
 * and the auto scan's step is held to SCAN_TICK_NS (Scan.step), so the two together stay near the budget.
 */
final class Budget {
	private Budget() {}

	private static long start, searchUsed;

	/** At the start of the mod's tick. */
	static void start() { start = System.nanoTime(); searchUsed = 0; }

	/** Time (ns) a route search may use now: the larger of what is left of the tick's budget and what is left of the searches' own share; at least the floor, at most
	 *  one search's cap. */
	static long forSearch() {
		long left = Tuning.TICK_BUDGET_NS - (System.nanoTime() - start), share = Tuning.SEARCH_SHARE_NS - searchUsed;
		return Math.max(Tuning.SEARCH_FLOOR_NS, Math.min(Tuning.ROUTE_TICK_NANOS, Math.max(left, share)));
	}

	/** Runs a route search for this tick's share of time. True when it is over. */
	static boolean run(PlayerChime.Search sr) {
		long t0 = System.nanoTime();
		boolean done = sr.run(PlayerChime.SLICE, forSearch());
		searchUsed += System.nanoTime() - t0;
		return done;
	}
}
