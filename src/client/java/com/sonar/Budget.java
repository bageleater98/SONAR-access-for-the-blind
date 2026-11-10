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

	private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "sonar-routes"); t.setDaemon(true); return t; });
	private static final java.util.Map<PlayerChime.Search, java.util.concurrent.Future<?>> RUNNING = new java.util.IdentityHashMap<>();

	/**
	 * Works a route search on a helper thread (it only reads blocks, and is not touched by the game thread until it is over): the game thread never waits for a
	 * route, so a long search can not stutter the game. True when the search is over (found, or no way); call it every tick until then.
	 */
	static boolean run(PlayerChime.Search sr) {
		var f = RUNNING.get(sr);
		if (RUNNING.size() > 16) RUNNING.values().removeIf(x -> x.isDone() && x != RUNNING.get(sr)); // (searches that were dropped before they ended)
		if (f == null) {
			f = POOL.submit(() -> {
				try {
					long end = System.nanoTime() + 20_000_000_000L; // never longer than 20 s
					while (!sr.run(PlayerChime.SLICE, 50_000_000L) && System.nanoTime() < end) {}
				} catch (Throwable e) { sr.done = true; sr.failed = true; } // (a block changed under it: no route this time, the next search tries again)
			});
			RUNNING.put(sr, f);
		}
		if (f.isDone()) { RUNNING.remove(sr); return true; }
		return false;
	}
}
