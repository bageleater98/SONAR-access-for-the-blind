package com.sonar;

import net.minecraft.world.level.BlockGetter;

import java.util.List;

/**
 * The far waypoint plan: straight at it while the way ahead walks clear; when something blocks it, follow round the obstacle ON ONE SIDE until the way is clear
 * again and you are nearer than where you met it (the "bug" way of getting round obstacles you can not see the whole of: only the land near you is loaded).
 *
 * Round 14: a lake 700 blocks wide. Each detour aimed at a spot out on the water, got to the nearest shore, then started over and could pick the other side:
 * it bounced you left and right along the bank. Now the side is kept, and when straight on fails it aims further round (35, 70, 100, 130 degrees) on that side,
 * so it walks you along the shore one way until it is round.
 */
final class WaypointPlanner {
	static final double[] ANGLES = {0, 35, 70, 100, 130, 160};
	static final double MIN_PROGRESS = 6;

	final double tx, tz;
	boolean following;
	int side; // +1 / -1 once chosen (kept until round the obstacle), 0 not yet
	double hitDist;
	private int tryIdx;
	private boolean scouting;
	/** While following an edge: the way you have been going along it. A detour that heads back against it is never taken (round 14 test: 260 blocks along a long
	 *  shore the waypoint lay so far to one side that "further round" from it pointed back the way you came, and it shuttled to and fro). */
	private double hx, hz;
	final double ty;
	/** How far beyond start and waypoint the scout looks; doubled when it ran into its own edge without finding a way (up to MAX_MARGIN). */
	int margin = Tuning.SCOUT_MARGIN;
	static final int MAX_MARGIN = 720;
	/** The scout's route counts as round the obstacle when it ends this much nearer the waypoint than you are. */
	static final double SCOUT_GAIN = 24, SCOUT_WEIGHT = 1.5;
	private double ax, az; // the direction the current search aims along
	private double lx = Double.NaN, lz = Double.NaN, lhx, lhz; // where you last went straight again, and the way you were going along the edge then
	/** Straight again only once clear AND this much nearer than where you met it; met again within REMEMBER blocks of that, it is the same obstacle. */
	static final double LEAVE = 8, REMEMBER = 24;

	WaypointPlanner(double tx, double tz) { this(tx, 64, tz); }
	WaypointPlanner(double tx, double ty, double tz) { this.tx = tx; this.ty = ty; this.tz = tz; }

	/** The next search to run from here, or null: walk straight at the waypoint. `clear`: the next WP_LOOK blocks toward it walk clear. */
	PlayerChime.Search begin(BlockGetter w, double px, double py, double pz, boolean clear) {
		double flat = Math.hypot(tx - px, tz - pz);
		if (!following) {
			if (clear) return null;
			following = true; hitDist = flat;
			// met again soon after going straight (round 14 trace: a moment of clear ground on the bank reset it, and the next time it could pick the other side):
			// the same obstacle, so the same side and the same way along it
			if (side != 0 && Math.hypot(px - lx, pz - lz) < REMEMBER) { hx = lhx; hz = lhz; tryIdx = 0; return make(w, px, py, pz); }
			side = 0; hx = 0; hz = 0;
			// first, the scout: one bigger (greedy) search toward the waypoint itself across all the loaded land. If it finds the way round, that is the route and its
			// side is kept; it picks the SHORT way round a lake whenever the end of it is loaded (edge following alone just takes the side it leans to)
			scouting = true; margin = Tuning.SCOUT_MARGIN;
			return scout(w, px, py, pz);
		} else if (clear && flat < hitDist - LEAVE) { following = false; lx = px; lz = pz; lhx = hx; lhz = hz; return null; } // round it: straight again (the side remembered a while)
		tryIdx = 0;
		return make(w, px, py, pz);
	}

