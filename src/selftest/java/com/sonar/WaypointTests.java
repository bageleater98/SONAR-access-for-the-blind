package com.sonar;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Waypoints: the route, any-angle walking, over a hill rather than through a cave, out-of-reach progress, volume, arrival, route keeping, save and reload. */
final class WaypointTests {
	private WaypointTests() {}

	static final BlockState STONE = Blocks.STONE.defaultBlockState(), GRASS = Blocks.GRASS_BLOCK.defaultBlockState(), AIR = Blocks.AIR.defaultBlockState();

	static List<double[]> route(World w, double mx, double mz, double gx, double gy, double gz) {
		var s = new PlayerChime.Search(w, mx, 64, mz, new PlayerChime.Tgt(null, gx, gy, gz, gy + 1.6), false, true);
		while (!s.run(100000)) {}
		return s.path();
	}

	/** Walks toward a waypoint far north (z 600) by the planner, a stretch of each route at a time: {z reached, looks, reversed (1/0), side moved along the shore (-1/+1)}. */
	static double[] walkRound(World w, double past, int maxLooks) {
		var pl = new WaypointPlanner(0.5, 600.5);
		var again = new PlayerChime.Search[1];
		double px = 0.5, pz = 0.5;
		int steps = 0, dir = 0;
		boolean reversed = false;
		while (steps++ < maxLooks && pz < past) {
			boolean clear = WaypointGuide.clearAhead(w, px, 64, pz, 0.5, 600.5, Tuning.WP_LOOK);
			var sr = pl.begin(w, px, 64, pz, clear);
			if (sr == null) { double dx = 0.5 - px, dz = 600.5 - pz, h = Math.hypot(dx, dz); px += dx / h * 8; pz += dz / h * 8; continue; }
			List<double[]> rt = null;
			while (sr != null) { while (!sr.run(200000)) {} rt = pl.done(sr, w, px, 64, pz, again); sr = again[0]; }
			if (rt == null || rt.size() < 2) break;
			for (int i = 1; i < rt.size() && i <= 12; i++) { // walk a stretch of it, as you would before the next look
				double nx = rt.get(i)[0] + 0.5;
				if (rt.get(i)[2] < 58 && Math.abs(nx - px) > 0.5) { int d = nx > px ? 1 : -1; if (dir != 0 && d != dir) reversed = true; dir = d; }
				px = nx; pz = rt.get(i)[2] + 0.5;
			}
		}
		return new double[]{pz, steps, reversed ? 1 : 0, dir};
	}

