package com.sonar;

/** Dependency-free checks of the pure logic. Run with: ./gradlew selfTest */
public class SelfTest {
	private static int failed;

	private static void check(boolean ok, String what) {
		System.out.println((ok ? "ok   " : "FAIL ") + what);
		if (!ok) failed++;
	}

	public static void main(String[] args) {
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

		// yaw sectors cover 0..15 and wrap
		boolean inRange = true;
		for (double a = 0; a < 2 * Math.PI; a += 0.01) { int s = Scan.yawSector(Math.cos(a), Math.sin(a)); if (s < 0 || s >= Scan.YAW) inRange = false; }
		check(inRange, "yawSector stays within 0.." + (Scan.YAW - 1));
		check(Scan.sector(1, 0, 0, 1) >= 0 && Scan.sector(1, 5, 0, 5) != Scan.sector(1, -5, 0, 5), "height bands differ");


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

		// ledge loudness: quiet at the far end of the warning range, full at the lip, rising faster near it
		check(Cues.ledgeCurve(0f, 3) < 0.1f && Math.abs(Cues.ledgeCurve(1f, 3) - 1f) < 1e-6, "ledge curve starts quiet and ends full");
		check(Math.abs(Cues.ledgeCurve(0f, 6) - 0.4f) < 1e-6, "big drops keep the early warning");
		check(Cues.ledgeCurve(0.75f, 3) - Cues.ledgeCurve(0.5f, 3) > Cues.ledgeCurve(0.25f, 3) - Cues.ledgeCurve(0f, 3), "ledge curve rises faster near the edge");

		System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
		if (failed > 0) System.exit(1);
	}
}
