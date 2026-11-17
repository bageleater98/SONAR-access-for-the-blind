package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.BlockGetter;

/**
 * WALKING INTO THINGS. Pushing into a block (walking straight in or sliding along it): a thud every second. Two thuds when the wall is taller than you can
 * jump (more than about one block: a block with a slab on top counts); one thud when any part of what you touch is a single block you could jump onto.
 * Tapping forward into a wall instead of holding it: the second tap against the same wall thuds straight away. Two thuds in a row (still pushing): you are
 * moved to the middle of your block and turned to face straight north, east, south or west, whichever is nearest. Opening a door squares you up to it too.
 */
final class Bump {
	private Bump() {}

	private static int pushTicks, thudAgain, thudRun, lastContact = -1000, lastCentered = -1000;
	private static double contactX, contactZ;

	static void tick(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		int tick = SonarClient.tick;
		if (thudAgain > 0 && --thudAgain == 0) thud(mc, cfg, p);
		var o = mc.options;
		double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0), sr = (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0);
		if (!cfg.stuckCue || (f == 0 && sr == 0) || p.isPassenger()) { pushTicks = 0; if (tick - lastContact > 40) thudRun = 0; return; }
		if (p.onClimbable()) { pushTicks = 0; thudRun = 0; return; } // on a ladder or vine you are pressed against the wall on purpose: no thuds
		if (!p.horizontalCollision) { pushTicks = 0; if (tick - lastContact > 40) thudRun = 0; return; }
		pushTicks++;
		// a new tap into the wall you touched a moment ago (within 2 s, without walking away): thud now instead of waiting for a long push
		boolean tap = pushTicks == 1 && tick - lastContact <= 40 && Math.hypot(p.getX() - contactX, p.getZ() - contactZ) < 0.6;
		lastContact = tick; contactX = p.getX(); contactZ = p.getZ();
		if (!tap && !(pushTicks >= 8 && (pushTicks - 8) % 20 == 0)) return;
		double yaw = Math.toRadians(p.getYRot());
		double dx = f * -Math.sin(yaw) + sr * -Math.cos(yaw), dz = f * Math.cos(yaw) + sr * -Math.sin(yaw);
		double len = Math.max(1e-6, Math.hypot(dx, dz));
		dx /= len; dz /= len;
		var lv = Sense.hear(mc); // the wall as Sonar hears it: a guide block (barrier) is not there (Sense)
		int feet = (int) Math.floor(p.getY() + 0.01);
		boolean climbable = false, tall = false, heard = false, guide = false;
		var gp = new BlockPos.MutableBlockPos();
		for (double off : new double[]{-0.29, 0.0, 0.29}) { // across your width (you are 0.6 wide), just past your front: every block you could be touching
			double sx = p.getX() + dx * 0.55 - dz * off, sz = p.getZ() + dz * 0.55 + dx * off;
			int bx = (int) Math.floor(sx), bz = (int) Math.floor(sz);
			if (Sense.guideAt(lv, gp.set(bx, feet, bz)) || Sense.guideAt(lv, gp.set(bx, feet + 1, bz))) guide = true;
			double top = wallTop(lv, bx, feet, bz) - p.getY(); // how high this part of the wall reaches above your feet
			if (top <= 0.6) continue; // a slab or carpet you step onto
			heard = true;
			int ty = (int) Math.floor(p.getY() + top - 0.01); // the block that forms the top
			if (top <= 1.1 && !blocks(lv, bx, ty + 1, bz) && !blocks(lv, bx, ty + 2, bz)) climbable = true; // one block with room above it: jump up
			if (top > 1.1) tall = true; // more than a block: you can not jump it
		}
		if (guide && !heard) { Tutorial.event(Tutorial.Ev.GUIDE_PUSH, 0); return; } // only a guide block in the way: silent, no thud and no squaring up (Sense)
		thud(mc, cfg, p);
		Tutorial.event(Tutorial.Ev.BUMP, tall && !climbable ? 2 : 1);
		if (tall && !climbable) thudAgain = 4; // too tall everywhere you touch: second thud
		if (++thudRun >= 2 && cfg.centerOnBump && tick - lastCentered >= Tuning.CENTER_COOLDOWN) { // at most once every 1.5 s
			centerAndSquare(p);
			thudRun = 0; lastCentered = tick;
			Tutorial.event(Tutorial.Ev.SQUARED, 0);
		}
	}

	/** Top of the wall in column x,z: the highest collision top of the blocks stacked from your feet level up (3 blocks), stopping at the first gap. */
	static double wallTop(BlockGetter lv, int x, int feet, int z) {
		double top = feet;
		for (int y = feet; y <= feet + 2; y++) {
			var bp = new BlockPos(x, y, z);
			var shape = lv.getBlockState(bp).getCollisionShape(lv, bp);
			if (shape.isEmpty() || y + shape.min(Direction.Axis.Y) > top + 0.01) break;
			top = y + shape.max(Direction.Axis.Y);
		}
		return top;
	}

	/** A block at x,y,z that stops you (it has any collision at all). */
	static boolean blocks(BlockGetter lv, int x, int y, int z) {
		var bp = new BlockPos(x, y, z);
		return !lv.getBlockState(bp).getCollisionShape(lv, bp).isEmpty();
	}

	/**
	 * Opening a door: you are turned straight at it, centred on its doorway, and moved right up against its block (0 blocks away), so the doorway is directly ahead
	 * and the next step goes through it. Backs off a little if something is in the way.
	 */
	static void centerOnDoor(LocalPlayer p, BlockPos door) {
		if (p.isPassenger()) return;
		double dx = door.getX() + 0.5 - p.getX(), dz = door.getZ() + 0.5 - p.getZ();
		boolean alongX = Math.abs(dx) >= Math.abs(dz);
		int sx = alongX ? (dx > 0 ? 1 : -1) : 0, sz = alongX ? 0 : (dz > 0 ? 1 : -1); // which way the door lies
		float yaw = alongX ? (sx > 0 ? -90f : 90f) : (sz > 0 ? 0f : 180f);
		for (double back = 0.31; back <= 0.5; back += 0.04) { // 0.3 is half your width: touching the door's block; a little further back if that is blocked
			double nx = alongX ? door.getX() + (sx > 0 ? -back : 1 + back) : door.getX() + 0.5, nz = alongX ? door.getZ() + 0.5 : door.getZ() + (sz > 0 ? -back : 1 + back);
			if (p.level().noCollision(p, p.getBoundingBox().move(nx - p.getX(), 0, nz - p.getZ()))) { p.setPos(nx, p.getY(), nz); break; }
		}
		p.setYRot(yaw); p.yRotO = yaw; p.setYHeadRot(yaw);
		if (SonarConfig.cur.debug) Dbg.send(p, Component.literal("door: moved up against it and turned to face it"));
	}

	/** Moves you to the middle of the block you stand on (only if you fit there) and turns you to the nearest of north, east, south, west. Up/down look is kept. */
	static void centerAndSquare(LocalPlayer p) {
		double cx = Math.floor(p.getX()) + 0.5, cz = Math.floor(p.getZ()) + 0.5;
		if (p.onGround() && p.level().noCollision(p, p.getBoundingBox().move(cx - p.getX(), 0, cz - p.getZ()))) p.setPos(cx, p.getY(), cz);
		float yaw = Math.round(p.getYRot() / 90f) * 90f;
		p.setYRot(yaw);
		p.yRotO = yaw;
		p.setYHeadRot(yaw);
		if (SonarConfig.cur.debug) Dbg.send(p, Component.literal("bump: centered and turned to " + Math.round(net.minecraft.util.Mth.wrapDegrees(yaw)) + " deg"));
	}

	private static void thud(Minecraft mc, SonarConfig cfg, net.minecraft.world.entity.player.Player p) {
		Cues.play(mc, SoundEvents.NOTE_BLOCK_BASEDRUM.value(), Math.min(1f, cfg.volume + 0.3f), 0.6f, p.getX(), p.getEyeY(), p.getZ());
	}
}
