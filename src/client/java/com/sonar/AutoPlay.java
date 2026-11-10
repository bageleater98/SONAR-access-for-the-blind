package com.sonar;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TEST RUNS ONLY (environment variable SONAR_TUTORIAL_AUTOPLAY): a scripted player that plays the whole tutorial in the real game, as a player would. It waits
 * for each instruction to be said, then does what it asks: presses the real keys (R, hold R, H, J), turns, walks the real route with the movement keys
 * (jumping onto blocks, climbing, swimming, opening doors), and takes a screenshot as each lesson starts. Everything it does and every place it gets stuck
 * goes to the log as "AUTOPLAY ..." lines, next to the tutorial's own lines. Never active in normal play.
 */
final class AutoPlay {
	private AutoPlay() {}

	static final boolean ON = System.getenv("SONAR_TUTORIAL_AUTOPLAY") != null;

	/** A job that runs a little each tick; true when finished. */
	interface Job { boolean tick(Minecraft mc); }

	private static final ArrayDeque<Job> jobs = new ArrayDeque<>();
	private static final Set<String> done = new HashSet<>();
	private static String lastLesson, lastVoice;
	private static int headphoneTrial;
	private static long waitUntil;

	static void log(String s) { SonarClient.LOG.info("AUTOPLAY " + s); }

	static void tick(Minecraft mc) {
		if (!ON || mc.player == null || !Tutorial.active()) { release(mc); return; }
		Director d = Tutorial.director;
		if (d == null || d.lesson == null) return;
		if (!d.lesson.id.equals(lastLesson) && d.phase == Director.Phase.PLAY) {
			lastLesson = d.lesson.id;
			jobs.clear();
			release(mc);
			log(d.lesson.id + ": room ready, screenshot");
			net.minecraft.client.Screenshot.grab(mc, false);
		}
		watchVoice(mc, d);
		if (!jobs.isEmpty()) {
			if (mc.gui.screen() != null && !(mc.gui.screen() instanceof WaypointsScreen)) mc.gui.setScreen(null); // a player closes menus to walk (not the one the waypoints lesson works in)
			if (jobs.peek().tick(mc)) { jobs.poll(); release(mc); }
			return;
		}
		if (d.phase != Director.Phase.PLAY || !d.open() || System.nanoTime() < waitUntil) return;
		String key = d.lesson.id + "#" + d.step;
		if (!done.add(key)) return;
		log(key + ": acting");
		plan(mc, d);
	}

	/** The headphone check: answers each chime toward its side, a moment after "here it comes". */
	private static void watchVoice(Minecraft mc, Director d) {
		String now = Voice.playingId;
		if (now != null && !now.equals(lastVoice)) {
			lastVoice = now;
			if (now.equals("l0.phones.ready") || now.equals("l0.phones.again")) {
				int side = now.equals("l0.phones.ready") ? -1 : 1;
				jobs.add(waitJob(60));
				jobs.add(tap(side < 0 ? mc.options.keyLeft : mc.options.keyRight, 3));
				log("l0: answering the chime " + (side < 0 ? "left" : "right"));
			}
		}
	}

