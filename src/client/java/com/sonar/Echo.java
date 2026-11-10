package com.sonar;

import com.sonar.Scan.Cand;
import com.sonar.Scan.Ledge;
import com.sonar.mixin.MobAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;
import static com.sonar.Cues.*;
import static com.sonar.Rooms.*;
import static com.sonar.Ledges.*;

/** The echo: the ways out, doorways, cave routes and drops around you, played as one short sequence after each sonar ping. (Split out of Cues.) */
final class Echo {
	private Echo() {}

	/**
	 * Drops that lead INTO A CAVE: hole edges met by the floor walk (CaveEcho.Walk: a step more than 3 down, DROP_DEEP+ below the lip), whose bottom has room to walk
	 * off sideways (4+ blocks) under a rock roof. A cliff down to open ground, a valley or a lake never counts. EACH HOLE echoes, from its nearest edge by walking,
	 * up to CAVE_DROPS_MAX, nearest first: {bearing, distance, volume, priority 0, entrance 1, elevation, size + 10}. Bigger (wider at the bottom, deeper) sounds bigger;
	 * further is fainter; a hole whose lip you can not see is muffled (Echo.hidden). Outside caves the walk covers the ground out to CAVE_DROP_RANGE; in a cave it
	 * is the corner walk (what you can see, and round the corners).
	 * (Round 16: this used to be 48 straight lines out from you: holes between them, round a corner or past a slope were missed, and only one "best" hole played.)
	 */
	static List<double[]> caveDrops(Minecraft mc, Scan s, boolean cave) {
		Vec3 eye = new Vec3(s.ex, s.ey, s.ez);
		CaveEcho.Walk wk = CaveEcho.forHoles(mc, eye, cave);
		if (wk == null) return List.of();
		if (!wk.done) { wk.run(Tuning.CORNER_FINISH_NS); wk.finish(); } // due now: what it has walked so far (the nearest first)
		List<double[]> found = holes(Sense.hear(mc), wk.edges, s.ex, s.ey, s.ez, s.feetY, s.yaw);
		if (SonarConfig.cur.debug && mc.player != null) {
			Dbg.send(mc.player, Component.literal(Dbg.fmt("holes: walked %d spots, %d edges, %d into a cave", wk.cells, wk.edges.size(), found.size())));
			for (double[] d : found) Dbg.send(mc.player, Component.literal(Dbg.fmt("cave drop: %.0f deg, %.1f blocks, elev %.0f deg, size %.2f%s", Math.toDegrees(d[0]), d[1], Math.toDegrees(d[5]), d[6] - 10, d.length > 7 ? ", out of sight" : "")));
		}
		return found;
	}

	/** Any world (self-test): walk the open ground from the eyes out to `range` and return its holes into caves. */
	static List<double[]> caveDrops(net.minecraft.world.level.BlockGetter w, double ex, double ey, double ez, int fy, float yaw, int range) {
		CaveEcho.Walk wk = CaveEcho.Walk.open(w, new Vec3(ex, ey, ez), range);
		wk.run(Long.MAX_VALUE);
		return holes(w, wk.edges, ex, ey, ez, fy, yaw);
	}

	/** The nearest hole only (or null): for the tests that ask about one hole. */
	static double[] caveDrop(net.minecraft.world.level.BlockGetter w, double ex, double ey, double ez, int fy, float yaw, int range) {
		List<double[]> l = caveDrops(w, ex, ey, ez, fy, yaw, range);
		return l.isEmpty() ? null : l.get(0);
	}

	/** How far you can walk off sideways at the bottom of a drop (x, z, floor `bottom`) under a rock roof no higher than the lip (`lip`): 0, or 4..12 blocks. */
	static int caveBelow(net.minecraft.world.level.BlockGetter w, int x, int z, int bottom, int lip) {
		var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		int reach = 0;
		for (int d = 0; d < 8; d++) {
			double ux = Math.cos(d * Math.PI / 4), uz = Math.sin(d * Math.PI / 4);
			int k = 1;
			for (; k <= 12; k++) {
				int bx = (int) Math.floor(x + 0.5 + ux * k), bz = (int) Math.floor(z + 0.5 + uz * k);
				if (!w.getBlockState(pos.set(bx, bottom + 1, bz)).getCollisionShape(w, pos).isEmpty()
					|| !w.getBlockState(pos.set(bx, bottom + 2, bz)).getCollisionShape(w, pos).isEmpty()) break;
			}
			k--;
			boolean roofed = k >= 4;
			for (int j = 0; j < 4 && roofed; j++) // the rock roof must run over the last four cells of the way on, not just touch the end of it (a small overhang, a ledge sticking out of a cliff, is not a cave)
				roofed = BlockRules.caveRoof(w, new Vec3(x + 0.5 + ux * (k - j), bottom + 1, z + 0.5 + uz * (k - j)), Math.max(2, lip - bottom));
			if (roofed) reach = Math.max(reach, k); // the roof over the way on must be the ground itself (rock, earth) and below the lip: a house roof beside a cliff is not a cave
		}
		return reach;
	}

