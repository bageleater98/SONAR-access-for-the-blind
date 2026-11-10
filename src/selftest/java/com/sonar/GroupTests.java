package com.sonar;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;

/** Round 15: one rule for every block sound. Touching blocks with the same sound are a group; every 3 heard blocks of it give one sound, spread evenly across it. */
final class GroupTests {
	private GroupTests() {}

	static final BlockState STONE = Blocks.STONE.defaultBlockState(), DIRT = Blocks.DIRT.defaultBlockState(), LEAVES = Blocks.OAK_LEAVES.defaultBlockState();

	static int[] groups(World w, int[][] heard) {
		return Cues.groupsOf(w, heard, st -> Cues.groupKey(st, true), 0.5, 65.6, 0.5, 18);
	}

	static void run(java.util.function.BiConsumer<Boolean, String> check) {
		// the count: 1-3 heard blocks one sound, 4-6 two, 7-9 three, no limit
		check.accept(Cues.soundsFor(0) == 0 && Cues.soundsFor(1) == 1 && Cues.soundsFor(3) == 1 && Cues.soundsFor(4) == 2 && Cues.soundsFor(6) == 2 && Cues.soundsFor(7) == 3
			&& Cues.soundsFor(30) == 10, "one sound for every 3 heard blocks of a group (1-3: 1, 4-6: 2, 7-9: 3, 30: 10)");

		// two separate trees (not touching): two groups, even though they are the same block
		World trees = new World().fill(-5, 66, 4, -3, 68, 6, LEAVES).fill(3, 66, 4, 5, 68, 6, LEAVES);
		int[] g1 = groups(trees, new int[][]{{-4, 67, 4}, {-3, 66, 4}, {4, 67, 4}, {3, 68, 4}});
		check.accept(g1[0] == g1[1] && g1[2] == g1[3] && g1[0] != g1[2], "two separate trees: two groups " + Arrays.toString(g1));

		// a wall whose heard blocks lie a few apart: one group, joined through the blocks between them
		World wall = new World().fill(-8, 64, 6, 8, 67, 6, STONE);
		int[] g2 = groups(wall, new int[][]{{-7, 65, 6}, {-3, 66, 6}, {1, 64, 6}, {5, 67, 6}});
		check.accept(g2[0] == g2[1] && g2[1] == g2[2] && g2[2] == g2[3], "a wall heard in spots a few blocks apart: one group " + Arrays.toString(g2));

		// stone touching dirt: different sounds, different groups
		World mixed = new World().fill(-3, 64, 5, 0, 66, 5, STONE).fill(1, 64, 5, 3, 66, 5, DIRT);
		int[] g3 = groups(mixed, new int[][]{{-2, 65, 5}, {2, 65, 5}});
		check.accept(g3[0] != g3[1], "stone against dirt: separate groups (different sounds)");

		// a tree standing on a grassy hill: its leaves touch the grass (the very same sound) but a tree is not the hill (round 15)
		World hillTree = new World().fill(-6, 60, 2, 6, 64, 10, Blocks.GRASS_BLOCK.defaultBlockState()).fill(-2, 65, 5, 2, 66, 7, LEAVES);
		int[] g6 = groups(hillTree, new int[][]{{0, 65, 5}, {-5, 64, 3}});
		// grass and dirt are both ground: a hill of grass on dirt is one group
		World ground = new World().fill(-4, 62, 4, 4, 63, 6, DIRT).fill(-4, 64, 4, 4, 64, 6, Blocks.GRASS_BLOCK.defaultBlockState());
		int[] g7 = groups(ground, new int[][]{{0, 64, 4}, {3, 62, 4}});
		check.accept(g6[0] != g6[1] && g7[0] == g7[1], "a tree on a grassy hill is not part of the hill (leaves and grass sound the same); grass and dirt are one ground");

		// touching only at a corner: separate (face to face only)
		World corner = new World().fill(0, 65, 5, 0, 65, 5, STONE).fill(1, 66, 6, 1, 66, 6, STONE);
		int[] g4 = groups(corner, new int[][]{{0, 65, 5}, {1, 66, 6}});
		check.accept(g4[0] != g4[1], "two blocks touching only at a corner: separate groups");

		// spread: 9 heard blocks across in front, 3 sounds: the middle of each third (left, middle, right), not the 3 nearest together
		double[] nine = new double[9];
		for (int i = 0; i < 9; i++) nine[i] = 1.0 + i * 0.1;
		int[] p = Cues.spread(nine, 3);
		Arrays.sort(p);
		check.accept(p.length == 3 && p[0] <= 2 && p[1] >= 3 && p[1] <= 5 && p[2] >= 6, "a group's sounds spread across it: one each end, one in the middle " + Arrays.toString(p));

		// a group across straight behind you (bearings either side of the -pi/pi seam): still one even sweep
		double[] behind = {3.0, 3.1, -3.1, -3.0, -2.9, -2.8};
		int[] q = Cues.spread(behind, 2);
		boolean firstHalf = false, secondHalf = false;
		for (int i : q) { if (i <= 2) firstHalf = true; else secondHalf = true; }
		check.accept(q.length == 2 && firstHalf && secondHalf, "a group across straight behind you: spread across it, not bunched " + Arrays.toString(q));

		// bunched: 10 heard blocks in a dense canopy on the left, 2 more of the group far round on the right, 4 sounds: the right side gets its share
		// (round 15: by count, nearly every sound went to the bunch on the left)
		double[] bunch = {-1.50, -1.48, -1.46, -1.44, -1.42, -1.40, -1.38, -1.36, -1.34, -1.32, 0.6, 1.4};
		int[] r = Cues.spread(bunch, 4);
		int rightSide = 0;
		for (int i : r) if (bunch[i] > 0) rightSide++;
		check.accept(rightSide >= 2, "a group bunched on one side: its sounds still spread across all of it by direction (" + rightSide + " of 4 on the far side)");

		// a canopy straight over your head (many heard leaves up there) and the same trees' leaves all round you at head height: 3 sounds go round you, not up
		double[] cb = new double[14], ce = new double[14], cd = new double[14];
		for (int i = 0; i < 10; i++) { cb[i] = i * 0.6; ce[i] = 1.45; cd[i] = 2.2; }            // overhead: all directions, nearly straight up, close
		double[] side = {-2.0, 0.0, 2.0, 3.1};
		for (int i = 0; i < 4; i++) { cb[10 + i] = side[i]; ce[10 + i] = 0.1; cd[10 + i] = 5; }  // round you at head height
		int[] cp = Cues.spread(cb, ce, cd, 3);
		int up = 0;
		for (int i : cp) if (i < 10) up++;
		check.accept(up <= 1, "a canopy straight overhead counts as one direction: the group's other sounds go to the leaves round you (" + up + " of 3 overhead)");

		// near beats far in much the same direction: a block of the wall right beside you, and a far part of it just a little round
		int[] nf = Cues.spread(new double[]{0.0, 0.05, 2.0}, new double[3], new double[]{1.5, 12, 6}, 2);
		check.accept(Arrays.stream(nf).anyMatch(i -> i == 0) && Arrays.stream(nf).noneMatch(i -> i == 1), "a block right beside you is never passed over for a far part of the same thing in that direction");

		// built against natural: a stone-brick house against a stone hillside is not the hillside
		World house = new World().fill(-6, 60, 8, 6, 70, 12, STONE).fill(-3, 64, 6, 3, 67, 7, Blocks.STONE_BRICKS.defaultBlockState());
		int[] g8 = groups(house, new int[][]{{0, 65, 6}, {-5, 69, 8}});
		check.accept(g8[0] != g8[1], "a stone-brick wall against a natural stone hillside: separate groups (built is not natural)");

		// the limit shared fairly: 20 sounds on your left (a lake), 3 on the right, 2 in front, 1 ledge warning; a limit of 8 keeps the warning, both in front and all 3 on the right
		int n = 26;
		double[] rel = new double[n], dd = new double[n];
		int[] pr = new int[n];
		for (int i = 0; i < 20; i++) { rel[i] = -1.6; dd[i] = 2 + i; pr[i] = 1; }
		for (int i = 20; i < 23; i++) { rel[i] = 1.5; dd[i] = 20 + i; pr[i] = 1; }
		rel[23] = 0.1; dd[23] = 30; pr[23] = 1; rel[24] = -0.2; dd[24] = 35; pr[24] = 1;
		rel[25] = 2.5; dd[25] = 3; pr[25] = 0;
		int[] fo = Cues.fairOrder(rel, dd, pr);
		java.util.Set<Integer> kept = new java.util.HashSet<>();
		for (int i = 0; i < 8; i++) kept.add(fo[i]);
		check.accept(fo[0] == 25 && kept.containsAll(java.util.List.of(20, 21, 22, 23, 24)) && fo.length == n && java.util.Arrays.stream(fo).distinct().count() == n,
			"the sound limit is shared round you: a busy side (a lake on the left) can not take it all; warnings always first");

		// a lake is one group (round 15: each bit of water heard was its own sound, and a lake took 20 of 32)
		World lake = new World().fill(-10, 62, 3, 10, 63, 14, Blocks.WATER.defaultBlockState());
		int[] gl = groups(lake, new int[][]{{-8, 63, 4}, {0, 63, 10}, {8, 63, 13}, {3, 62, 6}});
		check.accept(java.util.Arrays.stream(gl).distinct().count() == 1, "a lake is one group: one sound per 3 bits of it heard " + java.util.Arrays.toString(gl) + " " + Cues.groupKey(Blocks.WATER.defaultBlockState(), true));

		// all heard, or none
		check.accept(Cues.spread(new double[]{0.1, 0.2}, 5).length == 2 && Cues.spread(new double[0], 1).length == 0, "spread: never more sounds than blocks, nothing from nothing");

		// a solid hillside all round you (every block stone within range): quick enough to run every ping
		World hill = new World().fill(-18, 47, -18, 18, 64, 18, STONE);
		int[][] heard = new int[48][];
		for (int i = 0; i < 48; i++) heard[i] = new int[]{(int) Math.round(Math.cos(i * 0.13) * 6), 64, (int) Math.round(Math.sin(i * 0.13) * 6)};
		long t0 = System.nanoTime();
		int[] gh = groups(hill, heard);
		double ms = (System.nanoTime() - t0) / 1e6;
		// the slow case: a big stone mass and a small separate stone pillar (the fill can not stop early, it runs to its cap)
		World two = new World().fill(-18, 47, -18, 18, 62, 18, STONE).fill(3, 64, 3, 3, 66, 3, STONE);
		long t1 = System.nanoTime();
		int[] g5 = groups(two, new int[][]{{0, 62, 4}, {2, 62, 0}, {3, 65, 3}, {3, 66, 3}});
		double ms2 = (System.nanoTime() - t1) / 1e6;
		check.accept(g5[0] == g5[1] && g5[2] == g5[3] && g5[0] != g5[2] && ms2 < 25, Dbg.fmt("ground and a separate pillar of the same stone: two groups, worked out in %.1f ms", ms2));
		check.accept(Arrays.stream(gh).distinct().count() == 1 && ms < 40, Dbg.fmt("a solid hillside all round you: one group, worked out in %.1f ms", ms));
	}
}