	static void run(java.util.function.BiConsumer<Boolean, String> check) {
		// 1. round a wall to the waypoint
		World wall = new World().fill(-15, 63, -15, 15, 63, 15, GRASS).fill(-8, 64, 0, 8, 66, 0, STONE);
		List<double[]> r1 = route(wall, 0.5, -6.5, 0.5, 64, 6.5);
		check.accept(r1 != null && Math.abs(r1.get(r1.size() - 1)[2] - 6) <= 1, "waypoint behind a wall: the route goes round it to the waypoint");

		// 2. any direction: across open ground at an odd angle the route is one straight line, not a staircase of turns
		World field = new World().fill(-5, 63, -5, 40, 63, 20, GRASS);
		List<double[]> r2 = route(field, 0.5, 0.5, 30.5, 64, 11.5);
		double worst = 0;
		for (double[] c : r2) worst = Math.max(worst, PathGuide.segDist(c[0] + 0.5, c[2] + 0.5, 0.5, 0.5, 30.5, 11.5));
		check.accept(worst <= 1.5, Dbg.fmt("open field at an odd angle: the route keeps to the straight line (worst %.1f blocks off it)", worst));

		// 3. a ridge with a tunnel through it: over the ridge in the open, not through the dark tunnel (round 13: led through a cave instead of over a mountain)
		World ridge = new World().fill(-30, 63, -8, 30, 63, 8, GRASS);
		for (int k = 0; k < 5; k++) ridge.fill(-8 + k, 64, -8, 8 - k, 64 + k, 8, STONE); // stepped up to y 68 and down again
		ridge.fill(-8, 64, -1, 8, 66, 1, AIR); // a roomy tunnel (3 wide, 3 high) straight through: only being under rock counts against it
		List<double[]> r3 = route(ridge, -20.5, 0.5, 20.5, 64, 0.5);
		double top = 0;
		for (double[] c : r3) top = Math.max(top, c[1]);
		check.accept(r3 != null && top >= 67, "a low ridge with a tunnel through it: the route goes over the top, not through the tunnel (highest floor " + (int) top + ")");

		// 4. out of reach (the land ends): the route still makes the easiest progress toward it
		World strip = new World().fill(-5, 63, -3, 90, 63, 3, GRASS);
		var s4 = new PlayerChime.Search(strip, 0.5, 64, 0.5, new PlayerChime.Tgt(null, 400.5, 64, 0.5, 65.6), false, true);
		while (!s4.run(100000)) {}
		List<double[]> r4 = s4.path();
		check.accept(r4 != null && !s4.reached() && r4.get(r4.size() - 1)[0] >= 80, "waypoint out of reach: the route leads as far toward it as the land goes (to x " + (r4 == null ? -1 : (int) r4.get(r4.size() - 1)[0]) + ")");

		// 5. volume: the quiet minimum at the start (and further off), rising steadily to full at the waypoint
		check.accept(Math.abs(WaypointGuide.volume(100, 100) - Tuning.WP_MIN_VOL * Tuning.JINGLE_NEAR_VOL) < 1e-9 && Math.abs(WaypointGuide.volume(0, 100) - Tuning.JINGLE_NEAR_VOL) < 1e-9
			&& WaypointGuide.volume(50, 100) > WaypointGuide.volume(60, 100) && WaypointGuide.volume(150, 100) == Tuning.WP_MIN_VOL * Tuning.JINGLE_NEAR_VOL, "waypoint volume: quiet minimum at the start, louder as you get closer, at the waypoint the jingle's close level (never louder than the jingle)");

		// 6. arriving
		Waypoints.Wp wp = new Waypoints.Wp("home", "minecraft:overworld", 10, 65, 10);
		check.accept(WaypointGuide.arrived(10.5, 64, 11.5, wp) && !WaypointGuide.arrived(14.5, 64, 10.5, wp) && !WaypointGuide.arrived(10.5, 70, 10.5, wp), "arriving: within 2 blocks of the waypoint, at its height");

		// 7. the plan far off: straight while the way ahead walks clear (a hill a block at a time too), else a detour; near, the whole route
		World hill = new World().fill(-5, 63, -5, 60, 63, 5, GRASS);
		for (int k = 0; k < 8; k++) hill.fill(10 + k, 64, -5, 60, 64 + k, 5, GRASS); // a slope up one block per block
		check.accept(WaypointGuide.clearAhead(field, 0.5, 64, 0.5, 300, 100, Tuning.WP_LOOK) && WaypointGuide.clearAhead(hill, 0.5, 64, 0.5, 300.5, 0.5, Tuning.WP_LOOK)
			&& !WaypointGuide.clearAhead(wall, 0.5, 64, -6.5, 0.5, 300, Tuning.WP_LOOK), "far waypoint: straight on over open ground and up a hill a block at a time, not into a wall");
		check.accept(WaypointGuide.mode(30, false) == WaypointGuide.Mode.NEAR && WaypointGuide.mode(200, true) == WaypointGuide.Mode.STRAIGHT && WaypointGuide.mode(200, false) == WaypointGuide.Mode.DETOUR,
			"near: the whole route; far: straight when clear, a detour when not");
		// a far waypoint behind the back wall of a house whose door is in the front: the detour leads out of the door first
		World house = new World().fill(-30, 63, -30, 30, 63, 30, GRASS).fill(-4, 64, -4, 4, 66, 4, STONE).fill(-3, 64, -3, 3, 66, 3, AIR).fill(0, 64, -4, 0, 65, -4, AIR);
		boolean blocked = !WaypointGuide.clearAhead(house, 0.5, 64, 0.5, 0.5, 500, Tuning.WP_LOOK);
		var pl7 = new WaypointPlanner(0.5, 500);
		var s7 = pl7.begin(house, 0.5, 64, 0.5, false);
		List<double[]> r7 = null;
		var again = new PlayerChime.Search[1];
		while (s7 != null) { while (!s7.run(100000)) {} r7 = pl7.done(s7, house, 0.5, 64, 0.5, again); s7 = again[0]; }
		boolean door = false;
		if (r7 != null) for (double[] c : r7) if ((int) c[0] == 0 && (int) c[2] == -4) door = true;
		double endZ = r7 == null ? -99 : r7.get(r7.size() - 1)[2];
		check.accept(blocked && door && endZ > 5, "far waypoint behind a house: out of the front door and round to the far side (end z " + (int) endZ + ", blocked " + blocked + ", door " + door + ")");

		// a wide lake between you and a far waypoint (round 14: it bounced you left and right along the bank): round it one way, never doubling back; the scout
		// walking round is clearly easier here (13 blocks to the near end, against 50 blocks of swimming): it walks, by the short side
		World lake = new World().fill(-150, 63, -10, 150, 63, 120, GRASS).fill(-12, 63, 10, 80, 63, 60, Blocks.WATER.defaultBlockState());
		double[] l1 = walkRound(lake, 65, 80);
		check.accept(l1[0] >= 65 && l1[2] == 0 && l1[3] == -1, Dbg.fmt("a wide lake in the way: round it one way, never doubling back, by the short side (reached z %.0f in %.0f looks, reversed %b, side %s)", l1[0], l1[1], l1[2] == 1, l1[3] == -1 ? "short" : "long"));
		// gone straight for a moment, then blocked again close by (round 14 trace: that reset the side, and it could swing round to the other one): same side kept
		var pm = new WaypointPlanner(0.5, 600.5);
		var sm = pm.begin(lake, 0.5, 64, 0.5, false);
		while (sm != null) { while (!sm.run(200000)) {} pm.done(sm, lake, 0.5, 64, 0.5, again); sm = again[0]; }
		int sideWas = pm.side;
		boolean left = pm.begin(lake, -50, 64, 30, true) == null && !pm.following;
		pm.begin(lake, -50, 64, 36, false);
		check.accept(sideWas != 0 && left && pm.following && pm.side == sideWas, "blocked again just after going straight: the same side as before (" + sideWas + " then " + pm.side + ")");
		// round 15: a lake with a cliff on the far side, and a gentle shore off to the right. Waypoint far north.
		World cove = new World().fill(-40, 60, -20, 40, 60, 80, STONE).fill(-40, 61, -20, 40, 63, -1, GRASS) // home shore (south)
			.fill(-40, 61, 0, 40, 63, 30, Blocks.WATER.defaultBlockState())                                    // the lake
			.fill(-40, 61, 31, 40, 63, 80, GRASS)                                                               // far shore
			.fill(-40, 64, 33, 6, 85, 80, STONE)                                                                // a cliff over most of it
			.fill(-3, 61, 31, 3, 63, 32, GRASS);                                                                // the strip at its foot
		// on the strip at the foot of the cliff (only water and the cliff around it): a way off it (swimming) to the gentle shore, not "no way" and straight into the rock
		var pc = new WaypointPlanner(0.5, 600.5);
		var sc = pc.begin(cove, 0.5, 64, 31.5, false);
		List<double[]> rc = null;
		while (sc != null) { while (!sc.run(400000)) {} rc = pc.done(sc, cove, 0.5, 64, 31.5, again); sc = again[0]; }
		double reachX = -99;
		if (rc != null) for (double[] c : rc) reachX = Math.max(reachX, c[0]);
		check.accept(rc != null && rc.size() >= 2 && reachX >= 7, "stuck on a strip under a cliff by a lake: a way off it to the open shore (swimming), not straight into the rock (reaches x " + (int) reachX + ")");
		// swimming in the middle of the lake: not "straight on" to the cliff, but a route that lands on the gentle shore
		check.accept(!WaypointGuide.clearAhead(cove, 0.5, 62.5, 15.5, 0.5, 600.5, Tuning.WP_LOOK), "swimming: the way ahead is never taken as clear (the planner picks where to land)");
		var ps = new WaypointPlanner(0.5, 600.5);
		var ss = ps.begin(cove, 0.5, 62.5, 15.5, false);
		List<double[]> rs = null;
		while (ss != null) { while (!ss.run(400000)) {} rs = ps.done(ss, cove, 0.5, 62.5, 15.5, again); ss = again[0]; }
		boolean landsOpen = false;
		if (rs != null) for (double[] c : rs) if (c[2] >= 31 && c[0] >= 7 && !cove.getBlockState(new net.minecraft.core.BlockPos((int) c[0], (int) c[1] + 1, (int) c[2])).getFluidState().isEmpty() == false) landsOpen = true;
		check.accept(landsOpen, "swimming across: the route lands on the open shore, not at the foot of the cliff");

		// a lake too big for the scout to see round (its search runs out): edge following still takes you round, one way
		World sea = new World().fill(-400, 63, -10, 400, 63, 120, GRASS).fill(-330, 63, 10, 330, 63, 60, Blocks.WATER.defaultBlockState());
		double[] l2 = walkRound(sea, 65, 200);
		check.accept(l2[0] >= 65 && l2[2] == 0 && l2[1] <= 20, Dbg.fmt("a lake far too big to walk round: it swims across (the easier way), never doubling back (reached z %.0f in %.0f looks, reversed %b)", l2[0], l2[1], l2[2] == 1));

		// round 18 (Nov 10, a sealed cave): no walking way at all must still lead somewhere real (the closest point), say so once, never point into the rock
		World pocket = new World().fill(-60, 50, -10, 60, 90, 300, STONE).fill(-8, 64, -5, 8, 67, 15, Blocks.AIR.defaultBlockState());
		var wrp = new WaypointRoute(0.5, 66, 250.5);
		for (int t = 0; t < 600; t++) wrp.tick(pocket, 0.5, 64, 0.5, new double[]{0.5, 64, 0.5}, Long.MAX_VALUE);
		double pEndZ = wrp.route == null ? -99 : wrp.route.get(wrp.route.size() - 1)[2];
		check.accept(wrp.route != null && wrp.closest && wrp.status(0.5, 64, 0.5) == WaypointRoute.Status.BLOCKED && pEndZ >= 12 && !"no way at all".equals(wrp.last), Dbg.fmt("sealed pocket: leads to the closest point (route end z %.0f, last '%s'), never a sound with nowhere to go", pEndZ, wrp.last));
		// the closest point names the rock between it and open ground: a second cave three blocks of rock further on
		World two = new World().fill(-60, 50, -10, 60, 90, 300, STONE).fill(-8, 64, -5, 8, 67, 15, Blocks.AIR.defaultBlockState()).fill(-8, 64, 19, 8, 67, 40, Blocks.AIR.defaultBlockState()).fill(-8, 63, 19, 8, 63, 40, STONE);
		var wr2 = new WaypointRoute(0.5, 66, 250.5);
		for (int t = 0; t < 400; t++) wr2.tick(two, 0.5, 64, 0.5, new double[]{0.5, 64, 0.5}, Long.MAX_VALUE);
		check.accept(wr2.closest && wr2.dig == 3 && wr2.lastSpots < Tuning.CLOSED_MAX, "closest point: says how much rock lies between it and the way on (dig " + wr2.dig + ", wanted 3)");
		// a pit with open ground a few blocks overhead: the closest point says how far up it is
		World pit = new World().fill(-60, 50, -10, 60, 70, 300, STONE).fill(-8, 64, -5, 8, 66, 15, Blocks.AIR.defaultBlockState());
		var wrU = new WaypointRoute(0.5, 66, 250.5);
		for (int t = 0; t < 400; t++) wrU.tick(pit, 0.5, 64, 0.5, new double[]{0.5, 64, 0.5}, Long.MAX_VALUE);
		check.accept(wrU.closest && wrU.up >= 5 && wrU.up <= 9, "closest point in a pit: open ground overhead is found (" + wrU.up + " up, wanted about 7)");
		// round 18 night 2: standing on the bottom of a deep lake: a route up to the surface and on, never "can't find a way" (and never spoken)
		World deep = new World().fill(-60, 40, -10, 60, 40, 300, STONE).fill(-60, 41, -10, 60, 52, 40, Blocks.WATER.defaultBlockState()).fill(-60, 41, 41, 60, 51, 300, STONE).fill(-60, 52, 41, 60, 52, 300, GRASS);
		var wd = new WaypointRoute(0.5, 54, 250.5);
		for (int t = 0; t < 400; t++) wd.tick(deep, 0.5, 42, 0.5, new double[]{0.5, 42, 0.5}, Long.MAX_VALUE);
		check.accept(wd.route != null && wd.route.size() >= 2 && wd.status(0.5, 42, 0.5) != WaypointRoute.Status.STUCK && !wd.last.startsWith("no way at all"), "on the bottom of a deep lake: a route up and out, no spoken complaint (last '" + wd.last + "')");
		// ground you walked counts where the world shows nothing (not loaded): a gap in the loaded land bridged by remembered cells
		World gap = new World().fill(-20, 63, -10, 20, 63, 40, GRASS).fill(-20, 63, 81, 20, 63, 400, GRASS);
		var wg0 = new WaypointRoute(0.5, 64, 300.5);
		double[] at0 = {0.5, 64, 0.5};
		for (int t = 0; t < 600; t++) { wg0.tick(gap, at0[0], at0[1], at0[2], new double[]{at0[0], at0[1], at0[2]}, Long.MAX_VALUE); at0[2] = walkOn(wg0, gap, at0[2]); }
		double noKnownZ = -1, knownZ0;
		for (int z = 41; z <= 80; z++) for (int x = -1; x <= 1; x++) Known.remember(x, 63, z);
		var wg1 = new WaypointRoute(0.5, 64, 300.5);
		double[] at1 = {0.5, 64, 0.5};
		for (int t = 0; t < 600; t++) { wg1.tick(gap, at1[0], at1[1], at1[2], new double[]{at1[0], at1[1], at1[2]}, Long.MAX_VALUE); at1[2] = walkOn(wg1, gap, at1[2]); }
		noKnownZ = Math.max(noKnownZ, at0[2]); knownZ0 = at1[2];
		double knownZ = knownZ0;
		Known.cells.clear();
		check.accept(noKnownZ < 45 && knownZ >= 81, Dbg.fmt("a gap in the loaded land: without the walked ground the route stops at z %.0f, with it crosses to z %.0f", noKnownZ, knownZ));
		// the guides' direction rule: full in front, behind exactly as before, the sides well down
		check.accept(Math.abs(Loud.guideFacing(0) - 1) < 1e-9 && Math.abs(Loud.guideFacing(Math.PI) - Loud.facing(Math.PI)) < 1e-9 && Loud.guideFacing(Math.PI / 2) < 0.3
			&& Loud.guideFacing(Math.PI / 2) < Loud.facing(Math.PI / 2) * 0.5, Dbg.fmt("guides: full in front, behind unchanged, the side down to %.2f (was %.2f)", Loud.guideFacing(Math.PI / 2), Loud.facing(Math.PI / 2)));

		// 8. save and reload
		var list = new java.util.ArrayList<Waypoints.Wp>(List.of(wp, new Waypoints.Wp("leo's house", "minecraft:the_nether", -120, 70, 33)));
		check.accept(Waypoints.read(Waypoints.write(list)).equals(list), "waypoints survive a save and reload (names, dimensions, places)");
	}

	/** Walks the player along z toward the end of the route (0.4 a tick), as far as the route goes. */
	static double walkOn(WaypointRoute wr, World w, double z) {
		if (wr.route == null) return z;
		double end = wr.route.get(wr.route.size() - 1)[2] + 0.5;
		return Math.min(end, z + 0.4);
	}
}
