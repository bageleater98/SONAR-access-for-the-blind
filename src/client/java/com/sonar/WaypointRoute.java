package com.sonar;

import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.List;

/**
 * THE ROLLING ROUTE to a waypoint: there is always a checked, walkable route ahead of you, and it is extended from its far end before you get there.
 *
 * Round 16 (Nov 8, 700 blocks across mountains): every time a detour was walked to its end, the guide pointed straight at the waypoint until the next search
 * finished (2 to 12 s): into the mountain, a tree, off a cliff, a jump across a gap. Now:
 *  - while you walk one stretch, the next is worked out FROM ITS END (EXTEND blocks before you reach it) and joined on, so there is no gap;
 *  - "straight on" is itself a route: the cells of the next WP_LOOK blocks toward the waypoint, each checked walkable (never a blind direction);
 *  - if you leave the route, a new one is worked out from where you are, while the sound holds on the old one;
 *  - a stretch that leads nowhere (no way on from its end) marks that spot a dead end: no later route ends near it, and a far route never ends under rock.
 */
final class WaypointRoute {
	/** Start the next stretch when this much walking is left on the route. */
	static final double EXTEND = 20;
	/** Off the route: more than this far from every cell of it (as PlayerChime.lead). */
	static final double OFF = 4;

	final double tx, ty, tz;
	final WaypointPlanner plan;
	/** The route: {x, y, z (floor cell), walking distance from its first cell, cost}. */
	List<double[]> route;
	/** The route ends at the waypoint itself. */
	boolean reached;
	private PlayerChime.Search sr;
	private boolean fromEnd, near;
	private double[] endAtStart; // the end the running search extends from
	final it.unimi.dsi.fastutil.longs.LongOpenHashSet dead = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private final PlayerChime.Search[] again = new PlayerChime.Search[1];
	private int failsFromHere;
	/** For the debug line: what the last finished search did. */
	String last = "";
	int lastSpots;

	WaypointRoute(double tx, double ty, double tz) { this.tx = tx; this.ty = ty; this.tz = tz; this.plan = new WaypointPlanner(tx, ty, tz); }

	boolean searching() { return sr != null; }

