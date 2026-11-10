package com.sonar;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The shared rules for what a block IS to the mod: part of a tree, a real roof, the roof of a cave. Every feature asks these, so a tree is a tree for the echo, the room
 * test and the cave check alike (slightly different copies of these rules in different files caused inconsistent results before).
 * They read any world (the game's, or a self-test world), so each rule is checked by the self-test.
 */
final class BlockRules {
	private BlockRules() {}

	/** Leaves within 2 blocks of a log for it to count as a tree's trunk or branch. */
	static final int TREE_MIN_LEAVES = 6;
	/** Full built blocks touching a log (at its height or above) that make it part of a building, not a tree. */
	static final int BUILT_WALL_BLOCKS = 4;

	/** Natural ground: dirt, grass, stone, sand, gravel, natural logs and leaves (not built blocks). */
	static boolean natural(BlockState s) { return FloorEdge.natural(s); }

	/**
	 * Part of a tree: a natural log with a real canopy around it (TREE_MIN_LEAVES+ leaves within 2 blocks) that is not built into a wall or roof: a log inside a building has
	 * walls round it, BUILT_WALL_BLOCKS+ full built blocks (planks, bricks, glass) touching it at its own height or above. A lantern, a fence, a sign, a path or a wall at the foot
	 * of a tree, or the odd block against it, do not make a tree a building; a log cabin's walls and a log roof stay walls and roofs.
	 */
	static boolean treeLog(BlockGetter w, BlockState bs, int x, int y, int z) {
		if (!bs.is(BlockTags.OVERWORLD_NATURAL_LOGS)) return false;
		var pos = new BlockPos.MutableBlockPos();
		int leaves = 0, built = 0;
		for (int ax = -2; ax <= 2; ax++) for (int ay = -2; ay <= 2; ay++) for (int az = -2; az <= 2; az++) {
			BlockState n = w.getBlockState(pos.set(x + ax, y + ay, z + az));
			if (n.is(BlockTags.LEAVES)) leaves++;
			else if (ay >= 0 && Math.abs(ax) <= 1 && Math.abs(ay) <= 1 && Math.abs(az) <= 1 && !natural(n) && n.isCollisionShapeFullBlock(w, pos)) built++;
		}
		return leaves >= TREE_MIN_LEAVES && built < BUILT_WALL_BLOCKS;
	}

	/** Something solid within `h` blocks straight above `pt` that is a real roof: leaves, tree logs, doors and trapdoors never count. */
	static boolean solidRoof(BlockGetter w, Vec3 pt, int h) {
		var pos = new BlockPos.MutableBlockPos();
		int x = (int) Math.floor(pt.x), y = (int) Math.floor(pt.y), z = (int) Math.floor(pt.z);
		for (int dy = 1; dy <= h; dy++) {
			BlockState bs = w.getBlockState(pos.set(x, y + dy, z));
			if (bs.getCollisionShape(w, pos).isEmpty() || bs.is(BlockTags.LEAVES) || bs.is(BlockTags.DOORS) || bs.is(BlockTags.TRAPDOORS)) continue;
			if (treeLog(w, bs, x, y + dy, z)) continue;
			return true;
		}
		return false;
	}

	/** Like solidRoof, but the roof must be the ground itself (rock, dirt...): the roof of a cave, never a house roof, a balcony or a bridge. */
	static boolean caveRoof(BlockGetter w, Vec3 pt, int h) {
		var pos = new BlockPos.MutableBlockPos();
		int x = (int) Math.floor(pt.x), y = (int) Math.floor(pt.y), z = (int) Math.floor(pt.z);
		for (int dy = 1; dy <= h; dy++) {
			BlockState bs = w.getBlockState(pos.set(x, y + dy, z));
			if (bs.getCollisionShape(w, pos).isEmpty() || bs.is(BlockTags.LEAVES) || treeLog(w, bs, x, y + dy, z)) continue;
			return (natural(bs) || CaveEcho.caveRock(bs)) && !bs.is(BlockTags.OVERWORLD_NATURAL_LOGS);
		}
		return false;
	}

	// ---- where you can step: ONE rule (guide blocks, barriers, are railings here: see Sense) for the player route, the doorway finder and the cave routes (they used to have their own copies that disagreed about fence
	// gates, ladders and fences). The edge warning's flood (Scan) keeps its own on purpose: there a fence or gate at a drop makes the edge SAFE, and leaves are not a floor.

	/** "Can not step there". */
	static final int NONE = Integer.MIN_VALUE;

	static boolean passable(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		net.minecraft.world.level.block.state.BlockState s = Sense.real(w, pos.set(x, y, z));
		if (Sense.guide(s)) return false; // a guide block (barrier) is a railing: never walked through, even in the heard view (Sense)
		if (s.is(BlockTags.DOORS) || s.is(BlockTags.TRAPDOORS) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.CLIMBABLE)) return true; // a door, trapdoor or gate is a way through (open or shut: you can open it), open or shut; a ladder or vine is climbed through
		return s.getCollisionShape(w, pos).isEmpty() && !(s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock);
	}

	/** Where stepTo says no because the floor is TOO FAR DOWN: the bottom's floor y, when the column x,z falls clear (no liquid, no fence or wall on the way) from
	 *  above ref to a floor more than 3 below it, within 60; NONE when it is not a drop (a wall, a low ceiling, water, a fence, or no bottom). A ladder or vine down
	 *  the column is a drop too (a shaft). */
	static int dropBottom(net.minecraft.world.level.BlockGetter w, int x, int z, int ref, BlockPos.MutableBlockPos pos) {
		for (int y = ref + 1; y >= ref - 60; y--) {
			net.minecraft.world.level.block.state.BlockState s = Sense.real(w, pos.set(x, y, z));
			if (Sense.guide(s)) { if (y >= ref) return NONE; return y < ref - 3 ? y : NONE; } // a guide block at the lip is a railing (not a drop); lower down it is the bottom you land on (Sense)
			if (!s.getFluidState().isEmpty()) return NONE;
			if (s.is(BlockTags.DOORS) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.CLIMBABLE)) continue;
			if (s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || s.is(net.minecraft.world.level.block.Blocks.IRON_BARS)) return NONE;
			if (!s.getCollisionShape(w, pos).isEmpty()) return y < ref - 3 ? y : NONE;
		}
		return NONE;
	}

	/** Floor y when stepping onto column x,z from a floor at ref (up 1, down 3, with 2 blocks of headroom); NONE when you can not. */
	static int stepTo(net.minecraft.world.level.BlockGetter w, int x, int z, int ref, BlockPos.MutableBlockPos pos) {
		for (int y = ref + 1; y >= ref - 3; y--) {
			net.minecraft.world.level.block.state.BlockState s = Sense.real(w, pos.set(x, y, z));
			if (Sense.guide(s)) return NONE; // a guide block: never stepped into or onto, like a fence (Sense)
			if (s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return NONE;
			if (s.is(BlockTags.DOORS) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.CLIMBABLE)) continue; // look through to the floor under the doorway (or the foot of a ladder)
			if (s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || s.is(net.minecraft.world.level.block.Blocks.IRON_BARS)) return NONE;
			if (!s.getCollisionShape(w, pos).isEmpty()) return passable(w, x, y + 1, z, pos) && passable(w, x, y + 2, z, pos) ? y : NONE;
		}
		return NONE;
	}

	static boolean climbable(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		return w.getBlockState(pos.set(x, y, z)).is(BlockTags.CLIMBABLE);
	}

	/** The floor under an entity (it may be jumping or a few blocks up in the air), or NONE. */
	static int floorUnder(net.minecraft.world.level.BlockGetter w, double x, double y, double z, BlockPos.MutableBlockPos pos) {
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
		for (int yy = (int) Math.floor(y + 0.01); yy >= (int) Math.floor(y) - 8; yy--) {
			net.minecraft.world.level.block.state.BlockState s = w.getBlockState(pos.set(bx, yy, bz));
			if (s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return NONE;
			if (!s.getCollisionShape(w, pos).isEmpty()) return yy;
		}
		return NONE;
	}
}