	/**
	 * The holes into caves among a walk's edges: the edges with a cave at the bottom; those that touch (within 2 blocks, lips within 2 of each other in height)
	 * are one hole; each hole echoes from its nearest edge by walking. (Every edge was asked as the walk found it: a pit beside a long sloping bank is not lost
	 * among the bank's edges.)
	 */
	static List<double[]> holes(net.minecraft.world.level.BlockGetter w, List<int[]> edges, double ex, double ey, double ez, int fy, float yaw) {
		double fwd = Math.atan2(Math.cos(Math.toRadians(yaw)), -Math.sin(Math.toRadians(yaw)));
		Vec3 eyeV = new Vec3(ex, ey, ez);
		List<int[]> es = new ArrayList<>();
		for (int[] e : edges) if (e[5] >= 4) es.add(e);
		es.sort((a, b) -> a[4] != b[4] ? Integer.compare(a[4], b[4]) : a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1])); // nearest by walking first (ties in a fixed order)
		int[] group = new int[es.size()];
		java.util.Arrays.fill(group, -1);
		List<List<int[]>> stretches = new ArrayList<>();
		for (int i = 0; i < es.size(); i++) {
			if (group[i] >= 0) continue;
			List<int[]> st = new ArrayList<>();
			group[i] = stretches.size();
			java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>(List.of(i));
			while (!todo.isEmpty()) {
				int[] p = es.get(todo.pop());
				st.add(p);
				for (int k = 0; k < es.size(); k++) {
					int[] o = es.get(k);
					if (group[k] < 0 && Math.max(Math.abs(o[0] - p[0]), Math.abs(o[1] - p[1])) <= 2 && Math.abs(o[2] - p[2]) <= 2) { group[k] = stretches.size(); todo.push(k); }
				}
			}
			st.sort((a, b) -> Integer.compare(a[4], b[4]));
			stretches.add(st);
		}
		List<double[]> out = new ArrayList<>();
		for (List<int[]> st : stretches) { // stretches are in order of their nearest edge
			if (out.size() >= Tuning.CAVE_DROPS_MAX) break;
			{
				int[] e = st.get(0); // its nearest edge
				int reach = e[5];
				int drop = fy - 1 - e[3]; // how far below YOU the bottom is
				double bx = e[0] + 0.5 - ex, bz = e[1] + 0.5 - ez, r = Math.max(1, Math.hypot(bx, bz)), ang = Math.atan2(bz, bx);
				double size = Math.max(0.15, Math.min(1.0, (reach - 3) / 9.0 * 0.7 + Math.min(drop, 40) / 40.0 * 0.3));
				// right at the mouth and looking toward it: louder and fuller, even for a small hole
				double rel = Math.abs(Math.atan2(Math.sin(ang - fwd), Math.cos(ang - fwd)));
				double vmul = r <= 3 && rel < Math.toRadians(60) ? 1.5 : 1.0;
				if (vmul > 1) size = Math.max(size, 0.5);
				double[] echo = {ang, Math.hypot(r, drop), Math.min(1.5, facingCurve(rel) * vmul), 0, 1, -Math.atan2(drop, r), size + 10};
				// out of sight: a straight look from your eyes to just over the hole is blocked
				Vec3 over = new Vec3(e[0] + 0.5, e[2] + 1.5, e[1] + 0.5);
				if (w.clip(new ClipContext(eyeV, over, Block.COLLIDER, Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty())).getType() != Type.MISS) echo = hidden(echo);
				out.add(echo);
			}
		}
		return out;
	}

	static List<double[]> mouths(Minecraft mc, Scan s) {
		int n = 64; // 5.6 degree steps: a doorway two blocks wide is seen from 10+ blocks away
		double[] r = new double[n];
		double[] base = new double[n];
		Vec3 from = new Vec3(s.ex, s.ey, s.ez);

		for (int i = 0; i < n; i++) {
			double a = i * 2 * Math.PI / n;
			r[i] = rayLen(mc, from, Math.cos(a), 0.0, Math.sin(a), s.range, false);
		}

		boolean[] open = new boolean[n];

		for (int i = 0; i < n; i++) {
			base[i] = Math.min(r[(i + n - 6) % n], r[(i + 6) % n]);
			// a wall anywhere inside the sonar range can have an opening: much deeper than the wall (1.8x), or clearly deeper (1.35x and 8+ blocks), which is what a door in a house front looks like from far off
			open[i] = base[i] < 0.98 * s.range && r[i] - base[i] > 6.0 && (r[i] > 1.8 * base[i] || (r[i] > 1.35 * base[i] && r[i] - base[i] >= 8.0));
		}

		double c25 = Math.cos(Math.toRadians(25.0));
		double s25 = Math.sin(Math.toRadians(25.0));
		List<double[]> out = new ArrayList<>();
		int i = 0;

		while (i < n) {
			if (!open[i]) {
				i++;
			} else {
				int j = i;
				double sumD = 0.0;
				double sumB = 0.0;

				while (j < n && open[j]) {
					sumD += r[j];
					sumB += base[j];
					j++;
				}

				int cnt = j - i;
				double mid = (i + j - 1) / 2.0 * 2.0 * Math.PI / n;
				double avgB = sumB / cnt;
				// roof must cover the stretch just inside the opening (a real cave), not just an overhang, a mountainside, or a roof far behind a gap
				double dc = Math.cos(mid), ds = Math.sin(mid), dm = sumD / cnt;
				int roofs = 0;
				for (double dd : new double[]{avgB + 1.5, avgB + 3.5, avgB + 5.5}) { // the roof must start right at the opening (a cave ceiling, a hall), not only far behind it (a gap between trees looking at a distant roof)
					double dist = Math.min(dd, dm * 0.95);
					Vec3 pt = from.add(dc * dist, 0.0, ds * dist);
					if (solidRoof(mc, pt, 24)) { // a tall hall or glass dome behind a doorway still has its roof
						roofs++;
					}
				}

				if (SonarConfig.cur.debug && mc.player != null) {
					Vec3 far = from.add(dc * 40.0, 0.0, ds * 40.0);
					var hit = Sense.hear(mc).clip(new ClipContext(from, far, Block.COLLIDER, Fluid.NONE, mc.player));
					Dbg.send(mc.player, Component.literal(Dbg.fmt("mouth: bearing %.0f deg, reach %.1f, wall before it %.1f, %d rays wide, roofs %d/3, ray hits %s at %.1f", Math.toDegrees(mid), dm, avgB, cnt, roofs,
						hit.getType() == Type.MISS ? "nothing" : Sense.hear(mc).getBlockState(hit.getBlockPos()).getBlock().getDescriptionId(), hit.getType() == Type.MISS ? 40.0 : from.distanceTo(hit.getLocation()))));
				}
				if (roofs < 3) { // a door is an entrance by itself: if the first thing along this bearing is a door, no roof is needed (the ray goes on through it, so the roof samples would land past the back of the building)
					var dh = Sense.hear(mc).clip(new ClipContext(from, from.add(dc * s.range, 0.0, ds * s.range), Block.COLLIDER, Fluid.NONE, mc.player));
					if (dh.getType() != Type.MISS && Sense.hear(mc).getBlockState(dh.getBlockPos()).is(BlockTags.DOORS)) roofs = 3;
				}
				if (roofs >= 3) {
					out.add(new double[]{mid, dm, cnt, avgB}); // bearing, depth, width in rays, distance to the opening itself
				}

				i = j;
			}
		}

		double fwd = Math.atan2(Math.cos(Math.toRadians(s.yaw)), -Math.sin(Math.toRadians(s.yaw)));
		out.sort(Comparator.comparingDouble(m -> Math.abs(Math.atan2(Math.sin(m[0] - fwd), Math.cos(m[0] - fwd)))));
		return out.size() > MAX_ECHOES ? new ArrayList<>(out.subList(0, MAX_ECHOES)) : out;
	}

	/** One echo click. Its loudness and pitch tell the distance relative to `range` (the sonar range, or the cave echo range underground); `elev` tilts it up or down (a route that climbs or descends). */
	static void scheduleEcho(Scan s, SonarConfig cfg, double ang, double dist, float mul, int delay, double range, double elev, double size, boolean hidden) {
		double dx = Math.cos(ang);
		double dz = Math.sin(ang);
		double fwd = Math.atan2(Math.cos(Math.toRadians(s.yaw)), -Math.sin(Math.toRadians(s.yaw)));
		float back = (float) Loud.behind(Math.abs(Math.atan2(Math.sin(ang - fwd), Math.cos(ang - fwd)))); // the shared direction rule: duller the further round behind you
		if (size >= 0) { caveEcho(s, cfg, dx, dz, dist, mul, delay, range, elev, size, back, hidden); return; }
		// further away is clearly fainter (it used to lose at most half, so an entrance 12 blocks off sounded right beside you)
		float vol = cfg.volume * cfg.echoVolume * 1.06F * mul * (float) Loud.at(hidden ? dist * Tuning.HIDDEN_FADE : dist, 2, range); // the shared fade over the echo's range (slower for one out of sight)
		float pitch = 1.4F - 0.7F * (float)Math.min(1.0, dist / range);
		if (dist < range * 0.2) {
			pitch = Math.min(2.0F, pitch + 0.2F); // tight space: a short, high click
		}

		SoundEvent hat = (SoundEvent)SoundEvents.NOTE_BLOCK_HAT.value();
		double[] lag = lag(s);
		double y = s.ey + lag[1] + 4.0 * Math.sin(elev);
		double at = 2.0 + Math.min(dist, 32) * 0.2; // placed further out the further it is (2 to 8 blocks), so near and far entrances do not all sound beside you
		y = s.ey + lag[1] + at * Math.sin(elev);
		queueDir(back, hidden, new com.sonar.Cues.Pending(hat, vol, pitch, s.ex + lag[0] + dx * at, y, s.ez + lag[2] + dz * at, new int[]{delay}));
		if (dist > range * 0.45) { // big space: a softer, lower tail after the echo
			queueDir(back, hidden, new com.sonar.Cues.Pending(hat, vol * 0.45F, pitch * 0.8F, s.ex + lag[0] + dx * at, y, s.ez + lag[2] + dz * at, new int[]{delay + 3}));
		}
	}

	/**
	 * A cave route's echo. Its SIZE is how big it sounds: a small hole is a quiet, light tick; a wide tunnel or cavern is louder and fuller, with a deep layer under it.
	 * Its DISTANCE is how deep it sounds, not how quiet: a far route is lower in pitch (on a log scale, so routes 10 and 40 blocks away are as easy to tell apart as 40 and 160) and only a little softer.
	 */
	static void caveEcho(Scan s, SonarConfig cfg, double dx, double dz, double dist, float mul, int delay, double range, double elev, double size, float back, boolean hidden) {
		float f = (float) (Math.log1p(Math.min(dist, range)) / Math.log1p(range));
		if (size >= 10) size -= 10; // a drop into a cave (caveDrop) carries its own measured size: a narrow shaft stays a light tick
		else if (elev < Math.toRadians(-20)) size = 1.0; // the floor of a ravine or cavern far below: as big as the biggest corridor
		float vol = Math.min(1.0F, cfg.volume * cfg.echoVolume * 1.06F * mul * (0.35F + 0.85F * (float) size) * (float) Loud.at(dist, 2, range)); // the shared fade over the cave echo range (the pitch still says how far, the size how big)
		float pitch = Math.max(0.5F, Math.min(2.0F, (1.6F - 0.9F * f) * (size < 0.25 ? 1.15F : 1.0F)));
		SoundEvent hat = (SoundEvent) SoundEvents.NOTE_BLOCK_HAT.value();
		double[] lag = lag(s);
		double x = s.ex + lag[0] + dx * 4.0, y = s.ey + lag[1] + 4.0 * Math.sin(elev), z = s.ez + lag[2] + dz * 4.0;
		queueDir(back, hidden, new com.sonar.Cues.Pending(hat, vol, pitch, x, y, z, new int[]{delay}));
		if (size >= 0.4) { // a big opening: a deeper layer under the click makes it sound big
			queueDir(back, hidden, new com.sonar.Cues.Pending((SoundEvent) SoundEvents.NOTE_BLOCK_BASEDRUM.value(), vol * 0.55F * (float) size, Math.max(0.5F, pitch * 0.6F), x, y, z, new int[]{delay}));
		}
		if (Math.abs(elev) > Math.toRadians(20)) { // a way far down (or up): a second click a little lower (or higher), as if the sound fell away below you
			float glide = elev < 0 ? 0.7F : 1.35F;
			double y2 = s.ey + lag[1] + 7.0 * Math.sin(elev);
			queueDir(back, hidden, new com.sonar.Cues.Pending(hat, vol * 0.8F, Math.max(0.5F, Math.min(2.0F, pitch * glide)), x, y2, z, new int[]{delay + 1}));
		}
		if (size >= 0.25 && f > 0.6F) { // and far away: a soft lower tail after it
			queueDir(back, hidden, new com.sonar.Cues.Pending(hat, vol * 0.4F, Math.max(0.5F, pitch * 0.8F), x, y, z, new int[]{delay + 3}));
		}
	}

	/** A detector's echo marked OUT OF SIGHT (round a corner): the same echo, played muffled and quieter. */
	static double[] hidden(double[] e) {
		double[] h = java.util.Arrays.copyOf(e, 8);
		h[6] = e.length > 6 ? e[6] : -1; // no size: -1
		h[7] = 1;
		return h;
	}

	private static final java.util.Map<SoundEvent, SoundEvent> ROUND_CORNER = new java.util.HashMap<>();

	/** The heavily muffled copy for an echo out of sight (sounds/h, listed as "h.<sound>"), or the behind-you muffled one if there is none. */
	static SoundEvent roundCorner(SoundEvent e) {
		return ROUND_CORNER.computeIfAbsent(e, k -> {
			String p = k.location().getPath();
			return p.equals("block.note_block.hat") || p.equals("block.note_block.basedrum")
				? SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.fromNamespaceAndPath("sonar", "h." + p)) : muffled(k);
		});
	}

	/** Queues an echo sound with the shared direction rule's dullness: the plain sound fades out and its muffled copy in, the further round behind you it is (`back` 0..1). */
	private static void queueDir(float back, boolean hidden, Pending p) {
		if (hidden) { // out of sight: one heavily muffled, quieter copy, wherever it is round you (round 16: "so it really sounds like it's around the corner")
			queue(new Pending(roundCorner(p.snd()), p.vol() * Tuning.HIDDEN_ECHO, p.pitch(), p.x(), p.y(), p.z(), p.ticks().clone()));
			return;
		}
		if (1 - back > 0.02f) queue(new Pending(p.snd(), p.vol() * (1 - back), p.pitch(), p.x(), p.y(), p.z(), p.ticks().clone()));
		SoundEvent dull = muffled(p.snd());
		if (back > 0.02f && dull != p.snd()) queue(new Pending(dull, p.vol() * back, p.pitch(), p.x(), p.y(), p.z(), p.ticks().clone()));
		else if (back > 0.02f) queue(new Pending(p.snd(), p.vol() * back, p.pitch(), p.x(), p.y(), p.z(), p.ticks().clone())); // no muffled copy: keep the full loudness
	}

	/** Most echoes in one ping. */
	static final int MAX_ECHOES = 8;

	/** Ticks from the echo until its last echo has rung out; the auto ping waits for it. */
	static int echoTail = 0;

	/** Volume factor by where it is: ahead 1.0, to the side 0.75, behind 0.5. */
	/** Echo loudness by how far round from where you face it is (rel, radians): full straight ahead, about half to the side, a quarter behind, smoothly in between. */
	static double facingCurve(double rel) {
		return Loud.facing(rel); // the shared direction rule
	}

	static double facingVol(Scan s, double ang) {
		double fwd = Math.atan2(Math.cos(Math.toRadians(s.yaw)), -Math.sin(Math.toRadians(s.yaw)));
		double rel = Math.abs(Math.atan2(Math.sin(ang - fwd), Math.cos(ang - fwd)));
		return facingCurve(rel);
	}

	/** The doors in sight as echoes {bearing, distance, volume, prio 3, entrance 1, elevation 0}, nearest first (merge() joins a double door, and a door where another echo already points). */
	static List<double[]> visibleDoors(Scan s, List<double[]> others) {
		if (s.doors.isEmpty() && !s.doorsSearched) { s.doorsSearched = true; s.findDoors(); }
		List<double[]> sorted = new ArrayList<>(s.doors);
		sorted.sort(Comparator.comparingDouble(d -> d[1]));
		List<double[]> out = new ArrayList<>();
		for (double[] d : sorted) {
			out.add(new double[]{d[0], d[1], facingVol(s, d[0]), 3, 1, 0});
			if (out.size() >= MAX_ECHOES) break;
		}
		return out;
	}

	/**
	 * Plays one ping's echoes as a single quick sequence. `echoes` = {angle, distance, volume, priority (lower = more important), entrance 0/1, elevation}.
	 * The `count` most important are kept, then they play least important first, and the NEAREST ENTRANCE (door, way out, cave route) always plays last.
	 * Up to 8 echoes, 3 ticks apart. The next auto ping waits until the last one has rung out (echoTail), so none is ever dropped or covered.
	 */
	static void playAll(Scan s, SonarConfig cfg, List<Way> echoes, int count, double range) {
		if (echoes.isEmpty() || count <= 0) return;
		// what is right by you, or where you are looking, comes first: a staircase or hole within 6 blocks, or anything within 16 blocks that you face (30 degrees), beats a bigger
		// way out further off when not all of them fit
		double fwdA = Math.atan2(Math.cos(Math.toRadians(s.yaw)), -Math.sin(Math.toRadians(s.yaw)));
		java.util.function.ToDoubleFunction<Way> rank = e -> e.prio() - (e.dist() < 6 ? 1.0 : 0) - (e.dist() < 16 && Math.abs(Math.atan2(Math.sin(e.angle() - fwdA), Math.cos(e.angle() - fwdA))) < Math.toRadians(30) ? 0.5 : 0);
		echoes = new ArrayList<>(echoes);
		echoes.sort(Comparator.comparingDouble(rank).thenComparingDouble((Way e) -> -e.vol()).thenComparingDouble(Way::dist));
		List<Way> seq = new ArrayList<>(echoes.subList(0, Math.min(Math.min(count, MAX_ECHOES), echoes.size())));
		java.util.Collections.reverse(seq); // least important first
		int near = -1;
		for (int i = 0; i < seq.size(); i++) if (seq.get(i).entrance() && (near < 0 || seq.get(i).dist() < seq.get(near).dist())) near = i;
		if (near >= 0) seq.add(seq.remove(near));
		// the echoes never come faster than one every 3 ticks (closer together they blur into a crowd): when more are found than fit before the next ping, the least important
		// (they play first) are left out instead
		// they all play: the next auto ping waits until the last echo has rung out (echoTail), so the sonar never covers an echo
		int gap = Tuning.ECHO_GAP;
		int t = 1;
		for (int i = 0; i < seq.size(); i++) {
			Way e = seq.get(i);
			scheduleEcho(s, cfg, e.angle(), e.dist(), (float) e.vol(), t, range, e.elev(), e.sizeCode(), e.hidden()); // only cave echoes carry a size
			if (i + 1 < seq.size()) t += gap;
		}
		echoTail = Math.max(echoTail, t + Tuning.ECHO_RING_OUT); // the last echo, its quieter second layer and its ring
		if (cfg.debug && Minecraft.getInstance().player != null) {
			double fwd = Math.atan2(Math.cos(Math.toRadians(s.yaw)), -Math.sin(Math.toRadians(s.yaw)));
			StringBuilder sb = new StringBuilder();
			for (Way e : seq) sb.append(Dbg.fmt("[%s%s%.0f deg %.0f bl] ", e.entrance() ? "ENTR " : "", e.hidden() ? "HIDDEN " : "", Math.toDegrees(Math.atan2(Math.sin(e.angle() - fwd), Math.cos(e.angle() - fwd))), e.dist()));
			Dbg.send(Minecraft.getInstance().player, Component.literal("echo plays " + seq.size() + " (gap " + gap + "): " + sb));
		}
	}

	/**
	 * One way you could go, as an echo: its direction (angle, radians), how far, how loud it was found (`vol`, already including the direction rule), how important
	 * (priority, lower = more important), whether it is an entrance (a door, a way out, a cave route), how far up or down (elevation, radians), and for cave echoes how
	 * big it sounds (size 0..1; -1 = not a cave echo; `measured` = the size was measured at a drop rather than worked out from a route).
	 * The detectors produce their findings as plain number lists; they become Ways at ONE point (of()), and everything after works with these names.
	 */
	/** hidden: its way is out of sight (round a corner): played muffled and quieter (Tuning.HIDDEN_ECHO). */
	record Way(double angle, double dist, double vol, double prio, boolean entrance, double elev, double size, boolean measured, boolean hidden) {
		/** From a detector's {angle, distance, volume, priority, entrance 0/1, elevation, [size, or size + 10 when measured, -1 none], [hidden 1]} (see Echo.hidden). */
		static Way of(double[] e) {
			boolean has = e.length > 6, measured = has && e[6] >= 10;
			return new Way(e[0], e[1], e[2], e[3], e[4] == 1, e[5], has ? (measured ? e[6] - 10 : e[6]) : -1, measured, e.length > 7 && e[7] == 1);
		}

		Way withVol(double v) { return new Way(angle, dist, v, prio, entrance, elev, size, measured, hidden); }
		Way joined(Way o) { return new Way(angle, dist, Math.max(vol, o.vol), prio, entrance || o.entrance, elev, size, measured, hidden && o.hidden); } // in sight if either is
		/** The size as the echo player takes it (+10 marks a measured size). */
		double sizeCode() { return size < 0 ? -1 : measured ? size + 10 : size; }
	}

	/** Echoes closer together than this in direction, and at about the same distance, are one way. */
	static final double SAME_WAY_DEG = Tuning.ECHO_SAME_WAY_DEG;

	/**
	 * ONE rule for "these are the same way": every detector (openings, doorways, cave routes, drops, doors) hands in what it found, and echoes pointing the same way
	 * (within SAME_WAY_DEG) at about the same distance (within 3 blocks, or a third of the distance) are joined into one: the most important kind is kept, it counts
	 * as an entrance if any of them was one, and it is as loud as the loudest. A double door is one echo; a doorway that is also an opening is one echo. (This replaces
	 * three separate "already covered" rules with different angles.)
	 * Echo entries: {angle, distance, volume, priority (lower = more important), entrance 0/1, elevation, [size]}.
	 */
	static List<Way> merge(List<Way> in) {
		List<Way> sorted = new ArrayList<>(in);
		sorted.sort(Comparator.comparingDouble(Way::prio).thenComparingDouble(Way::dist)); // most important first, then nearest
		List<Way> out = new ArrayList<>();
		for (Way e : sorted) {
			int same = -1;
			for (int i = 0; i < out.size() && same < 0; i++) {
				Way o = out.get(i);
				double da = Math.abs(Math.atan2(Math.sin(e.angle() - o.angle()), Math.cos(e.angle() - o.angle())));
				if (da < Math.toRadians(SAME_WAY_DEG) && Math.abs(e.dist() - o.dist()) <= Math.max(3.0, Math.min(e.dist(), o.dist()) / 3.0)) same = i;
			}
			if (same < 0) out.add(e);
			else out.set(same, out.get(same).joined(e));
		}
		return out;
	}

	/** One echo, all at once (the echo key, or a ping with its echo right away). */
	static void echo(Minecraft mc, Scan s, SonarConfig cfg) {
		Job j = new Job(s, cfg);
		while (!j.step(mc)) { }
		j.play(mc);
	}

	/**
	 * One echo's work, cut into stages so it can be spread over a few ticks (one stage a tick) instead of one long tick: the room / cave test, the routes or doorways,
	 * the drop into a cave, then the doors in sight. The auto echo starts it a few ticks before it is due to play, in the quiet after the sonar ping, and plays it on time.
	 */
	/** What the last echo found, where. */
	private static final class Remembered {
		Object level;
		int x, y, z, settings;
		long at;
		List<Way> ways;
		double[] share;
		boolean cave, room;
		List<double[]> routes, corners;
	}

	private static Remembered last;

	/** The settings an echo's findings depend on: a change in them means searching again. */
	private static int settingsKey(SonarConfig cfg) { return java.util.Objects.hash(cfg.echoCount, cfg.caveRange, cfg.range); }

	/** A new world: nothing remembered. */
	static void forget() { last = null; }

	static final class Job {
		final Scan s;
		final SonarConfig cfg;
		final int count;
		List<Way> echoes = new ArrayList<>();
		int stage;
		boolean room, caveMode, off;
		List<double[]> routes = List.of();
		List<double[]> corners = List.of();

		Job(Scan s, SonarConfig cfg) {
			this.s = s; this.cfg = cfg;
			this.count = cfg.echoCount < 0 ? MAX_ECHOES : cfg.echoCount; // auto: every way out that was found, up to 8
			this.off = cfg.echoCount == 0;
			// standing on the same block as the last echo (in the last few seconds): the ways around you are the same, so reuse them instead of searching again.
			// Only the direction rule is worked out afresh, for where you face now.
			Remembered r = last;
			if (!off && r != null && r.level == Minecraft.getInstance().level && r.x == (int) Math.floor(s.ex) && r.y == s.feetY && r.z == (int) Math.floor(s.ez)
				&& System.nanoTime() - r.at < Tuning.ECHO_REUSE_NS && r.settings == settingsKey(cfg)) {
				for (int i = 0; i < r.ways.size(); i++) echoes.add(r.ways.get(i).withVol(r.share[i] * facingVol(s, r.ways.get(i).angle())));
				caveMode = r.cave; room = r.room; routes = r.routes; corners = r.corners;
				stage = 99; reused = true;
			}
		}

		boolean reused;

		/** Remembers what this echo found, for the next one if you have not moved. */
		private void remember() {
			Remembered r = new Remembered();
			r.level = Minecraft.getInstance().level; r.x = (int) Math.floor(s.ex); r.y = s.feetY; r.z = (int) Math.floor(s.ez); r.at = System.nanoTime(); r.settings = settingsKey(cfg);
			r.ways = new ArrayList<>(echoes); r.share = new double[echoes.size()];
			for (int i = 0; i < echoes.size(); i++) r.share[i] = echoes.get(i).vol() / Math.max(1e-6, facingVol(s, echoes.get(i).angle())); // its loudness apart from the direction
			r.cave = caveMode; r.room = room; r.routes = routes; r.corners = corners;
			last = r;
		}

		/** Does the next stage. True when all the work is done (then play). */
		boolean step(Minecraft mc) {
			if (off) return true;
			switch (stage++) {
				case 0 -> {
					probe(mc);
					Rooms.Space sp = Rooms.space(mc, s); // the same decision the sonar uses
					caveMode = sp == Rooms.Space.CAVE;
					room = sp == Rooms.Space.ROOM;
				}
				case 1 -> {
					if (caveMode) {
						routes = CaveEcho.routes(mc, s, cfg.caveRange);
						for (int i = 0; i < routes.size(); i++) {
							double[] r = routes.get(i);
							echoes.add(Way.of(new double[]{r[0], r[1], facingVol(s, r[0]), i, 1, r[4], Math.max(0, Math.min(1, (r[5] - 1.0) / 7.0))})); // priority = how clearly it stands out; size 0 = a hole a block wide, 1 = 8+ wide
						}
					} else if (room) for (double[] e : ExitGuide.find(mc, s)) echoes.add(Way.of(e)); // inside a building: its ways out (doors, open sides) and staircases, found by walking the floor; walls never echo
					else for (double[] g : mouths(mc, s)) echoes.add(Way.of(new double[]{g[0], g[1], facingVol(s, g[0]), 1, 1, 0})); // outside: cave mouths and big openings in sight
				}
				case 2 -> {
					if (caveMode) {
						corners = CaveEcho.aroundCorners(mc, s, routes);
						for (double[] r : corners) echoes.add(Way.of(hidden(new double[]{r[0], r[1], facingVol(s, r[0]), routes.size() + 1, 1, r[4], 0.3}))); // round a corner: out of sight, after the routes you can see
					} else if (!room) {
						for (double[] e : ExitGuide.entrances(mc, s)) echoes.add(Way.of(e)); // doorways found by walking in along the floor (a step up, a tall glass hall behind): the echo looks into rooms you can not see into
					}
				}
				case 3 -> {
					if (caveMode || !room) {
						for (double[] drop : caveDrops(mc, s, caveMode)) echoes.add(Way.of(drop)); // every drop that opens into a cave below (a hole in the ground outside; a shaft or ravine down here), nearest first
					}
				}
				case 99 -> { return true; } // reused from the last echo
				default -> {
					for (double[] e : visibleDoors(s, List.of())) echoes.add(Way.of(e)); // every door in sight gets its own echo, unless one already points that way
					remember();
					return true;
				}
			}
			return false;
		}

		/** Plays the echoes found. */
		void play(Minecraft mc) {
			if (off) return;
			if (cfg.debug && mc.player != null) {
				if (caveMode) Dbg.send(mc.player, Component.literal("echo: CAVE mode, " + routes.size() + " routes, " + corners.size() + " round a corner, doors seen " + s.doors.size()));
				else {
					StringBuilder sb = new StringBuilder();
					for (Way e : echoes) sb.append(Dbg.fmt("[%.0f deg, %.1f blocks, prio %.0f%s] ", Math.toDegrees(e.angle()), e.dist(), e.prio(), e.entrance() ? ", entrance" : ""));
					Dbg.send(mc.player, Component.literal("echo: " + (room ? "ROOM" : "OUTSIDE") + " mode | doors seen " + s.doors.size() + " | " + sb));
				}
			}
			playAll(s, cfg, merge(echoes), count, caveMode ? cfg.caveRange : s.range);
		}

		private void probe(Minecraft mc) {
			if (cfg.debug && mc.player != null) { // what is straight ahead: does a collision ray stop at it, does an outline ray, and which blocks are there
					double yr = Math.toRadians(s.yaw), ax = -Math.sin(yr), az = Math.cos(yr);
					Vec3 o = new Vec3(s.ex, s.ey, s.ez), far = o.add(ax * 20.0, 0.0, az * 20.0);
					var hc = Sense.hear(mc).clip(new ClipContext(o, far, Block.COLLIDER, Fluid.NONE, mc.player));
					var ho = Sense.hear(mc).clip(new ClipContext(o, far, Block.OUTLINE, Fluid.NONE, mc.player));
					StringBuilder sb = new StringBuilder();
					for (int d = 1; d <= 6; d++) {
						var bp = net.minecraft.core.BlockPos.containing(s.ex + ax * d, s.ey, s.ez + az * d);
						sb.append(d).append('=').append(Sense.hear(mc).getBlockState(bp).getBlock().getDescriptionId().replace("block.minecraft.", "")).append(' ');
					}
					Dbg.send(mc.player, Component.literal(Dbg.fmt("ahead probe: collider %s at %.1f, outline %s at %.1f | %s", hc.getType() == Type.MISS ? "nothing" : Sense.hear(mc).getBlockState(hc.getBlockPos()).getBlock().getDescriptionId(), hc.getType() == Type.MISS ? 20.0 : o.distanceTo(hc.getLocation()),
						ho.getType() == Type.MISS ? "nothing" : Sense.hear(mc).getBlockState(ho.getBlockPos()).getBlock().getDescriptionId(), ho.getType() == Type.MISS ? 20.0 : o.distanceTo(ho.getLocation()), sb)));
				}
		}
	}
}
