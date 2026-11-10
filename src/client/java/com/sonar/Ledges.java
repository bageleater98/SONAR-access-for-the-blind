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
import static com.sonar.Echo.*;

/** Ledges and drops: the edge warning tone, the side tones on stairs and narrow paths, and how loud and high a drop sounds. (Split out of Cues.) */
final class Ledges {
	private Ledges() {}

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

	/** One continuous tone tracking the nearest edge ahead (louder/higher when closer; tall drops louder and lower). Returns ticks until the next check. */
	/** Edges to your left and right (outside the forward cone), each as its own quieter tone placed on that side, so ledges on both sides are easy to tell apart. Returns whether any played. */
	static boolean sideTones(Minecraft mc, Scan s, SonarConfig cfg, double lead) {
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
					if (Math.abs(Math.toDegrees(Math.atan2(ex * rx + ez * rz, ex * fx + ez * fz))) < 45.0 && !sideways(l, fx, fz)) {
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
				if (sideways(l, fx, fz)) {
					r = (l.ox * rx + l.oz * rz) < 0 ? -Math.PI / 2 : Math.PI / 2; // the ground falls away to that side (the open side of a staircase or a ridge ahead of you): it sounds from that side, never ahead
				} else if (Math.abs(Math.toDegrees(r)) < 45.0) {
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

		// a bridge (up to 3 wide): measured to the real drop lines, scaled to its width (Tuning.LEDGE_BRIDGE_*): centred silent (a 1-wide: faint both sides),
		// drifting toward a side fades that side in, each from its own side of your head.
		double[] sides = bridgeSides(s, fx, fz, rx, rz);
		narrow = sides != null;
		if (narrow) {
			float[] lv = bridgeLevels(sides[0], sides[1]);
			for (int k = 0; k < 2; k++) {
				int drop = (int) sides[2 + k];
				float c = lv[k];
				if (c <= 0) { LedgeTone.quiet(k + 1); continue; }
				float vol = c < 0.05F ? c * Tuning.LEDGE_LEVEL * cfg.volume / 0.6F * cfg.ledgeVolume // (the 1-wide's faint centred tone)
					: Math.min(1.0F, Tuning.LEDGE_LEVEL * ledgeCurve(c, drop) * dropFactor(drop) * cfg.volume / 0.6F * cfg.ledgeVolume);
				float pitch = (0.75F + 0.7F * c) * dropPitch(drop);
				double a = (k == 0 ? -1.0 : 1.0) * Math.PI / 2;
				LedgeTone.update(mc, ledgeSound(cfg), k + 1, vol, pitch, s.ex + 4.0 * (Math.cos(a) * fx + Math.sin(a) * rx), s.feetY - 1.0, s.ez + 4.0 * (Math.cos(a) * fz + Math.sin(a) * rz));
			}
			return true;
		}

		if (corridor) { // on a path: silent while centred; the nearer edge gets its own tone, panned hard to that side, once you are clearly off-centre
			boolean anyC = false;
			for (int k = 0; k < 2; k++) {
				double dn = k == 0 ? corrLeft : corrRight, df = k == 0 ? corrRight : corrLeft;
				if (dn <= 0.8 && dn < df * 0.6) {
					float c = 1.0F - (float)(dn / 0.8);
					float vol = Math.min(1.0F, Tuning.LEDGE_LEVEL * ledgeCurve(c, k == 0 ? corrLeftDrop : corrRightDrop) * dropFactor(k == 0 ? corrLeftDrop : corrRightDrop) * cfg.volume / 0.6F * cfg.ledgeVolume);
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
			float vol = Math.min(1.0F, 0.6F * Tuning.LEDGE_LEVEL * ledgeCurve(c, l.drop) * dropF * cfg.volume / 0.6F * cfg.ledgeVolume);
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

	static int dbgTick;

	static int ledgeMiss;

	/** True while an edge is within warning range, so the tone keeps going even if you stand still next to it. */
	static boolean ledgeActive;
	/** On a narrow walkway right now (both side edges sound; the far end waits until it is close). */
	static boolean narrow;

	/**
	 * A bridge you are on: {distance from your body to the left drop line, to the right one, left drop, right drop}, or null when there is no drop on one
	 * side or it is wider than Tuning.LEDGE_BRIDGE_W. Only edges beside you count (a step behind to two ahead) whose ground falls away sideways.
	 */
	static double[] bridgeSides(Scan s, double fx, double fz, double rx, double rz) {
		double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, 0, 0};
		for (Ledge l : s.ledges) {
			if (l.d == Float.MAX_VALUE || l.d > 4.0F) continue;
			double sd = l.ox * rx + l.oz * rz; // which way the ground falls, sideways
			if (Math.abs(sd) < 0.5) continue;
			double ex = l.x + 0.5 - s.ex, ez = l.z + 0.5 - s.ez;
			double fwd = ex * fx + ez * fz;
			if (fwd < -1.5 || fwd > 2.5) continue;
			double line = ex * rx + ez * rz + 0.5 * Math.signum(sd); // the drop line, sideways from you
			if (Math.signum(line) != Math.signum(sd)) continue; // a drop on the far side of you that falls back toward you: not this side's edge
			int k = sd < 0 ? 0 : 1;
			double dist = Math.abs(line);
			if (dist < out[k]) { out[k] = dist; out[2 + k] = l.drop; }
		}
		if (out[0] == Double.MAX_VALUE || out[1] == Double.MAX_VALUE || out[0] + out[1] > Tuning.LEDGE_BRIDGE_W) return null;
		return out;
	}

	/** Each side's warning level (0 silent .. 1 full) on a bridge, from your distance to each drop line: scaled to its width; the nearer side only. */
	static float[] bridgeLevels(double dl, double dr) {
		double w = dl + dr, sc = w / 3.0;
		double start = Tuning.LEDGE_BRIDGE_START * sc, full = Tuning.LEDGE_BRIDGE_FULL * sc;
		float[] c = new float[2];
		double[] d = {dl, dr};
		for (int k = 0; k < 2; k++) {
			double dn = d[k], df = d[1 - k];
			if (dn < df - 0.05) c[k] = (float) Math.max(0, Math.min(1, (start - dn) / (start - full)));
			if (w <= Tuning.LEDGE_ONE_WIDE) c[k] = Math.max(c[k], Tuning.LEDGE_ONE_WIDE_FAINT * 0.4F); // a 1-wide: never fully silent (a faint level, below the fade-in)
		}
		return c;
	}

	static boolean corridor, wasCorridor;

	static double corrLeft, corrRight;

	static int corrLeftDrop = 99, corrRightDrop = 99;

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
		if (corridor && !wasCorridor && offCorridorChecks >= Tuning.GRACE_OFF_PATH_CHECKS) { // really stepped onto a path after a while off it (about a second of checks): the island's edge tone must not ring on. A flicker of the path state (the guide losing and regaining the path while you stand at an edge) must NOT cut the tone.
			LedgeTone.hush(0);
			LedgeTone.hush(1);
			LedgeTone.hush(2);
		}
		offCorridorChecks = corridor ? 0 : offCorridorChecks + 1;
		wasCorridor = corridor;

		if (cfg.debug && ++dbgTick % 7 == 3) {
			Dbg.send(mc.player, Component.literal(Dbg.fmt("hole check: guided %b corridor %b (L %.1f x%d, R %.1f x%d) edges<=4: %d, maxGap %.0f", guided, corridor, dLeft > 1e6 ? -1.0 : dLeft, nLeft, dRight > 1e6 ? -1.0 : dRight, nRight, holeCount, Math.toDegrees(maxGap))));
		}
		if (!corridor && !PathGuide.active() && holeCount >= 4 && maxGap < Math.toRadians(110.0)) {
			if (cfg.debug) {
				Dbg.send(mc.player, Component.literal("TONE: hole (under you), nearest " + nearest + " deepest " + deepest));
			}
			ledgeMiss = 0;
			ledgeActive = true;
			float c = Math.max(0.0F, Math.min(1.0F, 1.0F - (float)Math.max(0.0, nearest - lead) / cfg.ledgeWarnRange));
			float dropF = dropFactor(deepest);
			float vol = Math.min(1.0F, Tuning.LEDGE_LEVEL * ledgeCurve(c, deepest) * dropF * cfg.volume / 0.6F * cfg.ledgeVolume);
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
						sb.append(Dbg.fmt(" [fwd %.1f lat %.1f drop %d]", ex * cfx + ez * cfz, ex * crx + ez * crz, e.drop));
					}
				}
			}
			Dbg.send(mc.player, Component.literal(Dbg.fmt("corridor %b (L %.1f R %.1f) ledges<=6: %d%s", corridor, dLeft, dRight, nLedges, sb)));
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

			Dbg.send(mc.player, Component.literal(nl == null ? "ledge: none detected" : Dbg.fmt("ledge: %d sectors, nearest %.1f blocks (real %.1f), drop %d, deep %b", n, nl.d, Math.hypot(nl.x + 0.5 - s.ex, nl.z + 0.5 - s.ez), nl.drop, nl.deep)));
		}

		for (Ledge l : s.ledges) {
			if (l.d - lead > cfg.ledgeWarnRange) {
				continue;
			}
			double ex = l.x + 0.5 - s.ex;
			double ez = l.z + 0.5 - s.ez;
			double el = Math.max(1.0E-6, Math.hypot(ex, ez));
			// only edges within ~45 degrees of where you are heading count, AND the ground must fall away in the direction you are going: a drop beside a staircase or ridge ahead of you
			// is a side edge (sideTones plays it from that side). el = the real distance: l.d can be shrunk by the stair-approach logic, which must not make a far edge count as "at your feet".
			if (el > 1.0 && ((ex * vx + ez * vz) / (el * sp) < 0.7 || (l.ox * vx + l.oz * vz) / sp < 0.5)) {
				continue;
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
			if (narrow && (sideways(l, vx / sp, vz / sp) || el > Tuning.LEDGE_NARROW_AHEAD)) {
				continue; // a narrow walkway: its sides are the side tones; its far end sounds only once it is close
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
		float vol = Math.min(1.0F, Tuning.LEDGE_LEVEL * ledgeCurve(c, l.drop) * dropF * cfg.volume / 0.6F * cfg.ledgeVolume);
		float pitch = Math.max(0.4F, Math.min(2.0F, (0.75F + 0.7F * c - (l.deep ? 0.15F : 0.0F)) * dropPitch(l.drop)));
		if (cfg.debug) {
			double dx0 = l.x + 0.5 - s.ex, dz0 = l.z + 0.5 - s.ez;
			Dbg.send(mc.player, Component.literal(Dbg.fmt("TONE: main from edge fwd %.1f lat %.1f drop %d d %.1f vol %.2f | guided %b corridor %b bnd L %.1f R %.1f", dx0 * cfx + dz0 * cfz, dx0 * crx + dz0 * crz, l.drop, l.d, vol, guided, corridor, bndL > 1e6 ? -1.0 : bndL, bndR > 1e6 ? -1.0 : bndR)));
		}
		LedgeTone.update(mc, ledgeSound(cfg), 0, vol, pitch, l.x + 0.5 + l.ox * 0.7, l.y - 0.5, l.z + 0.5 + l.oz * 0.7); // from just past the lip, where the ground falls away
		edgeAheadD = l.d;
		edgeAheadDrop = l.drop;
		edgeAheadX = l.x + 0.5;
		edgeAheadZ = l.z + 0.5;

		return 3; // check often while an edge is near so the tone tracks it closely
	}

	/** The warning's loudness follows the danger: a 3-block drop is a stumble (soft), 4 is a little louder, and 5 or more (a real fall, or lava, which counts as 99) is full strength. */
	/** Loudness of an edge tone by closeness c (0 = at the edge of the warning range, 1 = at the lip): for small drops (3-4 blocks) it starts very quiet and climbs steeply as you close in, so it is a sharp,
	 *  clear warning when it matters, not a constant drone while you walk near a ledge. */
	static float ledgeCurve(float c, int drop) {
		c = Math.max(0F, Math.min(1F, c));
		if (drop >= Tuning.LEDGE_BIG_DROP) return Tuning.LEDGE_BIG_FLOOR + (1 - Tuning.LEDGE_BIG_FLOOR) * c; // a real fall (or lava): the early, steady warning, already clear from the far end of the range
		return Tuning.LEDGE_SMALL_FLOOR + (1 - Tuning.LEDGE_SMALL_FLOOR) * (float) Math.pow(c, Tuning.LEDGE_SMALL_CURVE);
	}

	/** The ground falls away sideways relative to where you face (not ahead): the open side of a staircase, a ridge or a path. */
	static boolean sideways(Ledge l, double fx, double fz) {
		return (l.ox != 0 || l.oz != 0) && Math.abs(l.ox * fx + l.oz * fz) < 0.5;
	}

	static float dropFactor(int drop) {
		if (drop >= Tuning.LEDGE_BIG_DROP) return 1.0F;
		if (drop == Tuning.LEDGE_BIG_DROP - 1) return Tuning.LEDGE_LOUD_4;
		return Tuning.LEDGE_LOUD_SMALL;
	}

	/** Small drops sound lower; the tone rises to the normal pitch as the drop reaches 5 blocks. */
	static float dropPitch(int drop) {
		if (drop >= Tuning.LEDGE_BIG_DROP) return 1.0F;
		if (drop == Tuning.LEDGE_BIG_DROP - 1) return Tuning.LEDGE_PITCH_4;
		return Tuning.LEDGE_PITCH_SMALL;
	}
}
