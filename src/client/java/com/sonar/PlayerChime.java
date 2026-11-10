package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

/**
 * Other players carry a constant, easy-to-follow amethyst footstep jingle. It leads you the way you can actually walk to them: a walking route is worked out (round corners, through
 * doorways, up the staircase), and the jingle plays from the farthest point of that route you can see and walk to in a straight line, like a sound bouncing round the corners
 * toward you. A player up on a balcony is reached by first being led to the bottom of the stairs; miss the stairs and the jingle comes from behind you, back at the stairs.
 * In front the jingle is bright and normal; toward the sides it gets a little lower and duller, and behind you lower and duller still.
 */
public class PlayerChime {
	/** Short looping tune (pitch multipliers), one note per beat. */
	static final float[] TUNE = {1.0f, 1.26f, 1.5f, 2.0f, 1.5f, 1.26f};
	private static int t;
	/** Amethyst footstep with the low thud filtered out (bundled in the mod), and its low-passed copy for sounds behind you. */
	static final net.minecraft.sounds.SoundEvent STEP_SND = ev("step"), STEP_DULL = ev("m.step");
	/** The vanilla amethyst chime (shimmer) at full volume, and its low-passed copy. */
	static final net.minecraft.sounds.SoundEvent JINGLE = ev("chime"), JINGLE_DULL = ev("m.chime");

