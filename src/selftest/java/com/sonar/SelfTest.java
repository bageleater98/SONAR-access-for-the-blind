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

		System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
		if (failed > 0) System.exit(1);
	}
}
