package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.state.BlockState;

/** Part of the sonar ping: plays the sound of an unusual floor material (stone path, sand, gravel, planks...) from where it begins, ahead and to the sides. Plain grass/dirt ground is ignored. */
public class FloorEdge {
	private static final int[] RAYS = {0, -40, 40}; // degrees relative to where you face (ahead only, never sideways)

	private static BlockState floorAt(Minecraft mc, int x, int z, int feetY) {
		var pos = new BlockPos.MutableBlockPos();
		for (int y = feetY; y >= feetY - 3; y--) {
			BlockState s = mc.level.getBlockState(pos.set(x, y, z));
			if (!s.getCollisionShape(mc.level, pos).isEmpty()) return s;
		}
		return null;
	}

	/** Terrain you find in the world as-is. Anything else (slabs, bricks, planks, cobblestone, concrete...) counts as built. */
	/** Grass, dirt and similar ground (the DIRT tag does not include grass blocks in this version). */
	static boolean ground(BlockState s) {
		var b = s.getBlock();
		return s.is(net.minecraft.tags.BlockTags.DIRT) || b == net.minecraft.world.level.block.Blocks.GRASS_BLOCK || b == net.minecraft.world.level.block.Blocks.DIRT
			|| b == net.minecraft.world.level.block.Blocks.PODZOL || b == net.minecraft.world.level.block.Blocks.MYCELIUM || b == net.minecraft.world.level.block.Blocks.COARSE_DIRT
			|| b == net.minecraft.world.level.block.Blocks.ROOTED_DIRT || b == net.minecraft.world.level.block.Blocks.MUD || b == net.minecraft.world.level.block.Blocks.MOSS_BLOCK
			|| b == net.minecraft.world.level.block.Blocks.FARMLAND || b == net.minecraft.world.level.block.Blocks.DIRT_PATH;
	}

	static boolean natural(BlockState s) {
		return ground(s) || s.is(net.minecraft.tags.BlockTags.SAND) || s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
			|| s.is(net.minecraft.tags.BlockTags.BASE_STONE_NETHER) || s.is(net.minecraft.tags.BlockTags.TERRACOTTA) || s.is(net.minecraft.tags.BlockTags.ICE)
			|| s.is(net.minecraft.tags.BlockTags.SNOW) || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.OVERWORLD_NATURAL_LOGS)
			|| s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || s.is(net.minecraft.world.level.block.Blocks.GRAVEL) || s.is(net.minecraft.world.level.block.Blocks.CLAY)
			|| s.is(net.minecraft.world.level.block.Blocks.SANDSTONE) || s.is(net.minecraft.world.level.block.Blocks.RED_SANDSTONE)
			|| s.is(net.minecraft.world.level.block.Blocks.SOUL_SAND) || s.is(net.minecraft.world.level.block.Blocks.SOUL_SOIL) || s.is(net.minecraft.world.level.block.Blocks.OBSIDIAN)
			|| s.is(net.minecraft.world.level.block.Blocks.BEDROCK) || s.is(net.minecraft.world.level.block.Blocks.WATER);
	}

	/** How many cells of the 9x9 around (x,z) have a built (non-natural) floor. */
	private static int builtCover(Minecraft mc, int x, int z, int fy) {
		int cover = 0;
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				BlockState c = floorAt(mc, x + dx, z + dz, fy);
				if (c != null && !natural(c)) cover++;
			}
		}
		return cover;
	}

	/** Returns true when a built path/floor is within reach (the caller then quiets everything else a little). Only built blocks make sound; natural ground beside the path stays silent. */
	public static boolean ping(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		if (!cfg.floorEdge || !p.onGround()) return false;
		int fy = p.getBlockY() - 1;
		BlockState here = floorAt(mc, p.getBlockX(), p.getBlockZ(), fy);
		if (here == null) return false;
		boolean onPath = !natural(here);
		if (onPath) { // a roof, plaza or big platform is not a path: stay quiet when built floor covers most of the 9x9 around you
			int cover = 0;
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					BlockState c = floorAt(mc, p.getBlockX() + dx, p.getBlockZ() + dz, fy);
					if (c != null && !natural(c)) cover++;
				}
			}
			if (cover > 40) return false;
		}
		double yaw = Math.toRadians(p.getYRot());
		boolean found = onPath;
		int played = 0;
		for (int deg : RAYS) {
			double a = yaw + Math.toRadians(deg), dx = -Math.sin(a), dz = Math.cos(a);
			BlockState last = null;
			double lastD = 0;
			for (double d = 1.5; d <= 7.0; d += 0.5) {
				int x = (int) Math.floor(p.getX() + dx * d), z = (int) Math.floor(p.getZ() + dz * d);
				BlockState s = floorAt(mc, x, z, fy);
				if (s == null) break; // drop or water: the ledge cue handles it
				boolean built = !natural(s);
				if (onPath) {
					if (!built) break; // the path ends: sound the last path block, nothing from the ground beside it
					last = s; lastD = d;
				} else if (built) {
					if (builtCover(mc, x, z, fy) > 40 || s.is(net.minecraft.tags.BlockTags.SLABS) || s.is(net.minecraft.tags.BlockTags.STAIRS)) break; // a slab roof, plaza or platform is not a path: stay quiet about it
					last = s; lastD = d; // path begins here
					break;
				}
			}
				if (last != null && played < 3) {
				found = true;
				int x = (int) Math.floor(p.getX() + dx * lastD), z = (int) Math.floor(p.getZ() + dz * lastD);
				SoundEvent snd = last.getSoundType().getStepSound();
				float vol = Math.min(1f, cfg.volume * 1.5f * (1.15f - (float) lastD / 7.5f));
								if (cfg.debug) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("path: " + last.getBlock().getDescriptionId() + " (" + (onPath ? "on path" : "off path") + ", here=" + here.getBlock().getDescriptionId() + ") -> " + snd.location().getPath() + " vol " + vol));
				Cues.play(mc, snd, vol, Cues.materialPitch(snd), p.getX() + dx * Math.min(lastD, 1.0), fy + 0.2, p.getZ() + dz * Math.min(lastD, 1.0)); // under your feet and just ahead (toward the bell), never out where the block is
				played++;
			}
		}
		return found;
	}
}
