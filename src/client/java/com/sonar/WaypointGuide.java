package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * THE WAYPOINT GUIDE: leads you to a saved waypoint from anywhere, by the player jingle's own route search and lead point.
 *
 * Within reach of the search it leads along the walkable route. Further off, the search (partial) keeps the easiest progress it found toward the waypoint
 * (lowest cost-so-far plus estimate, so it never takes a cave just because it points the right way). The search is redone as you near its end, so the route
 * keeps reaching on ahead of you until the waypoint itself is in reach. Its sound: a pulsing beacon hum, with the path bell's chime raised into a little rising
 * three-note call on top; both from the lead point. Volume: a quiet minimum from the start, up to full at the waypoint, measured from where you started.
 */
final class WaypointGuide {
	private WaypointGuide() {}

	static final SoundEvent HUM = ev("waypoint_hum"), CHIME = ev("waypoint_chime");
	private static SoundEvent ev(String id) { return SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("sonar", id)); }
	/** The three-note call: pitches (of the raised chime: 1.5, 1.89 and 2.25 of the path bell), ticks into the bar, and loudness. */
	static final float[] CALL_PITCH = {1.0f, 1.26f, 1.5f}, CALL_VOL = {1f, 0.9f, 0.85f};
	static final int[] CALL_AT = {0, 3, 6};

	static Waypoints.Wp target;
	private static double startDist;
	private static WaypointRoute wr;
	private static double[] stand; // where you last stood on the ground (a route search started mid-jump starts there)
	private static List<double[]> route;
	private static boolean reached; // the route ends at the waypoint itself (not a spot on the way)
	private static int t;
	private static double walkLeft = -1;
	private static boolean saidNoWay, saidDim;
	private static Vec3 sound;
	private static Hum hum;

	static boolean active() { return target != null; }

	/** Starts guiding to w (from here: the volume scale starts at this distance). */
	static void start(Minecraft mc, Waypoints.Wp w) {
		stop();
		target = w;
		startDist = Math.max(1, dist(mc));
		saidNoWay = false; saidDim = false;
		Speech.say(mc, Component.literal("Guiding to " + w.name + ", " + Math.round(startDist) + " blocks"));
		Tutorial.event(Tutorial.Ev.WAYPOINT_GUIDING, startDist);
	}

	static void stop() {
		target = null; wr = null; stand = null; route = null; sound = null; reached = false; walkLeft = -1;
		if (hum != null) { hum.tv = 0; hum = null; }
	}

	/** A new world or dimension: the search and route belong to the old one. (The target stays: in another dimension it waits and says so.) */
	static void reset() {
		wr = null; stand = null; route = null; sound = null; saidDim = false; reached = false; walkLeft = -1;
		if (hum != null) { hum.tv = 0; hum = null; }
	}

	private static double dist(Minecraft mc) {
		var p = mc.player;
		return Math.sqrt(p.distanceToSqr(target.x + 0.5, target.y - 1, target.z + 0.5));
	}

	/** Volume share for this far from the waypoint, having started `start` blocks away: the quiet minimum at the start (or further), full at the waypoint. */
	static double volume(double dist, double start) {
		double prog = 1 - dist / Math.max(1, start);
		return Tuning.JINGLE_NEAR_VOL * (Tuning.WP_MIN_VOL + (1 - Tuning.WP_MIN_VOL) * Math.max(0, Math.min(1, prog))); // at most the jingle's close level
	}

	/** Arrived: within WP_ARRIVE blocks across, and within 2.5 blocks up or down (your feet against the waypoint, a block above where you stood). */
	static boolean arrived(double feetX, double feetY, double feetZ, Waypoints.Wp w) {
		return Math.hypot(w.x + 0.5 - feetX, w.z + 0.5 - feetZ) <= Tuning.WP_ARRIVE && Math.abs(w.y - 1 - feetY) <= 2.5;
	}

	/**
	 * Can you walk straight on toward (tx,tz) for `len` blocks from your feet? Walks the line (and two lines half a body width either side) a quarter block at a
	 * time, by the route search's own step rule: up one block, down up to three, no liquid, no fence or wall, room for your body. A hill climbed one block at a time
	 * is clear (no turning left and right to climb it); a house wall, a cliff or a river is not.
	 */
	static boolean clearAhead(net.minecraft.world.level.BlockGetter w, double mx, double my, double mz, double tx, double tz, double len) {
		var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		int f0 = PlayerChime.floorUnder(w, mx, my, mz, pos);
		// swimming: not "clear": the planner finds a good place to land (round 15: straight on across a lake brought you to the foot of a cliff). In the air: nothing to check
		if (f0 == BlockRules.NONE) return !w.getBlockState(pos.set((int) Math.floor(mx), (int) Math.floor(my + 0.2), (int) Math.floor(mz))).getFluidState().getType().isSame(net.minecraft.world.level.material.Fluids.WATER);
		double dx = tx - mx, dz = tz - mz, h = Math.max(1e-6, Math.hypot(dx, dz));
		dx /= h; dz /= h;
		len = Math.min(len, h);
		for (double side : new double[]{-0.3, 0, 0.3}) {
			int cur = f0, cx = (int) Math.floor(mx - dz * side), cz = (int) Math.floor(mz + dx * side);
			for (double s = 0.25; s <= len; s += 0.25) {
				int nx = (int) Math.floor(mx + dx * s - dz * side), nz = (int) Math.floor(mz + dz * s + dx * side);
				if (nx == cx && nz == cz) continue;
				int nf = PlayerChime.stepTo(w, nx, nz, cur, pos);
				if (nf == BlockRules.NONE) return false;
				cur = nf; cx = nx; cz = nz;
			}
		}
		return true;
	}

	/** The plan: NEAR (the jingle's whole route to the waypoint), STRAIGHT (the way ahead is open), DETOUR (a short route round what blocks it). */
	enum Mode { NEAR, STRAIGHT, DETOUR }

	static Mode mode(double flatDist, boolean clear) {
		return flatDist <= Tuning.WP_NEAR ? Mode.NEAR : clear ? Mode.STRAIGHT : Mode.DETOUR;
	}


	static void tick(Minecraft mc, SonarConfig cfg) {
		if (target == null) return;
		if (!cfg.waypoints || !cfg.enabled || mc.player == null || mc.level == null) { if (hum != null) hum.tv = 0; return; }
		t++;
		var p = mc.player;
		if (!target.dim.equals(Waypoints.dim(mc))) { // in another dimension: nothing to follow here
			if (!saidDim) { Speech.say(mc, Component.literal(target.name + " is in another dimension")); saidDim = true; }
			if (hum != null) hum.tv = 0;
			return;
		}
		if (arrived(p.getX(), p.getY(), p.getZ(), target)) {
			Speech.say(mc, Component.literal("Arrived at " + target.name));
			Tutorial.event(Tutorial.Ev.WAYPOINT_ARRIVED, 0);
			for (int k = 0; k < 3; k++) Cues.queue(new Cues.Pending(CHIME, Math.min(1f, cfg.volume * cfg.waypointVolume), CALL_PITCH[k] * 1.12f, p.getX(), p.getEyeY() + 0.5, p.getZ(), new int[]{k * 2}));
			stop();
			return;
		}
		double d = dist(mc), tx = target.x + 0.5, tz = target.z + 0.5;
		PlayerChime.Tgt wpT = new PlayerChime.Tgt(null, tx, target.y - 1, tz, target.y + 0.6);
		// the rolling route (WaypointRoute): always a checked route ahead, extended from its end before you get there; never a blind "straight at it"
		if (wr == null || wr.tx != tx || wr.tz != tz) wr = new WaypointRoute(tx, target.y - 1, tz);
		if (p.onGround()) stand = new double[]{p.getX(), p.getY(), p.getZ()};
		String before = wr.last;
		boolean wasSearching = wr.searching();
		wr.tick(new BlockCache(mc.level), p.getX(), p.getY(), p.getZ(), stand, 0);
		if (cfg.debug && wasSearching && !wr.searching()) Dbg.send(p, Component.literal(Dbg.fmt("waypoint: %s (%d spots), side %d, %.0f blocks to %s, route %d cells, dead ends %d", wr.last, wr.lastSpots, wr.plan.side, d, target.name, wr.route == null ? 0 : wr.route.size(), wr.dead.size())));
		if ("no way at all".equals(wr.last) && !"no way at all".equals(before) && !saidNoWay) { Speech.say(mc, Component.literal("No walking way to " + target.name + " from here yet")); saidNoWay = true; }
		if (wr.route != null) { saidNoWay = false; wr.trim(p.getX(), p.getY(), p.getZ()); }
		route = wr.route; reached = wr.reached;
		// where it sounds from: the lead point along the route. Off the route (or at its end before the next stretch is joined on): it holds where it was
		Vec3 me = p.getEyePosition();
		Vec3 want;
		if (route != null) {
			double[] end = route.get(route.size() - 1);
			PlayerChime.Tgt endT = reached ? wpT : new PlayerChime.Tgt(null, end[0] + 0.5, end[1] + 1, end[2] + 0.5, end[1] + 2.6);
			PlayerChime.Lead ld = wr.off(p.getX(), p.getY(), p.getZ()) ? null : PlayerChime.lead(mc, endT, route);
			if (ld == null) { walkLeft = -1; double[] c = route.get(Math.max(0, wr.at(p.getX(), p.getY(), p.getZ()))); want = sound != null ? sound : new Vec3(c[0] + 0.5, c[1] + 1.6, c[2] + 0.5); } // left the route: hold until the new one
			else { want = new Vec3(ld.x(), ld.y(), ld.z()); walkLeft = ld.walk(); }
			// never in your ears: at least WP_MIN_SOUND blocks out, the same way (on rough ground the lead point pulls in to a block or two: round 14 trace)
			double hd = Math.hypot(want.x - me.x, want.z - me.z);
			if (hd < Tuning.WP_MIN_SOUND) { double k = Tuning.WP_MIN_SOUND / Math.max(1e-3, hd); want = hd < 1e-3 ? want : new Vec3(me.x + (want.x - me.x) * k, want.y, me.z + (want.z - me.z) * k); }
		} else if (sound != null) {
			want = sound; // no route yet: the sound stays where it was
		} else {
			if (hum != null) hum.tv = 0; // the very start, before the first route (a second or two): quiet, never a guess
			return;
		}
		double h = Math.hypot(want.x - me.x, want.z - me.z); // never steeper than 1 in 2 from your eyes (as the jingle)
		want = new Vec3(want.x, me.y + Math.max(-0.5 * h, Math.min(0.5 * h, want.y - me.y)), want.z);
		sound = sound == null ? want : Smooth.toward(sound, want, Tuning.SOUND_GLIDE); // always glides: never jumps (a new route, a corner)
		if (cfg.debug && cfg.trace && t % 10 == 0) { // the trace: where you are, the plan, where the sound wants to be and where it is (bearings from you, 0 = toward the waypoint)
			double bw = Math.toDegrees(Math.atan2(tz - me.z, tx - me.x));
			double bWant = Math.toDegrees(Math.atan2(want.z - me.z, want.x - me.x)) - bw, bNow = Math.toDegrees(Math.atan2(sound.z - me.z, sound.x - me.x)) - bw;
			double[] re = route == null ? null : route.get(route.size() - 1);
			SonarClient.LOG.info(Dbg.fmt("SONAR wp trace: at %.1f,%.1f,%.1f following %b side %d searching %b | route %s left %.0f off %b | want %+.0f deg %.1f away, sound %+.0f deg",
				me.x, me.y, me.z, wr.plan.following, wr.plan.side, wr.searching(), re == null ? "none" : (route.size() + " to " + (int) re[0] + "," + (int) re[1] + "," + (int) re[2]),
				walkLeft, wr.off(p.getX(), p.getY(), p.getZ()), ((bWant + 540) % 360) - 180, Math.hypot(want.x - me.x, want.z - me.z), ((bNow + 540) % 360) - 180));
		}
		// the icon over the waypoint itself, with its name
		Markers.showFixed(mc, "waypoint", net.minecraft.world.item.Items.BEACON, target.name, target.x + 0.5, target.y + 1.0, target.z + 0.5);
		// and one on the sound itself, a block above where it plays from (to see where it leads you)
		Markers.showFixed(mc, "waypoint-sound", net.minecraft.world.item.Items.NOTE_BLOCK, "waypoint sound", sound.x, sound.y + 1.0, sound.z);
		// loudness: progress from the start, the shared direction rule (front full, behind quieter), the volume sliders
		double yaw = Math.toRadians(p.getYRot()), fx = -Math.sin(yaw), fz = Math.cos(yaw);
		double dx = sound.x - me.x, dz = sound.z - me.z, hl = Math.max(1e-6, Math.hypot(dx, dz));
		double rel = Math.acos(Math.max(-1, Math.min(1, (dx * fx + dz * fz) / hl)));
		float v = (float) (volume(d, startDist) * Loud.guideFacing(rel) * cfg.volume * cfg.waypointVolume * Voice.duck()); // (quieter while the narrator speaks)
		if (hum == null || hum.isStopped()) { hum = new Hum(sound); mc.getSoundManager().play(hum); }
		hum.tv = Math.min(1f, v * Tuning.WP_HUM_LEVEL); hum.tx = sound.x; hum.ty = sound.y; hum.tz = sound.z;
		int inBar = t % Tuning.WP_CALL_BAR;
		for (int k = 0; k < CALL_AT.length; k++) if (inBar == CALL_AT[k] && v > 0.001f)
			Cues.play(mc, CHIME, Math.min(1f, v * Tuning.WP_CHIME_LEVEL * CALL_VOL[k]), CALL_PITCH[k], sound.x, sound.y, sound.z);
	}

	/** Straight toward the waypoint, 6 blocks out at your eye height. */
	static Vec3 ahead(Vec3 me, double tx, double tz) {
		double dx = tx - me.x, dz = tz - me.z, h = Math.max(1e-6, Math.hypot(dx, dz)), k = Math.min(h, 6) / h;
		return new Vec3(me.x + dx * k, me.y, me.z + dz * k);
	}

	/** The hum: one looping sound that follows the lead point and glides to its loudness (never clicks on or off). */
	static final class Hum extends AbstractTickableSoundInstance {
		float tv;
		double tx, ty, tz;
		Hum(Vec3 at) {
			super(HUM, SoundSource.AMBIENT, RandomSource.create());
			x = tx = at.x; y = ty = at.y; z = tz = at.z;
			looping = true; volume = 0.02f; relative = false;
			attenuation = Attenuation.NONE; // its loudness is set here (how near you are), not by the game's distance fade
		}
		@Override public void tick() {
			volume += (tv - volume) * 0.2f;
			x = Smooth.toward(x, tx, 0.25); y = Smooth.toward(y, ty, 0.25); z = Smooth.toward(z, tz, 0.25);
			if (tv <= 0.001f && volume < 0.01f) stop();
		}
	}
}