	/** What the player does for this lesson's current step. */
	private static void plan(Minecraft mc, Director d) {
		var o = mc.options;
		String id = d.lesson.id;
		int s = d.step;
		switch (id) {
			case "l1" -> { jobs.add(turnTo(180)); jobs.add(waitJob(20)); jobs.add(walk(d, "north wall")); jobs.add(push(o.keyUp, 50)); }
			case "l2" -> { jobs.add(click(SonarClient.scanKey)); jobs.add(waitJob(200)); jobs.add(walk(d, "at the wall")); jobs.add(click(SonarClient.scanKey)); }
			case "l3" -> { jobs.add(click(SonarClient.scanKey)); jobs.add(waitJob(30)); jobs.add(walk(d, "through the opening")); }
			case "l4" -> { jobs.add(click(SonarClient.scanKey)); jobs.add(waitJob(30)); jobs.add(walk(d, "at the wooden wall")); jobs.add(turnTo(180)); jobs.add(push(o.keyUp, 50)); }
			case "tree" -> { jobs.add(walk(d, "by the tree")); jobs.add(chop(d)); }
			case "l5" -> {
				if (s == 0) jobs.add(hold(SonarClient.scanKey, 46));
				else jobs.add(walk(d, "by the pillar"));
			}
			case "hedges" -> jobs.add(walk(d, "at the gate"));
			case "l6" -> jobs.add(walk(d, new String[]{"on the step", "top of the stairs", "top of the ladder", "in the cottage"}[Math.min(s, 3)]));
			case "l7" -> {
				if (s == 0) jobs.add(walk(d, "near the lip"));
				else if (s == 1) { jobs.add(sneakTo(d, 6.5, 7, 10.35)); }
				else if (s == 2) { jobs.add(sneakTo(d, 10.5, 7, 10.5)); jobs.add(sneakTo(d, 10.5, 7, 2.5)); }
				else { jobs.add(click(SonarClient.ledgeKey)); jobs.add(waitJob(20)); jobs.add(click(SonarClient.ledgeKey)); }
			}
			case "l8" -> jobs.add(walk(d, new String[]{"big room", "garden"}[Math.min(s, 1)]));
			case "l9" -> jobs.add(walk(d, "the court"));
			case "l10" -> {
				if (s == 0) { jobs.add(walk(d, "by the shed")); jobs.add(turnTo(180)); jobs.add(push(o.keyUp, 30)); }
				else if (s == 1) jobs.add(walk(d, "in the gazebo"));
				else jobs.add(walk(d, "in the cottage"));
			}
			case "l11" -> jobs.add(walk(d, new String[]{"in the big tunnel", "round the corner", "lower cave"}[s]));
			case "l12" -> jobs.add(walk(d, "at the well"));
			case "l13" -> jobs.add(walk(d, s == 0 ? "by the lava" : "far bank"));
			case "l14" -> jobs.add(walk(d, "by the dummy"));
			case "l15" -> {
				if (s == 0 || s == 2) jobs.add(once(m -> { m.gui.setScreen(new WaypointsScreen(null)); log("l15: opened Waypoints"); }));
				else if (s == 1) { jobs.add(waitJob(40)); jobs.add(once(m -> { Waypoints.add(m, "fountain"); Tutorial.event(Tutorial.Ev.WAYPOINT_ADDED, 0); m.gui.setScreen(null); log("l15: added waypoint fountain, closed the menu"); })); }
				else if (s == 3) { jobs.add(waitJob(40)); jobs.add(once(m -> { var l = Waypoints.list(m); m.gui.setScreen(null); if (!l.isEmpty()) WaypointGuide.start(m, l.get(0)); log("l15: guiding to the waypoint"); })); }
				else jobs.add(walk(d, "by the fountain"));
			}
			case "l17" -> jobs.add(walk(d, "finish"));
			default -> {}
		}
	}

	// ---------------------------------------------------------------- jobs

	/** Chops the tree's trunk: aims at each log still standing (eye to its centre) and holds the attack button until it breaks. Gives up after 30 s. */
	private static Job chop(Director d) {
		var room = d.lesson.room;
		int[] t = {0};
		return mc -> {
			var p = mc.player;
			if (++t[0] > 600) { log(d.lesson.id + ": STUCK chopping (" + Lessons.trunkLeft(d) + " logs left)"); mc.options.keyAttack.setDown(false); return true; }
			for (int[] b : TutorialRooms.TRUNK) {
				if (!d.host.blockAt(room, b[0], b[1], b[2]).endsWith("_log")) continue;
				double dx = room.ox() + b[0] + 0.5 - p.getX(), dy = room.oy() + b[1] + 0.5 - p.getEyeY(), dz = room.oz() + b[2] + 0.5 - p.getZ();
				p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
				p.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
				mc.options.keyAttack.setDown(true);
				return false;
			}
			mc.options.keyAttack.setDown(false);
			p.setXRot(0);
			log(d.lesson.id + ": chopped the trunk down");
			return true;
		};
	}

	private static Job waitJob(int ticks) { int[] t = {ticks}; return mc -> --t[0] <= 0; }

	private static Job once(java.util.function.Consumer<Minecraft> a) { return mc -> { a.accept(mc); return true; }; }

