package com.sonar;

/** Round 18 (Nov 11): a cave whose way out lies further BEHIND you than the search area reached (the guide called it closed and sat there re-planning). */
final class R18Tests {
	private R18Tests() {}

	/** Walks the player along z toward the end of the route (0.4 a tick), as far as the route goes. */
	static double walkOn(WaypointRoute wr, World w, double z) {
		if (wr.route == null) return z;
		return Math.min(wr.route.get(wr.route.size() - 1)[2] + 0.5, z + 0.4);
	}

	static void run(java.util.function.BiConsumer<Boolean, String> check) {
		var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
		var grass = net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
		var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
		// a plain; a mountain across it with a 300-block tunnel through its length; you stand at the dead end of the tunnel and the waypoint is out the OTHER way
		// (north, beyond the mountain), so the only way is 300 blocks back out of the tunnel and round the mountain
		World w = new World().fill(-250, 62, -400, 250, 62, 450, stone).fill(-250, 63, -400, 250, 63, 450, grass)
			.fill(-60, 64, -100, 60, 72, 300, stone).fill(-1, 64, 3, 1, 66, 300, air);
		var wr = new WaypointRoute(0.5, 64, -250.5);
		double[] at = {0.5, 64, 5.5};
		double farZ = 0;
		for (int t = 0; t < 400; t++) {
			wr.tick(w, at[0], at[1], at[2], new double[]{at[0], at[1], at[2]}, Long.MAX_VALUE);
			if (wr.route != null) for (double[] c : wr.route) farZ = Math.max(farZ, c[2]);
		}
		check.accept(wr.route != null && !wr.closest && !wr.last.startsWith("closed in") && !wr.last.startsWith("closest") && farZ >= 290,
			"a tunnel 300 blocks long, the way out behind you: it leads back out (route reaches z " + (int) farZ + ", last '" + wr.last + "')");
		// a chamber under the plain whose only way out is a ladder shaft at its far end: the tip of the chamber is nearer the waypoint, but the way is UP the ladder and over the plain
		var ladder = net.minecraft.world.level.block.Blocks.LADDER.defaultBlockState();
		World pit = new World().fill(-250, 60, -400, 250, 60, 450, stone).fill(-250, 61, -400, 250, 69, 450, stone).fill(-250, 70, -400, 250, 70, 450, grass)
			.fill(-4, 61, 100, 4, 63, 140, air).fill(0, 61, 140, 0, 70, 140, ladder);
		var wp = new WaypointRoute(0.5, 71, -250.5);
		double[] ap = {0.5, 61, 125.5};
		double upY = 0, minZ = 999;
		for (int t = 0; t < 400; t++) {
			wp.tick(pit, ap[0], ap[1], ap[2], new double[]{ap[0], ap[1], ap[2]}, Long.MAX_VALUE);
			if (wp.route != null) for (double[] c : wp.route) { upY = Math.max(upY, c[1]); minZ = Math.min(minZ, c[2]); }
		}
		check.accept(wp.route != null && !wp.closest && upY >= 69, "a chamber whose way out is a ladder: the route goes up it to the open ground, not to the chamber's far tip (highest floor " + (int) upY + ", closest " + wp.closest + ", last '" + wp.last + "')");
		// a BIG cavern (the room closest to the waypoint is a dead end) whose only way out is a long tunnel leading AWAY from the waypoint (round 18 night 3, no ladders): it
		// must lead out, not sit at the cavern's far wall nearest the waypoint
		World big = new World().fill(-250, 59, -400, 250, 59, 650, stone).fill(-250, 60, -400, 250, 60, 650, grass)
			.fill(-150, 61, -100, 150, 70, 450, stone).fill(-140, 61, 0, 140, 63, 250, air).fill(-1, 61, 251, 1, 63, 450, air);
		var wb = new WaypointRoute(0.5, 61, -250.5);
		double[] ab = {0.5, 61, 125.5};
		double outZ = 0;
		for (int t = 0; t < 2000; t++) {
			wb.tick(big, ab[0], ab[1], ab[2], new double[]{ab[0], ab[1], ab[2]}, Long.MAX_VALUE);
			if (wb.route != null) { for (double[] c : wb.route) outZ = Math.max(outZ, c[2]); ab[2] = walkOn(wb, big, ab[2]); }
		}
		check.accept(wb.route != null && !wb.closest && outZ >= 445, "a big cavern, the only way out a long tunnel leading away from the waypoint: it leads out (reaches z " + (int) outZ + ", last '" + wb.last + "', closest " + wb.closest + ")");
		// a crater: a walled basin open to the sky, walls too high to climb, the waypoint outside. Its open sky is no way out: it must try the other open spots, give up after a few
		// (not loop for ever) and settle on the closest point
		World crater = new World().fill(-300, 60, -400, 300, 60, 400, stone).fill(-300, 61, -400, 300, 69, 400, stone).fill(-300, 70, -400, 300, 70, 400, grass)
			.fill(-70, 71, -70, 70, 95, 70, stone).fill(-60, 71, -60, 60, 95, 60, air);
		var wc = new WaypointRoute(0.5, 71, -250.5);
		int settled = -1;
		double[] ac = {0.5, 71, 0.5};
		for (int t = 0; t < 4000 && settled < 0; t++) {
			wc.tick(crater, ac[0], ac[1], ac[2], new double[]{ac[0], ac[1], ac[2]}, Long.MAX_VALUE);
			var st = wc.status(ac[0], ac[1], ac[2]);
			if (st == WaypointRoute.Status.BLOCKED || st == WaypointRoute.Status.STUCK) settled = t; // (at the wall, the closest point is where you stand: nothing to lead to, so it is STUCK: the honest answer)
			if (wc.route != null) { // walk toward the end of the route (inside the basin, in a straight line), 0.4 a tick
				double[] e = wc.route.get(wc.route.size() - 1);
				double dx = e[0] + 0.5 - ac[0], dz = e[2] + 0.5 - ac[2], h = Math.hypot(dx, dz);
				if (h > 0.5) { ac[0] += dx / h * 0.4; ac[2] += dz / h * 0.4; }
			}
		}
		check.accept(settled >= 0, "a walled crater open to the sky: after trying its other open spots it settles on blocked or stuck, never loops (settled at tick " + settled + ", last '" + wc.last + "', at " + (int) ac[0] + "," + (int) ac[2] + ")");
		speech(check);
	}

