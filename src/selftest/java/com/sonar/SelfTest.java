package com.sonar;

/** Dependency-free checks of the pure logic. Run with: ./gradlew selfTest */
public class SelfTest {
	private static int failed;

	private static void check(boolean ok, String what) {
		System.out.println((ok ? "ok   " : "FAIL ") + what);
		if (!ok) failed++;
	}

	public static void main(String[] args) {
		World.boot();
		var leaf = net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState();
		check(leaf.is(net.minecraft.tags.BlockTags.LEAVES), "fake world: vanilla block tags are loaded (oak leaves are leaves)");
		if (System.getenv("SONAR_R18") != null) { try { R18Explore.run(); } catch (Exception e) { e.printStackTrace(); } System.exit(0); }
		if (System.getenv("SONAR_TEST_ONLY") != null) { R16Tests.run((ok, what) -> check(ok, what)); System.out.println(failed == 0 ? "ALL OK" : failed + " FAILED"); System.exit(failed == 0 ? 0 : 1); } // quick: just the newest round's checks
		WorldTests.run((ok, what) -> check(ok, what));
		PorchTests.run((ok, what) -> check(ok, what));
		WaypointTests.run((ok, what) -> check(ok, what));
		SplitTests.run((ok, what) -> check(ok, what));
		GroupTests.run((ok, what) -> check(ok, what));
		CornerTests.run((ok, what) -> check(ok, what));
		TutorialTests.run((ok, what) -> check(ok, what));
		RoomTests.run((ok, what) -> check(ok, what));
		DirectorTests.run((ok, what) -> check(ok, what));
		R16Tests.run((ok, what) -> check(ok, what));
		R18Tests.run((ok, what) -> check(ok, what));

		// the path bell's slider: the top of it must still be louder (the game caps a sound at 1.0)
		check(PathGuide.bellVolume(1f, 2f) < 1f && PathGuide.bellVolume(1f, 2f) > PathGuide.bellVolume(1f, 1.9f), "path bell: 200% on the slider is louder than 190% (not cut off at the game's 1.0 cap)");

		// the shared loudness and direction rules
		check(Loud.shape(0) == 1 && Loud.shape(1) == 0 && Loud.shape(0.5) < Loud.shape(0.25), "every sound: full close up, fading, silent at the edge of its range");
		check(Loud.at(40, 2, 128) > Loud.at(80, 2, 128) && Math.abs(Loud.at(16, 2, 30) - Loud.at(64.4, 2, 128)) < 0.02, "the same fraction of a sound's range sounds the same, whatever the range");
		check(Math.abs(Loud.facing(0) - 1) < 1e-9 && Math.abs(Loud.facing(Math.PI) - 0.4) < 1e-9 && Math.abs(Loud.facing(Math.PI / 2) - 0.7) < 1e-9, "direction: full in front, 70% to the side, 40% behind");
		// the sound timeline
		Timeline.reset(); Timeline.tick(100); Timeline.book(2);
		check(!Timeline.free(), "a guide sound waits while an echo click is about to play");
		Timeline.tick(104);
		check(Timeline.free(), "and plays once it has passed");
		Timeline.reset();

		// the echo merge rule
		java.util.function.Function<double[], Echo.Way> w = Echo.Way::of;
		var doors2 = java.util.List.of(w.apply(new double[]{0.00, 10, 0.5, 3, 1, 0}), w.apply(new double[]{0.05, 10.5, 0.6, 3, 1, 0})); // a double door
		check(Echo.merge(doors2).size() == 1 && Echo.merge(doors2).get(0).vol() == 0.6, "a double door is one echo, as loud as the louder half");
		var same = java.util.List.of(w.apply(new double[]{1.0, 12, 0.4, 3, 1, 0}), w.apply(new double[]{1.1, 13, 0.5, 1, 1, 0})); // a door, and the doorway finder's echo for it
		check(Echo.merge(same).size() == 1 && Echo.merge(same).get(0).prio() == 1, "a door that the doorway finder also found is one echo (the more important kind kept)");
		var apart = java.util.List.of(w.apply(new double[]{0.0, 10, 0.5, 1, 1, 0}), w.apply(new double[]{0.6, 10, 0.5, 1, 1, 0}), w.apply(new double[]{0.0, 30, 0.3, 1, 1, 0}));
		check(Echo.merge(apart).size() == 3, "different ways stay separate: 35 degrees apart, or the same way but much further off");
		Echo.Way drop = w.apply(new double[]{0, 9, 0.5, 0, 1, -1.4, 10.77});
		check(drop.measured() && Math.abs(drop.size() - 0.77) < 1e-9 && Math.abs(drop.sizeCode() - 10.77) < 1e-9, "a cave drop keeps its measured size through the named echo type");

		// the path bell's lead on the centre line
		var straightLine = new java.util.ArrayList<double[]>();
		for (int i = 0; i <= 20; i++) straightLine.add(new double[]{0.5, 64, i + 0.5});
		double[] ls = PathGuide.leadOnLine(straightLine, 0.5, 0.5, 4.3);
		check(ls != null && ls[2] > 7.5 && ls[2] < 9.6, "bell leads about 8 ahead on a straight path (" + (ls == null ? "none" : Math.round(ls[2] - 0.5)) + ")");
		var turnNow = new java.util.ArrayList<double[]>(); // the path turns hard left right in front of you (round 9, 01:11: the bell sat 2 blocks off, hard left)
		for (int i = 0; i <= 2; i++) turnNow.add(new double[]{0.5, 64, i + 0.5});
		for (int i = 1; i <= 12; i++) turnNow.add(new double[]{0.5 - i, 64, 2.5});
		double[] lt = PathGuide.leadOnLine(turnNow, 0.5, 0.5, 4.3);
		check(lt != null && lt[0] <= -1.0 && Math.abs(lt[2] - 2.5) < 0.01, "at a turn right in front of you the bell leads round it, at least 3.5 blocks along the path (" + (lt == null ? "none" : lt[0] + "," + lt[2]) + ")");
		double[] lr = PathGuide.leadOnLine(straightLine, 0.5, 0.5, 5.6);
		check(lr != null && ls != null && lr[2] > ls[2] + 2, "running, the bell leads further ahead");
		var arcLine = new java.util.ArrayList<double[]>(); // a quarter circle of radius 5 round (0,0), starting at (5,0)
		for (int i = 0; i <= 16; i++) { double a = i * Math.PI / 2 / 16; arcLine.add(new double[]{5 * Math.cos(a), 64, 5 * Math.sin(a)}); }
		double[] lc = PathGuide.leadOnLine(arcLine, 5, 0, 4.3);
		boolean onMiddle = lc != null;
		if (lc != null) for (double[] c : arcLine) { if (c == lc) break; if (PathGuide.segDist(c[0], c[2], 5, 0, lc[0], lc[2]) > 1.0) onMiddle = false; }
		check(onMiddle && Math.hypot(lc[0] - 5, lc[2]) < 7, "on a gentle curve the bell comes in closer and the straight line to it stays over the path");

		// player jingle distance -> loudness: smooth everywhere (no jump, including where the straight and walking readings cross), only ever quieter further away,
		// loudest close up and silent exactly at the edge of hearing
		boolean smooth = true, falls = true;
		for (double walkExtra : new double[]{0, 20, 60, 120}) {
			double prevV = -1;
			for (double d = 0; d <= 128; d += 0.25) {
				double v = PlayerChime.volumeAt(PlayerChime.farness(d, d + walkExtra, 128));
				if (prevV >= 0 && Math.abs(v - prevV) > 0.02) smooth = false;
				if (prevV >= 0 && v > prevV + 1e-9) falls = false;
				prevV = v;
			}
		}
		check(smooth, "jingle volume never jumps as distance changes (straight and walking readings crossing included)");
		check(falls, "jingle volume only gets quieter further away");
		check(PlayerChime.volumeAt(PlayerChime.farness(5, 5, 128)) > 0.5 && PlayerChime.volumeAt(PlayerChime.farness(128, 128, 128)) == 0, "jingle loudest close up, silent at the edge of hearing");
		check(PlayerChime.volumeAt(PlayerChime.farness(110, 110, 128)) < 0.05, "jingle near silent 110 blocks away");
		// direction spread: identity at 1, monotonic, keeps front in front and behind behind, ends at +-pi
		boolean identity = true;
		for (double a = -Math.PI; a <= Math.PI; a += 0.05) if (Math.abs(Cues.spread(a, 1.0) - a) > 1e-9) identity = false;
		check(identity, "spread(1.0) is the identity");
		double prev = -10;
		boolean mono = true;
		for (double a = -Math.PI; a <= Math.PI; a += 0.01) { double v = Cues.spread(a, 1.6); if (v < prev - 1e-9) mono = false; prev = v; }
		check(mono, "spread(1.6) is monotonic");
		check(Math.abs(Cues.spread(Math.PI, 1.6) - Math.PI) < 1e-9 && Math.abs(Cues.spread(-Math.PI, 1.6) + Math.PI) < 1e-9, "spread keeps straight behind straight behind");
		check(Cues.spread(0.3, 1.5) > 0.3 && Cues.spread(0.3, 1.5) < Math.PI / 2, "spread widens small front angles");
		check(Math.abs(Cues.spread(0, 2.0)) < 1e-9, "spread keeps dead ahead dead ahead");
		// round 15: at 200% every sound to the side or behind was put straight behind you (heard in the middle of your head)
		check(Math.abs(Cues.spread(Math.PI / 2, 2.0) - Math.PI / 2) < 1e-9 && Math.abs(Cues.spread(-Math.PI / 2, 2.0) + Math.PI / 2) < 1e-9, "spread keeps straight to the side at the side, even at 200%");
		check(Math.abs(Cues.spread(Math.toRadians(120), 2.0)) < Math.toRadians(120) && Math.abs(Cues.spread(Math.toRadians(-100), 2.0)) < Math.toRadians(100),
			"spread never pushes a side sound round toward straight behind (it widens toward the side instead)");

		// yaw sectors cover 0..15 and wrap
		boolean inRange = true;
		for (double a = 0; a < 2 * Math.PI; a += 0.01) { int s = Scan.yawSector(Math.cos(a), Math.sin(a)); if (s < 0 || s >= Scan.YAW) inRange = false; }
		check(inRange, "yawSector stays within 0.." + (Scan.YAW - 1));
		check(Scan.sector(1, 0, 0, 1) >= 0 && Scan.sector(1, 5, 0, 5) != Scan.sector(1, -5, 0, 5), "height bands differ");
		{ // round 15 (birch hill spot): the edge of a grass step in front of an oak trunk hid the trunk (same slice); they are different slices now
			double ex = 19.43, ey = 75.62, ez = 645.78;
			double[] step = {18.5 - ex, 74.5 - ey, 642.5 - ez}, trunk = {17.5 - ex, 75.5 - ey, 640.5 - ez}; // the real pair at that spot
			int a = Scan.sector(step[0], step[1], step[2], Math.sqrt(step[0] * step[0] + step[1] * step[1] + step[2] * step[2]));
			int b = Scan.sector(trunk[0], trunk[1], trunk[2], Math.sqrt(trunk[0] * trunk[0] + trunk[1] * trunk[1] + trunk[2] * trunk[2]));
			check(a != b, "a grass step at your feet and a tree trunk at head height beyond it are different slices (the trunk is heard)");
		}


		// cave routes: synthetic reach profiles (120 directions, index 0 = east, 30 = south...)
		int n = CaveEcho.N;
		double[] el = new double[n];
		double[] ravine = new double[n]; // narrow ravine running east-west: walls 4 blocks away north and south, open to full range both ways
		for (int i = 0; i < n; i++) { double a = i * 2 * Math.PI / n; ravine[i] = Math.min(175, 4 / Math.max(0.02, Math.abs(Math.sin(a)))); }
		check(CaveEcho.peaks(ravine, el).size() == 2, "ravine: two routes (both ends), got " + CaveEcho.peaks(ravine, el).size());
		double[] cavern = new double[n]; // round cavern 20 blocks across with one 3-wide tunnel going 60 blocks east, and a shallow alcove (26) to the west
		for (int i = 0; i < n; i++) cavern[i] = 20;
		for (int i = -2; i <= 2; i++) cavern[(i + n) % n] = 60;
		for (int i = 58; i <= 62; i++) cavern[i] = 26;
		var cr = CaveEcho.peaks(cavern, el);
		check(cr.size() == 1 && Math.abs(cr.get(0)[0]) < 0.06, "cavern: the tunnel is a route, the alcove is not (got " + cr.size() + ")");
		double[] crack = new double[n]; // one ray slipping through a crack is not a route
		for (int i = 0; i < n; i++) crack[i] = 15;
		crack[40] = 90;
		check(CaveEcho.peaks(crack, el).isEmpty(), "a one-ray crack is not a route");
		double[] wide = new double[n]; // a quarter of the view wide open to full range: one route, not four
		for (int i = 0; i < n; i++) wide[i] = i < 30 ? 175 : 12;
		check(CaveEcho.peaks(wide, el).size() == 1, "a wide opening is one route, got " + CaveEcho.peaks(wide, el).size());
		double[] four = new double[n]; // a junction chamber with four tunnels
		for (int i = 0; i < n; i++) four[i] = 8;
		for (int t = 0; t < 4; t++) for (int i = -2; i <= 2; i++) four[(t * 30 + i + n) % n] = 40 + t * 10;
		check(CaveEcho.peaks(four, el).size() == 4, "four tunnels: four routes, got " + CaveEcho.peaks(four, el).size());

		check(cr.get(0)[5] < CaveEcho.peaks(wide, el).get(0)[5], "a narrow tunnel sounds smaller than a wide opening");

		// path ways: where 3 or more meet, and sharp corners
		var straight = new java.util.HashMap<Long, Integer>();
		for (int x = -12; x <= 12; x++) for (int z = -1; z <= 1; z++) straight.put(PathGuide.key(x, z), 64);
		check(PathGuide.continues(7, 1, 5, 1, 0, 1, 0), "a dirt path runs straight on onto a wooden bridge");
		check(!PathGuide.continues(7, 1, 5, 0, 1, 1, 0), "a path never steps sideways onto a different material (a farm frame, a porch)");
		check(PathGuide.continues(6, 6, 0, 0, 1, 1, 0), "the same material always continues");
		check(!PathGuide.continues(7, 1, 0, 1, 0, 1, 0), "no change of material right at the start");
		double[] ws = PathGuide.ways(straight, 0, 0);
		check(ws[0] == 2 && ws[1] * ws[3] + ws[2] * ws[4] < -0.95, "straight path: two ways, opposite (got " + (int) ws[0] + ")");
		var tee = new java.util.HashMap<Long, Integer>(straight);
		for (int x = -1; x <= 1; x++) for (int z = 2; z <= 12; z++) tee.put(PathGuide.key(x, z), 64);
		check(PathGuide.ways(tee, 0, 0)[0] == 3, "T junction: three ways, got " + (int) PathGuide.ways(tee, 0, 0)[0]);
		var notched = new java.util.HashMap<Long, Integer>(); // a wide path (4 across) with a 2x2 bulge and a 5-block stub off its sides: still just one path, two ways
		for (int x = -1; x <= 2; x++) for (int z = -14; z <= 14; z++) notched.put(PathGuide.key(x, z), 64);
		for (int x = -3; x <= -2; x++) for (int z = 1; z <= 2; z++) notched.put(PathGuide.key(x, z), 64);
		for (int x = 3; x <= 7; x++) notched.put(PathGuide.key(x, -1), 64);
		check(PathGuide.ways(notched, 0, 0)[0] == 2, "wide path with a bulge and a stub: two ways, not an intersection (got " + (int) PathGuide.ways(notched, 0, 0)[0] + ")");
		var ell = new java.util.HashMap<Long, Integer>();
		for (int x = -12; x <= 1; x++) for (int z = -1; z <= 1; z++) ell.put(PathGuide.key(x, z), 64);
		for (int x = -1; x <= 1; x++) for (int z = -12; z <= 1; z++) ell.put(PathGuide.key(x, z), 64);
		double[] wl = PathGuide.ways(ell, 0, 0);
		check(wl[0] == 2 && Math.abs(wl[1] * wl[3] + wl[2] * wl[4]) < 0.3, "corner: two ways at right angles (got " + (int) wl[0] + ")");

		check(wl.length >= 9 && wl[5] * wl[1] + wl[6] * wl[2] > 0.94 && wl[7] * wl[3] + wl[8] * wl[4] > 0.94, "corner: both legs are straight");
		var curve = new java.util.HashMap<Long, Integer>(); // a quarter circle of radius 8, 3 wide: a curve, not a corner
		for (double a = 0; a <= Math.PI / 2 + 1e-9; a += 0.01) for (double r = 7; r <= 9; r += 0.5) curve.put(PathGuide.key((int) Math.floor(r * Math.cos(a)), (int) Math.floor(r * Math.sin(a))), 64);
		for (int x = -6; x <= 8; x++) for (int dz = -1; dz <= 1; dz++) curve.put(PathGuide.key(x, -dz - 1 + 0), 64);
		double[] wc = PathGuide.ways(curve, 8, 4);
		check(!(wc[0] == 2 && wc.length >= 9 && wc[5] * wc[1] + wc[6] * wc[2] > 0.94 && wc[7] * wc[3] + wc[8] * wc[4] > 0.94 && Math.acos(Math.max(-1, Math.min(1, wc[1] * wc[3] + wc[2] * wc[4]))) < Math.toRadians(105)), "a curve is not announced as a corner");

		// ledge loudness: quiet at the far end of the warning range, full at the lip, rising faster near it
		check(Ledges.ledgeCurve(0f, 3) < 0.1f && Math.abs(Ledges.ledgeCurve(1f, 3) - 1f) < 1e-6, "ledge curve starts quiet and ends full");
		check(Math.abs(Ledges.ledgeCurve(0f, 6) - 0.4f) < 1e-6, "big drops keep the early warning");
		check(Ledges.ledgeCurve(0.75f, 3) - Ledges.ledgeCurve(0.5f, 3) > Ledges.ledgeCurve(0.25f, 3) - Ledges.ledgeCurve(0f, 3), "ledge curve rises faster near the edge");

		System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
		if (failed > 0) System.exit(1);
	}
}
