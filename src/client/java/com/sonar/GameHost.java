package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;

/** The Director's Host in the game. */
final class GameHost implements Host {
	private final Minecraft mc;

	GameHost(Minecraft mc) { this.mc = mc; }

	@Override public long now() { return System.nanoTime(); }
	@Override public boolean paused() { return mc.isPaused(); }

	@Override public void teleport(TutorialRooms.Room r, double rx, double ry, double rz, float yaw) {
		TutorialWorld.teleport(mc, r, rx, ry, rz, yaw);
		SonarClient.forgetFacing();
		// the enderman's teleport sound, so you know you were moved (a new room, put back after a fall, the far corner)
		mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.ENDERMAN_TELEPORT, 1f, 0.5f));
	}

	@Override public String notLoaded(TutorialRooms.Room r, double rx, double ry, double rz) {
		var p = mc.player;
		if (p == null || mc.level == null) return "no player";
		if (!TutorialWorld.roomBuilt(r)) return "room still being built";
		double tx = r.ox() + rx, ty = r.oy() + ry, tz = r.oz() + rz;
		if (Math.abs(p.getX() - tx) > 1.5 || Math.abs(p.getZ() - tz) > 1.5 || Math.abs(p.getY() - ty) > 2)
			return String.format("not at the spawn yet (at %.1f %.1f %.1f)", p.getX(), p.getY(), p.getZ());
		int cx = p.getBlockX() >> 4, cz = p.getBlockZ() >> 4;
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (!mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) return "chunks not loaded";
		if (!p.onGround() && !p.isInWater() && !p.onClimbable()) return "not on the ground";
		if (!mc.levelRenderer.hasRenderedAllSections()) return "chunks not drawn yet";
		return null;
	}

	@Override public double x() { return mc.player.getX(); }
	@Override public double y() { return mc.player.getY(); }
	@Override public double z() { return mc.player.getZ(); }
	@Override public float yaw() { return mc.player.getYRot(); }
	@Override public boolean onGround() { return mc.player.onGround(); }
	@Override public boolean inWater() { return mc.player.isInWater(); }

	@Override public boolean down(Key k) {
		var o = mc.options;
		return switch (k) {
			case FORWARD -> o.keyUp.isDown();
			case BACK -> o.keyDown.isDown();
			case LEFT -> o.keyLeft.isDown();
			case RIGHT -> o.keyRight.isDown();
			case JUMP -> o.keyJump.isDown();
			case SNEAK -> mc.player.isShiftKeyDown();
		};
	}

	@Override public String floorBlock() {
		var p = mc.player;
		var at = p.blockPosition(); // a carpet or a path block (lower than a full block) is in the block your feet are in
		var st = mc.level.getBlockState(at);
		if (st.getCollisionShape(mc.level, at).isEmpty()) st = mc.level.getBlockState(at.below());
		return BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
	}

	@Override public void chime(int side) {
		var p = mc.player;
		double yaw = Math.toRadians(p.getYRot()), rx = -Math.cos(yaw), rz = -Math.sin(yaw); // your right
		Cues.play(mc, SoundEvents.NOTE_BLOCK_CHIME.value(), 0.8f, 1.2f, p.getX() + side * rx * 4, p.getEyeY(), p.getZ() + side * rz * 4);
	}

	@Override public void passChime() {
		var p = mc.player;
		if (p == null) return;
		for (int k = 0; k < 3; k++)
			Cues.queue(new Cues.Pending(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.6f, new float[]{1.0f, 1.26f, 1.5f}[k], p.getX(), p.getEyeY() + 0.5, p.getZ(), new int[]{k * 3}));
	}

	@Override public void click() {
		var p = mc.player;
		Cues.play(mc, SoundEvents.UI_BUTTON_CLICK.value(), 0.25f, 1.6f, p.getX(), p.getEyeY(), p.getZ());
	}

	@Override public SonarConfig cfg() { return SonarConfig.cur; }
	@Override public void saveCfg() { try { SonarConfig.save(); } catch (Exception ignored) {} }
	@Override public void leave() { Tutorial.leave(mc); }
	@Override public boolean timelineFree() { return Timeline.free(); }
	@Override public void serverCommand(String cmd) { TutorialWorld.command(cmd); }

	@Override public void clearWaypoints() {
		var list = Waypoints.list(mc);
		list.clear();
		Waypoints.save(mc);
		WaypointGuide.stop();
	}

	@Override public boolean screenOpen() { return mc.gui.screen() != null; }
	@Override public boolean guiding() { return WaypointGuide.active(); }
	@Override public boolean waypointsOpen() { return mc.gui.screen() instanceof WaypointsScreen; }
	@Override public void inventory(boolean axe) { TutorialWorld.inventory(mc, axe); }
	@Override public void fallDamage(boolean on) { TutorialWorld.fallDamage(on); }

	@Override public String blockAt(TutorialRooms.Room r, int rx, int ry, int rz) {
		if (mc.level == null) return "air";
		return BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(new net.minecraft.core.BlockPos(r.ox() + rx, r.oy() + ry, r.oz() + rz)).getBlock()).getPath();
	}

	@Override public void fanfare() {
		if (mc.player != null) mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f));
	}
}
