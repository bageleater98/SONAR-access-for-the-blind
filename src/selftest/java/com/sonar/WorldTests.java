package com.sonar;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Checks of the block rules and the player route in small worlds built in code: each one is a bug found in testing, kept so it can not come back. */
final class WorldTests {
	private static final BlockState STONE = Blocks.STONE.defaultBlockState(), GRASS = Blocks.GRASS_BLOCK.defaultBlockState(), LOG = Blocks.OAK_LOG.defaultBlockState(),
		LEAVES = Blocks.OAK_LEAVES.defaultBlockState(), PLANKS = Blocks.OAK_PLANKS.defaultBlockState(), BRICKS = Blocks.STONE_BRICKS.defaultBlockState(),
		LANTERN = Blocks.LANTERN.defaultBlockState(), FENCE = Blocks.OAK_FENCE.defaultBlockState(), LADDER = Blocks.LADDER.defaultBlockState(),
		FARMLAND = Blocks.FARMLAND.defaultBlockState(), WHEAT = Blocks.WHEAT.defaultBlockState();

	/** An oak-like tree: trunk at x,z from y0 up 5, a 5x5x2 leaf canopy around its top. */
	private static World tree(World w, int x, int y0, int z) {
		w.fill(x - 2, y0 + 3, z - 2, x + 2, y0 + 4, z + 2, LEAVES);
		w.fill(x, y0, z, x, y0 + 4, z, LOG);
		return w;
	}

