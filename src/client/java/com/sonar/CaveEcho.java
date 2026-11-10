package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Cave mode for the echo, separate from the house echo. Underground (surrounded by natural rock) the echo listens far out (Cave echo range, 175 blocks by default)
 * and plays one echo for every route leading away from where you stand: tunnels and corridors off a cavern, both ends of a ravine, side passages.
 */
public class CaveEcho {
	/** Rock that caves are made of: stone, deepslate, tuff, netherrack, basalt, blackstone, ores, dripstone, gravel, dirt and the like. Built blocks never count. */
	static boolean caveRock(BlockState s) {
		if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND)) return true;
		if (s.is(Blocks.GRAVEL) || s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.POINTED_DRIPSTONE) || s.is(Blocks.SMOOTH_BASALT) || s.is(Blocks.AMETHYST_BLOCK)
			|| s.is(Blocks.BUDDING_AMETHYST) || s.is(Blocks.CLAY) || s.is(Blocks.SCULK) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.OBSIDIAN) || s.is(Blocks.SOUL_SAND)
			|| s.is(Blocks.SOUL_SOIL) || s.is(Blocks.END_STONE) || s.is(Blocks.BEDROCK) || s.is(Blocks.GLOW_LICHEN) || s.is(Blocks.MOSS_BLOCK)) return true;
		String id = s.getBlock().getDescriptionId();
		return id.endsWith("_ore") || id.contains("raw_") || id.contains("_amethyst_bud") || id.endsWith("amethyst_cluster");
	}

	private static String lastMsg = "";

	/** Underground: rays in 26 directions (48 blocks) mostly hit something, at least 3 out of 4 of what they hit is natural rock, the space has something above it on most upward rays
	 *  (a ravine's open top still has its walls on the slanted rays) and walls around on most level ones. A stone house or a cliff in the open does not pass. */
	static boolean inCave(Minecraft mc, Vec3 eye) {
		return inCave(mc, Sense.hear(mc), eye, net.minecraft.world.phys.shapes.CollisionContext.of(mc.player));
	}

	/** The cave test in world w (as Sonar hears it); mc only for the debug line (may be null: the self-test). */
	static boolean inCave(Minecraft mc, BlockGetter w, Vec3 eye, net.minecraft.world.phys.shapes.CollisionContext ctx) {
		int hits = 0, rock = 0, up = 0, level = 0;
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
			if (dx == 0 && dy == 0 && dz == 0) continue;
			Vec3 d = new Vec3(dx, dy, dz).normalize();
			var hit = w.clip(new ClipContext(eye, eye.add(d.scale(48)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx));
			if (hit.getType() == HitResult.Type.MISS) continue;
			hits++;
			if (caveRock(w.getBlockState(hit.getBlockPos()))) rock++;
			if (dy > 0) up++;
			if (dy == 0) level++;
		}
		boolean cave = hits >= 18 && rock >= 0.75 * hits && up >= 4 && level >= 5;
		if (SonarConfig.cur.debug && mc != null && mc.player != null) {
			String m = Dbg.fmt("cave test: %b (hits %d/26 need 18, rock %d need %d, above %d/9 need 4, level %d/8 need 5)", cave, hits, rock, (int) Math.ceil(0.75 * hits), up, level);
			if (!m.equals(lastMsg)) { lastMsg = m; Dbg.send(mc.player, Component.literal(m)); }
		}
		return cave;
	}

	static final int N = 120; // 3 degree steps all the way round
	private static final double[] ELEV = {-50, -28, -14, 0, 14}; // degrees: a passage that slopes up or down is still found, and so is the floor of a ravine or cavern you look down into

	/**
	 * Every route out of the space: the reach of the cave is measured all the way round (the longest of three slightly tilted rays per direction), one-ray cracks are ignored, and every
	 * direction that reaches clearly further than the walls around it (a peak that stands out: half again as deep as the walls beside it, and at least 6 blocks deeper) is a route.
	 * Returns {bearing (atan2 z,x), how far it goes, prominence, distance to the opening, elevation in radians}, most prominent first, at most 8.
	 */
	/** The reach measurement (360 long rays) is spread over the ticks between the ping and its echo, so a cave echo never stalls one frame. */
	private static final class Job {
		final Vec3 from;
		final double range;
		final double[] r = new double[N], el = new double[N];
		int next;
		Job(Vec3 from, double range) { this.from = from; this.range = range; }
	}

	private static Job job;
	private static final int PER_TICK = 30; // directions measured per tick (5 rays each)

	/** Start measuring from where you stand now (called at each ping while underground). */
	static void prepare(Minecraft mc, Vec3 from, double range) {
		job = new Job(from, range);
	}

	/** Each tick: measure the next few directions of the pending job; once all are measured, the corner walk starts from them and goes on a little each tick. */
	static void tick(Minecraft mc) {
		if (mc.level == null) { job = null; walk = null; holeWalk = null; return; }
		if (holeWalk != null && holeWalk.w != Sense.hear(mc)) holeWalk = null;
		if (holeWalk != null && !holeWalk.done) holeWalk.run(Tuning.CORNER_TICK_NS);
		if (job != null && job.next < N) {
			measure(Sense.hear(mc), job, Math.min(N, job.next + PER_TICK));
			if (job.next >= N) startWalk(Sense.hear(mc), job);
		}
		if (walk != null && walk.w != Sense.hear(mc)) walk = null; // another world or dimension
		if (walk != null && !walk.done) walk.run(Tuning.CORNER_TICK_NS);
	}

	private static void measure(BlockGetter w, Job j, int upTo) {
		for (; j.next < upTo; j.next++) {
			int i = j.next;
			double a = i * 2 * Math.PI / N;
			for (double e : ELEV) {
				double er = Math.toRadians(e), c = Math.cos(er);
				double len = Rooms.ray(w, j.from, Math.cos(a) * c, Math.sin(er), Math.sin(a) * c, j.range, false, true, false); // (the same ray as Rooms.rayLen)
				if (len > j.r[i]) { j.r[i] = len; j.el[i] = er; }
			}
		}
	}

	/** The whole reach profile at once, in any world (self-test): {reach per direction, tilt per direction}. */
	static double[][] profile(BlockGetter w, Vec3 from, double range) {
		Job j = new Job(from, range);
		measure(w, j, N);
		return new double[][]{j.r, j.el};
	}

	static List<double[]> routes(Minecraft mc, Scan s, double range) {
		Vec3 from = new Vec3(s.ex, s.ey, s.ez);
		Job j = job;
		if (j == null || j.range != range || j.from.distanceToSqr(from) > 1.0) j = new Job(from, range); // moved since the ping: measure from here
		measure(Sense.hear(mc), j, N); // whatever is still missing
		job = null;
		if (walk == null || walk.src != j) startWalk(Sense.hear(mc), j); // (a job finished in its tick has started it already)
		double[] r = j.r, el = j.el;
		List<double[]> out = peaks(r, el);
		if (SonarConfig.cur.debug && mc.player != null) {
			StringBuilder sb = new StringBuilder();
			double fwd = Math.atan2(Math.cos(Math.toRadians(s.yaw)), -Math.sin(Math.toRadians(s.yaw)));
			for (double[] o : out) sb.append(Dbg.fmt("[%.0f deg, %.0f up, goes %.0f, opens at %.0f, %.0f wide] ", Math.toDegrees(Math.atan2(Math.sin(o[0] - fwd), Math.cos(o[0] - fwd))), Math.toDegrees(o[4]), o[1], o[3], o[5]));
			Dbg.send(mc.player, Component.literal("cave routes (range " + (int) range + "): " + out.size() + " " + sb));
		}
		return out;
	}

	/*
	 * PASSAGES ROUND A CORNER, which no straight ray can see. Round 16: the cave floor used to be walked out to 20 blocks from you in every direction, which cost a
	 * lot (most of it over floor you could already see) and missed every corner further off, while the routes you CAN see reach the whole cave echo range. Now
	 * the walk goes through the space you can see (judged by the reach rays already measured: a spot is in view when it is no further than the ray toward it got)
	 * and only a little way out of view: where the floor runs on out of sight for CORNER_DEPTH steps, that is a passage round a corner. A pillar's shadow or a
	 * shallow nook comes back into view (or ends) before that, so it never counts. It reaches as far as the cave echo range, is capped in size, and runs a slice
	 * each tick between the ping and its echo (the echo takes what it has found by then: the walk goes outward, so the nearest corners come first).
	 */

	/**
	 * A floor walk from one spot, in two passes. 1: walk outward through what you can see, and on out of view only while the way stays out of view, up to
	 * CORNER_DEPTH steps. 2: for every spot walked out of view, how far it is (by walking) from the NEAREST spot in view: the shadow behind a pillar is a few steps
	 * from view to the side however long it runs, a passage round a corner is not. Spots CORNER_DEPTH from any view are passages.
	 * found: {x, floor y, z (CORNER_PEEK steps round the corner), steps from you by walking, corner x, corner z}, nearest first.
	 */
	static final class Walk {
		final BlockGetter w;
		final Job src; // the reach measurement it walks by (null in a self-test)
		final Vec3 from;
		final double[] reach, el;
		final int range, px, pz;
		/** Walked spots: {x, z, floor, steps, in view 1/0, steps out of view along the walk, steps from the nearest view (pass 2), from x, from z (pass 2)}. */
		final java.util.HashMap<Long, int[]> spots = new java.util.HashMap<>();
		private final java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
		private final java.util.ArrayDeque<int[]> q2 = new java.util.ArrayDeque<>();
		private final List<int[]> edge = new ArrayList<>(); // spots in view next to one out of view: where pass 2 starts
		private final List<int[]> unseen = new ArrayList<>(); // the spots walked out of view (all pass 2 and the result look at)
		final List<int[]> found = new ArrayList<>();
		/** Hole edges met on the way: {x, z (the column that falls away), floor at the lip, floor at the bottom, steps, room at the bottom (Echo.caveBelow: 4+ =
		 *  a cave)}, in walking order (nearest first). Every edge is asked, as it is found, in the walk's own time slices. */
		final List<int[]> edges = new ArrayList<>();
		final net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		int cells, pass = 1;
		boolean done;

		Walk(BlockGetter w, Job src, Vec3 from, double[] reach, double[] el, double range) {
			this.w = w; this.src = src; this.from = from; this.reach = reach; this.el = el; this.range = (int) Math.ceil(range);
			px = (int) Math.floor(from.x); pz = (int) Math.floor(from.z);
			int f0 = Integer.MIN_VALUE;
			for (int y = (int) Math.floor(from.y) - 1; y >= (int) Math.floor(from.y) - 4; y--) if (!w.getBlockState(pos.set(px, y, pz)).getCollisionShape(w, pos).isEmpty()) { f0 = y; break; }
			if (f0 == Integer.MIN_VALUE) { done = true; return; } // in the air or swimming: nothing to walk from
			int[] s0 = {px, pz, f0, 0, 1, 0, 0, px, pz};
			spots.put(key(px, pz), s0);
			q.add(s0);
		}

		static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

		/** A walk over ALL the walkable ground within `range` (everything counts as in view: no corners), for the hole edges outside caves. */
		static Walk open(BlockGetter w, Vec3 from, double range) {
			double[] r = new double[N], el = new double[N];
			java.util.Arrays.fill(r, range * 2);
			return new Walk(w, null, from, r, el, range);
		}

		/** In view from where the walk started: no further than the reach ray toward it (the nearest three of the 3-degree rays) got, along the ground. */
		boolean inView(int x, int z) {
			double dx = x + 0.5 - from.x, dz = z + 0.5 - from.z, d = Math.hypot(dx, dz);
			if (d < 1.5) return true;
			int i = (int) Math.round(Math.atan2(dz, dx) / (2 * Math.PI / N));
			double best = 0;
			for (int k = -1; k <= 1; k++) { int ii = ((i + k) % N + N) % N; best = Math.max(best, reach[ii] * Math.cos(el[ii])); }
			return d <= best + 1.0;
		}

		private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

		/** Walk on for up to `nanos` (or until done). */
		void run(long nanos) {
			long start = System.nanoTime(); // (elapsed time, not an end time: start + a huge budget would overflow)
			int n = 0;
			while (!done) {
				if ((++n & 7) == 0 && System.nanoTime() - start > nanos) return;
				if (pass == 1) {
					if (q.isEmpty()) { startPass2(); continue; }
					int[] c = q.poll();
					if (c[5] >= Tuning.CORNER_DEPTH) continue; // far enough out of view along this way: walked no further
					for (int[] d : DIRS) {
						int nx = c[0] + d[0], nz = c[1] + d[1];
						if (Math.max(Math.abs(nx - px), Math.abs(nz - pz)) > range) continue;
						long k = key(nx, nz);
						if (spots.containsKey(k)) continue;
						int nf = BlockRules.stepTo(w, nx, nz, c[2], pos);
						if (nf == BlockRules.NONE) { // not walkable (remembered so it is tested once). Too far down? a hole edge
							spots.put(k, null);
							int bottom = BlockRules.dropBottom(w, nx, nz, c[2], pos);
							if (bottom != BlockRules.NONE && c[2] - bottom >= Tuning.DROP_DEEP) edges.add(new int[]{nx, nz, c[2], bottom, c[3] + 1, Echo.caveBelow(w, nx, nz, bottom, c[2])});
							continue;
						}
						boolean view = inView(nx, nz);
						int[] s = {nx, nz, nf, c[3] + 1, view ? 1 : 0, view ? 0 : c[5] + 1, view ? 0 : Integer.MAX_VALUE, nx, nz};
						spots.put(k, s);
						if (!view) unseen.add(s);
						for (int[] e : DIRS) { // a spot in view beside one out of view (either found first): pass 2 starts there
							int[] o = spots.get(key(nx + e[0], nz + e[1]));
							if (o != null && o[4] != s[4]) edge.add(view ? s : o);
						}
						q.add(s);
						if (++cells >= Tuning.CORNER_MAX_CELLS) { q.clear(); break; } // a huge open cavern: what was walked so far
					}
				} else if (pass == 2) {
					if (q2.isEmpty()) { collect(); continue; }
					int[] c = q2.poll();
					for (int[] d : DIRS) {
						int[] o = spots.get(key(c[0] + d[0], c[1] + d[1]));
						if (o == null || o[4] == 1 || o[6] <= c[6] + 1 || Math.abs(o[2] - c[2]) > 3) continue;
						o[6] = c[6] + 1; o[7] = c[0]; o[8] = c[1];
						q2.add(o);
					}
				}
			}
		}

		private void startPass2() {
			pass = 2;
			for (int[] e : edge) { e[6] = 0; e[7] = e[0]; e[8] = e[1]; q2.add(e); }
		}

		/** The passages: spots CORNER_DEPTH or more from the nearest view, each traced back to CORNER_PEEK steps round its corner. Nearest by walking first. */
		private void collect() {
			java.util.HashSet<Long> peeks = new java.util.HashSet<>();
			for (int[] s : unseen) {
				if (s[6] == Integer.MAX_VALUE || s[6] < Tuning.CORNER_DEPTH) continue;
				int[] p = s;
				int guard = 0;
				while (p[6] > Tuning.CORNER_PEEK && guard++ < 4 * Tuning.CORNER_DEPTH) { int[] up = spots.get(key(p[7], p[8])); if (up == null || up == p) break; p = up; }
				int[] c = p;
				guard = 0;
				while (c[6] > 0 && guard++ < 4 * Tuning.CORNER_DEPTH) { int[] up = spots.get(key(c[7], c[8])); if (up == null || up == c) break; c = up; }
				if (peeks.add(key(p[0], p[1]))) found.add(new int[]{p[0], p[2], p[1], p[3], c[0], c[1]});
			}
			found.sort((a, b) -> a[3] != b[3] ? Integer.compare(a[3], b[3]) : a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[2], b[2])); // (ties in a fixed order)
			pass = 3;
			done = true;
		}

		/** No more time (the echo is due): stop walking out and judge what was walked. */
		void finish() {
			if (done) return;
			if (pass == 1) { q.clear(); startPass2(); }
			run(Long.MAX_VALUE);
		}
	}

	private static Walk walk;
	/** Outside caves: the walk over the ground around you for hole edges, started at the ping (prepareHoles) and walked a little each tick. */
	private static Walk holeWalk;

	static void prepareHoles(Minecraft mc, Vec3 eye) {
		if (mc.level != null) holeWalk = Walk.open(Sense.hear(mc), eye, Tuning.CAVE_DROP_RANGE);
	}

	/** The walk to take hole edges from, for an echo from `eye` (null if none fits): in a cave the corner walk (it covers what you see and round the corners),
	 *  outside the hole walk (started now if the ping did not start one here). */
	static Walk forHoles(Minecraft mc, Vec3 eye, boolean cave) {
		double m2 = Tuning.CORNER_MOVED * Tuning.CORNER_MOVED;
		if (cave) return walk != null && walk.w == Sense.hear(mc) && walk.from.distanceToSqr(eye) <= m2 ? walk : null;
		if (holeWalk == null || holeWalk.w != Sense.hear(mc) || holeWalk.from.distanceToSqr(eye) > m2) prepareHoles(mc, eye);
		return holeWalk;
	}

	private static void startWalk(BlockGetter w, Job j) {
		walk = new Walk(w, j, j.from, j.r.clone(), j.el.clone(), j.range);
	}

	/**
	 * The passages round a corner for an echo from where you stand now: from the walk started at the ping (finished now if it has not got there yet, within
	 * CORNER_FINISH_NS). At most CORNERS_MAX, nearest by walking first, never where a route you can see already points (30 degrees), never two the same way, and only
	 * if you really can not see it from here (a straight ray from your eyes is blocked). Returns {bearing, distance, prominence 0, straight distance, elevation, width 3}.
	 */
	static List<double[]> aroundCorners(Minecraft mc, Scan s, List<double[]> seen) {
		Walk wk = walk;
		Vec3 eye = new Vec3(s.ex, s.ey, s.ez);
		if (wk == null || wk.w != Sense.hear(mc) || wk.from.distanceToSqr(eye) > Tuning.CORNER_MOVED * Tuning.CORNER_MOVED) return new ArrayList<>(); // none from near here (routes() starts one)
		if (!wk.done) { wk.run(Tuning.CORNER_FINISH_NS); wk.finish(); } // due now: what it has walked so far (the nearest first)
		List<double[]> out = corners(Sense.hear(mc), wk, eye, seen);
		if (SonarConfig.cur.debug && mc.player != null)
			Dbg.send(mc.player, Component.literal(Dbg.fmt("corners: walked %d spots%s, %d passages out of sight, %d used", wk.cells, wk.done ? "" : " (not finished)", wk.found.size(), out.size())));
		return out;
	}

	/** The corner choice on its own (any world, so it can be tested). */
	static List<double[]> corners(BlockGetter w, Walk wk, Vec3 eye, List<double[]> seen) {
		List<double[]> out = new ArrayList<>();
		double moved = Math.hypot(eye.x - wk.from.x, eye.z - wk.from.z);
		outer:
		for (int[] f : new ArrayList<>(wk.found)) {
			Vec3 p = new Vec3(f[0] + 0.5, f[1] + 1.6, f[2] + 0.5);
			double bx = p.x - eye.x, bz = p.z - eye.z, ang = Math.atan2(bz, bx), flat = Math.hypot(bx, bz);
			for (double[] o : seen) if (Math.abs(Math.atan2(Math.sin(ang - o[0]), Math.cos(ang - o[0]))) < Math.toRadians(30)) continue outer;
			for (double[] o : out) if (Math.abs(Math.atan2(Math.sin(ang - o[0]), Math.cos(ang - o[0]))) < Math.toRadians(30)) continue outer;
			var hit = w.clip(new ClipContext(eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
			if (hit.getType() == HitResult.Type.MISS) continue; // in plain view from here after all: the rays judge what you can see
			double el = Math.max(-Math.PI / 4, Math.min(Math.PI / 4, Math.atan2(p.y - eye.y, Math.max(1, flat)))); // a passage leading down (or up) round the corner
			out.add(new double[]{ang, Math.max(flat, f[3] - moved), 0, flat, el, 3});
			if (out.size() >= Tuning.CORNERS_MAX) break;
		}
		return out;
	}

	/** The route finding on its own (no world access, so it can be tested): r = reach per direction (N directions round), el = the tilt each reach was measured at. */
	static List<double[]> peaks(double[] r, double[] el) {
		int n = r.length;
		double[] q = new double[n]; // a route must be at least two rays wide: a single ray slipping through a crack is not a way you can go
		for (int i = 0; i < n; i++) {
			double a = r[(i + n - 1) % n], b = r[i], c = r[(i + 1) % n];
			q[i] = Math.max(Math.min(a, b), Math.min(b, c));
		}
		double gmin = Double.MAX_VALUE;
		for (double v : q) gmin = Math.min(gmin, v);
		List<double[]> found = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			double v = q[i];
			if (v < 8 || q[(i + n - 1) % n] >= v) continue; // only the first ray of a flat run is looked at (a wide opening, or rays all reaching full range, is ONE route)
			int len = 1;
			while (len < n && q[(i + len) % n] == v) len++;
			if (len < n && q[(i + len) % n] > v) continue; // the run keeps rising: not a peak
			int mid = (i + (len - 1) / 2) % n;
			// prominence: walk each way until the profile rises above this peak; the deepest wall passed on the way is that side's valley
			double lmin = v, rmin = v;
			boolean lHigher = false, rHigher = false;
			for (int k = 1; k < n; k++) { double w = q[(i - k + n * 2) % n]; if (w > v) { lHigher = true; break; } lmin = Math.min(lmin, w); }
			for (int k = 1; k < n; k++) { double w = q[(i + k) % n]; if (w > v) { rHigher = true; break; } rmin = Math.min(rmin, w); }
			double key = lHigher && rHigher ? Math.max(lmin, rmin) : lHigher ? lmin : rHigher ? rmin : gmin;
			double prom = v - key;
			if (prom < Math.max(6, 0.5 * key)) continue; // an alcove or a bulge in the wall is not a route
				double mouth = v; // where the route opens: the nearest wall within 20 degrees either side of it
			for (int k = -n / 18; k <= n / 18; k++) mouth = Math.min(mouth, q[(i + k + n) % n]);
			// how wide the opening is: the rays reaching past halfway between the walls and the far end, as blocks across at the opening
			double half = key + prom / 2;
			int wr = len;
			for (int k = 1; k < n / 4 && q[(i - k + n) % n] >= half; k++) wr++;
			for (int k = len; k < n / 4 + len && q[(i + k) % n] >= half; k++) wr++;
			double width = wr * 2 * Math.PI / n * Math.max(1.0, mouth);
			found.add(new double[]{mid * 2 * Math.PI / n, v, prom, mouth, el[mid], width});
		}
		found.sort((a, b) -> Double.compare(b[2], a[2]));
		List<double[]> out = new ArrayList<>();
		outer:
		for (double[] pk : found) { // a wide opening (or a plateau at full range) is one route, not one per ray
			for (double[] o : out) if (Math.abs(Math.atan2(Math.sin(pk[0] - o[0]), Math.cos(pk[0] - o[0]))) < Math.toRadians(20)) continue outer;
			out.add(pk);
			if (out.size() >= 8) break;
		}
		return out;
	}
}
