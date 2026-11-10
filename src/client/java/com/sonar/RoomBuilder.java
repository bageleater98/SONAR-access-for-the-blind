package com.sonar;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Writes a tutorial room's blocks through a put-block interface, in room coordinates (origin = the room's corner). The SAME definitions build the real
 * tutorial world (TutorialWorld, on the server, a batch per tick) and the self-test's fake World, so what is tested is what the player walks in.
 */
final class RoomBuilder {
	/** Where blocks go: the real world, the self-test World, or a hash of the room. */
	interface Put { void put(int x, int y, int z, BlockState s); }

	private final Put out;
	final int ox, oy, oz;

	RoomBuilder(Put out, int ox, int oy, int oz) { this.out = out; this.ox = ox; this.oy = oy; this.oz = oz; }

	RoomBuilder set(int x, int y, int z, BlockState s) { out.put(ox + x, oy + y, oz + z, s); return this; }
	RoomBuilder set(int x, int y, int z, Block b) { return set(x, y, z, b.defaultBlockState()); }

	/** Fills a box (inclusive corners, either order). */
	RoomBuilder fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
		for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
			for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
				for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) set(x, y, z, s);
		return this;
	}
	RoomBuilder fill(int x0, int y0, int z0, int x1, int y1, int z1, Block b) { return fill(x0, y0, z0, x1, y1, z1, b.defaultBlockState()); }

	/** The four walls of a rectangle (its outline), from y0 to y1. */
	RoomBuilder walls(int x0, int z0, int x1, int z1, int y0, int y1, Block b) {
		fill(x0, y0, z0, x1, y1, z0, b);
		fill(x0, y0, z1, x1, y1, z1, b);
		fill(x0, y0, z0, x0, y1, z1, b);
		fill(x1, y0, z0, x1, y1, z1, b);
		return this;
	}

	/** The six faces of a box of barriers: the invisible, silent shell round a room (Sense: Sonar never hears it). */
	RoomBuilder shell(int x0, int y0, int z0, int x1, int y1, int z1) {
		BlockState b = Blocks.BARRIER.defaultBlockState();
		fill(x0, y0, z0, x1, y0, z1, b);
		fill(x0, y1, z0, x1, y1, z1, b);
		fill(x0, y0, z0, x1, y1, z0, b);
		fill(x0, y0, z1, x1, y1, z1, b);
		fill(x0, y0, z0, x0, y1, z1, b);
		fill(x1, y0, z0, x1, y1, z1, b);
		return this;
	}

	/** A door (both halves) standing at x,y,z, facing `facing` (the way you face when you walk through it from its front). */
	RoomBuilder door(int x, int y, int z, Block door, Direction facing) {
		BlockState s = door.defaultBlockState().setValue(DoorBlock.FACING, facing);
		set(x, y, z, s.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		set(x, y + 1, z, s.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		return this;
	}
}