	static void run(BiConsumer<Boolean, String> check) {
		// --- trees ---
		World t = tree(new World().fill(-5, 63, -5, 5, 63, 5, GRASS), 0, 64, 0);
		check.accept(BlockRules.treeLog(t, LOG, 0, 67, 0), "a log under a leaf canopy is a tree");
		t.set(1, 66, 0, LANTERN).set(0, 66, 1, FENCE).set(-1, 64, 0, PLANKS);
		check.accept(BlockRules.treeLog(t, LOG, 0, 66, 0), "a tree with a lantern, a fence and a plank against it is still a tree");
		t.set(1, 67, 0, PLANKS).set(-1, 67, 0, PLANKS).set(0, 67, 1, PLANKS);
		check.accept(BlockRules.treeLog(t, LOG, 0, 67, 0), "3 built blocks touching a log: still a tree (a building needs 4)");
		World cabin = new World().fill(-3, 63, -3, 3, 63, 3, STONE);
		cabin.fill(-2, 64, 0, 2, 67, 0, PLANKS).fill(0, 64, 0, 0, 67, 0, LOG).fill(-2, 68, -2, 2, 68, 2, LEAVES); // a log post in a plank wall, leaves on the roof as decoration
		check.accept(!BlockRules.treeLog(cabin, LOG, 0, 66, 0), "a log built into a plank wall is a wall, even with leaves on the roof");

		// --- a saved spot (F2) loads back into a test world exactly ---
		try {
			String spot = Spot.write(t, -3, 63, -3, 7, 7, 7, 0.5, 64, 0.5, 90f);
			World back = new World();
			double[] pl = Spot.read(spot, back::set);
			boolean same = pl[3] == 90.0;
			for (int x = -3; x <= 3; x++) for (int y = 63; y <= 69; y++) for (int z = -3; z <= 3; z++) {
				var bp = new net.minecraft.core.BlockPos(x, y, z);
				if (t.getBlockState(bp) != back.getBlockState(bp)) same = false;
			}
			check.accept(same, "a saved spot loads back as the same blocks (stairs, lanterns, leaves with all their states)");
		} catch (Exception e) { check.accept(false, "a saved spot loads back: " + e); }

		// --- rooms (the room survey) ---
		World house = new World().fill(-6, 63, -6, 6, 63, 6, STONE).fill(-5, 64, -5, 5, 67, 5, PLANKS).fill(-4, 64, -4, 4, 67, 4, Blocks.AIR.defaultBlockState()).fill(-5, 68, -5, 5, 68, 5, PLANKS);
		house.fill(0, 64, -5, 0, 65, -5, Blocks.AIR.defaultBlockState()); // a doorway
		check.accept(Rooms.roomAt(house, new Vec3(0.5, 65.6, 0.5)), "inside a house is a room");
		check.accept(Rooms.roomAt(house, new Vec3(0.5, 65.6, -3.5)), "just inside the doorway is a room");
		// a way in must lead into a space (round 10: a one-block-deep hole under a stone staircase echoed as a doorway)
		var AIR = Blocks.AIR.defaultBlockState();
		World hill = new World().fill(-6, 63, -6, 6, 63, 6, STONE).fill(-6, 64, 0, 6, 70, 6, STONE).fill(0, 64, 0, 0, 65, 0, AIR);
		check.accept(ExitGuide.roofedSpace(hill, 0, 63, 0, Tuning.ENTRANCE_MIN_SPACE) < Tuning.ENTRANCE_MIN_SPACE, "a one-block-deep hole in a wall leads nowhere: not a way in");
		World hut = new World().fill(-6, 63, -6, 6, 63, 6, STONE).fill(-3, 64, -3, 3, 67, 3, PLANKS).fill(-2, 64, -2, 2, 67, 2, AIR).fill(-3, 68, -3, 3, 68, 3, PLANKS).fill(0, 64, -3, 0, 65, -3, AIR);
		check.accept(ExitGuide.roofedSpace(hut, 0, 63, -3, Tuning.ENTRANCE_MIN_SPACE) >= Tuning.ENTRANCE_MIN_SPACE, "the doorway of a small hut leads into a room: still a way in");

		try { // the real spot (round 10, 02:26): four roofed cells under the stone staircase were echoed as ways in
			World sp = new World(); World.load(java.nio.file.Path.of("src/selftest/spots/r10-stair-nook.txt"), sp);
			StringBuilder bad = new StringBuilder();
			for (int[] f : new int[][]{{-132, 108, -56}, {-130, 111, -61}, {-132, 103, -51}, {-132, 99, -48}}) {
				int n = ExitGuide.roofedSpace(sp, f[0], f[1], f[2], Tuning.ENTRANCE_MIN_SPACE);
				if (n >= Tuning.ENTRANCE_MIN_SPACE) bad.append(" ").append(f[0]).append(",").append(f[2]).append("=").append(n);
			}
			check.accept(bad.length() == 0, "stone staircase spot (round 10): no nook under it is a way in" + bad);
		} catch (Exception e) { check.accept(false, "stone staircase spot loads: " + e); }

		// covered path: a porch along a house is part of the house; a covered bridge is still a path (round 11, 02:42: the bell went off on a covered bridge)
		World porch = new World().fill(-8, 63, -8, 8, 63, 8, STONE).fill(-5, 64, -5, 5, 67, 5, PLANKS).fill(-4, 64, -4, 4, 67, 4, AIR).fill(-5, 68, -5, 5, 68, 8, PLANKS); // the roof runs on over a porch on the south side
		check.accept(PathGuide.roomBeside(porch, 0, 63, 6, 0, -1), "porch: through the wall from the porch is the house's room");
		try {
			World br = new World(); World.load(java.nio.file.Path.of("src/selftest/spots/r11-covered-bridge.txt"), br);
			StringBuilder bad = new StringBuilder();
			for (int x = 70; x <= 94; x++) for (int z = -1; z <= 1; z++) for (int[] o : new int[][]{{0, 1}, {0, -1}, {1, 0}, {-1, 0}}) if (PathGuide.roomBeside(br, x, 106, z, o[0], o[1])) bad.append(" ").append(x).append(",").append(z);
			check.accept(bad.length() == 0, "covered bridge (round 11 spot): no room beside it, so it stays a path" + (bad.length() > 60 ? bad.substring(0, 60) : bad));
		} catch (Exception e) { check.accept(false, "covered bridge spot loads: " + e); }

		// a cherry-like grove on a hillside (round 9, 01:23 to 01:25: false echoes): trees all round, the ground rising on one side. Not a room.
		World grove = new World().fill(-12, 63, -12, 12, 63, 12, GRASS);
		for (int k = 0; k < 8; k++) grove.fill(-12, 64, 4 + k, 12, 64 + k, 4 + k, GRASS); // the hill rising to the south
		for (int[] tp : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 2}, {0, -5}}) tree(grove, tp[0], 64, tp[1]);
		check.accept(!Rooms.roomAt(grove, new Vec3(0.5, 65.6, 0.5)), "a tree grove on a hillside is not a room");
		// the logged case: a tree straight overhead, in a small dell where the ground rises close by on three sides (the old test counted the slopes as roof and walls)
		World dell = new World().fill(-12, 63, -12, 12, 63, 12, GRASS);
		for (int k = 0; k < 6; k++) { // steep banks (2 up for every block across), from 2 blocks away on three sides
			dell.fill(-12, 64, 2 + k, 12, 65 + 2 * k, 2 + k, GRASS);
			dell.fill(2 + k, 64, -12, 2 + k, 65 + 2 * k, 12, GRASS);
			dell.fill(-2 - k, 64, -12, -2 - k, 65 + 2 * k, 12, GRASS);
			dell.fill(-12, 64, -3 - k, 12, 65 + 2 * k, -3 - k, GRASS); // and the fourth side: a hollow
		}
		tree(dell, 0, 64, 0);
		check.accept(!Rooms.roomAt(dell, new Vec3(0.5, 66.6, -0.5)), "a tree in a small hollow is not a room (no roof straight overhead)");
		// a small stone overhang against a hillside (a slab over a path): a roof overhead, but no walls under it all round. Not a room.
		World nook = new World().fill(-10, 63, -10, 10, 63, 10, GRASS);
		for (int k = 0; k < 8; k++) nook.fill(-10, 64, 2 + k, 10, 64 + k, 2 + k, GRASS);
		nook.fill(-1, 67, -1, 1, 67, 1, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
		check.accept(!Rooms.roomAt(nook, new Vec3(0.5, 65.6, 0.5)), "a small overhang against a hillside is not a room");

		// --- roofs ---
		World h = new World().fill(-3, 70, -3, 3, 70, 3, BRICKS);
		check.accept(BlockRules.solidRoof(h, new Vec3(0, 64, 0), 40), "stone bricks overhead are a roof");
		check.accept(!BlockRules.caveRoof(h, new Vec3(0, 64, 0), 40), "a brick (house) roof is never a cave roof");
		World c = new World().fill(-3, 70, -3, 3, 70, 3, STONE);
		check.accept(BlockRules.caveRoof(c, new Vec3(0, 64, 0), 40), "rock overhead is a cave roof");
		check.accept(!BlockRules.caveRoof(c, new Vec3(0, 64, 0), 3), "a cave roof further up than allowed does not count");
		World canopy = tree(new World(), 0, 64, 0);
		check.accept(!BlockRules.solidRoof(canopy, new Vec3(1, 64, 1), 40), "a leaf canopy is not a roof");

		// --- player route ---
		// a ladder whose bottom rung is a block up (you jump to grab it), up a wall onto a platform
		World lad = new World().fill(-6, 63, -6, 6, 63, 6, STONE);
		lad.fill(-6, 64, 1, 6, 70, 1, STONE); // a wall 7 high along z = 1
		lad.fill(-6, 70, 1, 6, 70, 6, STONE); // a platform on top of it, reaching back
		lad.fill(0, 65, 0, 0, 70, 0, LADDER); // the ladder against the wall, starting at y 65 (the floor is 63, so it starts one block up)
		lad.fill(-6, 71, 2, 6, 73, 6, Blocks.AIR.defaultBlockState());
		PlayerChime.Tgt top = new PlayerChime.Tgt(UUID.randomUUID(), 0.5, 71, 4.5, 72.6);
		var walk = new PlayerChime.Search(lad, 0.5, 64, -4.5, top, false);
		walk.run(60000);
		check.accept(walk.path() == null, "ladder wall: no walking route up");
		var climb = new PlayerChime.Search(lad, 0.5, 64, -4.5, top, true);
		climb.run(60000);
		check.accept(climb.path() != null, "ladder starting a block up: the route climbs it");

		// a farm between you and the player, with a way round: the route goes round, never across the crops
		World farm = new World().fill(-8, 63, -8, 8, 63, 8, GRASS);
		farm.fill(-4, 63, -8, 4, 63, 4, FARMLAND).fill(-4, 64, -8, 4, 64, 4, WHEAT);
		PlayerChime.Tgt across = new PlayerChime.Tgt(UUID.randomUUID(), 0.5, 64, 7.5, 65.6);
		var fs = new PlayerChime.Search(farm, 0.5, 64, -7.5 + 0.0, across, false);
		farm.fill(-4, 63, -8, 4, 63, -8, FARMLAND);
		var fs2 = new PlayerChime.Search(farm, 6.5, 64, -7.5, across, false);
		fs2.run(60000);
		List<double[]> fp = fs2.path();
		boolean onFarm = false;
		if (fp != null) for (double[] cell : fp) if (farm.getBlockState(new net.minecraft.core.BlockPos((int) cell[0], (int) cell[1], (int) cell[2])).is(Blocks.FARMLAND)) onFarm = true;
		check.accept(fp != null && !onFarm, "farm in the way: the route walks round it, not across the crops");

		// a long 2-high wall beside open ground: walking along it, the route keeps a block or more away from it
		World wall = new World().fill(-2, 63, -12, 8, 63, 12, STONE).fill(-2, 64, -12, -2, 65, 12, BRICKS);
		PlayerChime.Tgt far = new PlayerChime.Tgt(UUID.randomUUID(), -0.5, 64, 11.5, 65.6);
		var ws = new PlayerChime.Search(wall, -0.5, 64, -11.5, far, false);
		ws.run(60000);
		List<double[]> wp = ws.path();
		int hugging = 0;
		if (wp != null) for (double[] cell : wp) if (cell[0] == -1 && Math.abs(cell[2]) < 9) hugging++;
		check.accept(wp != null && hugging == 0, "along a 2-high wall the route keeps off the cells right beside it (" + hugging + " hugging)");

		// an L-shaped corridor 3 wide between 2-high walls: round the bend the route keeps to the middle, not the shorter inner edge (round 8: "led me up the inner edge")
		World el = new World().fill(-3, 63, -12, 12, 63, 3, STONE).fill(-3, 64, -12, 12, 65, 3, BRICKS);
		el.fill(-1, 64, -11, 1, 65, 1, Blocks.AIR.defaultBlockState()).fill(-1, 64, -1, 11, 65, 1, Blocks.AIR.defaultBlockState());
		PlayerChime.Tgt round = new PlayerChime.Tgt(UUID.randomUUID(), 10.5, 64, 0.5, 65.6);
		var es = new PlayerChime.Search(el, 0.5, 64, -10.5, round, false);
		es.run(60000);
		List<double[]> ep = es.path();
		int inner = 0, cells = 0;
		if (ep != null) for (double[] cell : ep) { cells++; if ((cell[0] == 1 && cell[2] <= -2 && cell[2] >= -9) || (cell[2] == -1 && cell[0] >= 2 && cell[0] <= 9)) inner++; }
		check.accept(ep != null && inner <= 1, "round a bend in a 3-wide corridor the route keeps to the middle (" + inner + " of " + cells + " cells on the inner edge)");

		// a 3-wide staircase with a wall on its left and an OPEN side on its right (round 8, 22:26: "brought me along the inner edge of the steps"): the route keeps
		// to the middle, off the open edge, even on the low steps where the drop beside it is only a block
		World st = new World().fill(-4, 63, -6, 6, 63, 12, Blocks.STONE_BRICKS.defaultBlockState());
		for (int k = 0; k < 6; k++) st.fill(0, 64, k, 2, 63 + 1 + k, k, PLANKS); // step k: a column of planks k+1 high
		st.fill(0, 64, 6, 2, 69, 11, PLANKS).fill(-1, 64, -6, -1, 72, 11, BRICKS); // the landing on top, and a tall wall all along the left
		PlayerChime.Tgt up = new PlayerChime.Tgt(UUID.randomUUID(), 2.5, 70, 10.5, 71.6); // the landing's right side, approached from the right: the shortest line runs up the open edge
		var ss = new PlayerChime.Search(st, 5.5, 64, -5.5, up, false);
		ss.run(60000);
		List<double[]> sp = ss.path();
		int edge = 0, steps = 0;
		if (sp != null) for (double[] cell : sp) if (cell[2] >= 0 && cell[2] <= 5) { steps++; if (cell[0] == 2) edge++; }
		check.accept(sp != null && steps > 0 && edge == 0, "staircase with an open side: the route keeps off the open edge (" + edge + " of " + steps + " steps on it)");

		// the edge rule itself
		check.accept(!PlayerChime.isEdge(65, 66, 64), "walking up a staircase is not an edge (step behind down, ahead up)");
		check.accept(PlayerChime.isEdge(65, 64, 65), "a 1-block drop with level ground behind (the open side of stairs) is an edge");
		check.accept(PlayerChime.isEdge(65, 63, 65) && PlayerChime.isEdge(65, Integer.MIN_VALUE, 65), "a 2-block drop and a wall are edges");
		check.accept(!PlayerChime.isEdge(65, 65, 65), "level ground is not an edge");

		// open ground with nothing near: the route is a straight line (clearance never makes a detour where there is room)
		World open = new World().fill(-20, 63, -20, 20, 63, 20, GRASS);
		PlayerChime.Tgt o = new PlayerChime.Tgt(UUID.randomUUID(), 0.5, 64, 15.5, 65.6);
		var os = new PlayerChime.Search(open, 0.5, 64, -15.5, o, false);
		os.run(60000);
		List<double[]> op = os.path();
		check.accept(op != null && op.get(op.size() - 1)[3] <= 32, "open ground: the route is straight (" + (op == null ? "none" : Math.round(op.get(op.size() - 1)[3])) + " blocks for 31)");

		// a 1-wide passage between walls is still a way (every spot in it is at an edge, so the route just takes it)
		World narrow = new World().fill(-3, 63, -10, 3, 63, 10, STONE).fill(-3, 64, -10, 3, 65, 10, BRICKS).fill(0, 64, -10, 0, 65, 10, Blocks.AIR.defaultBlockState());
		PlayerChime.Tgt nt = new PlayerChime.Tgt(UUID.randomUUID(), 0.5, 64, 9.5, 65.6);
		var ns = new PlayerChime.Search(narrow, 0.5, 64, -9.5, nt, false);
		ns.run(60000);
		check.accept(ns.path() != null, "a 1-wide passage is still a way through");

		// THE LEAD POINT (jingle sound and icon): up to 7 blocks of walking ahead along the route, never past what you can see
		PlayerChime.Lead l1 = PlayerChime.lead(el, 0.5, 65.62, -10.5, 0.5, 64, -10.5, round, ep, 7);
		check.accept(l1 != null && Math.abs(l1.x() - 0.5) <= 1.2 && l1.z() > -5 && l1.z() < -2.5, "lead point 7 ahead on a straight stretch (" + (l1 == null ? "none" : Math.round(l1.x()) + "," + Math.round(l1.z())) + ")");
		PlayerChime.Lead l2 = PlayerChime.lead(el, 0.5, 65.62, -2.5, 0.5, 64, -2.5, round, ep, 7);
		check.accept(l2 != null && (l2.x() > 1.6 || l2.z() > -0.5), "near a bend the lead point is already round it, where you can see (" + (l2 == null ? "none" : Math.round(l2.x()) + "," + Math.round(l2.z())) + ")");
		// a TIGHT corner: a 1-wide corridor turning right. Before the corner the lead point stops where you can see, never through the wall
		World tight = new World().fill(-3, 63, -12, 12, 63, 3, STONE).fill(-3, 64, -12, 12, 65, 3, BRICKS);
		tight.fill(0, 64, -11, 0, 65, 0, Blocks.AIR.defaultBlockState()).fill(0, 64, 0, 11, 65, 0, Blocks.AIR.defaultBlockState());
		PlayerChime.Tgt corner = new PlayerChime.Tgt(UUID.randomUUID(), 10.5, 64, 0.5, 65.6);
		var ts = new PlayerChime.Search(tight, 0.5, 64, -10.5, corner, false);
		ts.run(60000);
		List<double[]> tp = ts.path();
		PlayerChime.Lead l3 = tp == null ? null : PlayerChime.lead(tight, 0.5, 65.62, -6.5, 0.5, 64, -6.5, corner, tp, 7);
		boolean clear = l3 != null && tight.clip(new net.minecraft.world.level.ClipContext(new Vec3(0.5, 65.62, -6.5), new Vec3(l3.x(), l3.y(), l3.z()), net.minecraft.world.level.ClipContext.Block.COLLIDER,
			net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty())).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
		check.accept(clear && l3.x() < 1.6, "tight corner: the lead point stays where you can see, not through the wall (" + (l3 == null ? "none" : Math.round(l3.x()) + "," + Math.round(l3.z())) + ")");

		{ // --- ROUND 16: a hole into a cave 14 blocks ahead, past a one-block rise (the user's F2 spot: it echoed only when standing pitNear it) ---
		World pit = caveField();
		double[] pitFar = Echo.caveDrop(pit, 0.5, 65.62, 0.5, 64, -90f, Tuning.CAVE_DROP_RANGE); // facing +x, the pit is at x 14..16
		check.accept(pitFar != null && Math.abs(pitFar[0]) < Math.toRadians(10) && Math.hypot(14, 12) - 2 < pitFar[1] && pitFar[1] < Math.hypot(16, 12) + 2,
			"a hole into a cave 14 blocks ahead, past a step up, echoes" + (pitFar == null ? " (none)" : Dbg.fmt(" (%.0f deg, %.1f blocks)", Math.toDegrees(pitFar[0]), pitFar[1])));
		check.accept(Echo.caveDrop(pit, 0.5, 65.62, 0.5, 64, -90f, 10) == null, "the same hole with the old 10-block search: silent (what the user heard)");
		double[] pitNear = Echo.caveDrop(pit, 10.5, 65.62, 0.5, 64, -90f, Tuning.CAVE_DROP_RANGE);
		check.accept(pitNear != null && pitFar != null && pitNear[1] < pitFar[1], "step closer: still there, and nearer");
		World shaft = caveField().fill(17, 51, -1, 30, 53, 1, STONE); // fill the tunnel in: a pit with nowhere to go at the bottom
		check.accept(Echo.caveDrop(shaft, 0.5, 65.62, 0.5, 64, -90f, Tuning.CAVE_DROP_RANGE) == null, "a closed pit (no cave at the bottom) does not echo");
		World pitWall = caveField().fill(8, 64, -6, 8, 67, 6, STONE); // a wall in the way (the ground goes on round its ends)
		double[] behind = Echo.caveDrop(pitWall, 0.5, 65.62, 0.5, 64, -90f, Tuning.CAVE_DROP_RANGE);
		check.accept(behind != null && behind.length == 8 && behind[7] == 1, "a hole behind a wall: found by walking round it, and muffled (out of sight), never as if in plain view");
		World walled = caveField().fill(8, 64, -10, 8, 67, 10, STONE); // a wall right across: no way round within reach
		check.accept(Echo.caveDrop(walled, 0.5, 65.62, 0.5, 64, -90f, Tuning.CAVE_DROP_RANGE) == null, "a hole you can not walk to (a wall right across) does not echo");
		}
	}

	/** Grass over rock; a pit (x 14..16, z -1..1) down to y 51 with a rock-roofed tunnel running on from its bottom (x up to 30); a one-block rise at x 6..9 on the way. */
	private static World caveField() {
		World w = new World().fill(-8, 40, -10, 34, 62, 10, STONE).fill(-8, 63, -10, 34, 63, 10, GRASS);
		w.fill(6, 64, -10, 9, 64, 10, GRASS); // a step up and down again
		w.fill(14, 51, -1, 16, 63, 1, Blocks.AIR.defaultBlockState()); // the pit
		w.fill(14, 51, -1, 30, 53, 1, Blocks.AIR.defaultBlockState()); // the tunnel at its bottom (rock overhead from y 54)
		return w;
	}
}
