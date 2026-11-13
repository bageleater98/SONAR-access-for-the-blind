package com.sonar;

import java.nio.file.Path;

/** Round 16 test notes (Nov 8): the path bell on the pale-oak bridge and the steep andesite stairs, the rolling waypoint route, the jingle to a friend in water. */
final class R16Tests {
	private R16Tests() {}

	static final SonarConfig CFG = new SonarConfig();
	static final boolean VERBOSE = System.getenv("SONAR_TEST_ONLY") != null;

	/** The bell at (x, z) on the floor there, facing yaw: its target relative to you {bearing from facing (deg), distance, end 1/0, found 1/0}. */
	static double[] bell(World w, double x, double z, int yHint, float yaw) {
		var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		int f = BlockRules.floorUnder(w, x, yHint + 0.5, z, pos);
		double y = f + 1;
		PathGuide.findIn(w, new PathGuide.Me(x, y, z, y + 1.62, yaw, 0, 0), CFG);
		double[] t = PathGuide.target();
		double yr = Math.toRadians(yaw), fx = -Math.sin(yr), fz = Math.cos(yr);
		double dx = t[0] - x, dz = t[2] - z, d = Math.hypot(dx, dz);
		double ang = Math.toDegrees(Math.atan2(fx * dz - fz * dx, fx * dx + fz * dz));
		return new double[]{ang, d, t[3], t[4], f};
	}

	/**
	 * Walks to a far waypoint by the rolling route, as in the game: each tick the search gets `spots` spots and you walk `speed` blocks along the route.
	 * Returns {arrived 1/0, ticks, ticks with no route ahead (gaps), route cells under rock walked, blocks walked, ticks spent at a known dead end}.
	 */
	static double[] walk(World w, double sx, double sy, double sz, double gx, double gy, double gz, int spots, double speed, int maxTicks) {
		var wr = new WaypointRoute(gx, gy, gz);
		double px = sx, py = sy, pz = sz, walked = 0, carry = 0;
		int gaps = 0, under = 0, t;
		for (t = 0; t < maxTicks; t++) {
			wr.tick(w, px, py, pz, null, -spots);
			if (Math.hypot(gx - px, gz - pz) <= Tuning.WP_ARRIVE && Math.abs(gy - py) <= 3) return new double[]{1, t, gaps, under, walked};
			if (wr.route == null) { if (t > 40) gaps++; continue; } // (the very start: the first route takes a moment)
			int i = wr.at(px, py, pz);
			if (i < 0 || wr.off(px, py, pz)) { gaps++; if (VERBOSE) System.out.println(Dbg.fmt("    off at %.0f,%.0f,%.0f tick %d last %s", px, py, pz, t, wr.last)); continue; }
			if (!wr.reached && wr.left(i) < 1.0) { gaps++; if (VERBOSE) System.out.println(Dbg.fmt("    gap at %.0f,%.0f,%.0f tick %d searching %s last %s", px, py, pz, t, wr.searching(), wr.last)); continue; } // at the end of the route with nothing joined on: you would have to stop (or be pointed blindly)
			carry += speed;
			while (carry >= 1 && i < wr.route.size() - 1) {
				double[] a = wr.route.get(i), b = wr.route.get(i + 1);
				carry -= Math.max(0.5, b[3] - a[3]); walked += b[3] - a[3]; i++;
				px = b[0] + 0.5; py = b[1] + 1; pz = b[2] + 0.5;
				if (BlockRules.caveRoof(w, new net.minecraft.world.phys.Vec3(px, py + 0.5, pz), 24)) { under++; if (VERBOSE) System.out.println(Dbg.fmt("    under rock at %.0f,%.0f,%.0f tick %d last %s dead %d", px, py, pz, t, wr.last, wr.dead.size())); }
			}
			wr.trim(px, py, pz);
		}
		return new double[]{0, t, gaps, under, walked};
	}

