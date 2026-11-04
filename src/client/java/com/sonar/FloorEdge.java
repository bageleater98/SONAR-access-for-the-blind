package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.state.BlockState;

/** Part of the sonar ping: plays the sound of an unusual floor material (stone path, sand, gravel, planks...) from where it begins, ahead and to the sides. Plain grass/dirt ground is ignored. */
public class FloorEdge {
	private static final int[] RAYS = {0, -40, 40}; // degrees relative to where you face (ahead only, never sideways)

	/** The sound a path block makes in the ripple: the dirt path's step sound is very faint, so it plays its louder block-breaking sound; everything else uses its step sound. */
	private static SoundEvent rippleSound(BlockState s) {
		return s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) ? s.getSoundType().getBreakSound() : s.getSoundType().getStepSound();
	}

	private static int foundY; // height of the floor block the last floorAt() call found

	private static BlockState floorAt(Minecraft mc, int x, int z, int feetY) {
		var pos = new BlockPos.MutableBlockPos();
		for (int y = feetY; y >= feetY - 3; y--) {
			BlockState s = mc.level.getBlockState(pos.set(x, y, z));
			if (!s.getCollisionShape(mc.level, pos).isEmpty()) { foundY = y; return s; }
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
		return (ground(s) && !s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) || s.is(net.minecraft.tags.BlockTags.SAND) || s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
			|| s.is(net.minecraft.tags.BlockTags.BASE_STONE_NETHER) || s.is(net.minecraft.tags.BlockTags.TERRACOTTA) || s.is(net.minecraft.tags.BlockTags.ICE)
			|| s.is(net.minecraft.tags.BlockTags.SNOW) || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.OVERWORLD_NATURAL_LOGS)
			|| s.is(net.minecraft.world.level.block.Blocks.GRAVEL) || s.is(net.minecraft.world.level.block.Blocks.CLAY)
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
		if (!cfg.floorEdge) return false;
		var p = mc.player;
		var trail = PathGuide.sparseTrail(p.getX(), p.getZ(), Math.toRadians(p.getYRot()), false);
		if (trail == null && cfg.debug && PathGuide.active()) p.sendSystemMessage(net.minecraft.network.chat.Component.literal("ripple: three-line mode (" + PathGuide.trailWhy + ")"));
		if (trail != null) { // a trail of separate blocks: ripple along the actual blocks (the three-line ripple would miss blocks that are not exactly on a line)
			playTrail(mc, cfg, p, trail);
			return true;
		}
		hits = 0;
		boolean found = rayPing(mc, cfg);
		if (hits == 0) { // the three lines found nothing to sound (you are between blocks, or standing off the trail): ripple along the trail blocks themselves instead, one per distance step
			var forced = PathGuide.sparseTrail(p.getX(), p.getZ(), Math.toRadians(p.getYRot()), true);
			if (forced != null) {
				if (cfg.debug) p.sendSystemMessage(net.minecraft.network.chat.Component.literal("ripple: three lines found nothing, using the trail blocks"));
				playTrail(mc, cfg, p, forced);
				return true;
			}
		}
		return found;
	}

	private static int hits; // rays that found a path block in the last three-line ripple

	/** Ripples along trail blocks {x, floor y, z, distance}, nearest first, one tick per block of distance, fading. */
	private static void playTrail(Minecraft mc, SonarConfig cfg, net.minecraft.client.player.LocalPlayer p, java.util.List<double[]> trail) {
		var tp = new net.minecraft.core.BlockPos.MutableBlockPos();
		int played = 0;
		var mv = p.getDeltaMovement();
		if (cfg.debug) {
			double yr = Math.toRadians(p.getYRot()), tfx = -Math.sin(yr), tfz = Math.cos(yr);
			StringBuilder sb = new StringBuilder();
			for (double[] c : trail) {
				double dx = c[0] + 0.5 - p.getX(), dz = c[2] + 0.5 - p.getZ();
				sb.append(String.format("[fwd %.1f lat %.1f dy %.0f] ", dx * tfx + dz * tfz, dx * -Math.cos(yr) + dz * -Math.sin(yr), c[1] + 1 - p.getY()));
				if (sb.length() > 400) break;
			}
			p.sendSystemMessage(net.minecraft.network.chat.Component.literal("ripple: trail, " + trail.size() + " blocks | " + sb));
		}
		for (double[] c : trail) {
			if (played >= 12) break;
			BlockState bs = mc.level.getBlockState(tp.set((int) c[0], (int) c[1], (int) c[2]));
			SoundEvent bsnd = rippleSound(bs);
			float v = Math.min(1f, cfg.volume * 1.5f) * (float) Math.pow(0.9, c[3] - 3.0);
			float pt = Cues.materialPitch(bsnd) * (float) Math.pow(0.98, c[3] - 3.0);
			int delay = (int) Math.round(c[3] - 3.0);
			if (delay <= 0) Cues.play(mc, bsnd, v, pt, c[0] + 0.5, c[1] + 0.2, c[2] + 0.5);
			else Cues.pending.add(new Cues.Pending(bsnd, v, pt, c[0] + 0.5 + mv.x * delay, c[1] + 0.2, c[2] + 0.5 + mv.z * delay, new int[]{delay})); // a delayed hit is shifted by how far you will have run by then, so the ripple stays in front of you
			played++;
		}
	}

	private static boolean rayPing(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		int fy = (int) Math.floor(p.getY() - 1.0E-3); // the block under your feet (a dirt path, slab or carpet is lower than a full block, so getBlockY() - 1 would miss it)
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
			int cy = fy; // the floor follows stairs and slopes up and down along the ray
			for (double d = 1.5; d <= 13.5; d += 0.5) {
				int x = (int) Math.floor(p.getX() + dx * d), z = (int) Math.floor(p.getZ() + dz * d);
				BlockState s = floorAt(mc, x, z, cy + 1);
				if (s == null) break; // drop or water: the ledge cue handles it
				cy = foundY;
				boolean built = !natural(s);
				if (onPath) {
					if (!built) { if (d - lastD > 4.0) break; continue; } // the path ends once there is a gap of more than 4 blocks of plain ground (a sparse trail of stepping stones has gaps up to that)
					last = s; lastD = d;
				} else if (built) {
					if (builtCover(mc, x, z, fy) > 40 || s.is(net.minecraft.tags.BlockTags.SLABS) || s.is(net.minecraft.tags.BlockTags.STAIRS)) break; // a slab roof, plaza or platform is not a path: stay quiet about it
					last = s; lastD = d; // path begins here
					break;
				}
			}
				if (last != null && played < 3) {
				hits++;
				found = true;
				int x = (int) Math.floor(p.getX() + dx * lastD), z = (int) Math.floor(p.getZ() + dz * lastD);
				SoundEvent snd = last.getSoundType().getStepSound();
				float vol = Math.min(1f, cfg.volume * 1.5f) * (deg == 0 ? 0.7f : 1f); // the two diagonals lead; straight ahead is softer
								if (cfg.debug) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("path: " + last.getBlock().getDescriptionId() + " (" + (onPath ? "on path" : "off path") + ", here=" + here.getBlock().getDescriptionId() + ") -> " + snd.location().getPath() + " vol " + vol));
				// a guide echo: one hit just in front of you at floor level, then the same sound runs out along the path (block 1, 2, 3 ... up to 6), a tick or two apart and fading
					int steps = (int) Math.min(12, Math.floor(lastD + 0.5)); // the ripple starts 3 blocks ahead and runs out to 12
					int ey = fy;
					for (int i = 1; i <= steps; i++) {
						double px = p.getX() + dx * i, pz = p.getZ() + dz * i;
						BlockState bs = floorAt(mc, (int) Math.floor(px), (int) Math.floor(pz), ey + 1); // each hit uses the sound of the block actually under it; plain ground stays silent
						if (bs == null) break;
						ey = foundY;
						if (natural(bs) || i < 3) continue; // blocks 1 and 2 only keep track of the floor height; nothing sounds beneath or right in front of you
						SoundEvent bsnd = rippleSound(bs);
						float v = vol * (float) Math.pow(0.9, i - 3);
						float pt = Cues.materialPitch(bsnd) * (float) Math.pow(0.98, i - 3);
						if (i == 3) Cues.play(mc, bsnd, v, pt, px, ey + 0.2, pz);
						else Cues.pending.add(new Cues.Pending(bsnd, v, pt, px + p.getDeltaMovement().x * (i - 3), ey + 0.2, pz + p.getDeltaMovement().z * (i - 3), new int[]{i - 3})); // 1 tick per block, rippling outward
					}
				played++;
			}
		}
		return found;
	}
}
