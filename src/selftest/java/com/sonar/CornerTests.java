package com.sonar;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Round 16: cave passages round a corner are found by walking through the space you can see and a little way out of it (CaveEcho.Walk), not by walking every
 * block within 20 of you; and holes into caves reach 32 blocks and are muffled when out of sight.
 */
final class CornerTests {
	private CornerTests() {}

	private static final BlockState STONE = Blocks.STONE.defaultBlockState(), GRASS = Blocks.GRASS_BLOCK.defaultBlockState(), AIR = Blocks.AIR.defaultBlockState();
	private static final Vec3 EYE = new Vec3(0.5, 65.62, 0.5); // feet on y 64 (floor 63)

	/** Solid rock (floor 63, roof 68) over the box; carve() opens 4-high spaces in it. */
	private static World rock(int x0, int z0, int x1, int z1) { return new World().fill(x0, 63, z0, x1, 68, z1, STONE); }
	private static World carve(World w, int x0, int z0, int x1, int z1) { return w.fill(x0, 64, z0, x1, 67, z1, AIR); }

	private static CaveEcho.Walk walk(World w, Vec3 eye) {
		double[][] p = CaveEcho.profile(w, eye, 175);
		CaveEcho.Walk wk = new CaveEcho.Walk(w, null, eye, p[0], p[1], 175);
		wk.run(Long.MAX_VALUE);
		return wk;
	}

	private static List<double[]> corners(World w, List<double[]> seen) { return CaveEcho.corners(w, walk(w, EYE), EYE, seen); }

	private static String deg(List<double[]> c) {
		StringBuilder sb = new StringBuilder();
		for (double[] o : c) sb.append(Dbg.fmt(" [%.0f deg, %.0f steps, %.0f straight]", Math.toDegrees(o[0]), o[1], o[3]));
		return sb.length() == 0 ? " none" : sb.toString();
	}