	private static net.minecraft.sounds.SoundEvent ev(String id) {
		return net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.fromNamespaceAndPath("sonar", id));
	}

	/** The latest route search result per player (the route itself is in `routes`). */
	private static final Map<UUID, Guide> guide = new HashMap<>();

	/** What is known about the way to one player: when the last route search finished, where THEY were then, and whether it found no way at all. */
	static final class Guide {
		int foundAt;
		double theirX, theirY, theirZ;
		boolean unreachable;
	}

	public static void tick(Minecraft mc, SonarConfig cfg) {
		if (!cfg.playerChime || mc.level == null || mc.player == null) return;
		t++;
		var me = mc.player.getEyePosition();
		List<Tgt> near = new ArrayList<>();
		for (Player p : mc.level.players()) {
			if (p == mc.player || p.isSpectator()) continue;
			if (p.getEyePosition().distanceTo(me) <= cfg.playerChimeRange) near.add(new Tgt(p.getUUID(), p.getX(), p.getY(), p.getZ(), p.getEyeY()));
		}
		// TEMPORARY test dummies: a jack o'lantern on a block of coal counts as a player (for testing with nobody else online)
		if (t % 40 == 1) findDummies(mc, cfg);
		for (Tgt d : dummies) if (d.eye().distanceTo(me) <= cfg.playerChimeRange) near.add(d);
		near.sort((a, b) -> Double.compare(a.dist2(mc.player), b.dist2(mc.player)));
		// routes: the nearest 3 players, each re-worked twice a second (a failed search waits 2 s before trying again, so an unreachable player costs little)
		for (int i = 0; i < Math.min(3, near.size()); i++) {
			Tgt p = near.get(i);
			Guide g = guide.get(p.id);
			// a new search when there is no route yet, when THEY have moved a block, or every 5 s as a refresh (an unreachable player is retried every 2 s). You walking
			// along the route never needs one: the lead point follows the stored route from wherever you are; leaving the route forces one (see below)
			int every = g != null && g.unreachable ? 40 : 10;
			boolean moved = g == null || !routes.containsKey(p.id) || Math.abs(p.x - g.theirX) + Math.abs(p.y - g.theirY) + Math.abs(p.z - g.theirZ) >= 1;
			Search sr = searching.get(p.id);
			if (sr == null && (g == null || (t - g.foundAt >= every && (moved || t - g.foundAt >= 100)))) {
				sr = new Search(mc, mc.player, p, false); // walking only first (stairs, ramps, a long way round)
				if (sr.gf == NONE || sr.pendingStart) sr.swimTo(); // you or they stand in water (a swimmer was "unreachable" and heard muffled) / they stand in water (round 16: a friend wading counted as unreachable, so the jingle went muffled): swim to them
				searching.put(p.id, sr);
			}
			if (sr != null) {
				if (!Budget.run(sr)) continue; // not finished: the last route keeps playing meanwhile
				List<double[]> rp = sr.path();
				if (rp == null && !sr.ladders) { // no walking way: ladders and vines too, as a new search
					walkWhy.put(p.id, lastSearch);
					Search lad = new Search(mc, mc.player, p, true);
					if (lad.gf == NONE || lad.pendingStart) lad.swimTo();
					searching.put(p.id, lad);
					continue;
				}
				searching.remove(p.id);
				String why = sr.ladders ? walkWhy.getOrDefault(p.id, "walking no way") + " | " + lastSearch : lastSearch;
				if (cfg.debug) Dbg.send(mc.player, net.minecraft.network.chat.Component.literal("player route: " + why + " (" + sr.expanded + " spots)"));
				Guide ng = new Guide();
				ng.foundAt = t; ng.theirX = p.x; ng.theirY = p.y; ng.theirZ = p.z;
				ng.unreachable = rp == null; // only a search that found NO way at all (walking and climbing) makes them unreachable
				if (rp != null) routes.put(p.id, rp); else routes.remove(p.id);
				guide.put(p.id, ng);
			}
		}
		if (t % 200 == 0) { beatPhase.keySet().removeIf(id -> near.stream().noneMatch(p -> p.id.equals(id))); farS.keySet().retainAll(beatPhase.keySet()); beats.keySet().retainAll(beatPhase.keySet()); walkWhy.keySet().retainAll(searching.keySet()); routes.keySet().removeIf(id -> near.stream().noneMatch(p -> p.id.equals(id))); guide.keySet().removeIf(id -> near.stream().noneMatch(p -> p.id.equals(id))); searching.keySet().removeIf(id -> near.stream().noneMatch(p -> p.id.equals(id))); }
		// EVERY tick: where the jingle leads from right now (it glides smoothly, and its icon with it). Only the sound itself plays on the beat.
		double yaw = Math.toRadians(mc.player.getYRot()), fx = -Math.sin(yaw), fz = Math.cos(yaw);
		Vec3 vel = mc.player.getDeltaMovement();
		for (Tgt p : near) {
			Guide g = guide.get(p.id);
			List<double[]> route = routes.get(p.id);
			// a route stays in use until a new one replaces it (also while a new search is still running, however long it takes: no gap in the middle of a walk)
			boolean routed = g != null && !g.unreachable && route != null && (t - g.foundAt <= 140 || searching.containsKey(p.id));
			boolean unreachable = g != null && g.unreachable;
			Lead ld = routed ? lead(mc, p, route) : null;
			if (routed && ld == null) { // you have left the route (or ran off it while it was worked out): lead back to where it starts, and work out a fresh one from here now
				double[] c0 = route.get(0), end = route.get(route.size() - 1);
				ld = new Lead(c0[0] + 0.5, c0[1] + 1.6, c0[2] + 0.5, end[3] + Math.hypot(c0[0] + 0.5 - mc.player.getX(), c0[2] + 0.5 - mc.player.getZ()), false);
				g.foundAt = Math.min(g.foundAt, t - 100);
			}
			boolean waypoint = ld != null && !ld.atPlayer(); // leading along the way, not yet at the player
			double tx = ld != null ? ld.x() : p.x, ty = ld != null ? ld.y() : p.ey, tz = ld != null ? ld.z() : p.z;
			// the sound never rises or sinks steeper than about 1 in 2 from your eyes (a lead point up a staircase would otherwise sound overhead): same spot ahead, gentler height
			if (waypoint) { double h = Math.hypot(tx - me.x, tz - me.z); ty = me.y + Math.max(-0.5 * h, Math.min(0.5 * h, ty - me.y)); }
			// glide: the sound's spot slides a quarter of the way to its new spot every tick (a jump of 6+ blocks, such as a new route, moves at once)
			double[] lp = lastPos.get(p.id);
			Vec3 sp = lp == null ? new Vec3(tx, ty, tz) : Smooth.towardOrSnap(new Vec3(lp[0], lp[1], lp[2]), new Vec3(tx, ty, tz), Tuning.SOUND_GLIDE, 6);
			tx = sp.x; ty = sp.y; tz = sp.z;
			if (lp == null) lastPos.put(p.id, new double[]{tx, ty, tz}); else { lp[0] = tx; lp[1] = ty; lp[2] = tz; } // reuses its array every tick
			// the icon: a tiny copy of them at the same lead point, every tick; with no route it goes away
			if (ld != null) Markers.showPlayerAt(mc, "jingle " + p.id, p.id, ld.x(), ld.y(), ld.z());
			double walkLeft = ld != null ? ld.walk() : -1;
			if (Tuning.SAY_NAME) sayNameWhenFaced(mc, p, tx, tz, routed ? walkLeft : p.eye().distanceTo(me), fx, fz); // OFF for now: it spoke too often (to be reworked)
			double dx = tx - me.x, dy = ty - me.y, dz = tz - me.z;
			double hl = Math.max(1e-6, Math.hypot(dx, dz));
			// front / side / behind: w = 0 straight ahead, 0.5 to the side, 1 straight behind. Lower and duller the further round it is.
			double rel = Math.acos(Math.max(-1, Math.min(1, (dx * fx + dz * fz) / hl)));
			float w = (float) Loud.behind(rel); // the shared direction rule: how far round behind you
			double straight = p.eye().distanceTo(me);
			// volume = how far along the walk you are: the walking distance left on a fixed scale (WALK_SCALE blocks = faintest), so it grows steadily as you close in.
			// No route at all (unreachable): faint and muffled from their body, wherever you face.
			double target;
			// within NEAR_WALK blocks of walking: the close level (NEAR_VOL). Further: it drops off on a curve, quickly at first, then tapers steadily to the faintest at WALK_SCALE
			// volume follows the SAME distance as the beat (walking distance with a route, straight distance without): close, loud and quick; further, quieter and slower
			// together, fading to silence at the far end. Unreachable: the same curve, a little quieter and muffled.
			// one FIXED scale, so the same walking distance always sounds the same (never depending on how you got there): silent at WALK_SCALE blocks of walking, which is
			// long enough for a winding route to someone at the edge of hearing. Near the edge of hearing itself it fades out separately (below), so it still ends in silence.
			// how far, 0 (within NEAR_WALK) to 1 (silent): whichever says further of the straight line (silent at the edge of hearing) and the walk (silent at WALK_SCALE
			// blocks of walking). Someone 110 blocks away is near silent either way; a winding route sounds further than a direct one; and both depend only on where you
			// are now, so the same spot always sounds the same.
			double farNow = farness(straight, routed ? walkLeft : -1, cfg.playerChimeRange);
			// smoothed: when a new route is found the walking distance can jump (a shorter way): the reading glides there over about a second instead, so the volume
			// AND the beat change smoothly, never with a hiccup
			Double fPrev = farS.get(p.id);
			double far = fPrev == null ? farNow : Smooth.toward(fPrev, farNow, 0.08);
			farS.put(p.id, far);
			target = volumeAt(far) * (routed ? 1 : 0.6);
			// still far off and walking away from the jingle with your back to it: a little quieter, back up as soon as you turn toward it or stop
			double away = -(vel.x * dx + vel.z * dz) / hl;
			if (routed && walkLeft > 24 && away > 0.05 && w > 0.5) target *= 0.6;
			Double cur = vol.get(p.id);
			double v = cur == null ? target : Smooth.toward(cur, target, 0.25); // (the distance reading is already smoothed; this only softens the walking-away dip below)
			vol.put(p.id, v);
			// tempo: close (10 blocks of walking or less) the quick beat, slowing smoothly to one beat a second at the far end: how fast it plays says how near they are.
			// Each tick adds 1/(ticks per beat) to the beat clock; it plays when that reaches 1. The beat speeds up the moment you get closer, not on a fixed grid.
			double every = STEP_MIN + (STEP_MAX - STEP_MIN) * far;
			double ph = beatPhase.getOrDefault(p.id, 1.0) + 1.0 / every;
			if (cfg.debug && t % 40 == 0) Dbg.send(mc.player, net.minecraft.network.chat.Component.literal(Dbg.fmt("player jingle: %s, walk %.0f, straight %.0f, vol %.2f, beat every %.1f ticks, from %.1f,%.1f,%.1f",
				routed ? (waypoint ? "route (leading)" : "route (at the player)") : unreachable ? "UNREACHABLE" : "no route yet", walkLeft, straight, v, every, tx, ty, tz)));
			if (ph < 1) { beatPhase.put(p.id, ph); continue; }
			if (!Timeline.free() && beatWait.merge(p.id, 1, Integer::sum) <= 4) { beatPhase.put(p.id, ph); continue; } // a sonar ping or echo click is playing: wait a tick or two
			beatWait.remove(p.id);
			beatPhase.put(p.id, ph - Math.floor(ph));
			int n = beats.merge(p.id, 1, Integer::sum);
			float pitch = TUNE[n % TUNE.length];
			float pt = pitch * (1f - 0.2f * w);
			float base = (float) (v * Loud.guideFacing(rel)); // the shared direction rule: full in front, about half to the side, a quarter behind (and duller: below)
			float bright = unreachable ? 0f : 1f - w, dull = unreachable ? 1f : w; // the low-passed copy fades in as the sound moves round behind you
			// played from where it really is (inside the player's head, at the turn), with no distance fade from the game: the volume above already says how far
			if (bright > 0.02f) {
				play(mc, STEP_SND, cfg.volume * base * bright * 0.9f * cfg.chimeVolume, pt, tx, ty, tz);
				play(mc, JINGLE, 0.6f * base * bright * cfg.chimeVolume, pt, tx, ty, tz);
			}
			if (dull > 0.02f) {
				play(mc, STEP_DULL, cfg.volume * base * dull * 0.9f * cfg.chimeVolume, pt, tx, ty, tz);
				play(mc, JINGLE_DULL, 0.6f * base * dull * cfg.chimeVolume, pt, tx, ty, tz);
			}
		}
		if (t % 200 == 0) { vol.keySet().removeIf(id -> near.stream().noneMatch(p -> p.id.equals(id))); lastPos.keySet().removeIf(id -> near.stream().noneMatch(p -> p.id.equals(id))); }
	}

	/** Walking distance left at which the jingle is faintest; it grows to full as that drops to 0. */
	private static final double WALK_SCALE = Tuning.JINGLE_FAR;
	/** How far someone sounds, 0 (close: loudest, quickest beat) to 1 (silent, slowest): whichever says further of the straight line (1 at the edge of hearing,
	 *  `range`) and the walk (1 at WALK_SCALE blocks; walk < 0 = no route). Both ramps are straight lines, so the larger of the two never jumps where they cross. */
	static double farness(double straight, double walk, double range) {
		double fs = (straight - NEAR_WALK) / (range - NEAR_WALK), fw = walk < 0 ? 0 : (walk - NEAR_WALK) / (WALK_SCALE - NEAR_WALK);
		return Math.max(0, Math.min(1, Math.max(fs, fw)));
	}

	/** Volume for a farness: NEAR_VOL close, falling smoothly to 0 at farness 1. */
	static double volumeAt(double far) {
		return NEAR_VOL * Loud.shape(far); // the shared fade
	}

	/**
	 * Face the jingle (within about 20 degrees) for 2 seconds and it says who it is and how far: "Alex, 40 blocks". Once; it says it again only after you have looked
	 * away (more than 35 degrees) and back. No key needed.
	 */
	private static void sayNameWhenFaced(Minecraft mc, Tgt p, double tx, double tz, double blocks, double fx, double fz) {
		double dx = tx - mc.player.getX(), dz = tz - mc.player.getZ(), l = Math.max(1e-6, Math.hypot(dx, dz));
		double rel = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (dx * fx + dz * fz) / l))));
		if (rel > 35) { facedFor.remove(p.id); named.remove(p.id); return; }
		if (rel > 20 || named.contains(p.id)) return;
		int n = facedFor.merge(p.id, 1, Integer::sum);
		if (n < Tuning.NAME_AFTER_TICKS) return;
		named.add(p.id);
		var info = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(p.id) : null;
		String who = info != null ? info.getProfile().name() : "test dummy";
		Speech.say(mc, net.minecraft.network.chat.Component.literal(who + ", " + Math.round(blocks) + " blocks"));
	}

	private static final Map<UUID, Integer> facedFor = new HashMap<>();
	private static final java.util.Set<UUID> named = new java.util.HashSet<>();

	/** Walking distance within which the jingle is at its close level and quickest beat. */
	private static final double NEAR_WALK = Tuning.JINGLE_NEAR;
	/** Volume close by (what you heard at 46 blocks in round 7 is now the close level), and the faintest, at WALK_SCALE. */
	private static final double NEAR_VOL = Tuning.JINGLE_NEAR_VOL;
	/** Ticks between beats: close, and at the far end (one a second). */
	private static final int STEP_MIN = Tuning.JINGLE_BEAT_NEAR, STEP_MAX = Tuning.JINGLE_BEAT_FAR;
	private static final Map<UUID, Integer> beats = new HashMap<>(), beatWait = new HashMap<>();
	private static final Map<UUID, Double> beatPhase = new HashMap<>(), farS = new HashMap<>();
	private static final Map<UUID, double[]> lastPos = new HashMap<>();
	private static final Map<UUID, Double> vol = new HashMap<>();

	private static void play(Minecraft mc, net.minecraft.sounds.SoundEvent e, float v, float pitch, double x, double y, double z) {
		mc.getSoundManager().play(new SimpleSoundInstance(e.location(), SoundSource.AMBIENT, Math.min(1f, v * Voice.duck()), pitch, RandomSource.create(), false, 0,
			net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE, x, y, z, false));
	}

	// ---------- walking route ----------

	private static final int NONE = BlockRules.NONE;
	static boolean passable(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) { return BlockRules.passable(w, x, y, z, pos); }

	/** Open water to swim at (x,z), its surface within a block of `ref`+1: the top water block with open air over it. Returned as a "floor" one below that water
	 *  block, so a swimmer is at the same height as someone standing on the bank beside it. NONE if there is no such water. */
	static int surface(net.minecraft.world.level.BlockGetter w, int x, int z, int ref, BlockPos.MutableBlockPos pos) {
		for (int y = ref + 2; y >= ref - 1; y--) {
			if (!w.getBlockState(pos.set(x, y, z)).getFluidState().getType().isSame(net.minecraft.world.level.material.Fluids.WATER)) continue;
			var above = w.getBlockState(pos.set(x, y + 1, z));
			if (above.getFluidState().isEmpty() && above.getCollisionShape(w, pos).isEmpty()) return y - 1;
		}
		return NONE;
	}
	static int stepTo(net.minecraft.world.level.BlockGetter w, int x, int z, int ref, BlockPos.MutableBlockPos pos) { return BlockRules.stepTo(w, x, z, ref, pos); }
	static boolean climbable(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) { return BlockRules.climbable(w, x, y, z, pos); }
	static int floorUnder(net.minecraft.world.level.BlockGetter w, double x, double y, double z, BlockPos.MutableBlockPos pos) { return BlockRules.floorUnder(w, x, y, z, pos); }





	private static long key(int x, int y, int z) {
		return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
	}

	/**
	 * A* over the floor you can walk on, from you to the other player (at most 12000 cells looked at). Returns {x, y, z, walking distance} of the point to sound from:
	 * the farthest point along the route that you can see and could walk to in a straight line (every route cell up to it lies within a block of that line). When that is the end
	 * of the route, it is the player. Null when there is no walking route (then the jingle simply plays from the player).
	 */
	/**
	 * One route search, worked a slice at a time (SLICE spots per tick) so a long route never stalls the game: the old route keeps playing until the new one is ready.
	 * The whole route is still searched (a strict path more than 64 blocks long still works); it just takes a few ticks instead of one long hitch.
	 */
	static final class Search {
		final net.minecraft.world.level.BlockGetter w;
		final Tgt other;
		final boolean ladders;
		/** Partial: when the goal can not be reached (too far, or no way), path() is the way to the reachable spot nearest to it (the waypoint guide: it keeps leading on). */
		final boolean partial;
		/** Partial, a detour: keep the spot NEAREST the goal (a little cost so far breaks ties). The far-waypoint way (lowest total) would sit still behind a wall rather than walk back to a door. */
		boolean nearest;
		/** Swimming allowed (the waypoint guide; never the jingle): open water can be crossed at the water's surface, each block ROUTE_SWIM times a walked one, so land
		 *  is still preferred but a lake can be crossed to a good landing, and a dead-end strip of shore left again (round 15: swimming, it pointed straight at a cliff,
		 *  and on the shore below it found no way at all). Set before the first run. */
		boolean swim;
		/** Far waypoint routes: the partial route never ends under rock (a cave pocket that points the right way: round 16, "led me into a dead end tunnel" twice) nor
		 *  within DEAD_R blocks of a spot already found to be a dead end (`avoid`, packed x,z). Set before the first run. */
		boolean openEnd;
		it.unimi.dsi.fastutil.longs.LongSet avoid;
		static final int DEAD_R = 4;
		/** Waypoint routes: a made path out in the open (not under rock) costs ROUTE_WP_PATH a block, so a path nearby is taken when it goes your way (round 16 wish). */
		boolean paths;
		private boolean pendingStart;
		private int startX, startZ, startFeet;

		/** Searches `m` blocks either side of the start and goal instead of 40 (the waypoint's scout: the way round a lake may lie further out). Before the first run. */
		Search widen(int m) {
			minX = Math.min(startX, gx) - m; maxX = Math.max(startX, gx) + m; minZ = Math.min(startZ, gz) - m; maxZ = Math.max(startZ, gz) + m;
			return this;
		}

		/** Greedy weight on the estimate (1: the best route; 1.5: a route at most about half as long again, found looking at far fewer spots: the waypoint's scout across loaded land). */
		double weight = 1;
		Search greedy(double w) { weight = w; for (Node n : open) n.f = n.g + w * (n.f - n.g); return this; }
		Node best;
		double bestD = Double.MAX_VALUE;
		final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		final int gx, gz;
		int gf;
		int minX, maxX, minZ, maxZ;
		// keyed by the packed block position as a plain number (fastutil, shipped with the game): no boxed Long for every spot looked at
		final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Node> nodes = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
		final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap sides = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		{ sides.defaultReturnValue(-1); }
		final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		Node goal;
		int expanded;
		boolean done, failed;

		Search(Minecraft mc, net.minecraft.client.player.LocalPlayer me, Tgt other, boolean ladders) {
			this(new BlockCache(mc.level), me.getX(), me.getY(), me.getZ(), other, ladders); // each search remembers the blocks it has read
		}

		/** From a spot in any world (the game, or a test world): you stand at x,y,z (your feet). */
		Search(net.minecraft.world.level.BlockGetter w, double mx, double my, double mz, Tgt other, boolean ladders) { this(w, mx, my, mz, other, ladders, false); }

		Search(net.minecraft.world.level.BlockGetter w, double mx, double my, double mz, Tgt other, boolean ladders, boolean partial) {
			this.w = w; this.other = other; this.ladders = ladders; this.partial = partial;
			int sx = (int) Math.floor(mx), sz = (int) Math.floor(mz), sf = floorUnder(w, mx, my, mz, pos);
			// on a ladder or vine (in the air, holding on): the route starts where you are on it, not on the floor at its foot (that made the jingle sound below and behind you while climbing)
			int feet = (int) Math.floor(my + 0.01);
			if (climbable(w, sx, feet, sz, pos)) sf = feet - 1;
			// swimming (allowed only for the waypoint guide; `swim` is set after construction, so the start is fixed up in run() too)
			startX = sx; startZ = sz; startFeet = feet;
			gx = (int) Math.floor(other.x); gz = (int) Math.floor(other.z);
			int g0 = floorUnder(w, other.x, other.y, other.z, pos);
			gf = g0 == NONE && partial ? (int) Math.floor(other.y) - 1 : g0; // a goal in the air or inside a hill (far off, a point toward the waypoint): head for it anyway
			minX = Math.min(sx, gx) - 40; maxX = Math.max(sx, gx) + 40; minZ = Math.min(sz, gz) - 40; maxZ = Math.max(sz, gz) + 40;
			if (gf == NONE) { done = true; failed = true; return; }
			if (sf == NONE) { pendingStart = true; return; } // in water (or the air): run() starts it if swimming is allowed
			Node start = new Node(sx, sf, sz, 0, null);
			start.f = 1.2 * Math.hypot(gx - sx, gz - sz) + Math.abs(gf - sf); // the same estimate as every other spot (partial routes compare them)
			nodes.put(key(sx, sf, sz), start);
			open.add(start);
		}

		/** Works up to `budget` more spots. True when the search is over (found, or no way). */
		boolean run(int budget) { return run(budget, Long.MAX_VALUE); }

		/** As run(budget), but also stops once `maxNanos` of time is used, so a slow patch of world never stalls the game (the game: about 2 ms a tick). */
		boolean run(int budget, long maxNanos) {
			if (pendingStart) {
				pendingStart = false;
				int sf = swim ? surface(w, startX, startZ, startFeet - 1, pos) : NONE;
				if (sf == NONE) { done = true; failed = true; return true; }
				Node start = new Node(startX, sf, startZ, 0, null);
				start.f = weight * (1.2 * Math.hypot(gx - startX, gz - startZ) + Math.abs(gf - sf));
				nodes.put(key(startX, sf, startZ), start);
				open.add(start);
			}
			if (done) return true;
			int stop = expanded + budget;
			long deadline = maxNanos == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + maxNanos;
			while (!open.isEmpty() && expanded < Tuning.ROUTE_MAX && expanded < stop) {
				if ((expanded & 31) == 0 && System.nanoTime() > deadline) break; // 60000: enough for anyone in hearing range, the long way round included
			Node c = open.poll();
			if (c.closed) continue;
			c.closed = true;
			expanded++;
			// partial: the spot with the lowest estimated total cost (cost so far + estimate on). Every block of easy progress lowers it, a hard detour raises it, so the
			// way it keeps is the easiest progress toward the goal, not merely the spot nearest to it (that was a cave under a mountain: near, but awful to walk)
			if (partial) { double sc = nearest ? c.f - 0.9 * c.g : c.f; if (sc < bestD && endable(c)) { bestD = sc; best = c; } }
			if (Math.abs(c.x - gx) <= 1 && Math.abs(c.z - gz) <= 1 && Math.abs(c.y - gf) <= 1) { goal = c; break; }
			// 8 directions: diagonal steps too, so a route at an angle is a straight diagonal line instead of an L ("line up with the stairs first, then walk at them")
			for (int k = 0; k < (ladders ? 10 : 8); k++) {
				boolean climb = k >= 8, diag = k >= 4 && k < 8;
				int ddx = climb ? 0 : DIRS[k][0], ddz = climb ? 0 : DIRS[k][1];
				int nx = c.x + ddx, nz = c.z + ddz;
				if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
				int nf;
				if (k == 8) { // up a ladder or vine: you are in a climbable block and there is room (or more ladder, or a trapdoor) above
					// the ladder may start a block up (you jump to grab the bottom rung): climbable at your feet, or at your head with room at your feet
					boolean grab = climbable(w, c.x, c.y + 1, c.z, pos) || (climbable(w, c.x, c.y + 2, c.z, pos) && passable(w, c.x, c.y + 1, c.z, pos));
					if (!grab || !passable(w, c.x, c.y + 3, c.z, pos)) continue;
					nf = c.y + 1;
				} else if (k == 9) { // down a ladder: the block you stand on is ladder too
					if (!climbable(w, c.x, c.y, c.z, pos)) continue;
					nf = c.y - 1;
				} else {
					nf = next(nx, nz, c.y);
					// onto the top of a ladder going down from a ledge (its top rung level with your floor): you hold on there, then climb down (k == 9). The step
					// rule looks through ladders for a floor at most 3 below, so a longer ladder down used to have no way onto it at all.
					if (nf == NONE && ladders && !diag && climbable(w, nx, c.y, nz, pos) && passable(w, nx, c.y + 1, nz, pos) && passable(w, nx, c.y + 2, nz, pos)) nf = c.y;
				}
				if (nf == NONE) continue;
				if (diag) { // only on the level, and never cutting a corner: both squares beside the diagonal must be walkable at the same height
					if (nf != c.y || next(c.x + ddx, c.z, c.y) != c.y || next(c.x, c.z + ddz, c.y) != c.y) continue;
				}
				// the EASIEST way to walk, not the shortest: built floor (a path, a house floor) is cheaper than grass, every side of a spot that is blocked (wall, rail, drop)
				// costs extra so the route keeps to the middle of stairs, corridors and doorways instead of hugging the inner edge, and each change of direction costs a little
				// (45 degrees less than a right angle) so the way is a few long straight stretches. All small, so it never makes a silly detour.
				// Ladders and vines are only searched at all when there is no walking way (climbing blind is hard), and then each rung costs extra.
				int dir = climb ? c.dir : k;
				double len = diag ? 1.414 : 1;
				double turn = 0;
				if (!climb && c.dir >= 0) {
					double dot = (DIRS[c.dir][0] * ddx + DIRS[c.dir][1] * ddz) / (Math.hypot(DIRS[c.dir][0], DIRS[c.dir][1]) * len);
					turn = dot > 0.99 ? 0 : dot > 0.5 ? Tuning.ROUTE_TURN_45 : Tuning.ROUTE_TURN_90;
				}
				double step = climb ? Tuning.ROUTE_RUNG : len * (builtFloor(w, nx, nf, nz, pos) ? (paths && !underground(nx, nf, nz) ? Tuning.ROUTE_WP_PATH : Tuning.ROUTE_BUILT_FLOOR) : 1) + (underground(nx, nf, nz) ? Tuning.ROUTE_UNDERGROUND * len : 0) + edgeCost(clearance(w, nx, nf, nz, pos, sides)) + (w.getBlockState(pos.set(nx, nf, nz)).is(net.minecraft.world.level.block.Blocks.FARMLAND) ? Tuning.ROUTE_FARM : 0) + turn;
				if (swim && !climb && swimming(nx, nf, nz)) step = len * Tuning.ROUTE_SWIM + turn;
				double cost = c.g + step + (nf > c.y ? 0.5 : 0) + (c.y - nf >= 2 ? 1 : 0);
				long nk = key(nx, nf, nz);
				Node n = nodes.get(nk);
				if (n != null && (n.closed || n.g <= cost)) continue;
				if (n != null) n.closed = true; // a cheaper way in was found: the old queue entry is dropped when it comes up
				n = new Node(nx, nf, nz, cost, c);
				n.dir = dir;
				n.walk = c.walk + (climb ? 1 : len);
				nodes.put(nk, n);
				n.f = cost + weight * (1.2 * Math.hypot(gx - nx, gz - nz) + Math.abs(gf - nf) * 1.0); // steps cost a little more than their length (sides, turns): the estimate is scaled to match so the search stays quick
				open.add(n);
			}
		}
		if (goal == null && !open.isEmpty() && expanded < Tuning.ROUTE_MAX) return false; // more to do next tick
		done = true;
		lastSearch = (ladders ? "with ladders" : "walking") + (goal != null ? " found" : open.isEmpty() ? " no way (searched all " + expanded + " spots)" : " gave up after " + expanded + " spots");
		return true;
		}

		/** May a partial route end here? Not under rock (openEnd) and not at a known dead end (avoid). */
		boolean endable(Node c) {
			if (avoid != null && !avoid.isEmpty())
				for (int dx = -DEAD_R; dx <= DEAD_R; dx++) for (int dz = -DEAD_R; dz <= DEAD_R; dz++) if (avoid.contains(xz(c.x + dx, c.z + dz))) return false;
			return !openEnd || c.parent == null || !underground(c.x, c.y, c.z);
		}

		static long xz(int x, int z) { return ((long) x << 32) ^ (z & 0xFFFFFFFFL); }

		/** The target stands in water (the jingle: a friend wading or swimming): the search may swim, and the goal is the water's surface there. Before the first run. */
		Search swimTo() {
			swim = true;
			if (gf == NONE) {
				int s = surface(w, gx, gz, (int) Math.floor(other.y) - 1, pos);
				if (s != NONE) {
					gf = s; done = false; failed = false;
					int sf = floorUnder(w, startX + 0.5, startFeet, startZ + 0.5, pos);
					if (open.isEmpty() && !pendingStart) {
						if (sf == NONE) pendingStart = true;
						else { Node st = new Node(startX, sf, startZ, 0, null); st.f = 1.2 * Math.hypot(gx - startX, gz - startZ) + Math.abs(gf - sf); nodes.put(key(startX, sf, startZ), st); open.add(st); }
					}
				}
			}
			return this;
		}

		/** The next spot over at (x,z) from floor `ref`: a walking step, or (swim) the water's surface there. */
		int next(int x, int z, int ref) {
			int nf = stepTo(w, x, z, ref, pos);
			return nf == NONE && swim ? surface(w, x, z, ref, pos) : nf;
		}

		/** A swimming spot: the water's surface is right above this "floor". */
		boolean swimming(int x, int f, int z) {
			return w.getBlockState(pos.set(x, f + 1, z)).getFluidState().getType().isSame(net.minecraft.world.level.material.Fluids.WATER);
		}

		private final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap under = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();

		/** Under rock here: the roof over this spot is the ground itself (a cave), not open sky, a tree or a house roof. Remembered per spot. */
		boolean underground(int x, int f, int z) {
			long k = key(x, f, z);
			byte b = under.getOrDefault(k, (byte) -1);
			if (b < 0) { b = (byte) (BlockRules.caveRoof(w, new Vec3(x + 0.5, f + 1.5, z + 0.5), 24) ? 1 : 0); under.put(k, b); }
			return b == 1;
		}

		/**
		 * ANY DIRECTION: the search walks a grid (8 directions), so a way across open ground at any other angle comes out as a staircase of little turns. This pulls the
		 * route tight: from each point it goes straight to the furthest later point it can WALK to in a straight line (every block along the line can be stepped onto from
		 * the one before, by the same step rule, with no change of height on the way except single steps up or down, and never cutting a corner), then lays that
		 * line out again block by block. Cells: {x, y, z, walking distance}. Turns stay only where the ground forces them.
		 */
		List<double[]> straighten(List<double[]> path) {
			if (path.size() < 3) return path;
			var pos = new BlockPos.MutableBlockPos();
			List<double[]> out = new ArrayList<>();
			out.add(path.get(0).clone());
			out.get(0)[3] = 0;
			int i = 0;
			while (i < path.size() - 1) {
				int j = Math.min(path.size() - 1, i + 1);
				List<double[]> bestLine = null;
				for (int k = j + 1; k < path.size(); k++) {
					if (path.get(k)[1] != path.get(i)[1] && Math.abs(path.get(k)[1] - path.get(i)[1]) > Math.abs(k - i)) break;
					List<double[]> ln = line(path.get(i), path.get(k), pos);
					if (ln == null) continue; // a later point may still be reachable straight (round a bump)
					// only as easy as the search's own way there, by the search's own costs (open edges, crops, rock overhead, the middle of a corridor): straightening
					// removes needless zigzags, it never trades the route's care for a shorter line
					if (lineCost(path.get(i), ln) > path.get(k)[4] - path.get(i)[4]) continue; // (the search's cost includes its turns, so a true straightening is always cheaper)
					bestLine = ln; j = k;
				}
				if (bestLine == null) { bestLine = new ArrayList<>(); bestLine.add(path.get(j)); } // (the search's own step: always walkable)
				double[] last = out.get(out.size() - 1);
				for (double[] c : bestLine) {
					if ((int) c[0] == (int) last[0] && (int) c[2] == (int) last[2] && (int) c[1] == (int) last[1]) continue;
					double d = last[3] + Math.hypot(c[0] - last[0], c[2] - last[2]) + Math.abs(c[1] - last[1]) * 0.5;
					last = new double[]{c[0], c[1], c[2], d};
					out.add(last);
				}
				i = j;
			}
			return out;
		}

		/** The cells of a straight walk from cell a to cell b (both floor cells), or null if you could not walk it straight. */
		private List<double[]> line(double[] a, double[] b, BlockPos.MutableBlockPos pos) {
			double x0 = a[0] + 0.5, z0 = a[2] + 0.5, x1 = b[0] + 0.5, z1 = b[2] + 0.5, len = Math.hypot(x1 - x0, z1 - z0);
			List<double[]> cells = new ArrayList<>();
			int cx = (int) a[0], cz = (int) a[2], cy = (int) a[1];
			int n = (int) Math.ceil(len / 0.2);
			for (int s = 1; s <= n; s++) {
				double t = (double) s / n;
				for (double side : new double[]{-0.3, 0, 0.3}) { // a body is about half a block wide: both edges of it must clear too
					double px = x0 + (x1 - x0) * t + side * -(z1 - z0) / Math.max(1e-6, len), pz = z0 + (z1 - z0) * t + side * (x1 - x0) / Math.max(1e-6, len);
					int nx = (int) Math.floor(px), nz = (int) Math.floor(pz);
					if (nx == cx && nz == cz) continue;
					if (side != 0) { // an edge of you: that block just has to be walkable near your height
						int f = stepTo(w, nx, nz, cy, pos);
						if (f == NONE || Math.abs(f - cy) > 1) return null;
						continue;
					}
					if (Math.abs(nx - cx) + Math.abs(nz - cz) == 2) { // crossing a corner: both blocks beside it walkable at this height
						if (stepTo(w, nx, cz, cy, pos) != cy || stepTo(w, cx, nz, cy, pos) != cy) return null;
					}
					int f = stepTo(w, nx, nz, cy, pos);
					if (f == NONE) return null;
					cx = nx; cz = nz; cy = f;
					cells.add(new double[]{cx, cy, cz, 0});
				}
			}
			if (cx != (int) b[0] || cz != (int) b[2] || cy != (int) b[1]) return null;
			return cells;
		}

		/** What walking those cells costs by the search's own step costs (without the turn cost: a straight line has none). */
		private double lineCost(double[] from, List<double[]> cells) {
			double c = 0, px = from[0], pz = from[2], py = from[1];
			for (double[] e : cells) {
				int x = (int) e[0], y = (int) e[1], z = (int) e[2];
				double len = Math.hypot(x - px, z - pz);
				c += len * (builtFloor(w, x, y, z, pos) ? (paths && !underground(x, y, z) ? Tuning.ROUTE_WP_PATH : Tuning.ROUTE_BUILT_FLOOR) : 1) + (underground(x, y, z) ? Tuning.ROUTE_UNDERGROUND * len : 0) + edgeCost(clearance(w, x, y, z, pos, sides))
					+ (w.getBlockState(pos.set(x, y, z)).is(net.minecraft.world.level.block.Blocks.FARMLAND) ? Tuning.ROUTE_FARM : 0) + (y > py ? 0.5 : 0) + (py - y >= 2 ? 1 : 0);
				px = x; pz = z; py = y;
			}
			return c;
		}


		/** True when path() reaches the goal itself (not just the nearest reachable spot). */
		boolean reached() { return goal != null; }

		/** The found route, start to goal: {x, y, z, walking distance from the start} per cell; null when there was no route. */
		List<double[]> path() {
			Node end = goal != null ? goal : partial && best != null && best.parent != null ? best : null;
			if (end == null) return null;
			List<double[]> path = new ArrayList<>(); // goal back to start, then reversed
			for (Node n = end; n != null; n = n.parent) path.add(new double[]{n.x, n.y, n.z, n.walk, n.g});
			java.util.Collections.reverse(path);
			return straighten(path);
		}
	}

	/** Where the jingle leads from right now: the spot (eye height there), the walking distance left to the player, and whether the spot is the player themselves. */
	record Lead(double x, double y, double z, double walk, boolean atPlayer) {}

	/**
	 * THE LEAD POINT, used by both the jingle sound and its icon: up to `ahead` blocks of walking further along the route from the route cell nearest you, but never past
	 * what you can see from your eyes. On a straight stretch it is a steady `ahead` blocks in front; at a tight corner it pulls in to the corner and then slides round it
	 * as you turn (it never jumps from corner to corner, never sits on top of you, and never sounds or shows through a wall). Up stairs it rises with the steps, since it
	 * is a point ON the route. Null when you have left the route (more than 4 blocks off it): a new one is needed.
	 */
	static Lead lead(net.minecraft.world.level.BlockGetter w, double ex, double ey, double ez, double mx, double my, double mz, Tgt other, List<double[]> full, double ahead) {
		int from = 0;
		double bd = Double.MAX_VALUE;
		for (int i = 0; i < full.size(); i++) {
			double[] c = full.get(i);
			double d = Math.hypot(c[0] + 0.5 - mx, c[2] + 0.5 - mz) + Math.abs(c[1] + 1 - my) * 0.5;
			if (d <= bd + 0.25) { bd = Math.min(bd, d); from = i; } // the furthest-along cell among the nearest (you may stand where the route doubles back)
		}
		if (bd > 4) return null;
		double start = full.get(from)[3], left = full.get(full.size() - 1)[3] - start + bd; // walking distance left
		Vec3 eye = new Vec3(ex, ey, ez);
		Vec3 best = null;
		for (int i = from + 1; i < full.size(); i++) {
			double[] a = full.get(i - 1), b = full.get(i);
			boolean last = i == full.size() - 1;
			Vec3 pb = last ? other.eye() : new Vec3(b[0] + 0.5, b[1] + 1.6, b[2] + 0.5);
			if (b[3] - start > ahead) { // the spot lies between a and b: slide to exactly `ahead` along
				double k = (ahead - (a[3] - start)) / Math.max(1e-6, b[3] - a[3]);
				Vec3 pa = new Vec3(a[0] + 0.5, a[1] + 1.6, a[2] + 0.5), p = pa.add(pb.subtract(pa).scale(k));
				if (seen(w, eye, p) && straightWalk(full, from, i - 1, mx, mz, p.x, p.z)) best = p;
				break;
			}
			// round a corner: stop at the last spot you can see. AND that you could walk straight to: every route cell before it lies within reach of the straight line from
			// you (on a staircase with an open side, a spot you can see may still be one the straight line to which runs off the edge)
			if (!seen(w, eye, pb) || !straightWalk(full, from, i, mx, mz, pb.x, pb.z)) break;
			best = pb;
			if (last) return new Lead(other.x(), other.ey(), other.z(), left, true);
		}
		if (best == null) { double[] n = full.get(Math.min(from + 1, full.size() - 1)); best = new Vec3(n[0] + 0.5, n[1] + 1.6, n[2] + 0.5); } // right at a corner: the next step
		return new Lead(best.x, best.y, best.z, left, false);
	}

	/** Walking straight from you (mx,mz) to point (tx,tz) keeps over the route: every route cell from `from` up to `upTo` lies within reach of that line
	 *  (0.6 of a block where the route changes height, a step or stairs, so a turning staircase is followed and never cut across; a block elsewhere). */
	static boolean straightWalk(List<double[]> full, int from, int upTo, double mx, double mz, double tx, double tz) {
		int y0 = (int) full.get(from)[1];
		for (int j = from + 1; j < upTo; j++) {
			double[] c = full.get(j);
			double tol = (int) c[1] != y0 || (j > 0 && (int) full.get(j - 1)[1] != (int) c[1]) ? 0.6 : 1.0;
			if (PathGuide.segDist(c[0] + 0.5, c[2] + 0.5, mx, mz, tx, tz) > tol) return false;
		}
		return true;
	}

	/** A clear line from your eyes to that point. */
	private static boolean seen(net.minecraft.world.level.BlockGetter w, Vec3 eye, Vec3 to) {
		return w.clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
			net.minecraft.world.phys.shapes.CollisionContext.empty())).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
	}

	/** The lead point for you in the game. */
	static Lead lead(Minecraft mc, Tgt other, List<double[]> full) {
		var me = mc.player;
		return lead(mc.level, me.getX(), me.getEyeY(), me.getZ(), me.getX(), me.getY(), me.getZ(), other, full, Markers.AHEAD);
	}

	/** The latest route found for each player, followed on from where you are every beat. */
	private static final Map<UUID, List<double[]>> routes = new HashMap<>();

	/** Spots a route search may work per tick, per player (a full search of 60000 spots then takes about 2 seconds, spread out, instead of one long stall). */
	static final int SLICE = Tuning.ROUTE_SLICE;
	private static final Map<UUID, Search> searching = new HashMap<>();
	private static final Map<UUID, String> walkWhy = new HashMap<>();

	/** Something the jingle leads to: another player, or a test dummy. */
	record Tgt(UUID id, double x, double y, double z, double ey) {
		Vec3 eye() { return new Vec3(x, ey, z); }
		double dist2(Player me) { double a = x - me.getX(), b = y - me.getY(), c = z - me.getZ(); return a * a + b * b + c * c; }
	}

	private static final List<Tgt> dummies = new ArrayList<>();

	/** A new world or dimension: forget every route, search and beat. */
	static void reset() {
		guide.clear(); routes.clear(); searching.clear(); walkWhy.clear(); beatPhase.clear(); farS.clear(); beats.clear(); beatWait.clear(); facedFor.clear(); named.clear(); lastPos.clear(); vol.clear(); dummies.clear();
	}

	/** TEMPORARY: finds jack o'lanterns sitting on coal blocks near you (only the 16-block sections whose palette holds a jack o'lantern are searched). */
	private static void findDummies(Minecraft mc, SonarConfig cfg) {
		dummies.clear();
		var lantern = net.minecraft.world.level.block.Blocks.JACK_O_LANTERN;
		var coal = net.minecraft.world.level.block.Blocks.COAL_BLOCK;
		int r = Math.min(cfg.playerChimeRange, 96), px = mc.player.getBlockX(), py = mc.player.getBlockY(), pz = mc.player.getBlockZ();
		var pos = new BlockPos.MutableBlockPos();
		for (int cx = (px - r) >> 4; cx <= (px + r) >> 4; cx++) for (int cz = (pz - r) >> 4; cz <= (pz + r) >> 4; cz++) {
			var ch = mc.level.getChunkSource().getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
			if (ch == null) continue;
			var secs = ch.getSections();
			for (int si = 0; si < secs.length; si++) {
				var sec = secs[si];
				int y0 = ch.getMinY() + si * 16;
				if (y0 + 16 < py - 48 || y0 > py + 48) continue;
				if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(st -> st.is(lantern))) continue;
				for (int lx = 0; lx < 16; lx++) for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) {
					if (!sec.getBlockState(lx, ly, lz).is(lantern)) continue;
					int x = (cx << 4) + lx, y = y0 + ly, z = (cz << 4) + lz;
					if (!mc.level.getBlockState(pos.set(x, y - 1, z)).is(coal)) continue;
					// the coal block is the body, the lantern the head: feet on the coal block's floor, eyes in the lantern
					dummies.add(new Tgt(UUID.nameUUIDFromBytes(("sonar-dummy " + x + " " + y + " " + z).getBytes()), x + 0.5, y - 1, z + 0.5, y + 0.6));
				}
			}
		}
		if (cfg.debug && !dummies.isEmpty() && t % 400 == 1) Dbg.send(mc.player, net.minecraft.network.chat.Component.literal("player chime: " + dummies.size() + " test dummy(s) (jack o'lantern on coal)"));
	}

	/**
	 * CLEARANCE: how far floor spot x,y,z is from the nearest EDGE, in blocks (1 = right at an edge, up to CLEAR_MAX). The route prefers spots with more clearance, so it
	 * runs down the middle of whatever it is in (a staircase, a corridor, a bend, a path, a bridge) and keeps a margin from walls and drops in the open. One rule for
	 * every shape, instead of separate penalties for each.
	 *
	 * An EDGE between a spot and the next one along a line is: a wall, rail, fence or water you can not step to; a drop of 2 or more; or a 1-block step up or down that is
	 * NOT part of a slope. A slope (stairs, a ramp, a hillside) is a step whose spot on the far side carries on the same way: on a staircase the step ahead is up and the
	 * one behind is down, so walking up it never meets an edge. The open side of a staircase drops with nothing rising on the other side, so it is an edge even where it
	 * is only one block down.
	 * Clearance is the shortest of the four straight lines (east, west, south, north) out to an edge. Remembered per search.
	 */
	static int clearance(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos, it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap memo) {
		long k = key(x, y, z);
		int m = memo.get(k);
		if (m >= 0) return m;
		int best = CLEAR_MAX;
		for (int d = 0; d < 4 && best > 1; d++) {
			int dx = d == 0 ? 1 : d == 1 ? -1 : 0, dz = d == 2 ? 1 : d == 3 ? -1 : 0;
			// the floor height just behind the start along this line (to tell a slope from an edge at the first step)
			int back = stepTo(w, x - dx, z - dz, y, pos);
			int cy = y, prev = back;
			for (int r = 1; r <= best; r++) {
				int ny = stepTo(w, x + dx * r, z + dz * r, cy, pos);
				if (isEdge(cy, ny, prev)) { best = Math.min(best, r); break; }
				prev = cy; cy = ny;
			}
		}
		memo.put(k, best);
		return best;
	}

	/** From a spot at height `cur` to the next at `next` (NONE: can not step there), having come from `prev` (NONE: unknown): is that an edge? */
	static boolean isEdge(int cur, int next, int prev) {
		if (next == NONE) return true; // wall, rail, fence, water, a deep drop
		int step = next - cur;
		if (step == 0) return false;
		if (Math.abs(step) >= 2) return true; // a drop of 2 or more
		// a 1-block step: part of a slope only if the ground behind went the other way by one (up a staircase: behind is a step down, ahead a step up)
		return prev == NONE || prev - cur != -step;
	}

	/** The cost of walking on a spot with this clearance: high right at an edge, small one block in, nothing further in. */
	static double edgeCost(int clearance) {
		return clearance <= 1 ? Tuning.ROUTE_EDGE_1 : clearance == 2 ? Tuning.ROUTE_EDGE_2 : 0;
	}

	/** Clearance is counted out to this many blocks (more is "plenty of room"). */
	static final int CLEAR_MAX = 3;

	/** A made floor (path, planks, stone bricks...) rather than natural ground. */
	static boolean builtFloor(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		BlockState s = w.getBlockState(pos.set(x, y, z));
		return !FloorEdge.natural(s) || s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH);
	}

	static String lastSearch = "";

	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	static final class Node {
		final int x, y, z;
		final double g;
		final Node parent;
		double f;
		int dir = -1;
		double walk;
		boolean closed;

		Node(int x, int y, int z, double g, Node parent) {
			this.x = x; this.y = y; this.z = z; this.g = g; this.parent = parent;
		}
	}

}
