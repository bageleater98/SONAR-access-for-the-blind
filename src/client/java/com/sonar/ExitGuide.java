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
	private static final int R = 16;
	private static final int NONE = Integer.MIN_VALUE;

	private static boolean door(BlockState s) {
		return s.is(BlockTags.DOORS);
	}

	private static boolean passable(Minecraft mc, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		BlockState s = mc.level.getBlockState(pos.set(x, y, z));
		if (door(s)) return true; // a door is a way through, open or shut
		return s.getCollisionShape(mc.level, pos).isEmpty() && !(s.getBlock() instanceof LiquidBlock);
	}

	/** Floor y when stepping onto column x,z from a floor at ref (up 1, down 3); NONE when blocked. */
	private static int stepTo(Minecraft mc, int x, int z, int ref, BlockPos.MutableBlockPos pos) {
		for (int y = ref + 1; y >= ref - 3; y--) {
			BlockState s = mc.level.getBlockState(pos.set(x, y, z));
			if (s.getBlock() instanceof LiquidBlock) return NONE;
			if (door(s)) continue; // look through to the floor under the doorway
			if (s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || s.is(BlockTags.FENCE_GATES) || s.is(net.minecraft.world.level.block.Blocks.IRON_BARS)) return NONE;
			if (!s.getCollisionShape(mc.level, pos).isEmpty()) {
				return passable(mc, x, y + 1, z, pos) && passable(mc, x, y + 2, z, pos) ? y : NONE;
			}
		}
		return NONE;
	}

	/** True when nothing solid (leaves excepted) is above this cell within 20 blocks: open sky, not a roof. */
	private static boolean open(Minecraft mc, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		for (int yy = y + 3; yy <= y + 22; yy++) {
			BlockState s = mc.level.getBlockState(pos.set(x, yy, z));
			if (s.is(BlockTags.LEAVES)) continue;
			if (!s.getCollisionShape(mc.level, pos).isEmpty()) return false;
		}
		return true;
	}

	/** A straight line from your eyes to a point above that floor cell is clear of solid blocks. */
	private static boolean visible(Minecraft mc, Scan s, int x, int floorY, int z, BlockPos.MutableBlockPos pos) {
		var from = new net.minecraft.world.phys.Vec3(s.ex, s.ey, s.ez);
		var to = new net.minecraft.world.phys.Vec3(x + 0.5, floorY + 1.6, z + 0.5);
		var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
	}

	/** Echoes {bearing, distance, volume factor, priority 0 = nearest way out, 1 = staircase, 2 = other ways out, entrance 0/1, elevation}: every way out you can see (up to 8) plus the nearest stairs up and down. */
	static List<double[]> find(Minecraft mc, Scan s) {
		List<double[]> out = new ArrayList<>();
		var pos = new BlockPos.MutableBlockPos();
		int px = s.px, pz = s.pz, W = 2 * R + 1;
		int f0 = stepTo(mc, px, pz, s.feetY - 1, pos);
		if (f0 == NONE) return out;
		boolean inside = !open(mc, px, f0, pz, pos);
		int[] floor = new int[W * W], dist = new int[W * W];
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
				boolean isDoor = door(mc.level.getBlockState(pos.set(c[0], cy + 1, c[1])));
				boolean sky = inside && open(mc, c[0], cy, c[1], pos);
				if ((isDoor || sky) && visible(mc, s, c[0], cy, c[1], pos)) { // only ways out you could see from here: no ticks from behind walls
					exitCells.add(new double[]{c[0], c[1], steps, cy});
				}
				if (inside && Math.abs(cy - f0) >= 3 && visible(mc, s, c[0], cy, c[1], pos)) {
					if (cy > f0 && up == null) up = new double[]{c[0], c[1], steps};
					if (cy < f0 && down == null) down = new double[]{c[0], c[1], steps};
				}
				if (isDoor || sky) continue; // do not explore beyond a way out: the first one in each direction is what matters
			}
			for (int k = 0; k < 4; k++) {
				int nx = c[0] + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = c[1] + (k == 2 ? 1 : k == 3 ? -1 : 0);
				int dx = nx - px, dz = nz - pz;
				if (dx * dx + dz * dz > R * R) continue;
				int ni = (dz + R) * W + (dx + R);
				if (dist[ni] != -1) continue;
				int nf = stepTo(mc, nx, nz, cy, pos);
				if (nf == NONE) { dist[ni] = -2; continue; }
				dist[ni] = steps + 1; floor[ni] = nf;
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
		for (int i = 0; i < openings.size() && i < Cues.MAX_ECHOES; i++) {
			double[] e = openings.get(i).get(0); // cells were added nearest first
			double[] en = entry(s, fwdA, e[0] + 0.5, e[1] + 0.5, i == 0 ? 0 : 2);
			en[4] = 1; // a way out is an entrance: the nearest one plays last
			out.add(en);
		}
		// a staircase is somewhere you can go too: it competes with the ways out for the last place in the sequence (the nearest of them all plays last)
		if (up != null) { double[] en = entry(s, fwdA, up[0] + 0.5, up[1] + 0.5, 1); en[4] = 1; out.add(en); }
		if (down != null) { double[] en = entry(s, fwdA, down[0] + 0.5, down[1] + 0.5, 1); en[4] = 1; out.add(en); }
		return out;
	}

	private static double[] entry(Scan s, double fwdA, double x, double z, int prio) {
		double dx = x - s.ex, dz = z - s.ez, ang = Math.atan2(dz, dx);
		double rel = Math.abs(Math.atan2(Math.sin(ang - fwdA), Math.cos(ang - fwdA)));
		return new double[]{ang, Math.hypot(dx, dz), 1.0 - 0.5 * rel / Math.PI, prio, 0, 0}; // {angle, distance, volume (ahead 1.0, sides 0.75, behind 0.5), priority, entrance, elevation}
	}
}
