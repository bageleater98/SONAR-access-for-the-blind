package com.sonar;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.Map;

/** A fake world for the self-test: real Minecraft blocks (with their vanilla tags) placed in code, everything else air. */
final class World implements BlockGetter {
	private static boolean booted;

	/** Starts the game's block registry and loads the vanilla block tags (leaves, logs, stairs...) from the game jar, once. */
	static void boot() {
		if (booted) return;
		booted = true;
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		var rm = new net.minecraft.server.packs.resources.MultiPackResourceManager(net.minecraft.server.packs.PackType.SERVER_DATA, java.util.List.<net.minecraft.server.packs.PackResources>of(net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource().fullResources()));
		var reg = (net.minecraft.core.WritableRegistry<net.minecraft.world.level.block.Block>) (Object) BuiltInRegistries.BLOCK;
		var tags = net.minecraft.tags.TagLoader.loadTagsForRegistry(rm, Registries.BLOCK, new net.minecraft.tags.TagLoader.ElementLookup<net.minecraft.core.Holder<net.minecraft.world.level.block.Block>>() {
			public java.util.Optional<net.minecraft.core.Holder<net.minecraft.world.level.block.Block>> get(net.minecraft.resources.Identifier id, boolean required) {
				return BuiltInRegistries.BLOCK.get(id).map(h -> (net.minecraft.core.Holder<net.minecraft.world.level.block.Block>) h);
			}
		});
		((net.minecraft.core.MappedRegistry<net.minecraft.world.level.block.Block>) reg).prepareTagReload(new net.minecraft.tags.TagLoader.LoadResult<>(Registries.BLOCK, tags)).apply(); // the game's own reload route (the registry is frozen)
		// fluid tags too (water, lava): without them lava in a self-test world was heard as water
		var fluids = net.minecraft.tags.TagLoader.loadTagsForRegistry(rm, Registries.FLUID, new net.minecraft.tags.TagLoader.ElementLookup<net.minecraft.core.Holder<net.minecraft.world.level.material.Fluid>>() {
			public java.util.Optional<net.minecraft.core.Holder<net.minecraft.world.level.material.Fluid>> get(net.minecraft.resources.Identifier id, boolean required) {
				return BuiltInRegistries.FLUID.get(id).map(h -> (net.minecraft.core.Holder<net.minecraft.world.level.material.Fluid>) h);
			}
		});
		((net.minecraft.core.MappedRegistry<net.minecraft.world.level.material.Fluid>) (Object) BuiltInRegistries.FLUID).prepareTagReload(new net.minecraft.tags.TagLoader.LoadResult<>(Registries.FLUID, fluids)).apply();
	}

	private final Map<Long, BlockState> blocks = new HashMap<>();

	World set(int x, int y, int z, BlockState s) { blocks.put(BlockPos.asLong(x, y, z), s); return this; }

	/** A world built from a spot saved in the game with F2 (config/sonar-spots/...). Returns the player's {x, y, z, yaw} there; the blocks go into `w`. */
	static double[] load(java.nio.file.Path file, World w) throws Exception { return Spot.read(java.nio.file.Files.readString(file), w::set); }

	/** Fills a box (inclusive) with one block. */
	World fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, s);
		return this;
	}

	@Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState()); }
	@Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
	@Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
	@Override public int getHeight() { return 384; }
	@Override public int getMinY() { return -64; }
}
