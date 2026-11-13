package com.sonar;

import java.nio.file.Path;
import java.util.*;

final class R18Explore {
	static void run() throws Exception {
		World w = new World();
		double[] me = World.load(Path.of("src/selftest/spots/r18-cave3.txt"), w);
		var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		int f0 = BlockRules.floorUnder(w, me[0], me[1], me[2], pos);
		System.out.println("  player " + me[0] + "," + me[1] + "," + me[2] + " floor " + f0);
		// flood fill by the walking rule (steps up 1, down per stepTo), 4 neighbours + diagonals ignored
		List<int[]> all = new ArrayList<>(); Map<Long, Integer> seen = new HashMap<>();
		ArrayDeque<int[]> q = new ArrayDeque<>();
		int sx = (int) Math.floor(me[0]), sz = (int) Math.floor(me[2]);
		q.add(new int[]{sx, f0, sz}); seen.put(((long) sx << 32) ^ (sz & 0xffffffffL) ^ ((long) f0 << 50), f0);
		Set<Long> cells = new HashSet<>(); cells.add(((long) sx * 100003L + sz) * 1000 + f0);
		int minY = f0, maxY = f0, n = 0, minZ = sz, maxZ = sz, minX = sx, maxX = sx;
		Map<Integer, Integer> blocked = new TreeMap<>();
		int maxDrop = 0; Map<Integer,Integer> drops = new TreeMap<>();
		while (!q.isEmpty() && n < 200000) {
			int[] c = q.poll(); n++;
			minY = Math.min(minY, c[1]); maxY = Math.max(maxY, c[1]); minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0]); minZ = Math.min(minZ, c[2]); maxZ = Math.max(maxZ, c[2]);
			for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
				int nx = c[0] + d[0], nz = c[2] + d[1];
				int nf = BlockRules.stepTo(w, nx, nz, c[1], pos);
				if (nf == BlockRules.NONE) {
					// what is there: a floor higher (wall) than we can step?
					for (int dy = 2; dy <= 8; dy++) { int f = BlockRules.stepTo(w, nx, nz, c[1] + dy, pos); if (f != BlockRules.NONE && Math.abs(f - c[1]) >= 2) { blocked.merge(f - c[1], 1, Integer::sum); break; } }
					continue;
				}
				long key = ((long) nx * 100003L + nz) * 1000 + nf;
				if (!cells.add(key)) continue;
				if (c[1] - nf >= 2) drops.merge(c[1] - nf, 1, Integer::sum);
				q.add(new int[]{nx, nf, nz}); all.add(new int[]{nx, nf, nz});
			}
		}
		int open = 0, wide = 0, highest = 0; int[] nearestOpen = null; double nd = 1e9;
		for (long k : cells) { }
		System.out.println("  walkable region: " + n + " cells, x " + minX + ".." + maxX + ", z " + minZ + ".." + maxZ + ", y " + minY + ".." + maxY);
		for (int[] c : all) {
			boolean under = BlockRules.caveRoof(w, new net.minecraft.world.phys.Vec3(c[0] + 0.5, c[1] + 1.5, c[2] + 0.5), 24);
			if (!under) {
				open++;
				boolean wd = true;
				for (int[] d : new int[][]{{3,0},{-3,0},{0,3},{0,-3}}) if (BlockRules.caveRoof(w, new net.minecraft.world.phys.Vec3(c[0] + d[0] + 0.5, c[1] + 1.5, c[2] + d[1] + 0.5), 24)) wd = false;
				if (wd) { wide++; double dd = Math.hypot(c[0] - me[0], c[2] - me[2]) + Math.abs(c[1] - me[1]); if (dd < nd) { nd = dd; nearestOpen = c; } }
			}
			highest = Math.max(highest, c[1]);
		}
		System.out.println("  of those: not under rock " + open + ", wide open " + wide + ", highest floor " + highest + ", nearest wide-open " + (nearestOpen == null ? "none" : nearestOpen[0] + "," + nearestOpen[1] + "," + nearestOpen[2]));
		System.out.println("  steps DOWN of 2+ inside the region (one-way): " + drops);
		System.out.println("  neighbouring floors it can NOT step onto, by height difference (dy -> count): " + blocked);
	}
}
