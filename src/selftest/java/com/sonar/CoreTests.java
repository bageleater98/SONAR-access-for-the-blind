package com.sonar;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.BiConsumer;

/** 2.0 bug hunt: everyday places the step rule and the route search (player jingle, waypoint guide) must get right: stair tunnels, carpets, doors, gates, pens, bridges. */
final class CoreTests {
	private CoreTests() {}

	private static final BlockState STONE = Blocks.STONE.defaultBlockState();
	private static final BlockState CARPET = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.Identifier.parse("minecraft:red_carpet")).get().value().defaultBlockState();

	/** A big flat stone floor (top block y 64; you stand with your feet at 65). */
	private static World flat() { return new World().fill(-45, 62, -45, 45, 64, 45, STONE); }

	private static PlayerChime.Search route(World w, double gx, double gy, double gz) {
		var s = new PlayerChime.Search(w, 0.5, 65, 0.5, new PlayerChime.Tgt(null, gx, gy, gz, gy + 1.6), true);
		while (!s.run(100000)) {}
		return s;
	}

	static void run(BiConsumer<Boolean, String> check) {
		var pos = new BlockPos.MutableBlockPos();

		// a stair tunnel: two blocks of air over every tread, the ceiling rising with the stairs (a dug staircase, a mineshaft, a cellar)
		World st = flat();
		BlockState up = Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH); // climbs toward +z
		st.fill(-1, 65, -6, -1, 75, 12, STONE).fill(1, 65, -6, 1, 75, 12, STONE); // side walls
		st.fill(0, 67, -6, 0, 67, 1, STONE); // the flat corridor's ceiling, right over your head
		for (int k = 0; k < 4; k++) { st.fill(0, 65, 2 + k, 0, 64 + k, 2 + k, STONE); st.set(0, 65 + k, 2 + k, up); st.set(0, 68 + k, 2 + k, STONE); }
		st.fill(0, 65, 6, 0, 68, 12, STONE).fill(0, 71, 6, 0, 71, 12, STONE); // the landing at the top and its ceiling
		check.accept(BlockRules.stepFrom(st, 0, 1, 0, 2, 64, pos) == 65, "step rule: stairs are walked up under a low ceiling (no jump needed from the foot of the stairs)");
		check.accept(BlockRules.stepFrom(st, 0, 2, 0, 3, 65, pos) == 66, "step rule: and from tread to tread");
		var sr = route(st, 0.5, 69, 10.5);
		check.accept(sr.reached(), "route: up a stair tunnel with a low ceiling to the landing");
		World back = flat(); // the same step met from its high side is a full block: that does need a jump
		back.set(0, 65, 2, Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH)).fill(0, 67, -3, 0, 67, 1, STONE);
		check.accept(BlockRules.stepFrom(back, 0, 1, 0, 2, 64, pos) == BlockRules.NONE, "step rule: the tall back of a stair under a low ceiling is not climbed");

		// a carpet on a raised block (a rug over a step): one jump, as without the rug
		World rug = flat();
		rug.set(0, 65, 2, STONE).set(0, 66, 2, CARPET);
		check.accept(BlockRules.stepTo(rug, 0, 2, 64, pos) != BlockRules.NONE, "step rule: a block with a carpet on it is still one step up");
		World hall = flat();
		hall.fill(-3, 65, -3, 3, 65, 3, CARPET);
		check.accept(route(hall, 0.5, 65, 10.5).reached(), "route: off a carpeted floor onto the bare floor");

		// a closed door in a wall too long to walk round
		World door = flat();
		door.fill(-45, 65, 5, 45, 67, 5, STONE);
		door.set(0, 65, 5, Blocks.OAK_DOOR.defaultBlockState()).set(0, 66, 5, Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
		var dr = route(door, 0.5, 65, 10.5);
		check.accept(dr.reached(), "route: through a closed door (you can open it)");
		World wall = flat();
		wall.fill(-45, 65, 5, 45, 67, 5, STONE);
		check.accept(!route(wall, 0.5, 65, 10.5).reached(), "route: no way through a wall without a door");

		// a fenced pen: in by the gate, never over the fence
		World pen = flat();
		BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
		pen.fill(-3, 65, 7, 3, 65, 7, fence).fill(-3, 65, 13, 3, 65, 13, fence).fill(-3, 65, 7, -3, 65, 13, fence).fill(3, 65, 7, 3, 65, 13, fence);
		check.accept(!route(pen, 0.5, 65, 10.5).reached(), "route: a fence all the way round is not climbed");
		pen.set(2, 65, 13, Blocks.OAK_FENCE_GATE.defaultBlockState()); // the gate is on the far side
		var pr = route(pen, 0.5, 65, 10.5);
		List<double[]> pp = pr.path();
		boolean round = false;
		if (pp != null) for (double[] c : pp) round |= c[2] > 13.5;
		check.accept(pr.reached() && round, "route: round the pen to the gate on its far side");

		// a ravine with one bridge, off to the side
		World rav = new World().fill(-45, 62, -45, 45, 64, 2, STONE).fill(-45, 62, 8, 45, 64, 45, STONE).fill(-45, 48, 3, 45, 50, 7, STONE);
		check.accept(!route(rav, 0.5, 65, 12.5).reached(), "route: a ravine 14 deep is not jumped into");
		rav.fill(10, 64, 3, 10, 64, 7, Blocks.OAK_PLANKS.defaultBlockState());
		var rr = route(rav, 0.5, 65, 12.5);
		List<double[]> rp = rr.path();
		boolean overBridge = rp != null, low = false;
		if (rp != null) for (double[] c : rp) { low |= c[1] < 60; if (c[2] > 3.2 && c[2] < 7.8 && (c[0] < 9.9 || c[0] > 11.1)) overBridge = false; }
		check.accept(rr.reached() && overBridge && !low, "route: across the ravine by its one bridge, ten blocks to the side");
	}
}
