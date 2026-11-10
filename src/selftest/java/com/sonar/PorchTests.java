package com.sonar;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Covered paths: is there a building's room beside this covered path (porch, part of the house) or not (covered bridge, a path)? Variants, round 11. */
final class PorchTests {
	private PorchTests() {}

	static final BlockState STONE = Blocks.STONE.defaultBlockState(), PLANKS = Blocks.OAK_PLANKS.defaultBlockState(), AIR = Blocks.AIR.defaultBlockState(),
		FENCE = Blocks.OAK_FENCE.defaultBlockState(), SLAB = Blocks.SPRUCE_SLAB.defaultBlockState(), LOG = Blocks.SPRUCE_LOG.defaultBlockState(), WATER = Blocks.WATER.defaultBlockState();

	/** True if any cell of the covered path (x0..x1 at rows zs, floor y) has a room beside it, through a wall, in any direction. */
	static boolean roomAlong(World w, int x0, int x1, int y, int[] zs) {
		for (int x = x0; x <= x1; x++) for (int z : zs) for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) if (PathGuide.roomBeside(w, x, y, z, d[0], d[1])) return true;
		return false;
	}

	/** A house: room inside x/z -4..4, walls `thick` blocks thick, roof at 68, floor 63. */
	static World house(int thick) {
		int o = 4 + thick;
		return new World().fill(-14, 63, -14, 14, 63, 14, STONE).fill(-o, 64, -o, o, 67, o, PLANKS).fill(-4, 64, -4, 4, 67, 4, AIR).fill(-o, 68, -o, o, 68, o, PLANKS);
	}

	static void run(java.util.function.BiConsumer<Boolean, String> check) {
		// 1. porch along the south wall, roof carried over it
		World a = house(1).fill(-5, 68, 6, 5, 68, 8, PLANKS);
		check.accept(roomAlong(a, -4, 4, 63, new int[]{6, 7}), "porch along one wall: a room beside it (bell off)");
		// 1b. porch with the front door in the wall (standing right outside the door)
		World a2 = house(1).fill(-5, 68, 6, 5, 68, 8, PLANKS);
		a2.set(0, 64, 5, Blocks.OAK_DOOR.defaultBlockState()).set(0, 65, 5, Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
		check.accept(PathGuide.roomBeside(a2, 0, 63, 6, 0, -1), "porch outside the front door: the house's room is through the door (bell off)");
		// 2. veranda with a fence railing and posts along its open side
		World b = house(1).fill(-5, 68, 6, 5, 68, 8, PLANKS).fill(-5, 64, 8, 5, 64, 8, FENCE);
		for (int x = -5; x <= 5; x += 2) b.fill(x, 65, 8, x, 67, 8, FENCE);
		check.accept(roomAlong(b, -4, 4, 63, new int[]{6, 7}), "veranda with railing and posts: a room beside it (bell off)");
		// 3. wrap-around porch at a corner (south and east)
		World c = house(1).fill(-5, 68, 6, 8, 68, 8, PLANKS).fill(6, 68, -5, 8, 68, 8, PLANKS);
		check.accept(roomAlong(c, 6, 7, 63, new int[]{-4, 0, 4}) || roomAlong(c, -4, 4, 63, new int[]{6, 7}), "wrap-around porch: a room beside it (bell off)");
		// 4. porch along a house with walls 2 thick (two over lands inside the wall: the room is 3 over)
		World d = house(2).fill(-6, 68, 7, 6, 68, 9, PLANKS);
		check.accept(roomAlong(d, -4, 4, 63, new int[]{7, 8}), "porch along a thick-walled house: a room beside it (bell off)");
		// 5. covered bridge over water: fences each side, a slab roof, open ends
		World e = new World().fill(-20, 58, -6, 20, 60, 6, WATER).fill(-12, 63, -2, 12, 63, 2, PLANKS).fill(-12, 64, -2, 12, 64, -2, FENCE).fill(-12, 64, 2, 12, 64, 2, FENCE)
			.fill(-12, 65, -2, -12, 67, 2, AIR).fill(-12, 68, -2, 12, 68, 2, SLAB);
		for (int x = -12; x <= 12; x += 4) e.fill(x, 64, -2, x, 67, -2, LOG).fill(x, 64, 2, x, 67, 2, LOG);
		check.accept(!roomAlong(e, -10, 10, 63, new int[]{-1, 0, 1}), "covered bridge with fences and posts: no room beside it (bell on)");
		// 6. enclosed covered bridge: solid plank sides 3 high, roof, open ends (a long tunnel)
		World f = new World().fill(-20, 58, -6, 20, 60, 6, WATER).fill(-15, 63, -2, 15, 63, 2, PLANKS).fill(-15, 64, -2, 15, 66, -2, PLANKS).fill(-15, 64, 2, 15, 66, 2, PLANKS).fill(-15, 67, -2, 15, 67, 2, PLANKS);
		check.accept(!roomAlong(f, -12, 12, 63, new int[]{-1, 0, 1}), "fully walled covered bridge: no room beside it (bell on)");
		// 7. pergola over a garden path, no building near
		World g = new World().fill(-14, 63, -14, 14, 63, 14, Blocks.GRASS_BLOCK.defaultBlockState()).fill(-10, 63, -1, 10, 63, 1, Blocks.DIRT_PATH.defaultBlockState()).fill(-10, 67, -2, 10, 67, 2, SLAB);
		for (int x = -10; x <= 10; x += 4) g.fill(x, 64, -2, x, 66, -2, FENCE).fill(x, 64, 2, x, 66, 2, FENCE);
		check.accept(!roomAlong(g, -9, 9, 63, new int[]{-1, 0, 1}), "pergola over a garden path: no room beside it (bell on)");
		// 9. covered bridge whose end runs up to a house wall: its middle has no room beside it
		World i = house(1).fill(5, 63, -1, 20, 63, 1, PLANKS).fill(6, 64, -2, 20, 64, -2, FENCE).fill(6, 64, 2, 20, 64, 2, FENCE).fill(6, 68, -2, 20, 68, 2, SLAB);
		check.accept(!roomAlong(i, 9, 18, 63, new int[]{-1, 0, 1}), "covered bridge running up to a house: no room beside its middle (bell on)");
		// 8. covered bridge running past a house's blank side wall, 3 blocks off (the house is not beside the path)
		World h = house(1).fill(-12, 63, 8, 12, 63, 12, PLANKS).fill(-12, 64, 8, 12, 64, 8, FENCE).fill(-12, 64, 12, 12, 64, 12, FENCE).fill(-12, 68, 8, 12, 68, 12, SLAB);
		check.accept(!roomAlong(h, -10, 10, 63, new int[]{10}), "covered bridge 3+ blocks from a house: no room beside its middle (bell on)");
	}
}