	static void run(BiConsumer<Boolean, String> check) {
		// 1. the classic: a corridor out of a small room turns a corner 12 blocks off (what the old 20-block walk found too)
		World bend = carve(carve(carve(rock(-30, -30, 40, 30), -4, -4, 4, 4), 1, -12, 2, -5), 3, -12, 30, -11);
		List<double[]> b = corners(bend, List.of());
		boolean bendOk = !b.isEmpty() && b.get(0)[0] < 0 && b.get(0)[0] > Math.toRadians(-90) && b.get(0)[1] < 25;
		check.accept(bendOk, "a corridor that turns a corner near you: a passage round the corner, that way (north-east)" + deg(b));

		// 2. a nook: the same corridor ends 3 blocks round the bend
		World nook = carve(carve(carve(rock(-30, -30, 40, 30), -4, -4, 4, 4), 1, -8, 2, -5), 3, -8, 5, -7);
		check.accept(corners(nook, List.of()).isEmpty(), "a nook 3 blocks round a bend leads nowhere: no echo" + deg(corners(nook, List.of())));

		// 3. a straight tunnel you can see all along: that is a route the rays already hear, not a corner
		World straight = carve(carve(rock(-30, -60, 30, 30), -4, -4, 4, 4), 0, -50, 1, -5);
		check.accept(corners(straight, List.of()).isEmpty(), "a straight tunnel in full view is not a corner" + deg(corners(straight, List.of())));

		// 4. a pillar in an open cavern: its shadow comes back into view, never a passage
		World pillar = carve(rock(-30, -30, 30, 30), -25, -25, 25, 25).fill(6, 64, -1, 8, 67, 1, STONE);
		check.accept(corners(pillar, List.of()).isEmpty(), "a pillar's shadow in an open cavern is not a passage" + deg(corners(pillar, List.of())));

		// 5. FURTHER THAN THE OLD 20: a big cavern; behind a rock fin 22+ blocks away a passage leaves east, out of sight
		World far = carve(rock(-35, -35, 70, 35), -30, -30, 30, 30).fill(22, 64, 12, 27, 67, 28, STONE);
		carve(far, 31, 20, 60, 21);
		List<double[]> f = corners(far, List.of());
		boolean farOk = false;
		for (double[] o : f) farOk |= o[3] > 20 && o[0] > Math.toRadians(10) && o[0] < Math.toRadians(70);
		check.accept(farOk, "a passage round a rock 25+ blocks across a big cavern (past the old 20-block walk): found" + deg(f));

		// 6. a route you can already hear that way: the corner is left to it (no second echo the same way)
		check.accept(b.isEmpty() || corners(bend, List.of(new double[]{b.get(0)[0] + Math.toRadians(10)})).isEmpty(),
			"a corner where a route you can see already points is not echoed twice");

		// 7. walked a little each tick gives the same as all at once, and the nearest come first
		double[][] p = CaveEcho.profile(far, EYE, 175);
		CaveEcho.Walk slow = new CaveEcho.Walk(far, null, EYE, p[0], p[1], 175);
		int slices = 0;
		while (!slow.done && slices < 100000) { slow.run(20_000); slices++; }
		List<double[]> fs = CaveEcho.corners(far, slow, EYE, List.of());
		boolean same = slow.done && fs.size() == f.size();
		for (int i = 0; same && i < fs.size(); i++) same = Math.abs(fs.get(i)[0] - f.get(i)[0]) < 1e-9 && fs.get(i)[1] == f.get(i)[1];
		boolean nearestFirst = true;
		for (int i = 1; i < slow.found.size(); i++) nearestFirst &= slow.found.get(i)[3] >= slow.found.get(i - 1)[3];
		check.accept(same && nearestFirst, "walked in " + slices + " small slices: the same passages as all at once, nearest first");

		// 8. cost: a wide-open cavern 120 across (all in view) stays under the size cap and each slice keeps to its time
		World open = carve(rock(-62, -62, 62, 62), -60, -60, 60, 60);
		double[][] po = CaveEcho.profile(open, EYE, 175);
		CaveEcho.Walk ow = new CaveEcho.Walk(open, null, EYE, po[0], po[1], 175);
		long worst = 0;
		while (!ow.done) { long t0 = System.nanoTime(); ow.run(Tuning.CORNER_TICK_NS); worst = Math.max(worst, System.nanoTime() - t0); }
		check.accept(ow.cells <= Tuning.CORNER_MAX_CELLS && worst < Tuning.CORNER_TICK_NS * 4,
			Dbg.fmt("an open cavern 120 across: %d spots walked (cap %d), longest slice %.1f ms (a tick allows %.1f)", ow.cells, Tuning.CORNER_MAX_CELLS, worst / 1e6, Tuning.CORNER_TICK_NS / 1e6));
		check.accept(CaveEcho.corners(open, ow, EYE, List.of()).isEmpty(), "an open cavern with nothing round a corner: no corner echoes");

		// 9. standing in mid-air or water: nothing to walk from, no crash
		CaveEcho.Walk air = new CaveEcho.Walk(new World(), null, EYE, new double[CaveEcho.N], new double[CaveEcho.N], 175);
		air.run(Long.MAX_VALUE);
		check.accept(air.done && air.found.isEmpty(), "nothing under you: the corner walk just finds nothing");

		// --- holes into caves: 32 blocks, and out of sight = muffled ---
		check.accept(Tuning.CAVE_DROP_RANGE == 32, "holes into caves are listened for out to 32 blocks, as far as the ways round a corner in buildings");
		double[] seenHole = Echo.caveDrop(field(false), 0.5, 65.62, 0.5, 64, -90f, Tuning.CAVE_DROP_RANGE);
		double[] overHill = Echo.caveDrop(field(true), 0.5, 65.62, 0.5, 64, -90f, Tuning.CAVE_DROP_RANGE);
		check.accept(seenHole != null && seenHole.length < 8, "a hole you can see plays a plain echo");
		check.accept(overHill != null && overHill.length == 8 && overHill[7] == 1 && Echo.Way.of(overHill).hidden(), "a hole over a rise, out of sight, plays muffled (round the corner)");
		World farPit = field(false);
		double[] far28 = Echo.caveDrop(farPit, -13.5, 65.62, 0.5, 64, -90f, Tuning.CAVE_DROP_RANGE); // 28 blocks back from the pit
		check.accept(far28 != null, "28 blocks back from the hole: still a faint echo");
		boolean every = true;
		for (int back = 0; back <= 30; back++) every &= Echo.caveDrop(farPit, 13.5 - 1 - back, 65.62, 0.5, 64, -90f, Tuning.CAVE_DROP_RANGE) != null;
		check.accept(every, "from every distance 1 to 31 blocks back, the hole is heard (no gap where its score drops below zero)");
		// each hole echoes: a small pit straight ahead AND a bigger, deeper one off to the left (the "best" one alone silenced the one in front)
		World two = field(false);
		two.fill(-20, 30, -24, 34, 62, -11, STONE).fill(-20, 63, -24, 34, 63, -11, GRASS); // ground on that side too
		two.fill(-2, 31, -17, 0, 63, -15, AIR).fill(-12, 31, -17, 0, 34, -15, AIR); // a deep pit 15 to the left (z -16), a big tunnel under it
		List<double[]> both = Echo.caveDrops(two, 0.5, 65.62, 0.5, 64, -90f, Tuning.CAVE_DROP_RANGE);
		boolean ahead = false, left = false;
		for (double[] d : both) { double a = Math.atan2(Math.sin(d[0]), Math.cos(d[0])); ahead |= Math.abs(a) < Math.toRadians(15); left |= a < Math.toRadians(-60) && a > Math.toRadians(-120); }
		boolean ordered = true;
		for (int k = 1; k < both.size(); k++) ordered &= both.get(k)[1] >= both.get(k - 1)[1];
		check.accept(ahead && left && ordered,
			"two holes: the one ahead AND the bigger one to the side both echo, nearest first" + drops(both));
		World wide = new World().fill(-20, 40, -20, 40, 62, 20, STONE).fill(-20, 63, -20, 40, 63, 20, GRASS);
		wide.fill(10, 51, -4, 18, 63, 4, AIR).fill(10, 51, -4, 34, 53, 4, AIR); // one pit 9 across
		List<double[]> one = Echo.caveDrops(wide, 0.5, 65.62, 0.5, 64, -90f, Tuning.CAVE_DROP_RANGE);
		check.accept(one.size() == 1, "one wide pit (seen by many rays) is ONE echo" + drops(one));
		// the user's F2 spot by the river (round 16): the pit in front of them must echo
		try {
			World sp = new World();
			double[] pl = World.load(java.nio.file.Path.of("src/selftest/spots/r16-river-pit.txt"), sp);
			List<double[]> at = Echo.caveDrops(sp, pl[0], pl[1] + 1.62, pl[2], (int) Math.floor(pl[1]), (float) pl[3], Tuning.CAVE_DROP_RANGE);
			double fwdA = Math.atan2(Math.cos(Math.toRadians(pl[3])), -Math.sin(Math.toRadians(pl[3])));
			boolean front = false;
			for (double[] d : at) front |= Math.abs(Math.atan2(Math.sin(d[0] - fwdA), Math.cos(d[0] - fwdA))) < Math.toRadians(35);
			StringBuilder rel = new StringBuilder();
			for (double[] d : at) rel.append(Dbg.fmt(" [%+.0f deg from facing, %.1f blocks%s]", Math.toDegrees(Math.atan2(Math.sin(d[0] - fwdA), Math.cos(d[0] - fwdA))), d[1], d.length > 7 ? ", out of sight" : ""));
			check.accept(front, "the user's river spot: the pit in front of them echoes" + (rel.length() == 0 ? " (none)" : rel));
		} catch (Exception ex) { check.accept(false, "the river spot loads: " + ex); }
		World flat = new World().fill(-40, 40, -40, 40, 63, 40, STONE); // nothing to find: the walk covers the full 32 blocks every way (the most work)
		long worstHole = 0; int holeSlices = 0;
		for (int k = 0; k < 3; k++) { // (warm up, then measure the last)
			CaveEcho.Walk hw = CaveEcho.Walk.open(flat, EYE, Tuning.CAVE_DROP_RANGE);
			worstHole = 0; holeSlices = 0;
			while (!hw.done) { long t0 = System.nanoTime(); hw.run(Tuning.CORNER_TICK_NS); worstHole = Math.max(worstHole, System.nanoTime() - t0); holeSlices++; }
		}
		check.accept(worstHole < Tuning.CORNER_TICK_NS * 2 && holeSlices <= 8, Dbg.fmt("the hole walk over open ground, 32 blocks every way: %d slices, longest %.1f ms (a tick allows %.1f; the echo comes about 10 ticks after the ping)", holeSlices, worstHole / 1e6, Tuning.CORNER_TICK_NS / 1e6));
		World cliffs = new World().fill(-40, 30, -40, 40, 63, 40, STONE);
		for (int x = -39; x <= 33; x += 6) cliffs.fill(x, 40, -36, x + 2, 63, 36, AIR); // long trenches: hundreds of edges, no cave anywhere
		CaveEcho.Walk cw = CaveEcho.Walk.open(cliffs, EYE, Tuning.CAVE_DROP_RANGE);
		long worstCliff = 0;
		while (!cw.done) { long t0 = System.nanoTime(); cw.run(Tuning.CORNER_TICK_NS); worstCliff = Math.max(worstCliff, System.nanoTime() - t0); }
		check.accept(cw.edges.size() > 50 && worstCliff < Tuning.CORNER_TICK_NS * 3 && Echo.holes(cliffs, cw.edges, 0.5, 65.62, 0.5, 64, -90f).isEmpty(),
			Dbg.fmt("cliffs everywhere (%d edges, every one asked): no echo, longest slice %.1f ms", cw.edges.size(), worstCliff / 1e6));
	}

	private static String drops(List<double[]> l) {
		StringBuilder sb = new StringBuilder();
		for (double[] d : l) sb.append(Dbg.fmt(" [%.0f deg, %.1f blocks]", Math.toDegrees(d[0]), d[1]));
		return sb.length() == 0 ? " none" : sb.toString();
	}

	/** Grass over rock; a pit (x 14..16) into a rock-roofed tunnel; with `hill`, a rise between you and it, higher than your eyes. */
	private static World field(boolean hill) {
		World w = new World().fill(-20, 40, -10, 34, 62, 10, STONE).fill(-20, 63, -10, 34, 63, 10, GRASS);
		if (hill) { w.fill(5, 64, -10, 9, 64, 10, GRASS); w.fill(6, 65, -10, 8, 65, 10, GRASS); w.fill(7, 66, -10, 7, 66, 10, GRASS); }
		w.fill(14, 51, -1, 16, 63, 1, AIR);
		w.fill(14, 51, -1, 30, 53, 1, AIR);
		return w;
	}
}