	/** What the guide says about itself: a state has to last, once per spell, never closer than six seconds, never while swimming. */
	static void speech(java.util.function.BiConsumer<Boolean, String> check) {
		var said = new java.util.ArrayList<String>();
		java.util.function.Consumer<String> out = said::add;
		var F = WaypointRoute.Status.FINDING; var G = WaypointRoute.Status.GUIDING; var B = WaypointRoute.Status.BLOCKED; var O = WaypointRoute.Status.OFF_ROUTE; var S = WaypointRoute.Status.STUCK;
		// logging in: nothing for the first moment, then "Finding a route." once, then a "hold on" after a long wait, then nothing more
		var g = new GuideSpeech();
		int t = 0;
		g.reset(t);
		for (; t < 50; t++) g.update(out, F, false, t);
		boolean quietAtFirst = said.isEmpty(); // (the first line waits for the "Guiding to..." announcement)
		for (; t < 140; t++) g.update(out, F, false, t);
		boolean finding = said.equals(java.util.List.of(GuideSpeech.FINDING));
		for (; t < 600; t++) g.update(out, F, false, t);
		check.accept(quietAtFirst && finding && said.equals(java.util.List.of(GuideSpeech.FINDING, GuideSpeech.HOLD)), "guide speech: 'Finding a route' once after the first moment, 'Hold on' once after a long wait, nothing more (" + said + ")");
		// "can't find a way" a moment before a route turns up: never said (the state did not last)
		said.clear(); g.reset(t);
		for (int k = 0; k < 200; k++, t++) g.update(out, k < 30 ? F : B, false, t); // blocked for 170 ticks would be said; so cut it short:
		said.clear(); g.reset(t);
		for (int k = 0; k < 400; k++, t++) g.update(out, k % 50 < 20 ? B : G, false, t); // flipping between blocked and guided every moment
		check.accept(said.isEmpty(), "guide speech: a state that flips back within a moment is never mentioned (" + said + ")");
		// blocked and staying blocked: said once; never again until guided for two seconds; swimming silences it
		said.clear(); g.reset(t); t += 200;
		for (int k = 0; k < 400; k++, t++) g.update(out, B, k < 100, t); // swimming for the first 100 ticks
		boolean oneBlocked = said.equals(java.util.List.of(GuideSpeech.BLOCKED));
		for (int k = 0; k < 60; k++, t++) g.update(out, G, false, t);
		for (int k = 0; k < 400; k++, t++) g.update(out, B, false, t);
		check.accept(oneBlocked && said.equals(java.util.List.of(GuideSpeech.BLOCKED, GuideSpeech.BLOCKED)), "guide speech: blocked is said once while swimming is over and it lasts, again only after being guided again (" + said.size() + " lines)");
		// lines never closer than six seconds
		said.clear(); g.reset(t); t += 200;
		int lastAt = -9999; boolean spaced = true;
		for (int k = 0; k < 2000; k++, t++) {
			int n = said.size();
			g.update(out, k % 300 < 100 ? O : k % 300 < 200 ? S : G, false, t);
			if (said.size() > n) { if (t - lastAt < GuideSpeech.GAP) spaced = false; lastAt = t; }
		}
		check.accept(spaced && !said.isEmpty(), "guide speech: never two lines closer than six seconds (" + said.size() + " lines)");
	}
}
