package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * THE HELPER KEY (V). A tap: the name and distance of whatever you are facing (block, animal, monster, player, a dropped item).
 * Held: where you are: what is around you on each side, your coordinates and the way you face, the biome, the time of day, and how far your waypoint is.
 */
final class Identify {
	private Identify() {}

	/** How far the tap reaches (blocks). */
	static final double REACH = 48;

	static void facing(Minecraft mc) {
		var p = mc.player;
		Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(REACH));
		String name = null;
		double best = REACH + 1;
		var world = Sense.hear(mc); // guide blocks (barriers) are not there for Sonar (Sense)
		HitResult h = world.clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.ANY, p));
		if (h.getType() == HitResult.Type.BLOCK) {
			var bh = (net.minecraft.world.phys.BlockHitResult) h;
			name = world.getBlockState(bh.getBlockPos()).getBlock().getName().getString();
			best = eye.distanceTo(h.getLocation());
		}
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == p || e.isInvisible()) continue;
			var hit = e.getBoundingBox().inflate(0.2).clip(eye, end);
			if (hit.isPresent()) {
				double d = eye.distanceTo(hit.get());
				if (d < best) { best = d; name = e instanceof net.minecraft.world.entity.item.ItemEntity ie ? "Dropped " + ie.getItem().getHoverName().getString() : e.getName().getString(); }
			}
		}
		Speech.say(mc, Component.literal(facingLine(name, best)));
		Tutorial.event(Tutorial.Ev.IDENTIFY, 0);
	}

	/** "Oak Door, 3 blocks", "Zombie, 1 block", "Stone, right here", or that nothing is in reach. */
	static String facingLine(String name, double dist) {
		if (name == null) return "Nothing within " + (int) REACH + " blocks";
		long n = Math.round(dist);
		return name + (n <= 0 ? ", right here" : n == 1 ? ", 1 block" : ", " + n + " blocks");
	}

	/** Held: said from a finished scan of the surroundings (which is not played). */
	static void where(Minecraft mc, Scan s) {
		var p = mc.player;
		StringBuilder sb = new StringBuilder(Cues.summary(mc, s));
		sb.append(" Facing ").append(Announce.compass(p.getYRot())).append(". ");
		sb.append("X ").append(p.getBlockX()).append(", Y ").append(p.getBlockY()).append(", Z ").append(p.getBlockZ()).append(". ");
		String biome = mc.level.getBiome(p.blockPosition()).unwrapKey().map(k -> k.identifier().getPath().replace('_', ' ')).orElse(null);
		if (biome != null) sb.append(biome).append(". ");
		if (mc.level.dimensionType().hasSkyLight()) sb.append(timeOfDay(mc.level.getDefaultClockTime())).append(". ");
		if (WaypointGuide.active()) {
			int d = Waypoints.distance(mc, WaypointGuide.target);
			sb.append("Guiding to ").append(WaypointGuide.target.name).append(d < 0 ? ", in another dimension." : ", " + d + " blocks.");
		}
		Speech.say(mc, Component.literal(sb.toString().trim()));
		Tutorial.event(Tutorial.Ev.IDENTIFY, 1);
	}

	/** The part of the day for a clock time (ticks; a day is 24000, sunrise at 0). */
	static String timeOfDay(long clock) {
		long t = Math.floorMod(clock, 24000L);
		return t < 5000 ? "Morning" : t < 7000 ? "Midday" : t < 11500 ? "Afternoon" : t < 13000 ? "Sunset" : t < 22500 ? "Night" : "Sunrise";
	}
}
