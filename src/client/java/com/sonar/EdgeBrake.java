package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * THE EDGE BRAKE: walking or sprinting straight at a real drop (4+ blocks, or a step down of 2+) within a block and a half slows you to a crawl for half a
 * second, so you can stop. Sneaking, stopping, or turning to the side or away ends it at once; then a short cooldown, so it can never trap you.
 * (KeyboardInputMixin does the slowing while `ticks` is above zero.)
 */
public final class EdgeBrake {
	private EdgeBrake() {}

	/** Ticks of slowing left (read by the mixin). */
	public static int ticks;
	private static int cooldown, dbg;
	private static double edgeX, edgeZ; // the edge being braked for
	private static boolean sprinting, forwardHeld; // were you sprinting, and has forward been held the whole time?

	static void cancel() { ticks = 0; }

	/** After each look at the edges ahead (Ledges.watchLedges): start a brake if you are heading for one. */
	static void consider(Minecraft mc, SonarConfig cfg, LocalPlayer p, Vec3 v) {
		if (ticks > 0 || cooldown > 0) return;
		String why = null;
		if (!cfg.edgeBrake) why = "brake is off";
		else if (!p.onGround()) why = "not on ground";
		else if (p.isShiftKeyDown()) why = "sneaking";
		else if (Math.hypot(v.x, v.z) < 0.05) why = "not moving";
		// the nearest thing ahead worth braking for: a real edge (drop 4+) or a step down of 2+ blocks
		double lip = Float.MAX_VALUE, bx = 0, bz = 0;
		int drop = 0;
		if (Ledges.edgeAheadD != Float.MAX_VALUE && Ledges.edgeAheadDrop >= 4) { lip = Ledges.edgeAheadD; bx = Ledges.edgeAheadX; bz = Ledges.edgeAheadZ; drop = Ledges.edgeAheadDrop; }
		if (Ledges.stepAheadD != Float.MAX_VALUE && Ledges.stepAheadD < lip) { lip = Ledges.stepAheadD; bx = Ledges.stepAheadX; bz = Ledges.stepAheadZ; drop = Ledges.stepAheadDrop; }
		if (why == null && lip == Float.MAX_VALUE) why = "no drop of 2+ blocks ahead of you (within 45 deg)";
		if (why == null && lip > 1.5) why = String.format("drop is %.1f blocks away (needs 1.5)", lip);
		if (why != null) {
			if (cfg.debug && lip != Float.MAX_VALUE && ++dbg % 10 == 0) Dbg.send(p, Component.literal("brake waiting: " + why));
			return;
		}
		Tutorial.event(Tutorial.Ev.BRAKE, drop);
		if (cfg.debug) Dbg.send(p, Component.literal("brake: FIRED, drop " + Math.round(lip * 10) / 10.0 + " blocks ahead, " + drop + " blocks deep"));
		ticks = 10; // half a second
		sprinting = p.isSprinting();
		forwardHeld = mc.options.keyUp.isDown();
		edgeX = bx; edgeZ = bz;
	}

	/** Every tick: while a brake is active, watch for what ends it; afterwards count the cooldown down. */
	static void tick(LocalPlayer p) {
		if (ticks <= 0) { if (cooldown > 0) cooldown--; return; }
		var o = Minecraft.getInstance().options;
		double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0), sr = (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0);
		double yaw = Math.toRadians(p.getYRot());
		double dx = f * -Math.sin(yaw) + sr * -Math.cos(yaw), dz = f * Math.cos(yaw) + sr * -Math.sin(yaw);
		double ex = edgeX - p.getX(), ez = edgeZ - p.getZ();
		double align = (dx * ex + dz * ez) / (Math.max(1e-6, Math.hypot(dx, dz)) * Math.max(1e-6, Math.hypot(ex, ez)));
		if (!o.keyUp.isDown()) forwardHeld = false; // letting go of forward even for a moment means no automatic sprint afterwards
		if (p.isShiftKeyDown() || (f == 0 && sr == 0) || align < 0.5) { // sneaking, stopping, or turning to the side or away ends it at once
			ticks = 0;
			cooldown = 30; // (ended early too: without it the brake fired again every look, a stutter)
			resumeSprint(p, o);
			return;
		}
		p.setSprinting(false);
		if (--ticks == 0) {
			cooldown = 30; // 1.5 s before it can brake again
			resumeSprint(p, o);
		}
	}

	/** If you were sprinting and never let go of forward, keep sprinting once the slowdown ends. */
	private static void resumeSprint(LocalPlayer p, net.minecraft.client.Options o) {
		if (sprinting && forwardHeld && o.keyUp.isDown() && !p.isShiftKeyDown()) p.setSprinting(true);
	}
}