	private static Job click(KeyMapping k) {
		return mc -> { KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(k.saveString())); log("pressed " + k.getName()); return true; };
	}

	private static Job tap(KeyMapping k, int ticks) { int[] t = {0}; return mc -> { k.setDown(++t[0] <= ticks); return t[0] > ticks; }; }

	private static Job hold(KeyMapping k, int ticks) { log("holding " + k.getName() + " " + ticks + " ticks"); return tap(k, ticks); }

	private static Job push(KeyMapping k, int ticks) { return tap(k, ticks); }

	private static Job setting(java.util.function.Consumer<SonarConfig> change, String label) {
		return mc -> {
			change.accept(SonarConfig.cur);
			try { SonarConfig.save(); } catch (Exception ignored) {}
			Tutorial.event(Tutorial.Ev.SETTING, 1, label);
			log("changed setting " + label);
			return true;
		};
	}

	private static Job turnTo(float target) {
		return mc -> {
			var p = mc.player;
			float diff = net.minecraft.util.Mth.wrapDegrees(target - p.getYRot());
			float step = Math.max(-8, Math.min(8, diff)); // turn smoothly, like a mouse
			p.setYRot(p.getYRot() + step);
			return Math.abs(diff) < 2;
		};
	}

	private static Job sneakTo(Director d, double rx, double ry, double rz) {
		Job w = walkTo(d, rx, ry, rz, "sneaking to " + rx + "," + rz, true);
		return mc -> { mc.options.keyShift.setDown(true); boolean f = w.tick(mc); if (f) { mc.options.keyShift.setDown(false); } return f; };
	}

	private static Job walk(Director d, String spot) {
		var s = d.lesson.room.spot(spot);
		return walkTo(d, s.x(), s.y(), s.z(), "walking to " + spot, false);
	}

	/**
	 * Walks to room coordinates rx, ry, rz by the route the jingle would take (ladders and swimming allowed), steering with the movement keys: jumps when it
	 * bumps something, holds jump in water, slides down ladders, opens doors in the way. No progress for 8 s: logs it, then teleports there (a real player
	 * would be stuck there too: that is a finding).
	 */
	private static Job walkTo(Director d, double rx, double ry, double rz, String what, boolean careful) {
		var room = d.lesson.room;
		double gx = room.ox() + rx, gy = room.oy() + ry, gz = room.oz() + rz;
		return new Job() {
			List<double[]> path;
			PlayerChime.Search search;
			int i, stall;
			double bestD = Double.MAX_VALUE;
			boolean started;

			public boolean tick(Minecraft mc) {
				var p = mc.player;
				if (!started) { started = true; log(d.lesson.id + ": " + what); }
				if (path == null) {
					if (search == null) {
						search = new PlayerChime.Search(new BlockCache(mc.level), p.getX(), p.getY(), p.getZ(), new PlayerChime.Tgt(null, gx, gy, gz, gy + 1.6), true, true);
						search.swim = true;
					}
					if (!search.run(60000)) return false;
					path = search.path();
					if (path == null || path.isEmpty()) { log(d.lesson.id + ": NO ROUTE " + what + " (teleporting)"); teleport(mc); return true; }
					i = 0;
				}
				double dGoal = Math.hypot(p.getX() - gx, p.getZ() - gz);
				if (dGoal < 0.6 && Math.abs(p.getY() - gy) < 1.2) { release(mc); return true; }
				if (dGoal < bestD - 0.2) { bestD = dGoal; stall = 0; } else if (++stall > 160) { log(d.lesson.id + ": STUCK " + what + String.format(" at %.1f %.1f %.1f", room.rx(p.getX()), room.ry(p.getY()), room.rz(p.getZ())) + " (teleporting)"); teleport(mc); return true; }
				while (i < path.size() - 1 && Math.hypot(path.get(i)[0] - p.getX(), path.get(i)[2] - p.getZ()) < 0.45) i++;
				double[] t = i < path.size() ? path.get(i) : new double[]{gx, gy, gz};
				if (i >= path.size() - 1) t = new double[]{gx, gy, gz};
				double dx = t[0] - p.getX(), dz = t[2] - p.getZ();
				float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
				p.setYRot(yaw);
				p.setYHeadRot(yaw);
				boolean below = t[1] < p.getY() - 0.6 && Math.hypot(dx, dz) < 0.5;
				mc.options.keyUp.setDown(!(below && p.onClimbable())); // on a ladder going down: let go and slide
				mc.options.keySprint.setDown(false);
				boolean jump = p.isInWater() || (p.horizontalCollision && p.onGround()) || (p.onClimbable() && t[1] > p.getY() + 0.5);
				mc.options.keyJump.setDown(jump);
				// a closed door in the way: open it, as a player would (right-click)
				BlockPos ahead = BlockPos.containing(p.getX() + Math.signum(dx) * 0.8, p.getY() + 0.5, p.getZ() + Math.signum(dz) * 0.8);
				var st = mc.level.getBlockState(ahead);
				if (st.is(BlockTags.DOORS) && st.hasProperty(net.minecraft.world.level.block.DoorBlock.OPEN) && !st.getValue(net.minecraft.world.level.block.DoorBlock.OPEN) && p.tickCount % 10 == 0) {
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(ahead), Direction.getApproximateNearest(-dx, 0, -dz), ahead, false));
					log(d.lesson.id + ": opened a door");
				}
				return false;
			}

			void teleport(Minecraft mc) { release(mc); TutorialWorld.teleport(mc, room, rx, ry, rz, mc.player.getYRot()); }
		};
	}

	/** Lets go of every key the autoplayer may hold. */
	static void release(Minecraft mc) {
		if (mc.options == null) return;
		for (KeyMapping k : new KeyMapping[]{mc.options.keyUp, mc.options.keyLeft, mc.options.keyRight, mc.options.keyJump, mc.options.keyShift, mc.options.keySprint, mc.options.keyAttack})
			if (k.isDown()) k.setDown(false);
		if (SonarClient.scanKey != null && SonarClient.scanKey.isDown()) SonarClient.scanKey.setDown(false);
	}
}
