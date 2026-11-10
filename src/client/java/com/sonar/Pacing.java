package com.sonar;

import java.util.ArrayList;
import java.util.List;

/**
 * ADAPTIVE PACING: how the player has been doing, lesson by lesson. Clearly ahead (the last two lessons solved quickly, with no hints and no retries): the
 * short intros and later hints. Struggling (a retry, two hints, or the last lesson needed two hints): hints sooner, and an extra worked example after the
 * first hint. Never patronising, never rushing: the player can always ask for "repeat" or wait for a hint.
 */
final class Pacing {
	/** One lesson's outcome. par = the time a confident player needs (seconds). */
	record Result(String lesson, double solveSec, double par, int hints, int retries, boolean skipped) {
		boolean fast() { return !skipped && solveSec <= par && hints == 0 && retries == 0; }
	}

	final List<Result> results = new ArrayList<>();

	void add(Result r) { results.add(r); }

	/** The last two lessons (not skipped) were both fast. */
	boolean ahead() {
		int n = 0;
		for (int i = results.size() - 1; i >= 0 && n < 2; i--) {
			Result r = results.get(i);
			if (r.skipped()) continue;
			if (!r.fast()) return false;
			n++;
		}
		return n == 2;
	}

	/** Struggling in this lesson (hints and retries so far), or the last lesson needed two hints. */
	boolean struggling(int hintsNow, int retriesNow) {
		if (retriesNow >= 1 || hintsNow >= 2) return true;
		for (int i = results.size() - 1; i >= 0; i--) if (!results.get(i).skipped()) return results.get(i).hints() >= 2;
		return false;
	}

	/** How much later (> 1) or sooner (< 1) hints come. */
	double hintFactor(int hintsNow, int retriesNow) {
		if (struggling(hintsNow, retriesNow)) return 0.6;
		if (ahead()) return 1.5;
		return 1.0;
	}
}
