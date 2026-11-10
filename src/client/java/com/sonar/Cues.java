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

import static com.sonar.Rooms.*;
import static com.sonar.Echo.*;
import static com.sonar.Ledges.*;

public class Cues {
	static final Map<Integer, Long> mobSeen = new HashMap<>();
	static final List<com.sonar.Cues.Pending> pending = new ArrayList<>();
	static long lastLava = -1000;
	/** Short clips of the vanilla lava/water ambience (the vanilla lava sound is 8 s long and kept playing from where you used to stand). */
	static final SoundEvent WATER_SHORT = SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.fromNamespaceAndPath("sonar", "water"));

	/** Queues a sound to play in a few ticks (a staggered sonar sound, an echo click), booking its tick on the timeline so guide sounds wait for it. */
	static void queue(Pending p) {
		Timeline.book(p.ticks()[0]);
		pending.add(p);
	}

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

	static boolean isTerrainSound(SoundEvent e) {
		String p = e.location().getPath();
		return p.contains("grass") || p.contains("gravel") || p.contains("rooted") || p.contains("mud") || p.contains("moss") || p.contains("roots");
	}

	private static boolean headWas;
	private static int headLast = -100;

	/**
	 * THE HEAD BONK: a soft low tone from above, played only when you jump into a ceiling (an upward collision), never because a ceiling is merely low. Once
	 * per bump (a held jump under a ceiling does not repeat it).
	 */
	static void headBonk(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		if (p == null) return;
		boolean hit = p.verticalCollision && !p.verticalCollisionBelow && !p.onGround() && !p.isInWater();
		if (hit && !headWas && cfg.ceilingCue && SonarClient.tick - headLast > 6) {
			headLast = SonarClient.tick;
			play(mc, (SoundEvent)SoundEvents.NOTE_BLOCK_BASS.value(), cfg.volume * 0.5F, 1.3F, p.getX(), p.getEyeY() + 1.5, p.getZ());
			Tutorial.event(Tutorial.Ev.HEAD_BONK, 1);
		}
		headWas = hit;
	}

	static void play(Minecraft mc, SoundEvent e, float vol, float pitch, double x, double y, double z) {
		vol = Math.min(1f, vol * Loud.level(e) * Voice.duck()); // evens out sounds that are naturally louder or softer than the rest; quieter while the narrator speaks
		mc.getSoundManager().play(new SimpleSoundInstance(e, SoundSource.AMBIENT, vol, pitch, RandomSource.create(), x, y, z));
	}

	static float closeness(float d, int range) {
		float c = 1.0F - Math.min(d, range) / range;
		return 0.12F + 0.88F * c * c;
	}

	static final Map<SoundEvent, SoundEvent> MUFFLED = new HashMap<>();
	static java.util.Set<String> muffledNames;

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
	/**
	 * DIRECTION SPREAD: pushes a sound's direction (radians from where you face, + one side) out TOWARD YOUR SIDE, never past it. Stereo tells left from right best at
	 * exactly the side (90 degrees) and not at all straight ahead or straight behind, so angles near the front, and near the back, are widened toward 90 degrees;
	 * straight ahead, straight to the side and straight behind stay where they are. k = 1: true angles; larger: wider.
	 * (Round 15: the old spread kept stretching past the side, and at 200% every sound 90 degrees or more round was put exactly straight behind you, the one direction
	 * stereo can not place at all: pings sounded "in the middle of my head".)
	 */
	static double spread(double a, double k) {
		double h = Math.PI / 2, abs = Math.abs(a);
		double out = abs <= h ? h * (1 - Math.pow(1 - abs / h, k)) : Math.PI - h * (1 - Math.pow(1 - (Math.PI - abs) / h, k));
		return Math.signum(a) * out;
	}

	/**
	 * WHERE A SONAR SOUND IS HEARD (radians from where you face, + right), from its real direction `rel`: where it really is, widened by Direction spread `k` when
	 * that is above 100%. Warnings (pri 0) are always heard where they really are. The beat is decided by the real direction, never this one (quarter).
	 */
	static double place(double rel, int pri, double k) {
		return pri == 0 || k <= 1.001 ? rel : spread(rel, k);
	}

	/** How loud a sonar ping is for its real direction `rel`: full straight ahead, to each side and behind, SONAR_DIAGONAL at 45 degrees between, a smooth curve
	 *  (1 - (1 - SONAR_DIAGONAL) * sin^2(2 rel)) so a block fades as you turn rather than jumping. */
	/** How much of the diagonal dip applies at distance d: none within DIAGONAL_NEAR (up close a wall fills your front-left and front-right and must be heard), all from DIAGONAL_FULL. */
	static double nearFade(double d) { return Math.max(0, Math.min(1, (d - Tuning.DIAGONAL_NEAR) / (Tuning.DIAGONAL_FULL - Tuning.DIAGONAL_NEAR))); }

	static double diagonal(double rel) {
		double s = Math.sin(2 * rel);
		return 1 - (1 - Tuning.SONAR_DIAGONAL) * s * s;
	}

	static com.sonar.Cues.Cue at(Scan s, SonarConfig cfg, float d, double tx, double ty, double tz, SoundEvent e, float volMul, float pitch, int pri) {
		double vx = tx - s.ex;
		double vy = ty - s.ey;
		double vz = tz - s.ez;
		double len = Math.max(1.0E-6, Math.sqrt(vx * vx + vy * vy + vz * vz));
		// how far out the sound is placed: further for further blocks, but never closer than CUE_CLOSEST (right beside your head the game can not place a
		// sound: a wall a block away collapsed into the middle of your head and was lost). How loud it is still follows the real distance.
		double ad = Math.max(Tuning.CUE_CLOSEST, 1.0 + Math.min(d, s.range) / s.range * (pri == 0 ? 6.0 : 12.0));
		double yawR = Math.toRadians(s.yaw);
		double fx = -Math.sin(yawR);
		double fz = Math.cos(yawR);
		double hl = Math.max(1.0E-6, Math.hypot(vx, vz));
		// behind you: the sounds of the sweep's last beat (more than 135 degrees round), muffled and quieter, so behind is plain (round 15: "more muted")
		boolean behind = pri != 0 && (vx * fx + vz * fz) / hl < -Math.cos(Math.PI / 4);
		double rx = -Math.cos(yawR), rz = -Math.sin(yawR);
		double rel = Math.atan2(vx * rx + vz * rz, vx * fx + vz * fz); // the real direction
		float vol = Math.min(1.0F, cfg.volume * closeness(d, s.range) * volMul * (behind ? Tuning.BEHIND_SONAR : 1.0F) * (pri != 0 ? (float) (1 - (1 - diagonal(rel)) * nearFade(d)) : 1.0F)); // (the diagonal dip fades out up close: a wall beside you is never dimmed)
		if (behind) {
			e = muffled(e);
			if (cfg.behindPitch) {
				pitch *= 0.88F;
			}
		}

		double heard = place(rel, pri, cfg.directionSpread);
		if (heard != rel) {
			double a = heard;
			vx = hl * (Math.cos(a) * fx + Math.sin(a) * rx);
			vz = hl * (Math.cos(a) * fz + Math.sin(a) * rz);
			len = Math.max(1.0E-6, Math.sqrt(vx * vx + vy * vy + vz * vz));
		}

		double[] lag = lag(s);
		return new com.sonar.Cues.Cue(d, e, vol, pitch, s.ex + lag[0] + vx / len * ad, s.ey + lag[1] + vy / len * ad, s.ez + lag[2] + vz / len * ad, pri, rel);
	}

	/** How far you moved since the scan began. The sounds are placed around where you are now, not where you were, so they do not trail behind when you run. */
	static double[] lag(Scan s) {
		if (!s.live()) return new double[3]; // a scan of a self-test world: nobody has moved
		var p = Minecraft.getInstance().player;
		return p == null ? new double[3] : new double[]{p.getX() - s.ex, p.getEyeY() - s.ey, p.getZ() - s.ez};
	}

	static SoundEvent blockSound(BlockState st, SonarConfig cfg) {
		return cfg.materialSounds ? materialSound(st) : SoundEvents.STONE_HIT;
	}

	/** A block's own sound (with Material sounds on). */
	static SoundEvent materialSound(BlockState st) {
		if (st.is(Blocks.ENCHANTING_TABLE)) return SoundEvents.ENCHANTMENT_TABLE_USE; // its own sound: the enchanting chime
		SoundType t = st.getSoundType();
		if ((t == SoundType.WOOD || t == SoundType.NETHER_WOOD || t == SoundType.CHERRY_WOOD || t == SoundType.BAMBOO_WOOD) && !st.is(BlockTags.LOGS))
			return SoundEvents.CHISELED_BOOKSHELF_BREAK; // all made wood (planks, doors, fences, stairs... every kind): one hollow knock, easy to pick out from stone (the user's pick). Logs keep their own sound.
		return t == SoundType.GLASS ? t.getPlaceSound() : t.getBreakSound();
	}

	/** Each material family gets its own pitch so they are easier to tell apart: stone high and clicky, wood hollow, soft ground low and muffled, metal bright, glass highest. */
	static float materialPitch(SoundEvent e) {
		String p = e.location().getPath();
		if (p.contains("glass") || p.contains("amethyst")) return 1.45F;
		if (p.contains("metal") || p.contains("copper") || p.contains("chain") || p.contains("netherite") || p.contains("anvil") || p.contains("iron")) return 1.15F;
		if (p.contains("stone") || p.contains("deepslate") || p.contains("tuff") || p.contains("calcite") || p.contains("brick") || p.contains("basalt")) return 1.25F;
		if (p.contains("bookshelf")) return 1.0F; // wood (its own sound, at its own pitch)
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
		Map<com.sonar.Cues.Cue, int[]> blockAt = new java.util.IdentityHashMap<>(); // the block each block sound is (for grouping)
		List<com.sonar.Cues.Cue> ledgeCues = new ArrayList<>();
		// water and lava as steady loops while auto scan is on (FluidLoop), not in every ping: here only the nearest of each is noted for them
		boolean fluidLoop = FluidLoop.on(cfg);
		double nearWater = Double.MAX_VALUE, nearLava = Double.MAX_VALUE;
		double[] waterAt = null, lavaAt = null;
		// lava: only the 3 nearest hazard sectors sound (a lava lake used to fire dozens of sounds per ping), and it never loops
		Integer[] hazOrder = new Integer[Scan.SECTORS];
		for (int i = 0; i < Scan.SECTORS; i++) {
			hazOrder[i] = i;
		}

		// inside a cave or building (where the echo would play): the sonar's block sounds would just be a wall of stone, so only the echo, hazards, water, mobs and ledges sound
		boolean inRoom = cfg.echoReplacesSonar && cfg.echoCue && cfg.echoCount != 0 && !s.front && insideSpace(mc, s);
		boolean quiet = inRoom && s.auto; // only the AUTO sonar goes quiet inside; a key press (R) always gives the full block sounds
		long nowTick = mc.level.getGameTime();
		boolean lavaDue = nowTick - lastLava >= 50; // lava sounds at most every 2.5 s, however fast you ping (repeat / fast auto scan)
		boolean lavaPlayed = false;
		final Cand[] hazards = s.best[0];
		java.util.Arrays.sort(hazOrder, Comparator.comparingDouble(i -> hazards[i].state == null ? Double.MAX_VALUE : hazards[i].d));

		for (int i = 0; i < Scan.SECTORS; i++) {
			Cand haz = s.best[0][i];
			Cand sol = s.best[1][i];
			Cand tac = s.best[2][i];
			Cand wat = s.best[3][i];
			if (haz.state != null && cfg.hazardCue) {
				int rank = java.util.Arrays.asList(hazOrder).indexOf(i);
				if (haz.d < nearLava) { nearLava = haz.d; lavaAt = new double[]{haz.x + 0.5, haz.y + 0.5, haz.z + 0.5}; }
				if (rank < 3 && lavaDue && !fluidLoop) {
					lavaPlayed = true;
					// the game's own gentle lava ambience (the old pop + rumble was too harsh); the nearest a little louder
					cues.add(at(s, cfg, haz.d, haz.x + 0.5, haz.y + 0.5, haz.z + 0.5, SoundEvents.LAVA_AMBIENT, (rank == 0 ? 0.7F : 0.45F) * cfg.lavaVolume, 1.0F, 0));
				}
			} else {
				if (wat.state != null && wat.d < nearWater) { nearWater = wat.d; waterAt = new double[]{wat.x + 0.5, wat.y + 0.5, wat.z + 0.5}; }
				if (wat.state != null && !fluidLoop) {
					int depth = waterDepth(mc, wat.x, wat.y, wat.z);
					float pitch = Math.max(0.5F, 1.2F - depth * 0.1F);
					com.sonar.Cues.Cue watCue = at(s, cfg, wat.d, wat.x + 0.5, wat.y + 0.5, wat.z + 0.5, WATER_SHORT, 0.6F + depth * 0.1F, pitch, 1);
					cues.add(watCue);
					blockAt.put(watCue, new int[]{wat.x, wat.y, wat.z}); // water is grouped like every block (round 15: a lake took 20 of 32 sounds)
				}

				if (sol.state != null && !quiet) {
					if (cfg.debug && mc.player != null) Dbg.send(mc.player, Component.literal("wall: " + sol.state.getBlock().getDescriptionId() + " at " + sol.x + "," + sol.y + "," + sol.z + " -> " + blockSound(sol.state, cfg).location().getPath()));
					// a thin layer on the floor (carpet, moss carpet, pressure plate, a layer of snow) is something you walk over, not a block in your way: a soft, light tick, not a full block
					boolean thin = sol.state.getShape(Sense.hear(mc), new net.minecraft.core.BlockPos(sol.x, sol.y, sol.z)).max(net.minecraft.core.Direction.Axis.Y) <= 0.2;
					float solVol = thin ? 0.25F : (sol.state.is(net.minecraft.tags.BlockTags.SLABS) || sol.state.is(net.minecraft.tags.BlockTags.STAIRS)) ? 0.45F : 0.9F;
					com.sonar.Cues.Cue solCue = at(s, cfg, sol.d, sol.x + 0.5, sol.y + (thin ? 0.1 : 0.5), sol.z + 0.5, blockSound(sol.state, cfg), solVol, materialPitch(blockSound(sol.state, cfg)) * (thin ? 1.35F : 1.0F), 1);
					cues.add(solCue);
					blockAt.put(solCue, new int[]{sol.x, sol.y, sol.z});
				} else if (tac.state != null && !quiet) {
					if (cfg.debug && mc.player != null) Dbg.send(mc.player, Component.literal("touch: " + tac.state.getBlock().getDescriptionId() + " at " + tac.x + "," + tac.y + "," + tac.z));
					com.sonar.Cues.Cue tacCue = at(s, cfg, tac.d, tac.x + 0.5, tac.y + 0.5, tac.z + 0.5, tactileSound(tac.state), 0.8F, 0.9F, 2);
					cues.add(tacCue);
					blockAt.put(tacCue, new int[]{tac.x, tac.y, tac.z});
				}
			}
		}

		if (!s.front) {
			if (!cfg.autoLedge) { // Ledge warning is off: a ping still names the nearest edges, but at the warning's own loudness (closer = louder, small drops quiet), not flat full blast
				List<Ledge> near = new ArrayList<>();
				for (Ledge l : s.ledges) if (l.d != Float.MAX_VALUE && l.d <= cfg.ledgeWarnRange) near.add(l);
				near.sort(Comparator.comparingDouble(l -> l.d));
				for (int i = 0; i < Math.min(2, near.size()); i++) {
					Ledge l = near.get(i);
					float c = Math.max(0.0F, Math.min(1.0F, 1.0F - l.d / cfg.ledgeWarnRange));
					float vol = Math.min(1.0F, 0.72F * ledgeCurve(c, l.drop) * dropFactor(l.drop) * cfg.volume / 0.6F * cfg.ledgeVolume);
					float pitch = Math.max(0.4F, Math.min(2.0F, (0.75F + 0.7F * c) * dropPitch(l.drop)));
					ledgeCues.add(at(s, cfg, l.d, l.x + 0.5, l.y, l.z + 0.5, ledgeSound(cfg), vol, pitch, 0));
				}
			}

			if (!quiet) {
				extraCues(s, cfg, cues);
			}
		}

		Integer[] order = new Integer[Scan.SECTORS];

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
				if (mobSeen.size() > 512) mobSeen.values().removeIf(v -> now - v >= 60L); // forget mobs long gone
				if (last == null || now - last >= 60L) {
					SoundEvent amb = ((MobAccessor)m).sonar$ambient();
					if (amb != null) {
						mobSeen.put(m.getId(), now);
						mobCount++;
						cues.add(at(s, cfg, s.mobD[i], m.getX(), m.getY() + m.getBbHeight() / 2.0F, m.getZ(), amb, 0.9F * (float) Loud.at(s.mobD[i], 2, s.range), 1.0F, 1)); // the shared fade over the scan's range: full right next to it, silent at the edge
					}
				}
			}
		}

		if (lavaPlayed) {
			lastLava = nowTick;
		}
		FluidLoop.waterAt = waterAt;
		FluidLoop.lavaAt = cfg.hazardCue ? lavaAt : null;

		boolean path = !inRoom && FloorEdge.ping(mc, cfg); // no path ripple inside a room

		if (!s.front && cfg.echoCue && s.withEcho) {
			echo(mc, s, cfg);
			Tutorial.event(Tutorial.Ev.ECHO, 1);
		}

		// ground (grass, dirt, gravel...) behind you is not needed, it only crowds the sounds ahead; ground in front (a bank, a step up, a hill) is what you walk into:
		// it stays loud, right up close loudest; hillside off to the sides quieter
		double tyaw = Math.toRadians(s.yaw), tfx = -Math.sin(tyaw), tfz = Math.cos(tyaw);
		for (int i = 0; i < cues.size(); i++) {
			com.sonar.Cues.Cue c = cues.get(i);
			if (c.pri() == 0 || !isTerrainSound(c.snd())) continue;
			double fwd = Math.cos(c.rel()); // by the real direction, not where it is heard
			if (fwd < -0.2) { blockAt.remove(c); cues.remove(i--); continue; }
			float k = fwd > 0.7 ? (c.d() < 3.5F ? 1.0F : 0.85F) : fwd > 0.0 ? 0.5F : 0.35F;
			com.sonar.Cues.Cue r = new com.sonar.Cues.Cue(c.d(), c.snd(), c.vol() * k, c.pitch(), c.x(), c.y(), c.z(), c.pri(), c.rel());
			int[] where = blockAt.remove(c);
			if (where != null) blockAt.put(r, where);
			cues.set(i, r);
		}
		// ONE RULE FOR EVERY BLOCK SOUND (round 15, replacing the tree, per-material and ground limits): blocks that make the same sound and touch (face to face, followed
		// through the world: the leaves of a tree, a wall, a hillside) are one group. Of the blocks you HEAR in it (the nearest in each direction, as real sonar), every 3
		// give it one sound: 1 to 3 heard, 1 sound; 4 to 6, 2; and so on. Its sounds are spread evenly across it, so a wide thing sounds wide.
		List<com.sonar.Cues.Cue> blockCues = new ArrayList<>(blockAt.keySet());
		if (!blockCues.isEmpty()) {
			int[][] at = new int[blockCues.size()][];
			for (int i = 0; i < at.length; i++) at[i] = blockAt.get(blockCues.get(i));
			int[] grp = groupsOf(Sense.hear(mc), at, st -> groupKey(st, cfg), s.ex, s.ey, s.ez, s.range + 2);
			double[] bearing = new double[at.length], elev = new double[at.length], dist = new double[at.length];
			for (int i = 0; i < at.length; i++) {
				double dx = at[i][0] + 0.5 - s.ex, dy = at[i][1] + 0.5 - s.ey, dz = at[i][2] + 0.5 - s.ez;
				bearing[i] = Math.atan2(dz, dx);
				elev[i] = Math.atan2(dy, Math.hypot(dx, dz));
				dist[i] = Math.sqrt(dx * dx + dy * dy + dz * dz);
			}
			java.util.Set<com.sonar.Cues.Cue> keep = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			for (int[] members : membersOf(grp)) {
				double[] b2 = new double[members.length], e2 = new double[members.length], d2 = new double[members.length];
				for (int j = 0; j < members.length; j++) { b2[j] = bearing[members[j]]; e2[j] = elev[members[j]]; d2[j] = dist[members[j]]; }
				for (int j : spread(b2, e2, d2, soundsFor(members.length))) keep.add(blockCues.get(members[j]));
			}
			cues.removeIf(c -> blockAt.containsKey(c) && !keep.contains(c));
		}

		// the wall straight in front of you (the next 2 blocks you would walk onto) always sounds, full and clear: it is what you are about to walk into. A "wall" is a block
		// you can not STEP onto, by the same rule the player route and the clearance use (BlockRules.stepTo): a wall, fence, rail or anything 2+ tall is one; a step up,
		// a slab, stairs or a doorway is not. Water is not a wall (and the ledge warning covers drops). Material budgets never drop it, and it plays even inside a cave or
		// room where the auto sonar otherwise leaves everything to the echo.
		if (!s.front) {
			var wpos = new net.minecraft.core.BlockPos.MutableBlockPos();
			int cy = BlockRules.floorUnder(Sense.hear(mc), s.ex, s.feetY, s.ez, wpos);
			int lx = (int) Math.floor(s.ex), lz = (int) Math.floor(s.ez);
			for (int r = 1; r <= 2 && cy != BlockRules.NONE; r++) {
				int cx = (int) Math.floor(s.ex + tfx * r), cz = (int) Math.floor(s.ez + tfz * r);
				if (cx == lx && cz == lz) continue;
				lx = cx; lz = cz;
				int next = BlockRules.stepTo(Sense.hear(mc), cx, cz, cy, wpos);
				if (next != BlockRules.NONE) { cy = next; continue; }
				int wy = BlockRules.NONE;
				boolean wet = false;
				for (int y = cy + 1; y <= cy + 2 && wy == BlockRules.NONE; y++) {
					BlockState bs = Sense.hear(mc).getBlockState(wpos.set(cx, y, cz));
					if (!bs.getFluidState().isEmpty()) wet = true;
					else if (!bs.getCollisionShape(Sense.hear(mc), wpos).isEmpty()) wy = y;
				}
				if (wet || wy == BlockRules.NONE) break; // water, or a drop: not a wall
				BlockState ws = Sense.hear(mc).getBlockState(wpos.set(cx, wy, cz));
				SoundEvent ev = blockSound(ws, cfg);
				double wd = Math.hypot(cx + 0.5 - s.ex, cz + 0.5 - s.ez) - 0.5;
				int bx = cx, by = wy, bz = cz;
				cues.removeIf(c -> Math.abs(c.x() - (bx + 0.5)) < 0.6 && Math.abs(c.y() - (by + 0.5)) < 0.6 && Math.abs(c.z() - (bz + 0.5)) < 0.6);
				cues.add(at(s, cfg, (float) Math.max(0.1, wd), bx + 0.5, by + 0.5, bz + 0.5, ev, 1.0F, materialPitch(ev), 1));
				if (cfg.debug && mc.player != null) Dbg.send(mc.player, Component.literal(Dbg.fmt("wall ahead: %s %.1f blocks (can not step there)", ws.getBlock().getDescriptionId(), wd)));
				break;
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
					cues.set(i, new com.sonar.Cues.Cue(c.d(), c.snd(), c.vol() * 0.4F, c.pitch(), c.x(), c.y(), c.z(), c.pri(), c.rel()));
				}
			}
		}

		if (path) { // a built path nearby: it comes first, everything else a bit quieter
			for (int i = 0; i < cues.size(); i++) {
				com.sonar.Cues.Cue c = cues.get(i);
				if (c.pri() != 0) {
					cues.set(i, new com.sonar.Cues.Cue(c.d(), c.snd(), c.vol() * 0.7F, c.pitch(), c.x(), c.y(), c.z(), c.pri(), c.rel()));
				}
			}
		}

		cues.addAll(ledgeCues);
		{ // the limit, shared fairly round you (see fairOrder)
			double[] rel = new double[cues.size()], dd = new double[cues.size()];
			int[] pr = new int[cues.size()];
			for (int i = 0; i < rel.length; i++) {
				com.sonar.Cues.Cue c = cues.get(i);
				rel[i] = c.rel(); // the real direction (round 16: the heard one, moved by Direction spread, put front and behind blocks on the side beats)
				dd[i] = c.d(); pr[i] = c.pri();
			}
			int[] ord = fairOrder(rel, dd, pr);
			List<com.sonar.Cues.Cue> fair = new ArrayList<>(cues.size());
			for (int i : ord) fair.add(cues.get(i));
			cues.clear(); cues.addAll(fair);
		}
		int n = Math.min(cues.size(), cfg.maxSounds);

		playSet(mc, s, cfg, cues, n, 0);

		if (cfg.debug && mc.player != null) {
			Dbg.send(mc.player, Component.literal("Sonar: " + s.nanos / 1000000.0 + " ms, " + cues.size() + " cues, played " + n));
		}
	}

	/**
	 * What a block is, for grouping: blocks group with their own kind, never across kinds that only happen to share a sound. Leaves with leaves, tree logs with tree
	 * logs, ground (grass, dirt, gravel, mud, moss) with ground, anything else by its sound (stone with stone, planks with wood); and built blocks never with natural
	 * ones (stone bricks or cobblestone walls are not the rock face they stand against). Null for air and water.
	 * (Round 15: by sound alone, a tree's leaves and the grassy hill under it were one group: leaves and grass blocks make the very same sound.)
	 */
	static Object groupKey(BlockState st, SonarConfig cfg) { return groupKey(st, cfg.materialSounds); }

	static Object groupKey(BlockState st, boolean materialSounds) {
		if (st.isAir()) return null;
		if (!st.getFluidState().isEmpty()) return st.getFluidState().getType().isSame(net.minecraft.world.level.material.Fluids.WATER) ? "water" : null; // a lake is one thing
		if (st.is(BlockTags.LEAVES)) return "leaves";
		if (st.is(BlockTags.OVERWORLD_NATURAL_LOGS)) return "logs";
		// built or natural: a stone house built into a stone mountain shares its sound, but it is not the mountain
		String made = FloorEdge.natural(st) || CaveEcho.caveRock(st) ? "natural " : "built ";
		SoundEvent e = materialSounds ? materialSound(st) : SoundEvents.STONE_HIT;
		if (isTerrainSound(e)) return made + "ground";
		return made + e.location();
	}

	/**
	 * THE LIMIT, SHARED FAIRLY: the order sounds are kept in when there are more than Max sounds. Warnings (priority 0: ledges, lava) first, nearest first. Then the
	 * four quarters round you (front, right, left, behind) take turns: each its nearest sound, then each its second nearest, and so on; a quarter with nothing left
	 * gives up its turn. One busy side (a tree, a lake) can no longer take the whole limit while the rest of you goes unheard (round 15).
	 * rel: direction from where you face (radians, + right); d: distance; pri: priority. Returns indices in keep order.
	 */
	static int[] fairOrder(double[] rel, double[] d, int[] pri) {
		int n = rel.length;
		List<List<Integer>> q = new ArrayList<>();
		for (int k = 0; k < 4; k++) q.add(new ArrayList<>());
		List<Integer> warn = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (pri[i] == 0) { warn.add(i); continue; }
			double a = Math.abs(rel[i]);
			q.get(a <= Math.PI / 4 ? 0 : a >= 3 * Math.PI / 4 ? 3 : rel[i] > 0 ? 1 : 2).add(i);
		}
		Comparator<Integer> near = Comparator.comparingDouble(i -> d[i]);
		warn.sort(near);
		for (List<Integer> l : q) l.sort(near);
		int[] out = new int[n];
		int o = 0;
		for (int i : warn) out[o++] = i;
		for (int r = 0; o < n; r++) for (List<Integer> l : q) if (r < l.size()) out[o++] = l.get(r);
		return out;
	}

	/** One rule for every block sound: of the blocks heard in a group, one sound per 3 (1-3: 1, 4-6: 2, 7-9: 3, ...). */
	static int soundsFor(int heard) { return heard <= 0 ? 0 : (heard + 2) / 3; }

	/**
	 * The groups the heard blocks fall into: blocks with the same sound key (keyOf: null for air and water) joined face to face, followed through the world (not
	 * only through the heard blocks: the heard blocks of a wall lie a few apart, its hidden blocks join them), within `radius` of you. A group number per heard block.
	 */
	static int[] groupsOf(net.minecraft.world.level.BlockGetter w, int[][] heard, java.util.function.Function<BlockState, Object> keyOf, double cx, double cy, double cz, double radius) {
		int n = heard.length;
		int[] grp = new int[n];
		Arrays.fill(grp, -1);
		var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		// one entry per block (a block can be the nearest in two directions: both are the same group), and each block's sound key
		it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap first = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		first.defaultReturnValue(-1);
		Object[] key = new Object[n];
		for (int i = 0; i < n; i++) {
			long l = net.minecraft.core.BlockPos.asLong(heard[i][0], heard[i][1], heard[i][2]);
			if (first.get(l) < 0) first.put(l, i);
			key[i] = keyOf.apply(w.getBlockState(pos.set(heard[i][0], heard[i][1], heard[i][2])));
		}
		double r2 = radius * radius;
		int next = 0;
		it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue q = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();
		for (int i = 0; i < n; i++) {
			if (grp[i] >= 0 || first.get(net.minecraft.core.BlockPos.asLong(heard[i][0], heard[i][1], heard[i][2])) != i) continue;
			int g = next++;
			grp[i] = g;
			if (key[i] == null) continue;
			// the heard blocks of this sound not yet in a group: the fill stops as soon as it has found them all (a hillside is not followed further than needed)
			int open = 0;
			for (int j = i + 1; j < n; j++) if (grp[j] < 0 && key[i].equals(key[j]) && first.get(net.minecraft.core.BlockPos.asLong(heard[j][0], heard[j][1], heard[j][2])) == j) open++;
			if (open == 0) continue;
			seen.clear(); q.clear();
			long start = net.minecraft.core.BlockPos.asLong(heard[i][0], heard[i][1], heard[i][2]);
			seen.add(start); q.enqueue(start);
			int left = Tuning.GROUP_FILL_MAX;
			while (!q.isEmpty() && left-- > 0 && open > 0) {
				long cur = q.dequeueLong();
				int x = net.minecraft.core.BlockPos.getX(cur), y = net.minecraft.core.BlockPos.getY(cur), z = net.minecraft.core.BlockPos.getZ(cur);
				for (int k = 0; k < 6; k++) {
					int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0), nz = z + (k == 4 ? 1 : k == 5 ? -1 : 0);
					long nl = net.minecraft.core.BlockPos.asLong(nx, ny, nz);
					if (!seen.add(nl)) continue;
					double ddx = nx + 0.5 - cx, ddy = ny + 0.5 - cy, ddz = nz + 0.5 - cz;
					if (ddx * ddx + ddy * ddy + ddz * ddz > r2) continue;
					if (!key[i].equals(keyOf.apply(w.getBlockState(pos.set(nx, ny, nz))))) continue;
					q.enqueue(nl);
					int h = first.get(nl);
					if (h >= 0 && grp[h] < 0) { grp[h] = g; open--; }
				}
			}
		}
		for (int i = 0; i < n; i++) if (grp[i] < 0) grp[i] = grp[first.get(net.minecraft.core.BlockPos.asLong(heard[i][0], heard[i][1], heard[i][2]))]; // the second listing of a block
		return grp;
	}

	/** The heard blocks of each group (their indices), from a group number per block. */
	static List<int[]> membersOf(int[] grp) {
		Map<Integer, List<Integer>> m = new java.util.LinkedHashMap<>();
		for (int i = 0; i < grp.length; i++) m.computeIfAbsent(grp[i], k -> new ArrayList<>()).add(i);
		List<int[]> out = new ArrayList<>();
		for (List<Integer> l : m.values()) out.add(l.stream().mapToInt(Integer::intValue).toArray());
		return out;
	}

	/** Spread by left-right direction only (all level, all equally far): see the full version. */
	static int[] spread(double[] bearing, int k) { return spread(bearing, new double[bearing.length], new double[bearing.length], k); }

	/**
	 * `k` of a group's heard blocks, spread across it BY DIRECTION, up and down as well as round you: the nearest block first, then each next one the block furthest in
	 * direction from all those already picked, with a nearer block preferred over a further one in much the same direction (SPREAD_NEAR radians per block). So:
	 * a wide group sounds across its whole width, not bunched where most of its blocks happen to be (round 15: a dense canopy on the left took all the sounds); a
	 * canopy straight overhead counts as one direction, not as many; and a block right beside you is not passed over for a far part of the same thing.
	 * Directions: bearing round you and elevation (radians), and distance (blocks). Returns positions in the given arrays.
	 */
	static int[] spread(double[] bearing, double[] elev, double[] dist, int k) {
		int m = bearing.length;
		if (k <= 0 || m == 0) return new int[0];
		if (k >= m) { int[] all = new int[m]; for (int i = 0; i < m; i++) all[i] = i; return all; }
		double[][] u = new double[m][];
		int first = 0;
		for (int i = 0; i < m; i++) {
			double c = Math.cos(elev[i]);
			u[i] = new double[]{c * Math.cos(bearing[i]), Math.sin(elev[i]), c * Math.sin(bearing[i])};
			if (dist[i] < dist[first]) first = i;
		}
		double[] minAng = new double[m];
		java.util.Arrays.fill(minAng, Double.MAX_VALUE);
		boolean[] used = new boolean[m];
		int[] pick = new int[k];
		int cur = first;
		for (int j = 0; j < k; j++) {
			pick[j] = cur;
			used[cur] = true;
			int best = -1;
			double bestScore = -Double.MAX_VALUE;
			for (int i = 0; i < m; i++) {
				if (used[i]) continue;
				double dot = u[i][0] * u[cur][0] + u[i][1] * u[cur][1] + u[i][2] * u[cur][2];
				minAng[i] = Math.min(minAng[i], Math.acos(Math.max(-1, Math.min(1, dot))));
				double score = minAng[i] - Tuning.SPREAD_NEAR * dist[i];
				if (score > bestScore) { bestScore = score; best = i; }
			}
			if (best < 0) break;
			cur = best;
		}
		return pick;
	}

	/**
	 * WHEN EACH SOUND OF A PING PLAYS (ticks after the ping): four evenly spaced beats by where it is: in FRONT (within 45 degrees of where you face), then RIGHT,
	 * then LEFT, then BEHIND, `gap` ticks apart, the same every ping; a quarter with nothing in it keeps its beat silent, so the rhythm never changes and the beat
	 * itself tells you the side. Warnings (priority 0: ledges, lava) play at once. gap 0: everything at once.
	 * (Round 15: the waves were the first, middle and last THIRD of the sounds sorted front to back, not real directions, so a sound on your left could play with the
	 * front one, and two quick sounds, a pause, two more was what you heard. Right and left are separate beats now, so they never blend into the middle either.)
	 * rel: direction from where you face (radians, + right).
	 */
	static int[] beatTicks(double[] rel, int[] pri, int gap) {
		int[] at = new int[rel.length];
		for (int i = 0; i < rel.length; i++) at[i] = pri[i] == 0 ? 0 : quarter(rel[i]) * gap;
		return at;
	}

	/** 0 front, 1 right, 2 left, 3 behind (45 degrees either side of each). */
	static int quarter(double rel) {
		double a = Math.abs(rel);
		return a <= Math.PI / 4 ? 0 : a >= 3 * Math.PI / 4 ? 3 : rel > 0 ? 1 : 2;
	}

	/** Plays one ping's sounds the way every ping plays them: in front first, then round to behind, in three quick steps. */
	static void playSet(Minecraft mc, Scan s, SonarConfig cfg, List<com.sonar.Cues.Cue> cues, int n, int delay) {
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

		final double rxs = -Math.cos(yawNow), rzs = -Math.sin(yawNow); // your right
		double[] rel = new double[n];
		int[] pri = new int[n];
		for (int rank = 0; rank < n; rank++) {
			com.sonar.Cues.Cue c = cues.get(playOrder[rank]);
			rel[rank] = c.rel(); // the beat is the REAL direction's quarter
			pri[rank] = c.pri();
		}
		int[] at = beatTicks(rel, pri, cfg.stagger ? Math.max(1, cfg.sweepGap) : 0); // the sweep speed slider
		boolean dbg = mc.player != null && cfg.debug;
		StringBuilder playedLog = dbg && cfg.trace ? new StringBuilder("played:") : null;
		for (int rank = 0; rank < n; rank++) {
			com.sonar.Cues.Cue c = cues.get(playOrder[rank]);
			if (playedLog != null) {
				double dx = c.x() - s.ex, dz = c.z() - s.ez;
				double relDeg = Math.toDegrees(Math.atan2(dx * rxs + dz * rzs, dx * fxs + dz * fzs)); // + right, - left, 0 ahead
				String snd = c.snd().location().getPath();
				playedLog.append(Dbg.fmt(" [%s %+.0f deg (heard %+.0f) %.1f bl up %.1f t%d]", snd.substring(snd.lastIndexOf('.', snd.lastIndexOf('.') - 1) + 1), Math.toDegrees(c.rel()), relDeg, c.d(), c.y() - s.ey, at[rank]));
			}
			if (at[rank] == 0 && delay == 0) {
				Timeline.book(0); // the ping books its tick: guide sounds wait for it
				play(mc, c.snd(), c.vol(), c.pitch(), c.x(), c.y(), c.z());
			} else {
				queue(new com.sonar.Cues.Pending(c.snd(), c.vol(), c.pitch(), c.x(), c.y(), c.z(), new int[]{Math.max(0, delay + at[rank] - 1)}));
			}
		}
		if (playedLog != null) SonarClient.LOG.info("SONAR " + playedLog);

	}

	static int waterDepth(Minecraft mc, int x, int y, int z) {
		int d = 0;
		MutableBlockPos p = new MutableBlockPos(x, y, z);

		while (d < 10 && Sense.hear(mc).getFluidState(p).is(FluidTags.WATER)) {
			d++;
			p.move(0, -1, 0);
		}

		return d;
	}

	static void extraCues(Scan s, SonarConfig cfg, List<com.sonar.Cues.Cue> out) {
		float[] dist = nearestBy16(s);

		double fwd = Math.atan2(Math.cos(Math.toRadians(s.yaw)), -Math.sin(Math.toRadians(s.yaw)));
		if (cfg.gapCue) {
			for (double ang : gaps(s)) {
				int i = Math.floorMod((int) Math.floor((ang + Math.PI) / (2 * Math.PI) * Scan.BLOCK_YAW), Scan.BLOCK_YAW);
				double r = Math.min(6.0, nearest(s)[i]);
				out.add(at(s, cfg, (float) r, s.ex + Math.cos(ang) * r, s.ey, s.ez + Math.sin(ang) * r, SoundEvents.AMETHYST_BLOCK_CHIME, 0.7F, 1.4F, 2));
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

	/**
	 * How far you can see at eye height in each of the scan's BLOCK_YAW (32) directions (slice j centred on (j + 0.5) / 32 of a turn from -180 degrees, as
	 * atan2 z,x counts): a ray as Sonar hears the world (guide blocks are air), passing doors (a door is a way through), out to the sonar's range.
	 * (Not the scan's block candidates: each block is filed under the slice its centre points into, so close up a slice by a corner can hold no block at
	 * all, and read as wide open.)
	 */
	static float[] nearest(Scan s) {
		float[] d = new float[Scan.BLOCK_YAW];
		var eye = new Vec3(s.ex, s.ey, s.ez);
		for (int j = 0; j < Scan.BLOCK_YAW; j++) {
			double a = (j + 0.5) / Scan.BLOCK_YAW * 2 * Math.PI - Math.PI;
			double r = Rooms.ray(s.world(), eye, Math.cos(a), 0, Math.sin(a), s.range, false, true, false);
			d[j] = r >= s.range ? Float.MAX_VALUE : (float) r;
		}
		return d;
	}

	/** A gap needs walls this near on both sides of it... */
	static final float GAP_NEAR = 6f;
	/** ...and to reach this much further than both of them... */
	static final float GAP_DEEPER = 4f;
	/** ...and to be no wider than this many slices (a quarter turn: wider is open ground, not a gap). */
	static final int GAP_MAX_SLICES = Scan.BLOCK_YAW / 4;

	/**
	 * THE GAP CUE: openings between walls you are near (a doorway, a gap in a hedge, the way out of a courtyard), as directions (atan2 z,x) to chime from.
	 * A gap is a RUN of neighbouring slices that all reach GAP_DEEPER further than the walls just either side of the run, those walls both within GAP_NEAR,
	 * the run at most a quarter turn wide; it chimes from the middle of the run. (It used to be one direction of 16 between two near ones: a doorway close by
	 * is wider than one direction, so it was missed; and after the round 15 finer slices it read the wrong slices altogether.)
	 */
	static List<Double> gaps(Scan s) {
		float[] d = nearest(s);
		int n = d.length;
		List<Double> out = new ArrayList<>();
		for (int start = 0; start < n; start++) {
			int before = (start + n - 1) % n;
			if (d[before] >= GAP_NEAR) continue; // a run starts just after a near wall
			int len = 0;
			while (len < GAP_MAX_SLICES && d[(start + len) % n] > d[before] + GAP_DEEPER) len++;
			if (len == 0 || len >= GAP_MAX_SLICES) continue;
			int after = (start + len) % n;
			if (d[after] >= GAP_NEAR) continue; // and ends at a near wall
			float far = Math.max(d[before], d[after]) + GAP_DEEPER;
			boolean deep = true;
			for (int k = 0; k < len; k++) if (d[(start + k) % n] <= far) deep = false;
			if (!deep) continue;
			double mid = start + len / 2.0; // slice units: direction j is centred on j + 0.5
			out.add(mid / n * 2 * Math.PI - Math.PI);
		}
		return out;
	}

	/**
	 * The nearest solid block in each of 16 directions round you (direction i is centred on (i + 0.5) / 16 of a turn from -180 degrees, as atan2 z,x counts),
	 * taken from the eye-level bands (30 degrees down to 30 up). The scan has BLOCK_YAW (32) slices round you and BANDS (5) up and down: each direction is two
	 * slices. (Round 15 made the slices finer and this still read them as 16 x 3: the gap cue then looked at the wrong slices, half of them under your feet.)
	 */
	static float[] nearestBy16(Scan s) {
		float[] dist = new float[16];
		int per = Scan.BLOCK_YAW / 16;
		for (int i = 0; i < 16; i++) {
			float m = Float.MAX_VALUE;
			for (int band = 1; band <= 3; band++)
				for (int k = 0; k < per; k++) m = Math.min(m, s.best[Scan.SOLID][band * Scan.BLOCK_YAW + i * per + k].d);
			dist[i] = m;
		}
		return dist;
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
			for (int band = 0; band < Scan.BANDS; band++) for (int k = 0; k < Scan.BLOCK_YAW / 16; k++) // (the 16 directions over the scan's 32 x 5 slices, every band)
				if (s.best[Scan.HAZARD][band * Scan.BLOCK_YAW + i * (Scan.BLOCK_YAW / 16) + k].state != null) lava[dir] = true;

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

	/** rel: the REAL direction of the block from where you faced at the scan (radians, + right, 0 ahead). Beats, fair shares and front/behind decisions use it;
	 *  x/y/z is where the sound is HEARD (moved by place()), and is only for playing it. */
	record Cue(float d, SoundEvent snd, float vol, float pitch, double x, double y, double z, int pri, double rel) {
	}

	record Pending(SoundEvent snd, float vol, float pitch, double x, double y, double z, int[] ticks) {
	}
}