	static void run(java.util.function.BiConsumer<Boolean, String> check) {
		try {
			var STONE = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
			var GRASS = net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
			var AIR = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
			// a cliff across the way (x -60..40) with a dead-end tunnel straight toward the waypoint; the way round is past its east end. Waypoint 180 blocks north.
			World cliff = new World().fill(-100, 63, -20, 100, 63, 220, GRASS).fill(-60, 64, 40, 40, 75, 70, STONE).fill(-1, 64, 40, 1, 66, 52, AIR);
			double[] c1 = walk(cliff, 0.5, 64, 0.5, 0.5, 64, 200.5, 150, 0.22, 6000);
			System.out.println(Dbg.fmt("  cliff: arrived %s in %.0f ticks, gaps %.0f, under rock %.0f, walked %.0f", c1[0] == 1, c1[1], c1[2], c1[3], c1[4]));
			check.accept(c1[0] == 1 && c1[2] == 0 && c1[3] == 0, Dbg.fmt("far waypoint past a cliff with a dead-end tunnel toward it: arrives, never a moment without a route ahead, never into the tunnel (gaps %.0f, under rock %.0f, %.0f blocks walked)", c1[2], c1[3], c1[4]));
			// the jingle to a friend standing in a pond (round 16: counted unreachable, so muffled)
			World pond = new World().fill(-10, 63, -10, 30, 63, 10, GRASS).fill(10, 62, -3, 16, 63, 3, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
			var js = new PlayerChime.Search(pond, 0.5, 64, 0.5, new PlayerChime.Tgt(null, 13.5, 63, 0.5, 64.6), false);
			if (js.gf == BlockRules.NONE) js.swimTo();
			while (!js.run(100000)) {}
			check.accept(js.path() != null, "jingle: a friend standing in a pond is reachable (not muffled as unreachable)");
			// open ground the whole way: straight stretches joined on, no gaps
			World open = new World().fill(-20, 63, -20, 20, 63, 260, GRASS);
			double[] o1 = walk(open, 0.5, 64, 0.5, 0.5, 64, 240.5, 150, 0.22, 6000);
			check.accept(o1[0] == 1 && o1[2] == 0 && o1[4] < 260, Dbg.fmt("far waypoint across open ground: straight stretches joined on ahead of you, no gaps (gaps %.0f, %.0f blocks walked)", o1[2], o1[4]));

			// round 18: the bell keeps its way while you turn on the spot, until you face more than 80 degrees from it (a T: arms east and north, you at the corner)
			{
				var PT = net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState();
				World tee = new World().fill(-40, 63, -40, 40, 63, 20, GRASS).fill(0, 63, -1, 30, 63, 1, PT).fill(-1, 63, -30, 1, 63, 0, PT);
				java.util.function.Function<Float, String> way = yaw -> { // which arm the bell leads along, remembering the last call
					PathGuide.findAgain(tee, new PathGuide.Me(0.5, 64, 0.5, 65.62, yaw, 0, 0), CFG);
					double[] g = PathGuide.target();
					return g[4] == 0 ? "none" : g[0] - 0.5 > 3 && Math.abs(g[2] - 0.5) < 3 ? "east" : g[2] - 0.5 < -3 && Math.abs(g[0] - 0.5) < 4 ? "north" : "other";
				};
				PathGuide.forgetWay();
				String s0 = way.apply(-90f), s1 = way.apply(-120f), s2 = way.apply(-150f), s3 = way.apply(-165f), s4 = way.apply(-175f);
				PathGuide.forgetWay();
				String fresh = way.apply(-150f); // the same facing with no memory: the old pick (the way you face most)
				System.out.println("  turning east to north: " + s0 + " " + s1 + " " + s2 + " " + s3 + " " + s4 + " | fresh at 60 deg: " + fresh);
				check.accept(s0.equals("east") && s1.equals("east") && s2.equals("east"), "path bell: turning from the east arm toward the north arm keeps the east arm up to 60 degrees round (was: flipped at 45)");
				check.accept(s4.equals("north") && fresh.equals("north"), "path bell: once you face the north arm (and with no memory of the east one) it leads north");
			}
			// plazas: a path across a square goes on across it; a path that only runs into a square (a patio) ends at its edge
			var PATH = net.minecraft.world.level.block.Blocks.DIRT_PATH.defaultBlockState(); var BRICK = net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState();
			World across = new World().fill(-40, 63, -20, 40, 63, 20, GRASS).fill(-30, 63, -1, 30, 63, 1, PATH).fill(-3, 63, -3, 3, 63, 3, BRICK);
			double[] pa = bell(across, -9.5, 0.5, 64, -90f);
			World patio = new World().fill(-40, 63, -20, 40, 63, 20, GRASS).fill(-30, 63, -1, -4, 63, 1, PATH).fill(-3, 63, -3, 3, 63, 3, BRICK);
			double[] pp = bell(patio, -9.5, 0.5, 64, -90f);
			if (VERBOSE) System.out.println(Dbg.fmt("  plaza across: %s end %s %+.0f deg %.1f; patio: %s end %s %+.0f deg %.1f", pa[3] == 1, pa[2] == 1, pa[0], pa[1], pp[3] == 1, pp[2] == 1, pp[0], pp[1]));
			double[] pa2 = bell(across, -5.5, 0.5, 64, -90f);
			check.accept(pa[3] == 1 && pa[2] == 0 && Math.abs(pa[0]) < 30 && pa2[3] == 1 && pa2[2] == 0, "path across a square: the bell leads on across it, no end of path at the square");
			check.accept(pp[3] == 1 && pp[2] == 1, "path that only runs into a square (a patio, no path out): the path ends at the square's edge, the bell never leads into it");
			// round 17, 09:08: flowering azalea bushes beside stone-brick stairs counted as path
			World az = new World();
			double[] ap = World.load(Path.of("src/selftest/spots/r17-azalea.txt"), az);
			PathGuide.findIn(az, new PathGuide.Me(ap[0], ap[1], ap[2], ap[1] + 1.62, (float) ap[3], 0, 0), CFG);
			int bush = 0;
			for (var e : PathGuide.cells().entrySet()) { var bs = az.getBlockState(new net.minecraft.core.BlockPos((int) (e.getKey() >> 32), e.getValue(), (int) (long) e.getKey())); if (bs.is(net.minecraft.world.level.block.Blocks.FLOWERING_AZALEA) || bs.is(net.minecraft.world.level.block.Blocks.AZALEA)) bush++; }
			check.accept(bush == 0 && FloorEdge.natural(net.minecraft.world.level.block.Blocks.FLOWERING_AZALEA.defaultBlockState()), "azalea bushes are never path (round 17, 09:08 spot: " + bush + " bush cells in the path, " + PathGuide.cells().size() + " cells)");
			// round 17, 09:17: a round square where four dirt paths meet, you in the middle: the bell rings toward the path you face, and it is a crossing
			World sq = new World();
			double[] sp = World.load(Path.of("src/selftest/spots/r17-plaza.txt"), sq);
			int ex = PathGuide.plazaExits(sq, (int) Math.floor(sp[0]), (int) Math.floor(sp[2]), (int) sp[1] - 1, new net.minecraft.core.BlockPos.MutableBlockPos()).size();
			int sqOk = 0;
			for (float yaw : new float[]{0f, 90f, 180f, 270f}) { double[] b = bell(sq, sp[0], sp[2], (int) sp[1], yaw); if (b[3] == 1 && Math.abs(b[0]) < 30) sqOk++; if (VERBOSE) System.out.println(Dbg.fmt("  square yaw %.0f: found %s %+.0f deg %.1f", yaw, b[3] == 1, b[0], b[1])); }
			check.accept(ex == 4 && sqOk == 4, "standing on a square where four paths meet (round 17, 09:17 spot): " + ex + " paths leave it, the bell rings toward the one you face (" + sqOk + " of 4 directions)");
			World fp = new World();
			double[] fpp = World.load(Path.of("src/selftest/spots/r17-falsepath.txt"), fp);
			boolean fpOn = PathGuide.findIn(fp, new PathGuide.Me(fpp[0], fpp[1], fpp[2], fpp[1] + 1.62, (float) fpp[3], 0, 0), CFG);
			if (VERBOSE) { var cnt = new java.util.TreeMap<String, Integer>(); int minx = 999, maxx = -999, minz = 999, maxz = -999; for (var e : PathGuide.cells().entrySet()) { int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey(); minx = Math.min(minx, x); maxx = Math.max(maxx, x); minz = Math.min(minz, z); maxz = Math.max(maxz, z); cnt.merge(fp.getBlockState(new net.minecraft.core.BlockPos(x, e.getValue(), z)).getBlock().getDescriptionId(), 1, Integer::sum); } double[] t = PathGuide.target(); System.out.println("  deck: on " + fpOn + " cells " + cnt + " x " + minx + ".." + maxx + " z " + minz + ".." + maxz + " target " + (int) t[0] + "," + (int) t[2]); }
			check.accept(!fpOn, "a stone-brick strip along the side of a big wooden deck (round 17, 09:16 spot) is the deck's edge, not a path: no bell");
			World jn = new World();
			World.load(Path.of("src/selftest/spots/r17-junction.txt"), jn);
			int tEnd = 0;
			for (float yaw : new float[]{90f, 135f, 180f})
				for (int k = 0; k <= 12; k += 2) {
					double x = -10.5 - k, z = 0.5;
					double[] b = bell(jn, x, z, 104, yaw);
					if (yaw == 180f && k <= 8 && b[2] == 1) tEnd++;
					if (VERBOSE) System.out.println(Dbg.fmt("  T yaw %.0f at %.1f: found %s end %s target %+.0f deg %.1f away", yaw, x, b[3] == 1, b[2] == 1, b[0], b[1]));
				}
			check.accept(tEnd == 0, "facing across a wide path (round 17 T-junction spot): the path's side edge is never called the end of the path (" + tEnd + " times)");
			World br = new World();
			World.load(Path.of("src/selftest/spots/r16-bell-16.57.48.txt"), br);
			// walking NE up the bridge (yaw -135), from its SW foot
			int bad = 0, onBridge = 0;
			String worst = "";
			for (int k = 0; k <= 9; k++) {
				double x = -132.5 + k, z = 121.5 - k;
				double[] b = bell(br, x, z, 120, -135f);
				System.out.println(Dbg.fmt("  bridge %d at %.1f,%.1f floor %d: found %s end %s target %+.0f deg %.1f away", k, x, z, (int) b[4], b[3] == 1, b[2] == 1, b[0], b[1]));
				if (b[4] == Integer.MIN_VALUE) continue; // off the side of the bridge
				onBridge++;
				if (b[3] == 1 && (Math.abs(b[0]) > 60 || b[1] < 2.4)) { bad++; worst = Dbg.fmt("%+.0f deg %.1f away at step %d", b[0], b[1], k); }
			}
			check.accept(onBridge >= 6 && bad == 0, "pale-oak bridge (round 16, 16:57): walking up it the bell stays ahead, 2.5+ blocks out, to its very end; never on you or behind you" + (bad > 0 ? " (" + bad + " bad, e.g. " + worst + ")" : ""));

			World st = new World();
			World.load(Path.of("src/selftest/spots/r16-bell-17.02.27.txt"), st);
			int stBad = 0, ends = 0, yh = 118;
			for (int k = 0; k <= 16; k++) {
				double x = -133.5, z = -59.5 + k;
				double[] b = bell(st, x, z, yh, 0f);
				if (b[4] != Integer.MIN_VALUE) yh = (int) b[4] + 2;
				System.out.println(Dbg.fmt("  stairs %d at %.1f,%.1f floor %d: found %s end %s target %+.0f deg %.1f away", k, x, z, (int) b[4], b[3] == 1, b[2] == 1, b[0], b[1]));
				if (b[4] == Integer.MIN_VALUE || b[3] == 0) continue;
				if (Math.abs(b[0]) > 90) stBad++;
				if (b[2] == 1 && k < 12) ends++;
			}
			check.accept(stBad == 0 && ends == 0, "long stone staircase down (round 16, 17:02): not called the end of the path part way down, never behind you (" + stBad + " behind, " + ends + " early ends)");

			// round 18 (Nov 10 20:05): climbing the same staircase UP (facing north, 1 block up per step): the uphill way was cut by the 6-block rise window before the 7-10 steps
			// the target is taken from, so only the way DOWN behind you was left and the bell went silent ("target is behind you")
			World up = new World();
			World.load(Path.of("src/selftest/spots/r18-bell-20.05.29.txt"), up);
			int upBad = 0, upFound = 0;
			for (int k = 0; k <= 5; k++) {
				double x = -133.5, z = -50.5 - k;
				double[] b = bell(up, x, z, 107 + k, -179f);
				System.out.println(Dbg.fmt("  climbing %d at %.1f,%.1f floor %d: found %s end %s target %+.0f deg %.1f away", k, x, z, (int) b[4], b[3] == 1, b[2] == 1, b[0], b[1]));
				if (b[4] == Integer.MIN_VALUE) continue;
				if (b[3] == 1) upFound++;
				if (b[3] == 0 || Math.abs(b[0]) > 60 || b[1] < 2.4) upBad++;
			}
			check.accept(upFound >= 4 && upBad == 0, "climbing a steep staircase up (round 18, 20:05): the bell leads up the stairs ahead, never behind you or silent (" + upBad + " bad of " + upFound + " found)");
		} catch (Exception e) { check.accept(false, "r16 tests crashed: " + e); e.printStackTrace(); }
	}
}
