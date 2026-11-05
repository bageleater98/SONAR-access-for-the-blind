package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
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
		int hits = 0, rock = 0, up = 0, level = 0;
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
			if (dx == 0 && dy == 0 && dz == 0) continue;
			Vec3 d = new Vec3(dx, dy, dz).normalize();
			var hit = mc.level.clip(new ClipContext(eye, eye.add(d.scale(48)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() == HitResult.Type.MISS) continue;
			hits++;
			if (caveRock(mc.level.getBlockState(hit.getBlockPos()))) rock++;
			if (dy > 0) up++;
			if (dy == 0) level++;
		}
		boolean cave = hits >= 18 && rock >= 0.75 * hits && up >= 4 && level >= 5;
		if (SonarConfig.cur.debug && mc.player != null) {
			String m = String.format("cave test: %b (hits %d/26 need 18, rock %d need %d, above %d/9 need 4, level %d/8 need 5)", cave, hits, rock, (int) Math.ceil(0.75 * hits), up, level);
			if (!m.equals(lastMsg)) { lastMsg = m; mc.player.sendSystemMessage(Component.literal(m)); }
		}
		return cave;
	}

	static final int N = 120; // 3 degree steps all the way round
	private static final double[] ELEV = {-14, 0, 14}; // degrees: a passage that slopes a little up or down is still found

	/**
	 * Every route out of the space: the reach of the cave is measured all the way round (the longest of three slightly tilted rays per direction), one-ray cracks are ignored, and every
	 * direction that reaches clearly further than the walls around it (a peak that stands out: half again as deep as the walls beside it, and at least 6 blocks deeper) is a route.
	 * Returns {bearing (atan2 z,x), how far it goes, prominence, distance to the opening, elevation in radians}, most prominent first, at most 8.
	 */
	static List<double[]> routes(Minecraft mc, Scan s, double range) {
		Vec3 from = new Vec3(s.ex, s.ey, s.ez);
		double[] r = new double[N], el = new double[N];
		for (int i = 0; i < N; i++) {
			double a = i * 2 * Math.PI / N;
			for (double e : ELEV) {
				double er = Math.toRadians(e), c = Math.cos(er);
				double len = Cues.rayLen(mc, from, Math.cos(a) * c, Math.sin(er), Math.sin(a) * c, range, false);
				if (len > r[i]) { r[i] = len; el[i] = er; }
			}
		}
		List<double[]> out = peaks(r, el);
		if (SonarConfig.cur.debug && mc.player != null) {
			StringBuilder sb = new StringBuilder();
			double fwd = Math.atan2(Math.cos(Math.toRadians(s.yaw)), -Math.sin(Math.toRadians(s.yaw)));
			for (double[] o : out) sb.append(String.format("[%.0f deg, goes %.0f, opens at %.0f] ", Math.toDegrees(Math.atan2(Math.sin(o[0] - fwd), Math.cos(o[0] - fwd))), o[1], o[3]));
			mc.player.sendSystemMessage(Component.literal("cave routes (range " + (int) range + "): " + out.size() + " " + sb));
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
			found.add(new double[]{mid * 2 * Math.PI / n, v, prom, mouth, el[mid]});
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
