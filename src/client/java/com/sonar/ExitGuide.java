package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The echo's guide through buildings: finds the ways out of the space you are walking in by following the floor you can actually walk on (not by shooting rays at walls).
 * Doors and open ways to the outside sound from their direction, and so do staircases up and down. Plain walls, glass included, never sound.
 */
public class ExitGuide {
	private static final int R = 24; // big halls and domes: the room test reaches 32 blocks, the floor walk must reach their doors too
	private static final int NONE = Integer.MIN_VALUE;

	private static boolean door(BlockState s) {
		return s.is(BlockTags.DOORS);
	}

	/** True when nothing solid (leaves excepted) is above this cell within 40 blocks: open sky, not a roof (a tall hall or dome still has its roof). */

	static boolean open(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		for (int yy = y + 3; yy <= y + 42; yy++) {
			BlockState s = w.getBlockState(pos.set(x, yy, z));
			if (s.is(BlockTags.LEAVES)) continue;
			if (!s.getCollisionShape(w, pos).isEmpty()) return false;
		}
		return true;
	}

	/**
	 * How much floor you can walk on under the roof from this doorway cell (the cell included), counting up to `cap`. A way in must lead into a space: a hole a block or
	 * two deep in a wall or a hillside is roofed and walled, but there is nowhere to go (round 10: a nook under a stone staircase echoed as a doorway).
	 */
	static int roofedSpace(net.minecraft.world.level.BlockGetter w, int x, int y, int z, int cap) {
		var pos = new BlockPos.MutableBlockPos();
		java.util.Map<Long, Integer> seen = new java.util.HashMap<>();
		var q = new ArrayDeque<int[]>();
		seen.put(BlockPos.asLong(x, 0, z), y); q.add(new int[]{x, y, z});
		while (!q.isEmpty() && seen.size() < cap) {
			int[] c = q.poll();
			for (int k = 0; k < 4; k++) {
				int nx = c[0] + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = c[2] + (k == 2 ? 1 : k == 3 ? -1 : 0);
				long key = BlockPos.asLong(nx, 0, nz);
				if (seen.containsKey(key) || Math.abs(nx - x) > 16 || Math.abs(nz - z) > 16) continue;
				int nf = BlockRules.stepTo(w, nx, nz, c[1], pos);
				if (nf == NONE || open(w, nx, nf, nz, pos)) continue; // a wall, or back out under the sky
				seen.put(key, nf); q.add(new int[]{nx, nf, nz});
			}
		}
		return seen.size();
	}

	/**
	 * The middle of the roofed floor you can walk on from this doorway cell (up to `cap` cells): {x, floor y, z}. A building is walled all round there; a gazebo or a
	 * carport, whose cells next to a post can look walled from the edge, is open all round at its middle.
	 */
	static int[] roofedCenter(net.minecraft.world.level.BlockGetter w, int x, int y, int z, int cap) {
		var pos = new BlockPos.MutableBlockPos();
		java.util.Map<Long, Integer> seen = new java.util.HashMap<>();
		var q = new ArrayDeque<int[]>();
		seen.put(BlockPos.asLong(x, 0, z), y); q.add(new int[]{x, y, z});
		long sx = x, sz = z; int n = 1, best = y;
		while (!q.isEmpty() && n < cap) {
			int[] c = q.poll();
			for (int k = 0; k < 4; k++) {
				int nx = c[0] + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = c[2] + (k == 2 ? 1 : k == 3 ? -1 : 0);
				long key = BlockPos.asLong(nx, 0, nz);
				if (seen.containsKey(key) || Math.abs(nx - x) > 16 || Math.abs(nz - z) > 16) continue;
				int nf = BlockRules.stepTo(w, nx, nz, c[1], pos);
				if (nf == NONE || open(w, nx, nf, nz, pos)) continue;
				seen.put(key, nf); q.add(new int[]{nx, nf, nz});
				sx += nx; sz += nz; n++;
			}
		}
		int cx = (int) Math.round((double) sx / n), cz = (int) Math.round((double) sz / n);
		Integer f = seen.get(BlockPos.asLong(cx, 0, cz));
		if (f == null) { // the middle is not a floor cell (a post, a pillar): the nearest cell that is
			long bd = Long.MAX_VALUE; cx = x; cz = z; f = y;
			for (var e : seen.entrySet()) {
				int ex = BlockPos.getX(e.getKey()), ez = BlockPos.getZ(e.getKey());
				long d = (long) (ex - sx / n) * (ex - sx / n) + (long) (ez - sz / n) * (ez - sz / n);
				if (d < bd) { bd = d; cx = ex; cz = ez; f = e.getValue(); }
			}
		}
		return new int[]{cx, f, cz};
	}

