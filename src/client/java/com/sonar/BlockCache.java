package com.sonar;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;


/**
 * A world view that remembers every block it has read: one route search reads the same blocks many times (each spot's neighbours, the clearance lines), and a lookup
 * in this table is cheaper than asking the world again. Made fresh for each search, so it never holds stale blocks for long.
 */
final class BlockCache implements BlockGetter, Sense.Wrapper {
	private final BlockGetter w;
	private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockState> seen = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>(); // packed position as a plain number: nothing boxed per read

	BlockCache(BlockGetter w) { this.w = w; }
	@Override public BlockGetter inner() { return w; }

	@Override public BlockState getBlockState(BlockPos pos) {
		long k = pos.asLong();
		BlockState s = seen.get(k);
		if (s == null) { s = w.getBlockState(pos); seen.put(k, s); }
		return s;
	}
	@Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
	@Override public BlockEntity getBlockEntity(BlockPos pos) { return w.getBlockEntity(pos); }
	@Override public int getHeight() { return w.getHeight(); }
	@Override public int getMinY() { return w.getMinY(); }
}
