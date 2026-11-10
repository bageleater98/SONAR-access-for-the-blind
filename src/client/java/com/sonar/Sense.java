package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * GUIDE BLOCKS (barriers): invisible walls that quietly keep a player on track. ONE rule for the whole mod, in every world:
 *  - Sonar never HEARS one. Everything that makes or shapes a sound (the ping's blocks, line of sight, echo rays, the room / roof / cave tests, the ceiling cue,
 *    the bump thud) reads the world through {@link #hear}, where a barrier is air (or the water it holds, when waterlogged).
 *  - Where a player can WALK, a barrier is a silent railing: the shared step rule (BlockRules.stepTo / passable / dropBottom) and the ledge flood treat it like a
 *    fence: you can not step into it or stand on it, and a drop behind it is not one you can walk off. So route searches lead round it, no echo offers a way out
 *    through it, and no edge tone sounds behind it.
 * A barrier added anywhere later needs no code: it is a barrier, so both halves apply.
 */
final class Sense {
	private Sense() {}

	/** A guide block: invisible, silent, and a railing for walking. */
	static boolean guide(BlockState s) { return s.is(Blocks.BARRIER); }

	/** What Sonar hears in place of a guide block: air, or the water when it is waterlogged. */
	static BlockState hollow(BlockState s) { return s.getFluidState().isEmpty() ? Blocks.AIR.defaultBlockState() : s.getFluidState().createLegacyBlock(); }

	/** A world view that wraps another (the cache, the heard view): the step rule looks through it to the real block. */
	interface Wrapper { BlockGetter inner(); }

	/** The world as Sonar hears it: guide blocks read as air. Rays (BlockGetter.clip) are built on getBlockState, so they pass through them too. */
	static final class Heard implements BlockGetter, Wrapper {
		final BlockGetter w;
		Heard(BlockGetter w) { this.w = w; }
		@Override public BlockGetter inner() { return w; }
		@Override public BlockState getBlockState(BlockPos pos) {
			BlockState s = w.getBlockState(pos);
			return guide(s) ? hollow(s) : s;
		}
		@Override public FluidState getFluidState(BlockPos pos) { return w.getFluidState(pos); }
		@Override public BlockEntity getBlockEntity(BlockPos pos) { return w.getBlockEntity(pos); }
		@Override public int getHeight() { return w.getHeight(); }
		@Override public int getMinY() { return w.getMinY(); }
	}

	/** The heard view of any world (the game's, a self-test world). Wrapping twice is harmless (returns the same view). */
	static BlockGetter hear(BlockGetter w) { return w instanceof Heard ? w : new Heard(w); }

	private static Heard cached;

	/** The heard view of the game world you are in (one object per world, so a feature can tell a new world by it). */
	static BlockGetter hear(Minecraft mc) {
		if (mc.level == null) return null;
		if (cached == null || cached.w != mc.level) cached = new Heard(mc.level);
		return cached;
	}

	/** True when this view is (or wraps) the given world: "is this walk still for the world I am in?" */
	static boolean of(BlockGetter view, BlockGetter world) {
		for (BlockGetter v = view; v != null; v = v instanceof Wrapper wr ? wr.inner() : null) if (v == world) return true;
		return false;
	}

	/**
	 * The REAL block at pos under a heard view (guide blocks included), in ONE read: the step rule asks this once per block and uses it both ways (a guide
	 * block is a railing; anything else is what Sonar hears too). A cache must wrap the real world, never a heard view.
	 */
	static BlockState real(BlockGetter w, BlockPos pos) { return w instanceof Heard h ? h.w.getBlockState(pos) : w.getBlockState(pos); }

	/** A guide block at pos in the REAL world under this view (through the heard view and caches). */
	static boolean guideAt(BlockGetter w, BlockPos pos) {
		BlockGetter real = w;
		while (real instanceof Wrapper wr) real = wr.inner();
		return guide(real.getBlockState(pos));
	}
}
