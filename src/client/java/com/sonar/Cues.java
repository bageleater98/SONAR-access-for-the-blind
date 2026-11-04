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

public class Cues {
	static final Map<Integer, Long> mobSeen = new HashMap<>();
	static final List<com.sonar.Cues.Pending> pending = new ArrayList<>();
	public static double loopX;
	public static double loopY;
	public static double loopZ;
	public static SoundEvent loopSound;
	private static long lastLava = -1000;
	/** Short clips of the vanilla lava/water ambience (the vanilla lava sound is 8 s long and kept playing from where you used to stand). */
	static final SoundEvent LAVA_SHORT = SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.fromNamespaceAndPath("sonar", "lava"));
	static final SoundEvent WATER_SHORT = SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.fromNamespaceAndPath("sonar", "water"));
	public static float loopVol;
	public static float loopPitch;

	public static void tickPending(Minecraft mc) {
		Iterator<com.sonar.Cues.Pending> it = pending.iterator();

		while (it.hasNext()) {
			com.sonar.Cues.Pending p = it.next();
			if (p.ticks()[0]-- <= 0) {
				play(mc, p.snd(), p.vol(), p.pitch(), p.x(), p.y(), p.z());
				it.remove();
			}
		}
	}

	static double rayLen(Minecraft mc, Vec3 from, double dx, double dy, double dz, double len, boolean ignoreLeaves) {
		Vec3 to = from.add(dx * len, dy * len, dz * len);
		BlockHitResult hit = mc.level.clip(new ClipContext(from, to, Block.COLLIDER, Fluid.NONE, mc.player));
		if (hit.getType() == Type.MISS) {
			return len;
		} else {
			return ignoreLeaves && mc.level.getBlockState(hit.getBlockPos()).is(BlockTags.LEAVES) ? len : from.distanceTo(hit.getLocation());
		}
	}

	static float ledgeVol(Ledge l) {
		return Math.max(0.8F, Math.min(1.8F, 0.8F + 0.1F * (l.drop - 4)));
	}

	static String ledgeId;
	static SoundEvent ledgeEv;

	/** The ledge sound (config id), looped as a continuous tone. */
	static SoundEvent ledgeSound(SonarConfig cfg) {
		if (ledgeEv == null || !cfg.ledgeSound.equals(ledgeId)) {
			ledgeId = cfg.ledgeSound;
			var id = net.minecraft.resources.Identifier.tryParse(ledgeId);
			ledgeEv = id == null ? SoundEvents.METAL_BREAK : SoundEvent.createVariableRangeEvent(id);
		}
		return ledgeEv;
	}

	/** Where the echo would play: an enclosed room, or a cave mouth in sight. Worked out once per scan. */
	static boolean insideSpace(Minecraft mc, Scan s) {
		if (s.inside == null) {
			s.inside = enclosed(mc, s); // inside a room the echo replaces the block sounds; outside (even next to a visible opening) the sonar keeps playing
		}
		return s.inside;
	}

	static boolean enclosed(Minecraft mc, Scan s) {
		Vec3 from = new Vec3(s.ex, s.ey, s.ez);
		double k = Math.sqrt(0.5);
		double[][] up = new double[][]{{0.0, 1.0, 0.0}, {k, k, 0.0}, {-k, k, 0.0}, {0.0, k, k}, {0.0, k, -k}};
		int roof = 0;

		for (double[] v : up) {
			if (rayLen(mc, from, v[0], v[1], v[2], 16.0, true) < 16.0) {
				roof++;
			}
		}

		if (roof < 3) return false;
		// a roof alone is not a room (a covered deck, a porch, a carport): it also needs walls around you, in most directions within 10 blocks
		int walls = 0;
		for (int i = 0; i < 8; i++) {
			double a = i * Math.PI / 4;
			if (rayLen(mc, from, Math.cos(a), 0.0, Math.sin(a), 10.0, true) < 10.0) walls++;
		}
		return walls >= 6;
	}

	static void tagTree(Map<com.sonar.Cues.Cue, Integer> kinds, com.sonar.Cues.Cue c, BlockState st) {
		if (st.is(BlockTags.LEAVES)) {
			kinds.put(c, 1);
		} else if (st.is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
			kinds.put(c, 2);
		}
	}

	static boolean isTerrainSound(SoundEvent e) {
		String p = e.location().getPath();
		return p.contains("grass") || p.contains("gravel") || p.contains("rooted") || p.contains("mud") || p.contains("moss") || p.contains("roots");
	}

	static List<double[]> mouths(Minecraft mc, Scan s) {
		int n = 32;
		double[] r = new double[n];
		double[] base = new double[n];
		Vec3 from = new Vec3(s.ex, s.ey, s.ez);

		for (int i = 0; i < n; i++) {
			double a = i * 2 * Math.PI / n;
			r[i] = rayLen(mc, from, Math.cos(a), 0.0, Math.sin(a), s.range, false);
		}

		boolean[] open = new boolean[n];

		for (int i = 0; i < n; i++) {
			base[i] = Math.min(r[(i + n - 3) % n], r[(i + 3) % n]);
			open[i] = base[i] < 0.85 * s.range && r[i] > 1.8 * base[i] && r[i] - base[i] > 6.0;
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
				// roof must cover the far stretch of the opening (a real cave), not just an overhang or a mountainside seen from outside
				double dc = Math.cos(mid), ds = Math.sin(mid), dm = sumD / cnt;
				int roofs = 0;
				for (double f : new double[]{0.6, 0.75, 0.9}) {
					Vec3 pt = from.add(dc * dm * f, 0.0, ds * dm * f);
					if (rayLen(mc, pt, 0.0, 1.0, 0.0, 8.0, true) < 8.0) {
						roofs++;
					}
				}

				if (SonarConfig.cur.debug && mc.player != null) {
					Vec3 far = from.add(dc * 40.0, 0.0, ds * 40.0);
					var hit = mc.level.clip(new ClipContext(from, far, Block.COLLIDER, Fluid.NONE, mc.player));
					mc.player.sendSystemMessage(Component.literal(String.format("mouth: bearing %.0f deg, reach %.1f, wall before it %.1f, %d rays wide, roofs %d/3, ray hits %s at %.1f", Math.toDegrees(mid), dm, avgB, cnt, roofs,
						hit.getType() == Type.MISS ? "nothing" : mc.level.getBlockState(hit.getBlockPos()).getBlock().getDescriptionId(), hit.getType() == Type.MISS ? 40.0 : from.distanceTo(hit.getLocation()))));
				}
				if (roofs >= 3) {
					out.add(new double[]{mid, dm, cnt, avgB}); // bearing, depth, width in rays, distance to the opening itself
				}

				i = j;
			}
		}

		double fwd = Math.atan2(Math.cos(Math.toRadians(s.yaw)), -Math.sin(Math.toRadians(s.yaw)));
		out.sort(Comparator.comparingDouble(m -> Math.abs(Math.atan2(Math.sin(m[0] - fwd), Math.cos(m[0] - fwd)))));
		return out.size() > 3 ? new ArrayList<>(out.subList(0, 3)) : out;
	}

	private static void scheduleEcho(Scan s, SonarConfig cfg, double ang, double dist, float mul, int delay) {
		double dx = Math.cos(ang);
		double dz = Math.sin(ang);
				float vol = cfg.volume * cfg.echoVolume * 1.06F * mul * (1.0F - 0.5F * (float)(dist / s.range));
		float pitch = 1.4F - 0.7F * (float)Math.min(1.0, dist / s.range);
		if (dist < s.range * 0.2) {
			pitch = Math.min(2.0F, pitch + 0.2F); // tight space: a short, high click
		}

		SoundEvent hat = (SoundEvent)SoundEvents.NOTE_BLOCK_HAT.value();
		double[] lag = lag(s);
		pending.add(new com.sonar.Cues.Pending(hat, vol, pitch, s.ex + lag[0] + dx * 4.0, s.ey + lag[1], s.ez + lag[2] + dz * 4.0, new int[]{delay}));
		if (dist > s.range * 0.45) { // big space: a softer, lower tail after the echo
			pending.add(new com.sonar.Cues.Pending(hat, vol * 0.45F, pitch * 0.8F, s.ex + lag[0] + dx * 4.0, s.ey + lag[1], s.ez + lag[2] + dz * 4.0, new int[]{delay + 3}));
		}
	}

	static void echo(Minecraft mc, Scan s, SonarConfig cfg) {
		if (cfg.echoCount == 0) {
			return;
		}

		if (cfg.debug && mc.player != null) { // what is straight ahead: does a collision ray stop at it, does an outline ray, and which blocks are there
			double yr = Math.toRadians(s.yaw), ax = -Math.sin(yr), az = Math.cos(yr);
			Vec3 o = new Vec3(s.ex, s.ey, s.ez), far = o.add(ax * 20.0, 0.0, az * 20.0);
			var hc = mc.level.clip(new ClipContext(o, far, Block.COLLIDER, Fluid.NONE, mc.player));
			var ho = mc.level.clip(new ClipContext(o, far, Block.OUTLINE, Fluid.NONE, mc.player));
			StringBuilder sb = new StringBuilder();
			for (int d = 1; d <= 6; d++) {
				var bp = net.minecraft.core.BlockPos.containing(s.ex + ax * d, s.ey, s.ez + az * d);
				sb.append(d).append('=').append(mc.level.getBlockState(bp).getBlock().getDescriptionId().replace("block.minecraft.", "")).append(' ');
			}
			mc.player.sendSystemMessage(Component.literal(String.format("ahead probe: collider %s at %.1f, outline %s at %.1f | %s", hc.getType() == Type.MISS ? "nothing" : mc.level.getBlockState(hc.getBlockPos()).getBlock().getDescriptionId(), hc.getType() == Type.MISS ? 20.0 : o.distanceTo(hc.getLocation()),
				ho.getType() == Type.MISS ? "nothing" : mc.level.getBlockState(ho.getBlockPos()).getBlock().getDescriptionId(), ho.getType() == Type.MISS ? 20.0 : o.distanceTo(ho.getLocation()), sb)));
		}
		boolean room = enclosed(mc, s);
		List<double[]> gaps = mouths(mc, s);
		if (room) gaps = new ArrayList<>(); // inside a building the walkable exit guide does the work; the ray-based openings also fired for a staircase and a window gap, giving extra echoes
		if (gaps.isEmpty() && !room) { // outside a building with no cave or hall opening in sight: nothing to point at
			return;
		}

		Vec3 from = new Vec3(s.ex, s.ey, s.ez);
		double fwd = Math.atan2(Math.cos(Math.toRadians(s.yaw)), -Math.sin(Math.toRadians(s.yaw)));
		// priority 0 = the main part of the space (its longest reach), 1 = the nearest big opening (entrance / door), 2 = everything else
		List<double[]> echoes = new ArrayList<>(); // {angle, distance, volume factor, priority}
		double[] door = null;
		double mainReach = 0.0;
		List<Double> roomRays = new ArrayList<>();
		for (double[] g : gaps) {
			if (door == null || g[2] > door[2] || (g[2] == door[2] && g[3] < door[3])) {
				door = g; // widest opening, nearest among equals
			}
		}

		if (room) { // inside a building: guide to its doors, ways out and staircases (walls, glass included, never echo)
			echoes.addAll(ExitGuide.find(mc, s));
			for (double[] e : echoes) if (e[3] == 0) mainReach = Math.max(mainReach, e[1]);
		}

		for (double[] g : gaps) {
			double rel = Math.abs(Math.atan2(Math.sin(g[0] - fwd), Math.cos(g[0] - fwd)));
			float vol = rel < Math.PI / 4 ? 1.0F : (rel > Math.PI * 3.0 / 4.0 ? 0.5F : 0.75F);
			if (g == door) {
				// the entrance sounds from the opening itself; seen from outside, the cave's depth is the main part
				echoes.add(new double[]{g[0], room ? g[3] : g[1], vol, room ? 1 : 0});
			} else {
				echoes.add(new double[]{g[0], g[1], vol, 2});
			}
		}

		if (cfg.debug && mc.player != null) {
			StringBuilder sb = new StringBuilder();
			for (double[] e : echoes) sb.append(String.format("[%.0f deg, %.1f blocks, prio %.0f] ", Math.toDegrees(e[0]), e[1], e[3]));
			mc.player.sendSystemMessage(Component.literal("echo: room " + room + ", entrances " + gaps.size() + " | " + sb));
		}
		if (echoes.isEmpty()) return; // nothing to point at: stay silent
		echoes.sort(Comparator.<double[]>comparingDouble(e -> e[3]).thenComparingDouble(e -> -e[2]).thenComparingDouble(e -> e[1]));
		int count = cfg.echoCount;
		if (count < 0) {
			// auto: 2 (main space + entrance), plus one for each extra entrance, one for a big space, one for an irregular shape (alcoves, side rooms)
			count = 2 + Math.max(0, gaps.size() - 1);
			if (Math.max(mainReach, door == null ? 0.0 : door[1]) > s.range * 0.6) {
				count++;
			}

			if (roomRays.size() >= 4) {
				double lo = Double.MAX_VALUE;
				double hi = 0.0;
				for (double r : roomRays) {
					lo = Math.min(lo, r);
					hi = Math.max(hi, r);
				}

				if (hi > lo * 2.5) {
					count++;
				}
			}

			count = Math.min(5, Math.max(count, Math.min(4, echoes.size()))); // every way out and staircase found gets its echo
		}

		int n = Math.min(count, echoes.size());
		for (int i = 0; i < n; i++) { // the list is in order of importance (main way out first): it plays in reverse, so the main echo lands last, after the others
			double[] e = echoes.get(i);
			scheduleEcho(s, cfg, e[0], e[1], (float)e[2], 1 + (n - 1 - i) * 6);
		}
	}

	/** One continuous tone tracking the nearest edge ahead (louder/higher when closer; tall drops louder and lower). Returns ticks until the next check. */
	/** Edges to your left and right (outside the forward cone), each as its own quieter tone placed on that side, so ledges on both sides are easy to tell apart. Returns whether any played. */
	private static boolean sideTones(Minecraft mc, Scan s, SonarConfig cfg, double lead) {
		double yawR = Math.toRadians(s.yaw);
		double fx = -Math.sin(yawR);
		double fz = Math.cos(yawR);
		double rx = -Math.cos(yawR);
		double rz = -Math.sin(yawR);
		Ledge[] side = new Ledge[2];
		double[] rad = new double[2];
		if (cfg.sideLedges) {
			boolean aheadEdge = false; // an edge in front of you: the ones behind are not needed
			for (Ledge l : s.ledges) {
				if (l.d <= cfg.ledgeWarnRange && l.d != Float.MAX_VALUE) {
					double ex = l.x + 0.5 - s.ex, ez = l.z + 0.5 - s.ez;
					if (Math.abs(Math.toDegrees(Math.atan2(ex * rx + ez * rz, ex * fx + ez * fz))) < 45.0) {
						aheadEdge = true;
						break;
					}
				}
			}
			for (Ledge l : s.ledges) {
				if (l.d > 4.0F || l.d < 0.6F) {
					continue;
				}
				double ex = l.x + 0.5 - s.ex;
				double ez = l.z + 0.5 - s.ez;
				double r = Math.atan2(ex * rx + ez * rz, ex * fx + ez * fz);
				if (Math.abs(Math.toDegrees(r)) < 45.0) {
					continue; // ahead is handled by the main tone
					}
				if (corridor) { // on a path: only the nearer edge sounds, and only once you are clearly off-centre (so a centred walk is silent)
					double latd = Math.abs(ex * rx + ez * rz);
					double od = ex * rx + ez * rz < 0 ? corrRight : corrLeft;
					if (latd > 1.8 || latd > od * 0.6) {
						continue;
					}
				}
				if (aheadEdge && Math.abs(Math.toDegrees(r)) > 110.0) {
					continue; // an edge ahead: leave out the ones behind you
				}
				int k = r < 0.0 ? 0 : 1;
				if (side[k] == null || l.d < side[k].d) {
					side[k] = l;
					rad[k] = r;
				}
			}
		}

		if (corridor) { // on a path: silent while centred; the nearer edge gets its own tone, panned hard to that side, once you are clearly off-centre
			boolean anyC = false;
			for (int k = 0; k < 2; k++) {
				double dn = k == 0 ? corrLeft : corrRight, df = k == 0 ? corrRight : corrLeft;
				if (dn <= 0.8 && dn < df * 0.6) {
					float c = 1.0F - (float)(dn / 0.8);
					float vol = Math.min(1.0F, 0.72F * (0.45F + 0.55F * c) * dropFactor(k == 0 ? corrLeftDrop : corrRightDrop) * cfg.volume / 0.6F * cfg.ledgeVolume);
					float pitch = (0.75F + 0.7F * c) * dropPitch(k == 0 ? corrLeftDrop : corrRightDrop);
					double a = (k == 0 ? -1.0 : 1.0) * 1.2;
					LedgeTone.update(mc, ledgeSound(cfg), k + 1, vol, pitch, s.ex + 4.0 * (Math.cos(a) * fx + Math.sin(a) * rx), s.feetY - 1.0, s.ez + 4.0 * (Math.cos(a) * fz + Math.sin(a) * rz));
					anyC = true;
				} else {
					LedgeTone.quiet(k + 1);
				}
			}
			return anyC;
		}

		boolean any = false;
		for (int k = 0; k < 2; k++) {
			Ledge l = side[k];
			if (l == null) {
				LedgeTone.quiet(k + 1);
				continue;
			}
			any = true;
			float c = Math.max(0.0F, Math.min(1.0F, 1.0F - (float)Math.max(0.0, l.d - lead) / cfg.ledgeWarnRange));
			float dropF = dropFactor(l.drop);
			float vol = Math.min(1.0F, 0.6F * 0.72F * (0.4F + 0.6F * c) * dropF * cfg.volume / 0.6F * cfg.ledgeVolume);
			float pitch = Math.max(0.4F, Math.min(2.0F, (0.75F + 0.7F * c) * dropPitch(l.drop)));
			// placed hard to that side: the sideways angle is exaggerated and the source sits well out (8 blocks), so it reads as coming from that direction
			double a = Math.signum(rad[k]) * Math.min(1.4, Math.abs(rad[k]) * 1.5);
			double dist = 8.0;
			LedgeTone.update(mc, ledgeSound(cfg), k + 1, vol, pitch, s.ex + dist * (Math.cos(a) * fx + Math.sin(a) * rx), l.y - 0.5, s.ez + dist * (Math.cos(a) * fz + Math.sin(a) * rz));
		}

		return any;
	}

	/** Distance and drop of the edge you are heading toward (for the optional edge brake); updated by watchLedges. */
	static float edgeAheadD = Float.MAX_VALUE;
	static int edgeAheadDrop;
	static double edgeAheadX, edgeAheadZ;
	/** Nearest walkable step down of 2+ blocks ahead of you (the brake also uses this). */
	static float stepAheadD = Float.MAX_VALUE;
	static int stepAheadDrop;
	static double stepAheadX, stepAheadZ;

	private static int dbgTick;
	private static int ledgeMiss;
	/** True while an edge is within warning range, so the tone keeps going even if you stand still next to it. */
	static boolean ledgeActive;
	private static boolean corridor, wasCorridor;
	private static double corrLeft, corrRight;
	private static int corrLeftDrop = 99, corrRightDrop = 99;

	public static int watchLedges(Minecraft mc, Scan s, SonarConfig cfg, double vx, double vz) {
		double sp = Math.max(1.0E-6, Math.hypot(vx, vz));
		var mv = mc.player.getDeltaMovement();
		double lead = Math.hypot(mv.x, mv.z) * 20.0 * 0.35; // running: judge the edge as if you were already 0.35 s further along
		List<Ledge> near = new ArrayList<>();
		stepAheadD = Float.MAX_VALUE;
		for (Ledge dr : s.drops) {
			if (dr.d == Float.MAX_VALUE || dr.d >= stepAheadD) {
				continue;
			}
			double ex = dr.x + 0.5 - s.ex;
			double ez = dr.z + 0.5 - s.ez;
			if ((ex * vx + ez * vz) / (Math.max(1.0E-6, Math.hypot(ex, ez)) * sp) >= 0.7 || dr.d <= 0.6) {
				stepAheadD = dr.d;
				stepAheadDrop = dr.drop;
				stepAheadX = dr.x + 0.5;
				stepAheadZ = dr.z + 0.5;
			}
		}

		// standing over (or right beside) a hole: edges nearly all the way around you within 4 blocks. Sound it from inside the hole, below you.
		// (A ridge or path with a drop on each side has big gaps ahead and behind, so it is not a hole.)
		int holeCount = 0;
		double cx = 0.0;
		double cz = 0.0;
		float nearest = Float.MAX_VALUE;
		int deepest = 0;
		List<Double> bearings = new ArrayList<>();
		for (Ledge l : s.ledges) {
			if (l.d <= 4.0F) {
				bearings.add(Math.atan2(l.z + 0.5 - s.ez, l.x + 0.5 - s.ex));
				cx += l.x + 0.5;
				cz += l.z + 0.5;
				holeCount++;
				nearest = Math.min(nearest, l.d);
				deepest = Math.max(deepest, l.drop);
			}
		}

		double maxGap = 2.0 * Math.PI;
		if (bearings.size() >= 4) {
			java.util.Collections.sort(bearings);
			maxGap = bearings.get(0) + 2.0 * Math.PI - bearings.get(bearings.size() - 1);
			for (int i = 1; i < bearings.size(); i++) {
				maxGap = Math.max(maxGap, bearings.get(i) - bearings.get(i - 1));
			}
		}

		// corridor: a straight path or ridge with a drop on BOTH sides. Each side is a line of edge cells at the same distance (3+ cells within 4 blocks along your heading).
		// Its edges are constant, so they only sound once you drift close to one. (It is also not a hole, though edges surround you.)
		double cyaw = Math.toRadians(s.yaw);
		double cfx = -Math.sin(cyaw), cfz = Math.cos(cyaw), crx = -Math.cos(cyaw), crz = -Math.sin(cyaw);
		double dLeft = Double.MAX_VALUE, dRight = Double.MAX_VALUE;
		int dropL = 99, dropR = 99; // how deep the nearest edge on each side is (the path guide does not know, so it counts as a full warning)
		for (Ledge l : s.ledges) {
			if (l.d > 5.0F || l.d == Float.MAX_VALUE) {
				continue;
			}
			double ex = l.x + 0.5 - s.ex, ez = l.z + 0.5 - s.ez;
			double lat = ex * crx + ez * crz, fwd = ex * cfx + ez * cfz;
			if (Math.abs(fwd) > 4.0 || Math.abs(lat) < 0.05) {
				continue; // far ahead or behind: not a side edge
			}
			if (lat < 0) {
				if (-lat < dLeft) { dLeft = -lat; dropL = l.drop; }
			} else {
				if (lat < dRight) { dRight = lat; dropR = l.drop; }
			}
		}
		int nLeft = 0, nRight = 0;
		for (Ledge l : s.ledges) {
			if (l.d > 5.0F || l.d == Float.MAX_VALUE) {
				continue;
			}
			double ex = l.x + 0.5 - s.ex, ez = l.z + 0.5 - s.ez;
			double lat = ex * crx + ez * crz, fwd = ex * cfx + ez * cfz;
			if (Math.abs(fwd) > 4.0 || Math.abs(lat) < 0.05) {
				continue;
			}
			if (lat < 0 && -lat - dLeft < 0.6) {
				nLeft++;
			} else if (lat > 0 && lat - dRight < 0.6) {
				nRight++;
			}
		}
		corridor = dLeft <= 4.5 && dRight <= 4.5 && nLeft >= 2 && nRight >= 2;
		boolean guided = false;
		double bndL = Double.MAX_VALUE, bndR = Double.MAX_VALUE;
		if (PathGuide.active()) { // the path guide knows the path (stairs, ramps, bends too): measure the sides from the path itself instead of guessing from the drops
			double[] pe = PathGuide.edges(mc, s.ex, s.ez, mc.player.getBlockY() - 1, crx, crz, cfg.ledgeMinDrop);
			if (pe != null) {
				guided = true;
				corridor = true;
				dLeft = pe[0];
				dRight = pe[1];
				bndL = pe[2];
				bndR = pe[3];
			}
		}
		if (guided) { dropL = 99; dropR = 99; }
		corrLeft = dLeft;
		corrRight = dRight;
		corrLeftDrop = dropL;
		corrRightDrop = dropR;
		if (corridor && !wasCorridor) {
			LedgeTone.hush(0); // stepped onto a path: the island's edge tone must not ring on for a second
			LedgeTone.hush(1);
			LedgeTone.hush(2);
		}
		wasCorridor = corridor;

		if (cfg.debug && ++dbgTick % 7 == 3) {
			mc.player.sendSystemMessage(Component.literal(String.format("hole check: guided %b corridor %b (L %.1f x%d, R %.1f x%d) edges<=4: %d, maxGap %.0f", guided, corridor, dLeft > 1e6 ? -1.0 : dLeft, nLeft, dRight > 1e6 ? -1.0 : dRight, nRight, holeCount, Math.toDegrees(maxGap))));
		}
		if (!corridor && !PathGuide.active() && holeCount >= 4 && maxGap < Math.toRadians(110.0)) {
			if (cfg.debug) {
				mc.player.sendSystemMessage(Component.literal("TONE: hole (under you), nearest " + nearest + " deepest " + deepest));
			}
			ledgeMiss = 0;
			ledgeActive = true;
			float c = Math.max(0.0F, Math.min(1.0F, 1.0F - (float)Math.max(0.0, nearest - lead) / cfg.ledgeWarnRange));
			float dropF = dropFactor(deepest);
			float vol = Math.min(1.0F, 0.72F * (0.4F + 0.6F * c) * dropF * cfg.volume / 0.6F * cfg.ledgeVolume);
			float pitch = Math.max(0.4F, Math.min(2.0F, (0.75F + 0.7F * c) * dropPitch(deepest)));
			LedgeTone.quiet(1);
			LedgeTone.quiet(2);
			LedgeTone.update(mc, ledgeSound(cfg), 0, vol, pitch, cx / holeCount, s.feetY - Math.min(deepest, 6) * 0.7 - 0.5, cz / holeCount);
			return 3;
		}

		edgeAheadD = Float.MAX_VALUE;
		boolean anySide = sideTones(mc, s, cfg, lead);
		if (cfg.debug && dbgTick % 7 == 1) {
			int nLedges = 0;
			StringBuilder sb = new StringBuilder();
			for (Ledge e : s.ledges) {
				if (e.d != Float.MAX_VALUE && e.d <= 6.0F) {
					double ex = e.x + 0.5 - s.ex, ez = e.z + 0.5 - s.ez;
					if (++nLedges <= 8) {
						sb.append(String.format(" [fwd %.1f lat %.1f drop %d]", ex * cfx + ez * cfz, ex * crx + ez * crz, e.drop));
					}
				}
			}
			mc.player.sendSystemMessage(Component.literal(String.format("corridor %b (L %.1f R %.1f) ledges<=6: %d%s", corridor, dLeft, dRight, nLedges, sb)));
		}
		if (cfg.debug && ++dbgTick % 7 == 0) {
			Ledge nl = null;
			int n = 0;
			for (Ledge e : s.ledges) {
				if (e.d != Float.MAX_VALUE) {
					n++;
					if (nl == null || e.d < nl.d) {
						nl = e;
					}
				}
			}

			mc.player.sendSystemMessage(Component.literal(nl == null ? "ledge: none detected" : String.format("ledge: %d sectors, nearest %.1f blocks (real %.1f), drop %d, deep %b", n, nl.d, Math.hypot(nl.x + 0.5 - s.ex, nl.z + 0.5 - s.ez), nl.drop, nl.deep)));
		}

		for (Ledge l : s.ledges) {
			if (l.d - lead > cfg.ledgeWarnRange) {
				continue;
			}
			double ex = l.x + 0.5 - s.ex;
			double ez = l.z + 0.5 - s.ez;
			double el = Math.max(1.0E-6, Math.hypot(ex, ez));
			if ((ex * vx + ez * vz) / (el * sp) < 0.7 && el > 1.0) { // el = the real distance: l.d can be shrunk to 0.5 by the stair-approach logic, which must not let an edge far behind you count as "right at your feet"
				continue; // only edges within ~45 degrees of where you are heading count (open sides of a staircase or bridge stay quiet)
			}
			if (guided) { // on the guided path: terrain beyond its sides (a cliff, terraces, the island next to it) is not on your way, so it stays silent until you are back on the main land
				double lat = ex * crx + ez * crz;
				if (lat < -(bndL + 0.1) || lat > bndR + 0.1) {
					continue;
				}
				if (Math.abs(lat + (bndL - 0.5)) < 0.6 || Math.abs(lat - (bndR - 0.5)) < 0.6) { // matched against both side lines: next to an edge the sign of lat flips
					continue; // the cells along the path's own side lines (any drop size): sideTones handles those, they are not "ahead"
				}
			}
			if (corridor) { // the two lines of edge cells along the path are handled by sideTones; only something else (the end of the path, a drop across it) counts as ahead
				double lat = ex * crx + ez * crz;
				if (Math.abs(lat + dLeft) < 0.6 || Math.abs(lat - dRight) < 0.6) {
					continue;
				}
			}
			near.add(l);
		}

		if (near.isEmpty()) {
			LedgeTone.quiet(0);
			if (anySide) {
				ledgeMiss = 0;
				ledgeActive = true;
				return 3;
			}

			if (++ledgeMiss > 3) {
				ledgeActive = false; // brief gaps (looking around, a step in the way) keep the tone instead of cutting out and restarting
				LedgeTone.fade();
			}
			return 5;
		}

		ledgeMiss = 0;
		ledgeActive = true;
		near.sort(Comparator.comparingDouble(e -> e.d));
		// one tone, sounded from the edge itself (just below the lip, where the ground falls away), louder and higher the closer it is
		// the edge you are heading toward: among the nearest ones, the one most squarely ahead (at the lip, the cell beside your foot must not win)
		Ledge l = near.get(0);
		double bestAlign = -2.0;
		for (Ledge e : near) {
			if (e.d > near.get(0).d + 2.0) {
				break;
			}

			double ex = e.x + 0.5 - s.ex;
			double ez = e.z + 0.5 - s.ez;
			double align = (ex * vx + ez * vz) / (Math.max(1.0E-6, Math.hypot(ex, ez)) * sp);
			if (align > bestAlign) {
				bestAlign = align;
				l = e;
			}
		}

		float c = Math.max(0.0F, Math.min(1.0F, 1.0F - (float)Math.max(0.0, l.d - lead) / cfg.ledgeWarnRange));
		float dropF = dropFactor(l.drop);
		float vol = Math.min(1.0F, 0.72F * (0.4F + 0.6F * c) * dropF * cfg.volume / 0.6F * cfg.ledgeVolume);
		float pitch = Math.max(0.4F, Math.min(2.0F, (0.75F + 0.7F * c - (l.deep ? 0.15F : 0.0F)) * dropPitch(l.drop)));
		if (cfg.debug) {
			double dx0 = l.x + 0.5 - s.ex, dz0 = l.z + 0.5 - s.ez;
			mc.player.sendSystemMessage(Component.literal(String.format("TONE: main from edge fwd %.1f lat %.1f drop %d d %.1f vol %.2f | guided %b corridor %b bnd L %.1f R %.1f", dx0 * cfx + dz0 * cfz, dx0 * crx + dz0 * crz, l.drop, l.d, vol, guided, corridor, bndL > 1e6 ? -1.0 : bndL, bndR > 1e6 ? -1.0 : bndR)));
		}
		LedgeTone.update(mc, ledgeSound(cfg), 0, vol, pitch, l.x + 0.5, l.y - 0.5, l.z + 0.5);
		edgeAheadD = l.d;
		edgeAheadDrop = l.drop;
		edgeAheadX = l.x + 0.5;
		edgeAheadZ = l.z + 0.5;

		return 3; // check often while an edge is near so the tone tracks it closely
	}

	/** The warning's loudness follows the danger: a 3-block drop is a stumble (soft), 4 is a little louder, and 5 or more (a real fall, or lava, which counts as 99) is full strength. */
	static float dropFactor(int drop) {
		if (drop >= 5) return 1.0F;
		if (drop == 4) return 0.45F;
		return 0.15F;
	}

	/** Small drops sound lower; the tone rises to the normal pitch as the drop reaches 5 blocks. */
	static float dropPitch(int drop) {
		if (drop >= 5) return 1.0F;
		if (drop == 4) return 0.68F;
		return 0.5F;
	}

	static void play(Minecraft mc, SoundEvent e, float vol, float pitch, double x, double y, double z) {
		mc.getSoundManager().play(new SimpleSoundInstance(e, SoundSource.AMBIENT, vol, pitch, RandomSource.create(), x, y, z));
	}

	static float closeness(float d, int range) {
		float c = 1.0F - Math.min(d, range) / range;
		return 0.12F + 0.88F * c * c;
	}

	private static final Map<SoundEvent, SoundEvent> MUFFLED = new HashMap<>();
	private static java.util.Set<String> muffledNames;

	/** Low-passed copy of a block sound (bundled in the mod), used for cues behind you so front and back sound different. Falls back to the original. */
	static SoundEvent muffled(SoundEvent e) {
		if (muffledNames == null) {
			muffledNames = new java.util.HashSet<>();
			try (var in = Cues.class.getResourceAsStream("/assets/sonar/muffled.txt")) {
				if (in != null) {
					for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
						muffledNames.add(line.trim());
					}
				}
			} catch (java.io.IOException ignored) {
			}
		}
		return MUFFLED.computeIfAbsent(e, k -> {
			String p = k.location().getPath();
			return muffledNames.contains(p) ? SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.fromNamespaceAndPath("sonar", "m." + p)) : k;
		});
	}

	/** Exaggerates the sideways angle: 1 = true angle, 2 = double (front stays front, behind stays behind). */
	static double spread(double a, double k) {
		double h = Math.PI / 2;
		double abs = Math.abs(a);
		double out = abs <= h ? abs * k : k * h + (abs - h) * (Math.PI - k * h) / h;
		return Math.signum(a) * Math.min(Math.PI, out);
	}

	static com.sonar.Cues.Cue at(Scan s, SonarConfig cfg, float d, double tx, double ty, double tz, SoundEvent e, float volMul, float pitch, int pri) {
		double vx = tx - s.ex;
		double vy = ty - s.ey;
		double vz = tz - s.ez;
		double len = Math.max(1.0E-6, Math.sqrt(vx * vx + vy * vy + vz * vz));
		double ad = 1.0 + Math.min(d, s.range) / s.range * (pri == 0 ? 6.0 : 12.0);
		double yawR = Math.toRadians(s.yaw);
		double fx = -Math.sin(yawR);
		double fz = Math.cos(yawR);
		double hl = Math.max(1.0E-6, Math.hypot(vx, vz));
		boolean behind = pri != 0 && (vx * fx + vz * fz) / hl < -0.5;
		float vol = Math.min(1.0F, cfg.volume * closeness(d, s.range) * volMul * (behind ? 0.5F : 1.0F));
		if (behind) {
			e = muffled(e);
			if (cfg.behindPitch) {
				pitch *= 0.88F;
			}
		}

		if (pri != 0 && cfg.directionSpread > 1.001F) {
			double rx = -Math.cos(yawR);
			double rz = -Math.sin(yawR);
			double a = spread(Math.atan2(vx * rx + vz * rz, vx * fx + vz * fz), cfg.directionSpread);
			vx = hl * (Math.cos(a) * fx + Math.sin(a) * rx);
			vz = hl * (Math.cos(a) * fz + Math.sin(a) * rz);
			len = Math.max(1.0E-6, Math.sqrt(vx * vx + vy * vy + vz * vz));
		}

		double[] lag = lag(s);
		return new com.sonar.Cues.Cue(d, e, vol, pitch, s.ex + lag[0] + vx / len * ad, s.ey + lag[1] + vy / len * ad, s.ez + lag[2] + vz / len * ad, pri);
	}

	/** How far you moved since the scan began. The sounds are placed around where you are now, not where you were, so they do not trail behind when you run. */
	static double[] lag(Scan s) {
		var p = Minecraft.getInstance().player;
		return p == null ? new double[3] : new double[]{p.getX() - s.ex, p.getEyeY() - s.ey, p.getZ() - s.ez};
	}

	static SoundEvent blockSound(BlockState st, SonarConfig cfg) {
		if (!cfg.materialSounds) {
			return SoundEvents.STONE_HIT;
		}

		SoundType t = st.getSoundType();
		return t == SoundType.GLASS ? t.getPlaceSound() : t.getBreakSound();
	}

	/** Each material family gets its own pitch so they are easier to tell apart: stone high and clicky, wood hollow, soft ground low and muffled, metal bright, glass highest. */
	static float materialPitch(SoundEvent e) {
		String p = e.location().getPath();
		if (p.contains("glass") || p.contains("amethyst")) return 1.45F;
		if (p.contains("metal") || p.contains("copper") || p.contains("chain") || p.contains("netherite") || p.contains("anvil") || p.contains("iron")) return 1.15F;
		if (p.contains("stone") || p.contains("deepslate") || p.contains("tuff") || p.contains("calcite") || p.contains("brick") || p.contains("basalt")) return 1.25F;
		if (p.contains("wood") || p.contains("bamboo") || p.contains("stem")) return 0.85F;
		if (p.contains("grass") || p.contains("gravel") || p.contains("sand") || p.contains("mud") || p.contains("moss") || p.contains("rooted") || p.contains("snow") || p.contains("wool")) return 1.0F;
		return 1.0F;
	}

	static SoundEvent tactileSound(BlockState st) {
		if (st.is(BlockTags.LEAVES)) {
			return SoundEvents.GRASS_STEP;
		} else if (st.is(BlockTags.LOGS)) {
			return SoundEvents.WOOD_HIT;
		} else if (st.is(BlockTags.SAND)) {
			return SoundEvents.SAND_STEP;
		} else {
			return st.is(Blocks.GRAVEL) ? SoundEvents.GRAVEL_STEP : SoundEvents.CHAIN_HIT;
		}
	}

	public static void emit(Minecraft mc, Scan s, SonarConfig cfg) {
		List<com.sonar.Cues.Cue> cues = new ArrayList<>();
		Map<com.sonar.Cues.Cue, Integer> treeKind = new java.util.IdentityHashMap<>();
		List<com.sonar.Cues.Cue> ledgeCues = new ArrayList<>();
		loopSound = null;
		float nearLoop = Float.MAX_VALUE;
		// lava: only the 3 nearest hazard sectors sound (a lava lake used to fire dozens of sounds per ping), and it never loops
		Integer[] hazOrder = new Integer[48];
		for (int i = 0; i < 48; i++) {
			hazOrder[i] = i;
		}

		// inside a cave or building (where the echo would play): the sonar's block sounds would just be a wall of stone, so only the echo, hazards, water, mobs and ledges sound
		boolean quiet = cfg.echoReplacesSonar && cfg.echoCue && cfg.echoCount != 0 && !s.front && insideSpace(mc, s);
		long nowTick = mc.level.getGameTime();
		boolean lavaDue = nowTick - lastLava >= 50; // lava sounds at most every 2.5 s, however fast you ping (repeat / fast auto scan)
		boolean lavaPlayed = false;
		final Cand[] hazards = s.best[0];
		java.util.Arrays.sort(hazOrder, Comparator.comparingDouble(i -> hazards[i].state == null ? Double.MAX_VALUE : hazards[i].d));

		for (int i = 0; i < 48; i++) {
			Cand haz = s.best[0][i];
			Cand sol = s.best[1][i];
			Cand tac = s.best[2][i];
			Cand wat = s.best[3][i];
			if (haz.state != null && cfg.hazardCue) {
				int rank = java.util.Arrays.asList(hazOrder).indexOf(i);
				if (rank < 3 && lavaDue) {
					lavaPlayed = true;
					if (rank == 0) {
						cues.add(at(s, cfg, haz.d, haz.x + 0.5, haz.y + 0.5, haz.z + 0.5, SoundEvents.LAVA_POP, 0.5F * cfg.lavaVolume, 0.7F, 0));
					}
					cues.add(at(s, cfg, haz.d, haz.x + 0.5, haz.y + 0.5, haz.z + 0.5, LAVA_SHORT, 0.35F * cfg.lavaVolume, 0.9F, 0));
				}
			} else {
				if (wat.state != null) {
					int depth = waterDepth(mc, wat.x, wat.y, wat.z);
					float pitch = Math.max(0.5F, 1.2F - depth * 0.1F);
					cues.add(at(s, cfg, wat.d, wat.x + 0.5, wat.y + 0.5, wat.z + 0.5, WATER_SHORT, 0.6F + depth * 0.1F, pitch, 1));
					if (wat.d < nearLoop) {
						nearLoop = wat.d;
						setLoop(wat, WATER_SHORT, 0.4F + depth * 0.08F, pitch);
					}
				}

				if (sol.state != null && !quiet) {
					if (cfg.debug && mc.player != null) mc.player.sendSystemMessage(Component.literal("wall: " + sol.state.getBlock().getDescriptionId() + " at " + sol.x + "," + sol.y + "," + sol.z + " -> " + blockSound(sol.state, cfg).location().getPath()));
					com.sonar.Cues.Cue solCue = at(s, cfg, sol.d, sol.x + 0.5, sol.y + 0.5, sol.z + 0.5, blockSound(sol.state, cfg), (sol.state.is(net.minecraft.tags.BlockTags.SLABS) || sol.state.is(net.minecraft.tags.BlockTags.STAIRS)) ? 0.45F : 0.9F, materialPitch(blockSound(sol.state, cfg)), 1);
					cues.add(solCue);
					tagTree(treeKind, solCue, sol.state);
				} else if (tac.state != null && !quiet) {
					if (cfg.debug && mc.player != null) mc.player.sendSystemMessage(Component.literal("touch: " + tac.state.getBlock().getDescriptionId() + " at " + tac.x + "," + tac.y + "," + tac.z));
					com.sonar.Cues.Cue tacCue = at(s, cfg, tac.d, tac.x + 0.5, tac.y + 0.5, tac.z + 0.5, tactileSound(tac.state), 0.8F, 0.9F, 2);
					cues.add(tacCue);
					tagTree(treeKind, tacCue, tac.state);
				}
			}
		}

		if (!s.front) {
			for (Ledge l : s.ledges) {
				if (l.d != Float.MAX_VALUE && !cfg.autoLedge) {
					if (l.deep && cfg.hazardCue) {
						ledgeCues.add(at(s, cfg, l.d, l.x + 0.5, l.y, l.z + 0.5, ledgeSound(cfg), ledgeVol(l), 0.8F, 0));
					} else {
						ledgeCues.add(at(s, cfg, l.d, l.x + 0.5, l.y, l.z + 0.5, ledgeSound(cfg), ledgeVol(l), 0.95F, 0));
					}
				}
			}

			if (!quiet) {
				extraCues(s, cfg, cues);
			}
		}

		Integer[] order = new Integer[48];

		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}

		Arrays.sort(order, Comparator.comparingDouble(ix -> s.mobD[ix]));
		long now = mc.level.getGameTime();
		int mobCount = 0;
		Integer[] var25 = order;
		int var27 = var25.length;

		for (int var30 = 0; var30 < var27; var30++) {
			int i = var25[var30];
			if (mobCount >= cfg.maxMobCues) {
				break;
			}

			if (s.mobs[i] != null && s.mobD[i] != Float.MAX_VALUE) {
				Mob m = s.mobs[i];
				Long last = mobSeen.get(m.getId());
				if (last == null || now - last >= 60L) {
					SoundEvent amb = ((MobAccessor)m).sonar$ambient();
					if (amb != null) {
						mobSeen.put(m.getId(), now);
						mobCount++;
						cues.add(at(s, cfg, s.mobD[i], m.getX(), m.getY() + m.getBbHeight() / 2.0F, m.getZ(), amb, 0.9F * (s.mobD[i] <= 2.0F ? 1.0F : Math.max(0.04F, (float)Math.pow(0.5, (s.mobD[i] - 2.0) / 4.0))), 1.0F, 1)); // full volume only right next to it, then it halves every 4 blocks
					}
				}
			}
		}

		if (lavaPlayed) {
			lastLava = nowTick;
		}

		boolean path = FloorEdge.ping(mc, cfg);
		if (cfg.ceilingCue && !s.front && rayLen(mc, new Vec3(s.ex, s.ey, s.ez), 0.0, 1.0, 0.0, 3.0, true) < 1.1) {
			play(mc, (SoundEvent)SoundEvents.NOTE_BLOCK_BASS.value(), cfg.volume * 0.5F, 1.3F, s.ex, s.ey + 1.5, s.ez); // low ceiling overhead
		}

		if (!s.front && cfg.echoCue && s.withEcho) {
			echo(mc, s, cfg);
		}

		cues.sort(Comparator.comparingDouble(com.sonar.Cues.Cue::d));
		int leaves = 0;
		int logs = 0; // trees are sparse: only the nearest couple of leaf and trunk sounds play
		for (int i = 0; i < cues.size(); i++) {
			com.sonar.Cues.Cue c = cues.get(i);
			Integer k = treeKind.get(c);
			if (k == null) {
				continue;
			}
			if (k == 1) {
				if (++leaves > 2) {
					cues.remove(i--);
				} else {
					cues.set(i, new com.sonar.Cues.Cue(c.d(), c.snd(), c.vol() * 0.7F, c.pitch(), c.x(), c.y(), c.z(), c.pri()));
				}
			} else if (++logs > 2) {
				cues.remove(i--);
			}
		}

		Map<SoundEvent, Integer> seen = new HashMap<>();
		int terrain = 0; // dirt/grass/gravel share one small budget so hillsides do not drown out everything else

		for (int i = 0; i < cues.size(); i++) {
			com.sonar.Cues.Cue c = cues.get(i);
			if (c.pri() != 0 && isTerrainSound(c.snd())) {
				double tdx = c.x() - s.ex, tdz = c.z() - s.ez, tl = Math.max(1.0E-6, Math.hypot(tdx, tdz));
				double tyaw = Math.toRadians(s.yaw);
				if ((tdx * -Math.sin(tyaw) + tdz * Math.cos(tyaw)) / tl < -0.2) { // grass and dirt behind you are not needed: they only crowd the sounds ahead
					cues.remove(i--);
				} else if (++terrain > 16) {
					cues.remove(i--);
				} else {
					cues.set(i, new com.sonar.Cues.Cue(c.d(), c.snd(), c.vol() * 0.6F, c.pitch(), c.x(), c.y(), c.z(), c.pri()));
				}
			} else if (c.pri() != 0) {
				int r = seen.merge(c.snd(), 1, Integer::sum) - 1;
				if (r >= cfg.maxPerMaterial) {
					cues.remove(i--);
				} else if (r >= 3) {
					cues.set(i, new com.sonar.Cues.Cue(c.d(), c.snd(), c.vol() * 0.55F, c.pitch(), c.x(), c.y(), c.z(), c.pri()));
				}
			}
		}

		boolean nearLedge = false;

		for (com.sonar.Cues.Cue c : ledgeCues) {
			if (c.d() < 6.0F) {
				nearLedge = true;
			}
		}

		if (nearLedge) {
			for (int i = 0; i < cues.size(); i++) {
				com.sonar.Cues.Cue c = cues.get(i);
				if (c.pri() != 0) {
					cues.set(i, new com.sonar.Cues.Cue(c.d(), c.snd(), c.vol() * 0.4F, c.pitch(), c.x(), c.y(), c.z(), c.pri()));
				}
			}
		}

		if (path) { // a built path nearby: it comes first, everything else a bit quieter
			for (int i = 0; i < cues.size(); i++) {
				com.sonar.Cues.Cue c = cues.get(i);
				if (c.pri() != 0) {
					cues.set(i, new com.sonar.Cues.Cue(c.d(), c.snd(), c.vol() * 0.7F, c.pitch(), c.x(), c.y(), c.z(), c.pri()));
				}
			}
		}

		cues.addAll(ledgeCues);
		cues.sort(Comparator.comparingInt(com.sonar.Cues.Cue::pri).thenComparingDouble(com.sonar.Cues.Cue::d));
		int n = Math.min(cues.size(), cfg.maxSounds);

		double yawNow = Math.toRadians(s.yaw);
		Integer[] playOrder = new Integer[n];
		for (int i = 0; i < n; i++) {
			playOrder[i] = i;
		}

		final double fxs = -Math.sin(yawNow);
		final double fzs = Math.cos(yawNow);
		final List<com.sonar.Cues.Cue> played = cues;
		java.util.Arrays.sort(playOrder, Comparator.comparingDouble(i -> {
			com.sonar.Cues.Cue c = played.get(i);
			double dx = c.x() - s.ex;
			double dz = c.z() - s.ez;
			return -(dx * fxs + dz * fzs) / Math.max(1.0E-6, Math.hypot(dx, dz));
		}));

		for (int rank = 0; rank < n; rank++) {
			com.sonar.Cues.Cue c = cues.get(playOrder[rank]);
			int bucket = !cfg.stagger || c.pri() == 0 ? 0 : Math.min(2, rank * 3 / Math.max(1, n));
			if (bucket == 0) {
				play(mc, c.snd(), c.vol(), c.pitch(), c.x(), c.y(), c.z());
			} else {
				pending.add(new com.sonar.Cues.Pending(c.snd(), c.vol(), c.pitch(), c.x(), c.y(), c.z(), new int[]{bucket - 1}));
			}
		}

		if (cfg.debug && mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("Sonar: " + s.nanos / 1000000.0 + " ms, " + cues.size() + " cues, played " + n));
		}
	}

	private static void setLoop(Cand c, SoundEvent e, float vol, float pitch) {
		loopX = c.x + 0.5;
		loopY = c.y + 0.5;
		loopZ = c.z + 0.5;
		loopSound = e;
		loopVol = Math.min(vol, 1.0F);
		loopPitch = pitch;
	}

	static int waterDepth(Minecraft mc, int x, int y, int z) {
		int d = 0;
		MutableBlockPos p = new MutableBlockPos(x, y, z);

		while (d < 10 && mc.level.getFluidState(p).is(FluidTags.WATER)) {
			d++;
			p.move(0, -1, 0);
		}

		return d;
	}

	private static void extraCues(Scan s, SonarConfig cfg, List<com.sonar.Cues.Cue> out) {
		float[] dist = new float[16];

		for (int i = 0; i < 16; i++) {
			float m = Float.MAX_VALUE;

			for (int b = 0; b < 3; b++) {
				m = Math.min(m, s.best[1][b * 16 + i].d);
			}

			dist[i] = m;
		}

		double fwd = Math.atan2(Math.cos(Math.toRadians(s.yaw)), -Math.sin(Math.toRadians(s.yaw)));
		if (cfg.gapCue) {
			for (int i = 0; i < 16; i++) {
				float a = dist[(i + 16 - 1) % 16];
				float b = dist[(i + 1) % 16];
				if (a < 6.0F && b < 6.0F && dist[i] > Math.max(a, b) + 4.0F) {
					double ang = (i + 0.5) / 16.0 * 2.0 * Math.PI - Math.PI;
					double r = Math.min(6.0, dist[i]);
					out.add(at(s, cfg, (float)r, s.ex + Math.cos(ang) * r, s.ey, s.ez + Math.sin(ang) * r, SoundEvents.AMETHYST_BLOCK_CHIME, 0.7F, 1.4F, 2));
				}
			}
		}

		if (cfg.emptyCue) {
			for (int k = 0; k < 4; k++) {
				double ang = fwd + k * Math.PI / 2.0;
				int i = Scan.yawSector(Math.cos(ang), Math.sin(ang));
				if (dist[i] == Float.MAX_VALUE) {
					out.add(at(s, cfg, s.range, s.ex + Math.cos(ang) * 8.0, s.ey, s.ez + Math.sin(ang) * 8.0, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.25F, 0.6F, 3));
				}
			}
		}
	}

	public static String summary(Minecraft mc, Scan s) {
		String[] names = new String[]{"ahead", "right", "behind", "left"};
		double fwd = Math.atan2(Math.cos(Math.toRadians(s.yaw)), -Math.sin(Math.toRadians(s.yaw)));
		Vec3 eye = new Vec3(s.ex, s.ey, s.ez);
		Vec3 low = new Vec3(s.ex, mc.player.getY() + 0.5, s.ez);
		boolean[] lava = new boolean[4];
		float[] ledgeD = new float[]{9.0E9F, 9.0E9F, 9.0E9F, 9.0E9F};

		for (int i = 0; i < 16; i++) {
			double rel = (i + 0.5) / 16.0 * 2.0 * Math.PI - Math.PI - fwd;
			rel = Math.atan2(Math.sin(rel), Math.cos(rel));
			int dir = Math.abs(rel) < Math.PI / 4 ? 0 : (Math.abs(rel) > Math.PI * 3.0 / 4.0 ? 2 : (rel > 0.0 ? 1 : 3));
			if (s.best[0][i].state != null || s.best[0][16 + i].state != null) {
				lava[dir] = true;
			}

			if (s.ledges[i].d < ledgeD[dir]) {
				ledgeD[dir] = s.ledges[i].d;
			}
		}

		double range = s.range;
		StringBuilder sb = new StringBuilder();

		for (int k = 0; k < 4; k++) {
			double ang = fwd + k * Math.PI / 2.0;
			double dx = Math.cos(ang);
			double dz = Math.sin(ang);
			double dEye = rayLen(mc, eye, dx, 0.0, dz, range, false);
			double dLow = rayLen(mc, low, dx, 0.0, dz, range, false);
			if (lava[k]) {
				sb.append("lava ").append(names[k]).append(". ");
			}

			if (ledgeD[k] < 1.0E9F) {
				sb.append("ledge ").append(names[k]).append(' ').append(Math.round(ledgeD[k])).append(" blocks. ");
			}

			if (dEye < range) {
				if (dLow < dEye - 1.5) {
					sb.append("step ").append(Math.round(dLow)).append(", ");
				}

				sb.append("wall ").append(names[k]).append(' ').append(Math.round(dEye)).append(" blocks. ");
			} else if (dLow < range) {
				sb.append("step ").append(names[k]).append(' ').append(Math.round(dLow)).append(" blocks. ");
			} else {
				sb.append("open ").append(names[k]).append(". ");
			}
		}

		double up = rayLen(mc, eye, 0.0, 1.0, 0.0, 20.0, false);
		sb.append(up < 20.0 ? "Ceiling " + Math.round(up) + " blocks above." : "Open above.");
		return sb.toString().trim();
	}

	record Cue(float d, SoundEvent snd, float vol, float pitch, double x, double y, double z, int pri) {
	}

	record Pending(SoundEvent snd, float vol, float pitch, double x, double y, double z, int[] ticks) {
	}
}