	/** A straight line from your eyes to a point above that floor cell is clear of solid blocks. */
	private static boolean visible(Scan s, int x, int floorY, int z, BlockPos.MutableBlockPos pos) {
		var from = new net.minecraft.world.phys.Vec3(s.ex, s.ey, s.ez);
		var to = new net.minecraft.world.phys.Vec3(x + 0.5, floorY + 1.6, z + 0.5);
		var hit = s.world().clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, s.collision()));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
	}

	/** Echoes {bearing, distance, volume factor, priority 0 = nearest way out, 1 = staircase, 2 = other ways out, entrance 0/1, elevation}: every way out you can see (up to 8) plus the nearest stairs up and down. */
	/** (mc may be null: the self-test; it is only used for debug output.) The world is the scan's (as Sonar hears it). */
	static List<double[]> find(Minecraft mc, Scan s) {
		List<double[]> out = new ArrayList<>();
		var pos = new BlockPos.MutableBlockPos();
		int px = s.px, pz = s.pz, W = 2 * R + 1;
		int f0 = BlockRules.stepTo(s.world(), px, pz, s.feetY - 1, pos);
		if (f0 == NONE) return out;
		boolean inside = !open(s.world(), px, f0, pz, pos);
		int[] floor = new int[W * W], dist = new int[W * W], parent = new int[W * W];
		java.util.Arrays.fill(dist, -1);
		var q = new ArrayDeque<int[]>();
		int si = R * W + R;
		dist[si] = 0; floor[si] = f0;
		q.add(new int[]{px, pz});
		double yaw = Math.toRadians(s.yaw), fwdA = Math.atan2(Math.cos(yaw), -Math.sin(yaw));
		List<double[]> exitCells = new ArrayList<>(); // every way-out cell you can see: {x, z, steps, floor y}
		double[] up = null, down = null; // nearest staircase going up / down: {x, z, steps}
		while (!q.isEmpty()) {
			int[] c = q.poll();
			int ci = (c[1] - pz + R) * W + (c[0] - px + R), cy = floor[ci], steps = dist[ci];
			if (steps > 0) {
				boolean isDoor = door(s.world().getBlockState(pos.set(c[0], cy + 1, c[1])));
				boolean sky = inside && open(s.world(), c[0], cy, c[1], pos);
				// ways out you can see, and ones just round a corner: a short walk (12 steps at most) that is not much longer than the straight line. Those play a little quieter.
				boolean seen = visible(s, c[0], cy, c[1], pos);
				boolean corner = !seen && steps <= 32; // round a corner or two: up to 32 steps away by walking
				if ((isDoor || sky) && (seen || corner)) exitCells.add(new double[]{c[0], c[1], steps, cy, seen ? 1 : 0});
				if (inside && Math.abs(cy - f0) >= 3 && (seen || corner)) {
					if (cy > f0 && up == null) up = new double[]{c[0], c[1], steps, seen ? 1 : 0};
					if (cy < f0 && down == null) down = new double[]{c[0], c[1], steps, seen ? 1 : 0};
				}
				if (isDoor || sky) continue; // do not explore beyond a way out: the first one in each direction is what matters
			}
			for (int k = 0; k < 4; k++) {
				int nx = c[0] + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = c[1] + (k == 2 ? 1 : k == 3 ? -1 : 0);
				int dx = nx - px, dz = nz - pz;
				if (dx * dx + dz * dz > R * R) continue;
				int ni = (dz + R) * W + (dx + R);
				if (dist[ni] != -1) continue;
				int nf = BlockRules.stepTo(s.world(), nx, nz, cy, pos);
				if (nf == NONE) { dist[ni] = -2; continue; }
				dist[ni] = steps + 1; floor[ni] = nf; parent[ni] = ci;
				q.add(new int[]{nx, nz});
			}
		}
		// group the way-out cells into openings: cells within 2 blocks of each other are one opening (a double door, a wide open side). Each opening echoes once, from its nearest cell.
		// Two doors in the same wall are two openings however close their directions are; there is no cap of 3 any more: up to 8, nearest (by walking) first.
		exitCells.sort((a, b) -> Double.compare(a[2], b[2]));
		List<List<double[]>> openings = new ArrayList<>();
		for (double[] e : exitCells) {
			List<double[]> home = null;
			for (List<double[]> o : openings) {
				for (double[] m : o) if (Math.abs(m[0] - e[0]) <= 2 && Math.abs(m[1] - e[1]) <= 2 && Math.abs(m[3] - e[3]) <= 2) { home = o; break; }
				if (home != null) break;
			}
			if (home == null) { home = new ArrayList<>(); openings.add(home); }
			home.add(e);
		}
		for (int i = 0; i < openings.size() && i < Echo.MAX_ECHOES; i++) {
			double[] e = openings.get(i).get(0); // cells were added nearest first
			for (double[] m : openings.get(i)) if (m[4] == 1) { e = m; break; } // sound from the part of the opening you can see, if any
			double[] en = entry(s, fwdA, e[0] + 0.5, e[1] + 0.5, i == 0 ? 0 : 2);
			if (e[4] == 0) en = Echo.hidden(aroundCorner(s, en, parent, floor, W, R, px, pz, (int) e[0], (int) e[1], fwdA)); // round a corner: out of sight, heard from the corner you would turn at
			en[4] = 1; // a way out is an entrance: the nearest one plays last
			out.add(en);
		}
		// a staircase is somewhere you can go too: it competes with the ways out for the last place in the sequence (the nearest of them all plays last)
		if (up != null) { double[] en = entry(s, fwdA, up[0] + 0.5, up[1] + 0.5, 1); en[4] = 1; if (up[3] == 0) en = Echo.hidden(en); out.add(en); }
		if (down != null) { double[] en = entry(s, fwdA, down[0] + 0.5, down[1] + 0.5, 1); en[4] = 1; if (down[3] == 0) en = Echo.hidden(en); out.add(en); }
		return out;
	}

	/**
	 * Outside: the ways INTO buildings near you, found by walking. The floor is walked out to 32 blocks from you; where it passes from open sky to under a roof, and the space
	 * under that roof is a room, that spot is an entrance, whether or not you can see into it (an entry a step up, a dark doorway, a tall glass hall behind it).
	 * Returns echoes {bearing, distance, volume, priority 1, entrance 1, elevation 0}, nearest first, at most 4.
	 */
	static List<double[]> entrances(Minecraft mc, Scan s) {
		final int RR = Tuning.ENTRANCE_RANGE, W = 2 * RR + 1;
		List<double[]> out = new ArrayList<>();
		var pos = new BlockPos.MutableBlockPos();
		int px = s.px, pz = s.pz;
		int f0 = BlockRules.stepTo(s.world(), px, pz, s.feetY - 1, pos);
		if (f0 == NONE) return out;
		// only from outside, and a roof with no room under it (a gazebo, a carport, a porch) is still outside: its cells are walked through, not taken for a way in
		boolean under = !open(s.world(), px, f0, pz, pos);
		if (under && Rooms.roomAt(s.world(), new net.minecraft.world.phys.Vec3(s.ex, s.ey, s.ez))) return out;
		int[] floor = new int[W * W], dist = new int[W * W], parent = new int[W * W];
		java.util.Arrays.fill(dist, -1);
		var q = new ArrayDeque<int[]>();
		dist[RR * W + RR] = 0; floor[RR * W + RR] = f0;
		q.add(new int[]{px, pz});
		double yaw = Math.toRadians(s.yaw), fwdA = Math.atan2(Math.cos(yaw), -Math.sin(yaw));
		List<int[]> found = new ArrayList<>();
		while (!q.isEmpty()) {
			int[] c = q.poll();
			int ci = (c[1] - pz + RR) * W + (c[0] - px + RR), cy = floor[ci];
			for (int k = 0; k < 4; k++) {
				int nx = c[0] + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = c[1] + (k == 2 ? 1 : k == 3 ? -1 : 0);
				if (Math.abs(nx - px) > RR || Math.abs(nz - pz) > RR) continue;
				int ni = (nz - pz + RR) * W + (nx - px + RR);
				if (dist[ni] != -1) continue;
				int nf = BlockRules.stepTo(s.world(), nx, nz, cy, pos);
				if (nf == NONE) { dist[ni] = -2; continue; }
				dist[ni] = dist[ci] + 1; floor[ni] = nf; parent[ni] = ci;
				if (!open(s.world(), nx, nf, nz, pos) && !(under && !Rooms.roomAt(s.world(), new net.minecraft.world.phys.Vec3(nx + 0.5, nf + 1.6, nz + 0.5)))) { // stepped from sky to under a roof: a way in, if a room lies behind it. Not walked further.
					boolean near = false;
					for (int[] f : found) if (Math.abs(f[0] - nx) <= 2 && Math.abs(f[1] - nz) <= 2) near = true;
					if (!near) found.add(new int[]{nx, nz, nf, dist[ni]});
					continue;
				}
				q.add(new int[]{nx, nz});
			}
		}
		found.sort((a, b) -> Integer.compare(a[3], b[3]));
		int tested = 0;
		for (int[] f : found) {
			if (++tested > 12) break; // each room test is ~20 rays: only the 12 nearest roof edges (trees and porches give many) are tried
			if (roofedSpace(s.world(), f[0], f[2], f[1], Tuning.ENTRANCE_MIN_SPACE) < Tuning.ENTRANCE_MIN_SPACE) continue; // a nook in a wall leads nowhere
			// look a couple of steps further in (the doorway itself is half outside) for the room
			boolean room = false;
			for (int in = 0; in <= 2 && !room; in++) {
				double ax = f[0] + 0.5 - s.ex, az = f[1] + 0.5 - s.ez, al = Math.max(1e-6, Math.hypot(ax, az));
				room = Rooms.roomAt(s.world(), new net.minecraft.world.phys.Vec3(f[0] + 0.5 + ax / al * in, f[2] + 2.6, f[1] + 0.5 + az / al * in));
			}
			if (room) { // and its middle is walled too: the edge of a gazebo, next to a post, can look walled
				int[] mid = roofedCenter(s.world(), f[0], f[2], f[1], 40);
				room = Rooms.roomAt(s.world(), new net.minecraft.world.phys.Vec3(mid[0] + 0.5, mid[1] + 2.6, mid[2] + 0.5));
			}
			if (!room) continue; // a porch, a carport, a gazebo, a tree: roofed but not a room
			double[] en = entry(s, fwdA, f[0] + 0.5, f[1] + 0.5, 1);
			en[4] = 1;
			en[5] = Math.atan2(f[2] + 1.6 - s.ey, Math.max(1, en[1])); // a house below you (or up a hill) echoes from down there (or up there)
			// out of sight (round the corner of the house): quieter, like the ways out round a corner inside
			var eyeV = new net.minecraft.world.phys.Vec3(s.ex, s.ey, s.ez);
			var doorV = new net.minecraft.world.phys.Vec3(f[0] + 0.5, f[2] + 1.6, f[1] + 0.5);
			boolean hidden = s.world().clip(new net.minecraft.world.level.ClipContext(eyeV, doorV, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, s.collision())).getType() != net.minecraft.world.phys.HitResult.Type.MISS;
			if (hidden) en = Echo.hidden(aroundCorner(s, en, parent, floor, W, RR, px, pz, f[0], f[1], fwdA));
			out.add(en);
			if (SonarConfig.cur.debug && mc != null && mc.player != null) {
				String roofBlock = "none";
				for (int y = f[2] + 2; y <= f[2] + 12; y++) {
					var b = s.world().getBlockState(pos.set(f[0], y, f[1]));
					if (!b.getCollisionShape(s.world(), pos).isEmpty()) { roofBlock = b.getBlock().getDescriptionId() + (b.is(net.minecraft.tags.BlockTags.LEAVES) ? " (leaves)" : BlockRules.treeLog(s.world(), b, f[0], y, f[1]) ? " (tree)" : ""); break; }
				}
				Dbg.send(mc.player, net.minecraft.network.chat.Component.literal("entrance echo at " + f[0] + "," + (f[2] + 1) + "," + f[1] + " (" + f[3] + " steps, " + (hidden ? "out of sight" : "in sight") + ", roof " + roofBlock + ")"));
			}
			if (out.size() >= 4) break;
		}
		return out;
	}

	/**
	 * A way you can not see is heard from where you would TURN to reach it, not straight through the walls: the walking route to it is followed from you, and the
	 * echo comes from the last cell of it that is still in view (the corner). Its loudness and distance stay those of the way itself.
	 */
	static double[] aroundCorner(Scan s, double[] en, int[] parent, int[] floor, int W, int R, int px, int pz, int tx, int tz, double fwdA) {
		int start = R * W + R, at = (tz - pz + R) * W + (tx - px + R);
		java.util.ArrayList<Integer> path = new java.util.ArrayList<>();
		for (int guard = 0; at != start && guard < W * W; guard++) { path.add(at); at = parent[at]; }
		java.util.Collections.reverse(path);
		var pos = new BlockPos.MutableBlockPos();
		int corner = -1;
		for (int k = 0; k < path.size(); k++) {
			int idx = path.get(k), cx = px + idx % W - R, cz = pz + idx / W - R;
			if (!visible(s, cx, floor[idx], cz, pos)) break;
			corner = k;
		}
		if (corner < 0 && !path.isEmpty()) corner = 0; // not even the first step is in view: that step is where it comes from
		if (corner < 0) return en;
		int idx = path.get(corner), cx = px + idx % W - R, cz = pz + idx / W - R;
		double[] near = entry(s, fwdA, cx + 0.5, cz + 0.5, (int) en[3]);
		double[] out = en.clone();
		out[0] = near[0]; // the direction of the corner...
		out[2] = near[2]; // ...and the loudness for that direction; the distance (how far the way is) stays
		return out;
	}

	private static double[] entry(Scan s, double fwdA, double x, double z, int prio) {
		double dx = x - s.ex, dz = z - s.ez, ang = Math.atan2(dz, dx);
		double rel = Math.abs(Math.atan2(Math.sin(ang - fwdA), Math.cos(ang - fwdA)));
		return new double[]{ang, Math.hypot(dx, dz), Echo.facingCurve(rel), prio, 0, 0}; // {angle, distance, volume (ahead 1.0, side about half, behind a quarter), priority, entrance, elevation}
	}
}
