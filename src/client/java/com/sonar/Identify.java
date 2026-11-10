package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Speaks the name and distance of whatever the crosshair points at (block or entity). */
public class Identify {
	public static void run(Minecraft mc) {
		var p = mc.player;
		double reach = SonarConfig.cur.range;
		Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(reach));
		String name = null;
		double best = reach + 1;

		HitResult h = Sense.hear(mc).clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.ANY, p)); // guide blocks (barriers) are not there for Sonar (Sense)
		if (h.getType() == HitResult.Type.BLOCK) {
			var bh = (net.minecraft.world.phys.BlockHitResult) h;
			name = Sense.hear(mc).getBlockState(bh.getBlockPos()).getBlock().getName().getString();
			best = eye.distanceTo(h.getLocation());
		}
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == p) continue;
			var hit = e.getBoundingBox().inflate(0.2).clip(eye, end);
			if (hit.isPresent()) {
				double d = eye.distanceTo(hit.get());
				if (d < best) { best = d; name = e.getName().getString(); }
			}
		}
		String msg = name == null ? "nothing" : name + ", " + Math.round(best) + " blocks";
		Speech.say(mc, Component.literal(msg));
	}
}
