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
	/** Short looping tune (pitch multipliers), one step every STEP ticks. */
	private static final float[] TUNE = {1.0f, 1.26f, 1.5f, 2.0f, 1.5f, 1.26f};
	private static final int STEP = 5;
	private static int t;
	/** Amethyst footstep with the low thud filtered out (bundled in the mod), and its low-passed copy for sounds behind you. */
	private static final net.minecraft.sounds.SoundEvent STEP_SND = ev("step"), STEP_DULL = ev("m.step");
	/** The vanilla amethyst chime (shimmer) at full volume, and its low-passed copy. */
	private static final net.minecraft.sounds.SoundEvent JINGLE = ev("chime"), JINGLE_DULL = ev("m.chime");

	private static net.minecraft.sounds.SoundEvent ev(String id) {
		return net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.fromNamespaceAndPath("sonar", id));
	}

	/** The latest route result per player: {x, y, z of the point to sound from, walking distance left, tick it was worked out}; x = NaN when no route was found. */
	private static final Map<UUID, double[]> guide = new HashMap<>();

	public static void tick(Minecraft mc, SonarConfig cfg) {
		if (!cfg.playerChime || mc.level == null || mc.player == null) return;
		t++;
		var me = mc.player.getEyePosition();
		List<Player> near = new ArrayList<>();
		for (Player p : mc.level.players()) {
			if (p == mc.player || p.isSpectator()) continue;
			if (p.getEyePosition().distanceTo(me) <= cfg.playerChimeRange) near.add(p);
		}
		near.sort((a, b) -> Double.compare(a.distanceToSqr(mc.player), b.distanceToSqr(mc.player)));
		// routes: the nearest 3 players, each re-worked twice a second (a failed search waits 2 s before trying again, so an unreachable player costs little)
		for (int i = 0; i < Math.min(3, near.size()); i++) {
			Player p = near.get(i);
			double[] g = guide.get(p.getUUID());
			int every = g != null && Double.isNaN(g[0]) ? 40 : 10;
			if (g == null || t - g[4] >= every) {
				double[] r = route(mc, mc.player, p);
				guide.put(p.getUUID(), r != null ? new double[]{r[0], r[1], r[2], r[3], t} : new double[]{Double.NaN, 0, 0, 0, t});
			}
		}
		if (t % 200 == 0) guide.keySet().removeIf(id -> near.stream().noneMatch(p -> p.getUUID().equals(id)));
		if (t % STEP != 0) return;
		float pitch = TUNE[(t / STEP) % TUNE.length];
		double yaw = Math.toRadians(mc.player.getYRot()), fx = -Math.sin(yaw), fz = Math.cos(yaw);
		for (int i = 0; i < near.size(); i++) {
			Player p = near.get(i);
			double[] g = guide.get(p.getUUID());
			boolean routed = g != null && !Double.isNaN(g[0]) && t - g[4] <= 60;
			double tx = routed ? g[0] : p.getX(), ty = routed ? g[1] : p.getEyeY(), tz = routed ? g[2] : p.getZ();
			double dx = tx - me.x, dy = ty - me.y, dz = tz - me.z;
			double dd = Math.max(1e-6, Math.sqrt(dx * dx + dy * dy + dz * dz));
			double d = routed ? Math.max(g[3], dd) : dd; // how far you have to go: the walking distance when there is a route
			double range = cfg.playerChimeRange;
			double ad = 1.0 + Math.min(d, range) / range * 12.0; // compressed so far players still sound near
			float c = 1f - (float) Math.min(d, range) / (float) range;
			float q = 0.03f + 0.97f * c * c * c; // steeper falloff: far players are faint
			double k = ad / dd;
			double px = me.x + dx * k, py = me.y + dy * k, pz = me.z + dz * k;
			// front / side / behind: w = 0 straight ahead, 0.5 to the side, 1 straight behind. Lower and duller the further round it is.
			double hl = Math.max(1e-6, Math.hypot(dx, dz));
			float w = (float) ((1 - (dx * fx + dz * fz) / hl) / 2);
			float pt = pitch * (1f - 0.2f * w);
			float base = q * (1f - 0.3f * w);
			float bright = 1f - w, dull = w; // the low-passed copy fades in as the sound moves round behind you
			if (bright > 0.02f) {
				mc.getSoundManager().play(new SimpleSoundInstance(STEP_SND, SoundSource.AMBIENT, Math.min(1f, cfg.volume * base * bright * 0.9f * cfg.chimeVolume), pt, RandomSource.create(), px, py, pz));
				mc.getSoundManager().play(new SimpleSoundInstance(JINGLE, SoundSource.AMBIENT, Math.min(1f, 0.6f * base * bright * cfg.chimeVolume), pt, RandomSource.create(), px, py, pz));
			}
			if (dull > 0.02f) {
				mc.getSoundManager().play(new SimpleSoundInstance(STEP_DULL, SoundSource.AMBIENT, Math.min(1f, cfg.volume * base * dull * 0.9f * cfg.chimeVolume), pt, RandomSource.create(), px, py, pz));
				mc.getSoundManager().play(new SimpleSoundInstance(JINGLE_DULL, SoundSource.AMBIENT, Math.min(1f, 0.6f * base * dull * cfg.chimeVolume), pt, RandomSource.create(), px, py, pz));
			}
		}
	}

	// ---------- walking route ----------

	private static final int NONE = Integer.MIN_VALUE;

	private static boolean passable(Minecraft mc, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		BlockState s = mc.level.getBlockState(pos.set(x, y, z));
		if (s.is(BlockTags.DOORS) || s.is(BlockTags.FENCE_GATES)) return true; // a door or gate is a way through, open or shut
		return s.getCollisionShape(mc.level, pos).isEmpty() && !(s.getBlock() instanceof LiquidBlock);
	}

	/** Floor y when stepping onto column x,z from a floor at ref (up 1, down 3, with 2 blocks of headroom); NONE when you can not. */
	private static int stepTo(Minecraft mc, int x, int z, int ref, BlockPos.MutableBlockPos pos) {
		for (int y = ref + 1; y >= ref - 3; y--) {
			BlockState s = mc.level.getBlockState(pos.set(x, y, z));
			if (s.getBlock() instanceof LiquidBlock) return NONE;
			if (s.is(BlockTags.DOORS) || s.is(BlockTags.FENCE_GATES)) continue; // look through to the floor under the doorway
			if (s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || s.is(net.minecraft.world.level.block.Blocks.IRON_BARS)) return NONE;
			if (!s.getCollisionShape(mc.level, pos).isEmpty()) return passable(mc, x, y + 1, z, pos) && passable(mc, x, y + 2, z, pos) ? y : NONE;
		}
		return NONE;
	}

	/** The floor under an entity (it may be jumping or a few blocks up in the air), or NONE. */
	private static int floorUnder(Minecraft mc, double x, double y, double z, BlockPos.MutableBlockPos pos) {
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
		for (int yy = (int) Math.floor(y + 0.01); yy >= (int) Math.floor(y) - 8; yy--) {
			BlockState s = mc.level.getBlockState(pos.set(bx, yy, bz));
			if (s.getBlock() instanceof LiquidBlock) return NONE;
			if (!s.getCollisionShape(mc.level, pos).isEmpty()) return yy;
		}
		return NONE;
	}

	private static long key(int x, int y, int z) {
		return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
	}

	/**
	 * A* over the floor you can walk on, from you to the other player (at most 12000 cells looked at). Returns {x, y, z, walking distance} of the point to sound from:
	 * the farthest point along the route that you can see and could walk to in a straight line (every route cell up to it lies within a block of that line). When that is the end
	 * of the route, it is the player. Null when there is no walking route (then the jingle simply plays from the player).
	 */
	static double[] route(Minecraft mc, net.minecraft.client.player.LocalPlayer me, Player other) {
		var pos = new BlockPos.MutableBlockPos();
		int sx = me.getBlockX(), sz = me.getBlockZ(), sf = floorUnder(mc, me.getX(), me.getY(), me.getZ(), pos);
		int gx = other.getBlockX(), gz = other.getBlockZ(), gf = floorUnder(mc, other.getX(), other.getY(), other.getZ(), pos);
		if (sf == NONE || gf == NONE) return null;
		int minX = Math.min(sx, gx) - 32, maxX = Math.max(sx, gx) + 32, minZ = Math.min(sz, gz) - 32, maxZ = Math.max(sz, gz) + 32;
		Map<Long, Node> nodes = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		Node start = new Node(sx, sf, sz, 0, null);
		start.f = Math.hypot(gx - sx, gz - sz);
		nodes.put(key(sx, sf, sz), start);
		open.add(start);
		Node goal = null;
		int expanded = 0;
		while (!open.isEmpty() && expanded < 12000) {
			Node c = open.poll();
			if (c.closed) continue;
			c.closed = true;
			expanded++;
			if (Math.abs(c.x - gx) <= 1 && Math.abs(c.z - gz) <= 1 && Math.abs(c.y - gf) <= 1) { goal = c; break; }
			for (int k = 0; k < 4; k++) {
				int nx = c.x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = c.z + (k == 2 ? 1 : k == 3 ? -1 : 0);
				if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
				int nf = stepTo(mc, nx, nz, c.y, pos);
				if (nf == NONE) continue;
				double cost = c.g + 1 + (nf > c.y ? 0.5 : 0) + (c.y - nf >= 2 ? 1 : 0); // climbing and big step-downs cost a little more
				long nk = key(nx, nf, nz);
				Node n = nodes.get(nk);
				if (n != null && (n.closed || n.g <= cost)) continue;
				if (n != null) n.closed = true; // a cheaper way in was found: the old queue entry is dropped when it comes up
				n = new Node(nx, nf, nz, cost, c);
				nodes.put(nk, n);
				n.f = cost + Math.hypot(gx - nx, gz - nz) + Math.abs(gf - nf) * 0.5;
				open.add(n);
			}
		}
		if (goal == null) return null;
		List<int[]> path = new ArrayList<>(); // goal back to start, then reversed
		for (Node n = goal; n != null; n = n.parent) path.add(new int[]{n.x, n.y, n.z});
		java.util.Collections.reverse(path);
		double length = goal.g;
		Vec3 eye = me.getEyePosition();
		int last = path.size() - 1;
		for (int k = last; k >= 1; k--) {
			int[] pk = path.get(k);
			double ax = me.getX(), az = me.getZ(), bx = pk[0] + 0.5, bz = pk[2] + 0.5;
			boolean straight = true;
			for (int i = 1; i < k && straight; i++) {
				int[] pi = path.get(i);
				if (segDist(pi[0] + 0.5, pi[2] + 0.5, ax, az, bx, bz) > 1.0) straight = false;
			}
			if (!straight) continue;
			Vec3 to = k == last ? other.getEyePosition() : new Vec3(bx, pk[1] + 1.6, bz);
			var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, me));
			if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) continue;
			if (k == last) return new double[]{other.getX(), other.getEyeY(), other.getZ(), length};
			if (SonarConfig.cur.debug && t % 40 == 0) me.sendSystemMessage(net.minecraft.network.chat.Component.literal(String.format("player guide: route %.0f blocks, sounding from a turn %d steps along at %.0f,%.0f,%.0f", length, k, bx, pk[1] + 1, bz)));
			return new double[]{bx, pk[1] + 1.6, bz, length};
		}
		int[] nx = path.get(Math.min(1, last)); // nothing visible and straight (should not happen): the next step of the route
		return new double[]{nx[0] + 0.5, nx[1] + 1.6, nx[2] + 0.5, length};
	}

	private static final class Node {
		final int x, y, z;
		final double g;
		final Node parent;
		double f;
		boolean closed;

		Node(int x, int y, int z, double g, Node parent) {
			this.x = x; this.y = y; this.z = z; this.g = g; this.parent = parent;
		}
	}

	/** Distance from point p to the segment a-b (flat, x and z). */
	private static double segDist(double px, double pz, double ax, double az, double bx, double bz) {
		double vx = bx - ax, vz = bz - az, l2 = vx * vx + vz * vz;
		double u = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / l2));
		return Math.hypot(px - (ax + u * vx), pz - (az + u * vz));
	}
}