	/** Index of the route cell you are at (the furthest-along of the nearest), or -1 when you are off the route. */
	int at(double mx, double my, double mz) {
		if (route == null) return -1;
		int from = -1;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < route.size(); i++) {
			double[] c = route.get(i);
			double d = Math.hypot(c[0] + 0.5 - mx, c[2] + 0.5 - mz) + Math.abs(c[1] + 1 - my) * 0.5;
			if (d <= bd + 0.25) { bd = Math.min(bd, d); from = i; }
		}
		return bd > OFF ? -1 : from;
	}

	/** Walking left from cell i to the route's end. */
	double left(int i) { return route.get(route.size() - 1)[3] - route.get(i)[3]; }

	/**
	 * One tick: keep a route ahead of you (feet at mx,my,mz; `standX..` the last floor you stood on, for a start in mid-air), working any search for up to `nanos`
	 * (Long.MAX_VALUE: to the end, for the tests).
	 */
	void tick(BlockGetter w, double mx, double my, double mz, double[] stand, long nanos) {
		int i = offForced ? -1 : at(mx, my, mz);
		if (wait > 0) wait--;
		if (sr == null && wait == 0) {
			if (i < 0) { // no route, or you left it: a new one from where you are (the old one keeps sounding meanwhile)
				double sx = mx, sy = my, sz = mz;
				if (PlayerChime.floorUnder(w, mx, my, mz, new net.minecraft.core.BlockPos.MutableBlockPos()) == BlockRules.NONE && stand != null && !inWater(w, mx, my, mz)) { sx = stand[0]; sy = stand[1]; sz = stand[2]; } // mid-jump: from where you last stood
				begin(w, sx, sy, sz, false);
			} else if (!reached && left(i) < EXTEND) {
				double[] e = route.get(route.size() - 1);
				begin(w, e[0] + 0.5, e[1] + 1, e[2] + 0.5, true);
			}
		}
		if (sr != null) {
			boolean done = nanos == Long.MAX_VALUE ? runAll() : nanos < 0 ? sr.run((int) -nanos) : Budget.run(sr); // (tests: -n = n spots this tick)
			if (done) finish(w, mx, my, mz);
		}
	}

	private boolean runAll() { while (!sr.run(1_000_000)) {} return true; }

	private static boolean inWater(BlockGetter w, double x, double y, double z) {
		return w.getBlockState(new net.minecraft.core.BlockPos((int) Math.floor(x), (int) Math.floor(y + 0.2), (int) Math.floor(z))).getFluidState().getType().isSame(net.minecraft.world.level.material.Fluids.WATER);
	}

	/** Starts the next stretch from (x,y,z): the whole route when the waypoint is near, else the planner (straight when the way ahead walks clear). */
	private void begin(BlockGetter w, double x, double y, double z, boolean fromEnd) {
		this.fromEnd = fromEnd;
		endAtStart = fromEnd ? route.get(route.size() - 1) : null;
		double flat = Math.hypot(tx - x, tz - z);
		if (fromEnd && flat <= Tuning.WP_ARRIVE && Math.abs(ty - (y - 1)) <= 2) { reached = true; last = "to the waypoint"; return; } // the route already ends at the waypoint
		near = flat <= Tuning.WP_NEAR;
		if (near) {
			sr = new PlayerChime.Search(w, x, y, z, new PlayerChime.Tgt(null, tx, ty, tz, ty + 1.6), false, true);
			plan.following = false;
		} else {
			boolean clear = WaypointGuide.clearAhead(w, x, y, z, tx, tz, Tuning.WP_LOOK) && straight(w, x, y, z, tx, tz, Tuning.WP_LOOK) != null; // walkable AND in the open
			sr = plan.begin(w, x, y, z, clear);
			if (sr == null) { // straight on: the next WP_LOOK blocks, each one checked
				List<double[]> st = straight(w, x, y, z, tx, tz, Tuning.WP_LOOK);
				last = "straight"; lastSpots = 0;
				if (st != null && st.size() >= 2) { take(st, false); return; }
				// (clearAhead said clear but the cells could not be laid: search instead)
				plan.following = true; plan.hitDist = flat;
				sr = plan.begin(w, x, y, z, false);
				if (sr == null) return;
			}
		}
		sr.swim = true;
		sr.openEnd = !near;
		sr.avoid = dead;
		sr.paths = true;
	}

	private void finish(BlockGetter w, double mx, double my, double mz) {
		List<double[]> r;
		double sx = fromEnd ? endAtStart[0] + 0.5 : mx, sy = fromEnd ? endAtStart[1] + 1 : my, sz = fromEnd ? endAtStart[2] + 0.5 : mz;
		if (near) r = sr.path();
		else {
			r = plan.done(sr, sr.w, sx, sy, sz, again);
			if (again[0] != null) { sr = again[0]; sr.swim = true; sr.openEnd = true; sr.avoid = dead; sr.paths = true; return; } // further round, same side
		}
		boolean hit = near && sr.reached();
		lastSpots = sr.expanded;
		sr = null;
		if (r != null && r.size() >= 2 && (hit || progress(r) >= 2)) {
			last = hit ? "to the waypoint" : fromEnd ? "extended" : "new route";
			failsFromHere = 0;
			take(r, hit);
			return;
		}
		// no way on from there
		last = "no way on";
		if (fromEnd) {
			// a dead end: that spot is avoided from now on, and the way is worked out again from where you are (it may need to go back a little: never blindly on)
			double[] e = endAtStart;
			dead.add(PlayerChime.Search.xz((int) e[0], (int) e[2]));
			route = trimToYou(mx, my, mz);
			offForced = true; // the route stays for the sound; the next search starts from you
		} else {
			wait = 20; // from where you are, no way on: look again in a second (the route you have keeps sounding)
			if (++failsFromHere >= 3 && route == null) last = "no way at all";
		}
	}

	private int wait;

	private boolean offForced;

	/** How much nearer the waypoint the route's end is than its start. */
	private double progress(List<double[]> r) {
		double[] a = r.get(0), b = r.get(r.size() - 1);
		return Math.hypot(tx - a[0] - 0.5, tz - a[2] - 0.5) - Math.hypot(tx - b[0] - 0.5, tz - b[2] - 0.5) + (b[3] >= 4 ? 2 : 0); // (a stretch round an obstacle may not gain at first: 4 blocks of walking counts)
	}

	/** Takes a found stretch: joined onto the route (from its end) or as the new route (from you). */
	private void take(List<double[]> r, boolean hit) {
		reached = hit;
		if (!fromEnd || route == null) { route = new ArrayList<>(r); offForced = false; return; }
		List<double[]> out = new ArrayList<>(route);
		double base = out.get(out.size() - 1)[3];
		for (int k = 1; k < r.size(); k++) { double[] c = r.get(k).clone(); c[3] += base; out.add(c); }
		route = out;
	}

	/** The route from the cell nearest you on, or null. */
	private List<double[]> trimToYou(double mx, double my, double mz) {
		int i = at(mx, my, mz);
		if (i < 0) return route;
		return new ArrayList<>(route.subList(Math.max(0, i - 2), route.size()));
	}

	/** Keeps the route short: drops what lies more than a few cells behind you. */
	void trim(double mx, double my, double mz) {
		int i = at(mx, my, mz);
		if (i > 8) {
			List<double[]> out = new ArrayList<>(route.subList(i - 4, route.size()));
			route = out;
		}
	}

	/** You (at mx..) count as off the route: forced after a dead end, or really off it. */
	boolean off(double mx, double my, double mz) { return offForced || at(mx, my, mz) < 0; }

	/**
	 * The straight stretch: from (x,y,z) toward (tx,tz), `len` blocks, each cell stepped onto by the route search's own step rule (and both edges of your body
	 * clear). Null when it can not be walked. Cells as the route's: {x, y, z, walking distance, cost}.
	 */
	static List<double[]> straight(BlockGetter w, double x, double y, double z, double tx, double tz, double len) {
		var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		int cur = PlayerChime.floorUnder(w, x, y, z, pos);
		if (cur == BlockRules.NONE) return null;
		double dx = tx - x, dz = tz - z, h = Math.max(1e-6, Math.hypot(dx, dz));
		dx /= h; dz /= h;
		len = Math.min(len, h);
		List<double[]> out = new ArrayList<>();
		int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
		out.add(new double[]{cx, cur, cz, 0, 0});
		double walk = 0;
		for (double s = 0.25; s <= len; s += 0.25) {
			int nx = (int) Math.floor(x + dx * s), nz = (int) Math.floor(z + dz * s);
			if (nx == cx && nz == cz) continue;
			if (Math.abs(nx - cx) + Math.abs(nz - cz) == 2 && (PlayerChime.stepTo(w, nx, cz, cur, pos) != cur || PlayerChime.stepTo(w, cx, nz, cur, pos) != cur)) break; // never cut a corner
			int nf = PlayerChime.stepTo(w, nx, nz, cur, pos);
			if (nf == BlockRules.NONE) break;
			// out in the open only: "straight on" never walks into a tunnel or cave mouth (a walkable tunnel pointing at the waypoint is how round 16 led into a dead end)
			if (BlockRules.caveRoof(w, new net.minecraft.world.phys.Vec3(nx + 0.5, nf + 1.5, nz + 0.5), 24)) return null;
			walk += Math.hypot(nx - cx, nz - cz) + Math.abs(nf - cur) * 0.5;
			cx = nx; cz = nz; cur = nf;
			out.add(new double[]{cx, cur, cz, walk, walk});
		}
		return out.size() >= 2 && WaypointGuide.clearAhead(w, x, y, z, tx, tz, Math.min(len, walk)) ? out : null;
	}
}
