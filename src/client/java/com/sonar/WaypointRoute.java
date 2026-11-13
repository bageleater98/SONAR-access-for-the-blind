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

	/** What the guide is doing for you right now, for what it says (GuideSpeech). */
	enum Status { FINDING, GUIDING, OFF_ROUTE, BLOCKED, STUCK }

	Status status(double mx, double my, double mz) {
		if (route == null) return stuck ? Status.STUCK : Status.FINDING;
		if (closest) return Status.BLOCKED;
		return at(mx, my, mz) < 0 ? Status.OFF_ROUTE : Status.GUIDING;
	}

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
		int i = at(mx, my, mz);
		if (wait > 0) wait--;
		if (closest && i < 0 && wait == 0) { closest = false; failsFromHere = 0; } // you left the closest-point route: look for a walking way again
		if (sr == null && wait == 0) {
			if (i < 0 || replan) { // no route, you left it, or it needs replacing: a new one from where you are (the old one keeps sounding meanwhile)
				replan = false;
				double sx = mx, sy = my, sz = mz;
				if (PlayerChime.floorUnder(w, mx, my, mz, new net.minecraft.core.BlockPos.MutableBlockPos()) == BlockRules.NONE && stand != null && !inWater(w, mx, my, mz)) { sx = stand[0]; sy = stand[1]; sz = stand[2]; } // mid-jump: from where you last stood
				begin(w, sx, sy, sz, false);
			} else if (!reached && !closest && left(i) < EXTEND) {
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
		if (desperate) { // nothing walks on from here: the last resort. Anything is allowed (ladders, swimming, ending under rock, old dead ends forgotten) and the spot NEAREST the waypoint is the goal
			near = false;
			if (dStage == 0 && !BlockRules.caveRoof(w, new net.minecraft.world.phys.Vec3(x, y + 0.5, z), 24)) dStage = 1; // not under rock: there is no cave to get out of
			if (dStage == 0) { // FIRST the way OUT: the nearest spot in the open, found with no aim at the waypoint at all (a cave system floods before it ever leads out, when it is aimed at where the waypoint is)
				sr = new PlayerChime.Search(w, x, y, z, new PlayerChime.Tgt(null, tx, ty, tz, ty + 1.6), false, true).greedy(0).widen(dMargin);
				sr.exitMode = true; sr.swim = true; sr.useKnown = true; sr.cap = 300_000; sr.avoid = dead;
				return;
			}
			sr = new PlayerChime.Search(w, x, y, z, new PlayerChime.Tgt(null, tx, ty, tz, ty + 1.6), true, true).widen(dMargin);
			sr.nearest = true; sr.swim = true; sr.paths = true; sr.useKnown = true;
			// first pass: only OPEN AIR may be where it ends (the way out of a cave system, ladders and all, to the surface); only if that finds nothing, anywhere at all (a sealed pocket's own far end)
			sr.openEnd = dStage == 1; if (dStage == 1) sr.avoid = dead;
			return;
		}
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
		sr.swim = true; sr.useKnown = true;
		sr.openEnd = !near;
		sr.avoid = dead;
		sr.paths = true;
	}

	private void finish(BlockGetter w, double mx, double my, double mz) {
		List<double[]> r;
		measure(sr);
		if (desperate && sr.clipped && sr.goal == null && sr.open.isEmpty() && dMargin < WaypointPlanner.MAX_MARGIN) { dMargin *= 2; sr = null; wait = 0; replan = true; return; } // the wider search hit its own edge: wider still
		// CLOSED IN (round 18, a sealed cave): the scout looked at every spot there is, a smallish closed area, and found no way out. Re-planning (other angles, other sides,
		// forgotten dead ends) only dithers: straight to the last resort, once, and then hold that route steady
		if (!near && !fromEnd && !desperate && sr.goal == null && sr.open.isEmpty() && !sr.clipped && sr.expanded >= 200 && sr.expanded < Tuning.CLOSED_MAX) { // (a search that could not even start, in water or air, has looked at nothing: not "closed in")
			lastSpots = sr.expanded; sr = null; plan.following = false;
			dMargin = Tuning.SCOUT_MARGIN; desperate = true; wait = 0; replan = true; last = "closed in (searched all " + lastSpots + " spots): the closest point";
			return;
		}
		double sx = fromEnd ? endAtStart[0] + 0.5 : mx, sy = fromEnd ? endAtStart[1] + 1 : my, sz = fromEnd ? endAtStart[2] + 0.5 : mz;
		if (near || desperate) r = sr.path();
		else {
			r = plan.done(sr, sr.w, sx, sy, sz, again);
			if (again[0] != null) { sr = again[0]; sr.swim = true; sr.useKnown = true; sr.openEnd = true; sr.avoid = dead; sr.paths = true; return; } // further round, same side
		}
		boolean hit = (near || desperate) && sr.reached();
		lastSpots = sr.expanded;
		sr = null;
		if (desperate && dStage == 0) { // the way out ran: out in the open (the route is joined on from its end by the normal search), or no way out
			if (hit && r != null && r.size() >= 2) {
				desperate = false; last = "out of the cave"; closest = false; failsFromHere = 0; wait = 0; closestWait = 100; reached = false; recovering = true;
				put(r);
				return;
			}
			dStage = 1; wait = 0; replan = true; return;
		}
		if (desperate && dStage == 1 && !(r != null && r.size() >= 2 && (hit || progress(r) >= 30))) { dStage = 2; wait = 0; replan = true; return; } // nothing out in the open: now anywhere
		if (desperate) { dStage = 0; // the last resort ran: the closest the walking world gets to the waypoint, or nothing
			desperate = false;
			// the wider search (ladders, anything) got through: that is just the way, not a blocked one (round 18 night 2: it reached the waypoint and was still called "closest point")
			if (r != null && r.size() >= 2 && (hit || progress(r) >= 30)) {
				last = hit ? "to the waypoint (wider search)" : "new route (wider search)"; closest = false; failsFromHere = 0; wait = 40; closestWait = 100;
				take(r, hit);
				return;
			}
			if (r != null && r.size() >= 2 && r.get(r.size() - 1)[3] >= 3) {
				last = "closest point"; closest = true; failsFromHere = 0; wait = closestWait; closestWait = Math.min(600, closestWait * 2); // (looked at again after 5 s, then 10, 20, 30: it would only find the same)
				put(r);
				dig = rockAhead(lastW, route.get(route.size() - 1));
				up = openGroundUp(lastW, route.get(route.size() - 1));
				digSaid = false;
				return;
			}
			if (lastSpots < 50) { last = "no way at all (could not start: in water or air)"; wait = 20; return; } // nothing was looked at: no words
			last = "no way at all"; wait = 60; stuck = true;
			return;
		}
		if (r != null && r.size() >= 2 && (hit || progress(r) >= 2)) {
			last = hit ? "to the waypoint" : fromEnd ? "extended" : "new route";
			recovering = false; exitTries = 0;
			failsFromHere = 0;
			if (!fromEnd) wait = 40; // a fresh route from where you stand: no other from-scratch search for two seconds (it was re-planning every few seconds and flipping sides)
			take(r, hit);
			return;
		}
		// no way on from there
		last = "no way on";
		if (fromEnd) {
			// a dead end: that spot is avoided from now on, and the way is worked out again from where you are (it may need to go back a little: never blindly on)
			double[] e = endAtStart;
			dead.add(PlayerChime.Search.xz((int) e[0], (int) e[2]));
				if (recovering) { // the way out ended in a place that leads nowhere either (a pit open to the sky): the next way out, at once, not after another round of looking
					if (++exitTries < MAX_EXIT_TRIES) { route = trimToYou(mx, my, mz); dMargin = Tuning.SCOUT_MARGIN; desperate = true; dStage = 0; wait = 0; replan = true; last = "that way out leads nowhere: the next"; return; }
					recovering = false; exitTries = 0; dStage = 1; desperate = true; wait = 0; replan = true; last = "no way out found: the closest point"; return;
				}
			route = trimToYou(mx, my, mz);
				replan = true; // the route stays for the sound; the next search starts from you
		} else {
			wait = 20; // from where you are, no way on: look again in a second
			if (++failsFromHere >= 2) { route = null; last = "no way on (the old route led nowhere: dropped)"; } // never keep leading into the wall
			if (failsFromHere >= 3) { dMargin = Tuning.SCOUT_MARGIN; desperate = true; wait = 0; replan = true; last = "no way on (last resort: the closest point)"; } // round 18 (Nov 10, a sealed cave): never "no way at all" and a sound stuck on a wall
		}
	}

	private int wait;

	/** The next tick starts a search from where you are, even though a route is still being followed (it keeps sounding until the new one is joined in). */
	private boolean replan;
	/** The next search is the last resort (see begin). */
	private boolean desperate;
	private int dMargin = Tuning.SCOUT_MARGIN, closestWait = 100, dStage, exitTries;
	/** A way out of a cave was taken and is being followed; if its end leads nowhere, the next way out is tried at once (at most MAX_EXIT_TRIES in a row). */
	private boolean recovering;
	static final int MAX_EXIT_TRIES = 5;
	/** The route ends at the closest point the walking world gets to the waypoint (no walking way on): it is not extended from its end. */
	boolean closest;
	/** Every way to go on was tried and none was found: no route, and nothing to lead you to. */
	private boolean stuck;
	/** At the closest point: how many blocks of rock lie between it and open ground toward the waypoint (1 to 6), else -1. Said when you get there (the guide). */
	int dig = -1;
	boolean digSaid;
	private BlockGetter lastW;
	private PlayerChime.Search lastSr;
	/** At the closest point: how many blocks up the nearest open ground (the surface, in the open) is, within 12 blocks across; -1 when there is none. */
	int up = -1;
	/** The box of what the last search looked at (minX, maxX, minY, maxY, minZ, maxZ), for the debug snapshot (F2). */
	int[] searched;

	private void measure(PlayerChime.Search s) {
		lastW = s.w; lastSr = s;
		if (!SonarConfig.cur.debug) return;
		int[] b = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE};
		for (var n : s.nodes.values()) {
			b[0] = Math.min(b[0], n.x); b[1] = Math.max(b[1], n.x); b[2] = Math.min(b[2], n.y); b[3] = Math.max(b[3], n.y); b[4] = Math.min(b[4], n.z); b[5] = Math.max(b[5], n.z);
		}
		searched = b[0] == Integer.MAX_VALUE ? null : b;
	}

	/** The nearest open ground ABOVE the end cell that the closed area does not reach: the topmost solid block of a column within 12 blocks, with two blocks of air over it. Its height above you, 2 to 30, else -1. */
	int openGroundUp(BlockGetter w, double[] end) {
		if (w == null) return -1;
		var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		int ex = (int) end[0], ez = (int) end[2], ey = (int) end[1];
		double best = Double.MAX_VALUE; int bestUp = -1;
		for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
			int x = ex + dx, z = ez + dz;
			for (int y = ey + 30; y >= ey + 2; y--) {
				var s = w.getBlockState(pos.set(x, y, z));
				if (s.getCollisionShape(w, pos).isEmpty()) continue;
				// the topmost solid block: open ground when there is air over it and it is not a tree top or a liquid
				if (w.getBlockState(pos.set(x, y + 1, z)).isAir() && w.getBlockState(pos.set(x, y + 2, z)).isAir() && !s.is(net.minecraft.tags.BlockTags.LEAVES) && (lastSr == null || !lastSr.nodes.containsKey(PlayerChime.key(x, y, z)))) {
					double sc = (y - ey) + 0.5 * Math.hypot(dx, dz);
					if (sc < best) { best = sc; bestUp = y - ey; }
				}
				break;
			}
		}
		return bestUp;
	}

	/** From the route's end cell toward the waypoint: how many columns of rock lie before the next ground you could step onto (1 to 6), else -1. */
	int rockAhead(BlockGetter w, double[] end) {
		if (w == null) return -1;
		var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		double dx = tx - end[0] - 0.5, dz = tz - end[2] - 0.5, h = Math.max(1e-6, Math.hypot(dx, dz));
		dx /= h; dz /= h;
		int cx = (int) end[0], cz = (int) end[2], cur = (int) end[1], solid = 0;
		for (double s = 0.5; s <= 8; s += 0.5) {
			int nx = (int) Math.floor(end[0] + 0.5 + dx * s), nz = (int) Math.floor(end[2] + 0.5 + dz * s);
			if (nx == cx && nz == cz) continue;
			cx = nx; cz = nz;
			int nf = PlayerChime.stepTo(w, nx, nz, cur, pos);
			if (nf != BlockRules.NONE) return solid >= 1 && solid <= 6 ? solid : -1; // open ground again
			if (w.getBlockState(pos.set(nx, cur + 1, nz)).isAir() && w.getBlockState(pos.set(nx, cur + 2, nz)).isAir()) return -1; // a gap or drop, not rock
			solid++;
		}
		return -1;
	}


	/** How much nearer the waypoint the route's end is than its start. */
	private double progress(List<double[]> r) {
		double[] a = r.get(0), b = r.get(r.size() - 1);
		return Math.hypot(tx - a[0] - 0.5, tz - a[2] - 0.5) - Math.hypot(tx - b[0] - 0.5, tz - b[2] - 0.5) + (b[3] >= 4 ? 2 : 0); // (a stretch round an obstacle may not gain at first: 4 blocks of walking counts)
	}

	/** Takes a found stretch: joined onto the route (from its end) or as the new route (from you). */
	private void take(List<double[]> r, boolean hit) {
		reached = hit;
		if (!fromEnd || route == null) { put(r); return; }
		List<double[]> out = new ArrayList<>(route);
		double base = out.get(out.size() - 1)[3];
		for (int k = 1; k < r.size(); k++) { double[] c = r.get(k).clone(); c[3] += base; out.add(c); }
		route = out;
	}

	/** The route from the cell nearest you on, or null. */
	/** A route found from where you are replaces the old one. */
	private void put(List<double[]> r) { route = new ArrayList<>(r); replan = false; stuck = false; }

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
	boolean off(double mx, double my, double mz) { return at(mx, my, mz) < 0; }

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