	private PlayerChime.Search make(BlockGetter w, double px, double py, double pz) {
		double dx = tx - px, dz = tz - pz, h = Math.max(1e-6, Math.hypot(dx, dz));
		dx /= h; dz /= h;
		double a = Math.toRadians(ANGLES[tryIdx] * (side == 0 ? 1 : side)), c = Math.cos(a), s = Math.sin(a);
		ax = dx * c - dz * s; az = dx * s + dz * c;
		var sr = new PlayerChime.Search(w, px, py, pz, new PlayerChime.Tgt(null, px + ax * Tuning.WP_DETOUR, py, pz + az * Tuning.WP_DETOUR, py + 1.6), false, true);
		sr.nearest = true;
		sr.swim = true; // every waypoint search may swim, at its cost (ROUTE_SWIM): walking wins unless swimming is the easier way (round 15: "swimming is not that hard")
		return sr;
	}

	private PlayerChime.Search scout(BlockGetter w, double px, double py, double pz) {
		var sc = new PlayerChime.Search(w, px, py, pz, new PlayerChime.Tgt(null, tx, ty, tz, ty + 1.6), false, true).greedy(SCOUT_WEIGHT).widen(margin);
		sc.swim = true;
		return sc;
	}

	/** The search finished. Returns the route to follow; or null with `again` set: the next search to run (further round on the kept side). */
	List<double[]> done(PlayerChime.Search sr, BlockGetter w, double px, double py, double pz, PlayerChime.Search[] again) {
		List<double[]> r = sr.path();
		again[0] = null;
		if (scouting && sr.clipped && sr.goal == null && sr.open.isEmpty() && margin < MAX_MARGIN) { margin *= 2; again[0] = scout(w, px, py, pz); return null; } // ran out against its own edge: look wider before judging
		if (scouting) {
			scouting = false;
			if (r != null && r.size() >= 2) {
				double[] e = r.get(r.size() - 1), m = r.get(Math.min(r.size() - 1, 16));
				side = sideOf(m[0] + 0.5 - px, m[2] + 0.5 - pz, px, pz); // the side the way round leans to (from its first stretch)
				if (sr.reached() || flat(px, pz) - Math.hypot(tx - e[0] - 0.5, tz - e[2] - 0.5) >= SCOUT_GAIN) { heading(e[0] + 0.5 - px, e[2] + 0.5 - pz); return r; } // round it: follow the scout's route
			}
			tryIdx = 0; // not round within the loaded land: follow the edge, on the side it leaned to
			again[0] = make(w, px, py, pz);
			return null;
		}
		double ex = 0, ez = 0;
		if (r != null && r.size() >= 2) { double[] e = r.get(r.size() - 1); ex = e[0] + 0.5 - px; ez = e[2] + 0.5 - pz; }
		if (r != null && r.size() >= 2 && ex * ax + ez * az >= MIN_PROGRESS && ex * hx + ez * hz >= 0) {
			if (side == 0) side = sideOf(ex, ez, px, pz);
			heading(ex, ez);
			return r;
		}
		if (side == 0) side = r != null && r.size() >= 2 ? sideOf(ex, ez, px, pz) : 1; // straight on failed: take the side the way leans to
		if (++tryIdx < ANGLES.length) { again[0] = make(w, px, py, pz); return null; }
		// nothing makes progress on this side. Never a route back the way you came along the edge (round 14 trace: it turned you round, -171 degrees): none
		if (r != null && r.size() >= 2 && (hx != 0 || hz != 0) && ex * hx + ez * hz < 0) return null;
		return r;
	}

	private void heading(double ex, double ez) { double h = Math.hypot(ex, ez); if (h > 1e-6) { hx = ex / h; hz = ez / h; } }

	private double flat(double px, double pz) { return Math.hypot(tx - px, tz - pz); }

	/** Which side of the line to the waypoint (dx,dz) from you lies: +1 left-turning (counter-clockwise), -1 the other way. */
	private int sideOf(double ex, double ez, double px, double pz) {
		double dx = tx - px, dz = tz - pz, cr = dx * ez - dz * ex;
		return cr >= 0 ? 1 : -1;
	}
}
