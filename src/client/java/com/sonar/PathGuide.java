package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

/** On or near a narrow built path (not in buildings, not on big platforms), a steady bell sounds from the path about 10 blocks ahead of you, so you can follow it. */
public class PathGuide {
	private static final int R = 14;
	/** The path never climbs more than this far above where it starts (a house wall or roof reached by stairs is higher than that), nor drops more than MAX_FALL. A hill path re-traces as you climb. */
	private static final int MAX_RISE = 3, MAX_FALL = 6;
	private static final double MIN_REACH = 8.0; // the connected path must extend at least this many blocks from you
	/** Short, sharp tick (the start of the vanilla ice chime with its ring cut off) so it is easy to follow. */
	private static final net.minecraft.sounds.SoundEvent GUIDE = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
		net.minecraft.resources.Identifier.fromNamespaceAndPath("sonar", "guide"));
	private static double tx, tz, ty;
	private static boolean have;
	private static int t, lastFound = -1000, lbx, lby, lbz;
	private static float lyaw;
	private static boolean endMode;
	/** The path cells (x,z -> floor y) of the path being followed, for the ledge warning: while the bell is on, the path itself tells where the sides are. */
	private static java.util.HashMap<Long, Integer> guideCells, traceCells;

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	public static boolean active() {
		return guideCells != null && t - lastFound <= 60; // 3 s grace: the bell flickering off near a junction must not flip the edge warning back and forth
	}

	/** Distances from you to the two sides of the path (left, right; MAX = no drop on that side), measured sideways along (rx,rz) = your right. Each is already reduced by 0.5 so it matches the
	 *  edge-cell distances the ledge scan uses. Null when you are not standing on the path. A side only counts when a drop of `minDrop`+ blocks lies just beyond the path there. */
	public static double[] edges(Minecraft mc, double ex, double ez, int fy, double rx, double rz, int minDrop) {
		var cells = guideCells;
		if (cells == null) return null;
		Integer here = cells.get(key((int) Math.floor(ex), (int) Math.floor(ez)));
		if (here == null || Math.abs(here - fy) > 1) return null;
		int hx = (int) Math.floor(ex), hz = (int) Math.floor(ez), linked = 0; // a sparse trail (stepping stones) has no sides to measure: the normal ledge warning applies instead of path mode
		for (int k = 0; k < 4; k++) {
			Integer ny = cells.get(key(hx + (k == 0 ? 1 : k == 1 ? -1 : 0), hz + (k == 2 ? 1 : k == 3 ? -1 : 0)));
			if (ny != null && Math.abs(ny - here) <= 1) linked++;
		}
		if (linked < 2) return null;
		double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}; // [2],[3] = distance to where the path ends sideways (drop or not)
		var pos = new BlockPos.MutableBlockPos();
		for (int side = 0; side < 2; side++) {
			double sg = side == 0 ? -1 : 1;
			for (double d = 0.1; d <= 6.0; d += 0.1) {
				int x = (int) Math.floor(ex + sg * rx * d), z = (int) Math.floor(ez + sg * rz * d);
				Integer y = cells.get(key(x, z));
				if (y != null && Math.abs(y - fy) <= 1) continue;
				out[2 + side] = d;
				boolean wall = false; // anything solid at or above foot level just beyond is a wall or a slope, not a drop
				for (int yy = fy + 1; yy >= fy - minDrop + 1; yy--) {
					if (!mc.level.getBlockState(pos.set(x, yy, z)).getCollisionShape(mc.level, pos).isEmpty()) { wall = true; break; }
				}
				if (!wall) out[side] = Math.max(0.05, d - 0.5);
				break;
			}
		}
		return out;
	}

	/** The cells of a SPARSE trail (stepping stones, scattered path blocks) 3 to 12 blocks ahead of you, nearest first, as {x, floor y, z, distance}; null when there is no guided path or it is a normal solid path. */
	public static String trailWhy = "";

	public static java.util.List<double[]> sparseTrail(double px, double pz, double yawRad, boolean force) {
		var cells = guideCells;
		if (cells == null || !active()) return null;
		double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
		java.util.List<double[]> out = new java.util.ArrayList<>();
		double links = 0;
		for (var e : cells.entrySet()) {
			long k = e.getKey();
			int x = (int) (k >> 32), z = (int) k;
			double dx = x + 0.5 - px, dz = z + 0.5 - pz, d = Math.hypot(dx, dz);
			if (d < 3.0 || d > 12.5 || (dx * fx + dz * fz) / d < 0.5) continue; // ahead of you, within about 60 degrees (a trail up terraces zigzags)
			int n = 0;
			if (cells.containsKey(key(x + 1, z))) n++;
			if (cells.containsKey(key(x - 1, z))) n++;
			if (cells.containsKey(key(x, z + 1))) n++;
			if (cells.containsKey(key(x, z - 1))) n++;
			links += n;
			out.add(new double[]{x, e.getValue(), z, d});
		}
		trailWhy = out.isEmpty() ? "no trail blocks 3-12 ahead within 60 degrees" : String.format("%d blocks ahead, %.1f touching neighbours each", out.size(), links / out.size());
		if (out.isEmpty() || (!force && links / out.size() >= 1.8)) return null; // cells that mostly touch each other are a real solid path: the ripple uses its three lines there
		out.sort((a, b) -> Double.compare(a[3], b[3]));
		if (!force) return out;
		java.util.Map<Integer, double[]> band = new java.util.TreeMap<>(); // forced: one block per distance step, the one closest to straight ahead
		double rx = -Math.cos(yawRad), rz = -Math.sin(yawRad);
		for (double[] c : out) {
			int b = (int) Math.round(c[3]);
			double lat = Math.abs((c[0] + 0.5 - px) * rx + (c[2] + 0.5 - pz) * rz);
			double[] cur = band.get(b);
			if (cur == null || lat < Math.abs((cur[0] + 0.5 - px) * rx + (cur[2] + 0.5 - pz) * rz)) band.put(b, c);
		}
		return new java.util.ArrayList<>(band.values());
	}

	/** y of the first solid block at x,z scanning down `depth` blocks from y, or MIN_VALUE. */
	private static int floorY(Minecraft mc, int x, int z, int y, int depth, BlockPos.MutableBlockPos pos) {
		for (int yy = y; yy >= y - depth; yy--) {
			BlockState s = mc.level.getBlockState(pos.set(x, yy, z));
			if (!s.getCollisionShape(mc.level, pos).isEmpty()) return yy;
		}
		return Integer.MIN_VALUE;
	}

	/** Part of the walkable path: any built block, plus dirt path blocks. */
	private static boolean built(Minecraft mc, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		BlockState s = mc.level.getBlockState(pos.set(x, y, z));
		var shape = s.getCollisionShape(mc.level, pos);
		if (shape.isEmpty() || shape.max(net.minecraft.core.Direction.Axis.Y) < 0.5 || s.is(net.minecraft.tags.BlockTags.TRAPDOORS) || s.is(net.minecraft.tags.BlockTags.DOORS)) return false; // thin things lying on the ground (trapdoors, carpets, plates, snow) are not path
		return !FloorEdge.natural(s) || s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH);
	}

	/** Something you could stand on: needs two free blocks above it, so the side of a house or a wall is never mistaken for path. */
	private static boolean headroom(Minecraft mc, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		return mc.level.getBlockState(pos.set(x, y + 1, z)).getCollisionShape(mc.level, pos).isEmpty()
			&& mc.level.getBlockState(pos.set(x, y + 2, z)).getCollisionShape(mc.level, pos).isEmpty();
	}

	/** A floor cell inside a building: a roof within 5 blocks overhead and built walls on at least 3 of its 4 sides within 4 blocks. A covered bridge or porch has walls on at most 2 sides.
	 *  The path flood must not run through a doorway into the rooms behind it, or a house's interior floor looks like a long path. */
	private static boolean indoors(Minecraft mc, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		boolean roof = false;
		for (int dy = 2; dy <= 5 && !roof; dy++) roof = !mc.level.getBlockState(pos.set(x, y + dy, z)).getCollisionShape(mc.level, pos).isEmpty();
		if (!roof) return false;
		int sides = 0;
		for (int k = 0; k < 4; k++) {
			int sx = k == 0 ? 1 : k == 1 ? -1 : 0, sz = k == 2 ? 1 : k == 3 ? -1 : 0;
			side:
			for (int d = 1; d <= 4; d++) for (int dy = 1; dy <= 2; dy++) {
				BlockState ws = mc.level.getBlockState(pos.set(x + sx * d, y + dy, z + sz * d));
				if (ws.getCollisionShape(mc.level, pos).isEmpty() || FloorEdge.natural(ws)) continue;
				sides++;
				break side;
			}
		}
		return sides >= 3;
	}

	/** Workstations, containers, lights and beds are furniture: the top of one is not floor you walk a path on. */
	/** How many of the 4 neighbouring cells are built floor at about the same height. */
	private static int builtNeighbours(Minecraft mc, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		int n = 0;
		for (int k = 0; k < 4; k++) {
			int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
			int ny = floorY(mc, nx, nz, y + 1, 3, pos);
			if (ny != Integer.MIN_VALUE && Math.abs(ny - y) <= 1 && built(mc, nx, ny, nz, pos) && headroom(mc, nx, ny, nz, pos)) n++;
		}
		return n;
	}

	private static boolean furniture(BlockState s) {
		if (s.hasBlockEntity()) return true;
		String n = s.getBlock().getDescriptionId();
		for (String f : new String[]{"crafting_table", "stonecutter", "lantern", "grindstone", "loom", "anvil", "smithing_table", "fletching_table", "cartography_table", "composter", "cauldron", "_bed", "bookshelf", "barrel"})
			if (n.contains(f)) return true;
		return false;
	}

	/** The top of a roof over a room: nothing solid right under the floor block, but built walls on at least 3 sides of the space below it. A bridge has open sides, so it still counts. */
	private static boolean roofOverRoom(Minecraft mc, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		if (!mc.level.getBlockState(pos.set(x, y - 1, z)).getCollisionShape(mc.level, pos).isEmpty()) return false;
		int sides = 0;
		for (int k = 0; k < 4; k++) {
			int sx = k == 0 ? 1 : k == 1 ? -1 : 0, sz = k == 2 ? 1 : k == 3 ? -1 : 0;
			side:
			for (int d = 1; d <= 5; d++) for (int dy = 1; dy <= 2; dy++) {
				BlockState ws = mc.level.getBlockState(pos.set(x + sx * d, y - dy, z + sz * d));
				if (ws.getCollisionShape(mc.level, pos).isEmpty() || FloorEdge.natural(ws)) continue;
				sides++;
				break side;
			}
		}
		return sides >= 3;
	}

	private static boolean notFloor(Minecraft mc, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		return furniture(mc.level.getBlockState(pos.set(x, y, z))) || indoors(mc, x, y, z, pos) || roofOverRoom(mc, x, y, z, pos);
	}

	private static boolean isRailOrPath(BlockState s) {
		return s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || s.is(net.minecraft.tags.BlockTags.FENCES) || s.is(net.minecraft.tags.BlockTags.WALLS)
			|| s.is(net.minecraft.tags.BlockTags.FENCE_GATES) || s.is(net.minecraft.world.level.block.Blocks.IRON_BARS);
	}

	private static String why = "", traceWhy;

	private static void say(Minecraft mc, SonarConfig cfg, String r) {
		if (cfg.debug && !r.equals(why)) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("guide: " + r));
		why = r;
	}

	private static void find(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		var pos = new BlockPos.MutableBlockPos();
		int px = p.getBlockX(), pz = p.getBlockZ(), fy = p.getBlockY() - 1;
		// not inside a building or under an arch/roof
		// no guide inside buildings or walled areas: count the walls within 7 blocks (8 directions at head height).
		// Under a roof 3 walls is enough (a covered bridge has only 2 sides); in the open it takes 6 (a walled courtyard).
		boolean roof = Cues.rayLen(mc, p.getEyePosition(), 0, 1, 0, 6, true) < 6;
		int walls = 0;
		for (int i = 0; i < 8; i++) { // only BUILT walls count: the dirt or stone sides of a trench or cliff stairway are not a building
			double a = i * Math.PI / 4;
			for (int d = 1; d <= 7; d++) {
				var wp = pos.set(Math.floor(p.getX() + Math.cos(a) * d), Math.floor(p.getEyeY()), Math.floor(p.getZ() + Math.sin(a) * d));
				BlockState ws = mc.level.getBlockState(wp);
				if (ws.getCollisionShape(mc.level, wp).isEmpty()) continue;
				if (!FloorEdge.natural(ws)) walls++;
				break;
			}
		}
		if (Cues.enclosed(mc, p.getEyePosition())) { say(mc, cfg, "off: inside a room"); return; } // the same test the echo uses: any roof-and-walls space, whatever the walls are made of
		if (walls >= (roof ? 3 : 6)) { say(mc, cfg, "off: " + (roof ? "inside a building" : "walled area") + " (" + walls + "/8 walls)"); return; }
		// candidate starts: the nearest built floor cell in each of 8 directions (and under you), within 3 blocks. Floor may be a few blocks below while jumping or on stairs.
		int[][] cand = new int[9][];
		int[] candD = new int[9];
		java.util.Arrays.fill(candD, 99);
		int nearest = -1;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				int y = floorY(mc, px + dx, pz + dz, fy + 2, 6, pos);
				if (y == Integer.MIN_VALUE || Math.abs(y - fy) > 2 || !built(mc, px + dx, y, pz + dz, pos) || !headroom(mc, px + dx, y, pz + dz, pos) || notFloor(mc, px + dx, y, pz + dz, pos)) continue;
				int d = dx * dx + dz * dz;
				int sec = (dx == 0 && dz == 0) ? 8 : Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4)), 8);
				if (d < candD[sec]) { candD[sec] = d; cand[sec] = new int[]{px + dx, pz + dz, y}; }
			}
		}
		for (int i = 0; i < 9; i++) if (cand[i] != null && (nearest < 0 || candD[i] < candD[nearest])) nearest = i;
		if (nearest < 0) { say(mc, cfg, "off: no path block within 3 of you"); return; }
		// big square paths, plazas and platforms are not helpful: skip when built floor covers most of the surrounding 9x9
		int sx = cand[nearest][0], sz = cand[nearest][1], sy = cand[nearest][2];
		int cover = 0;
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				int y = floorY(mc, sx + dx, sz + dz, sy + 1, 2, pos);
				if (y != Integer.MIN_VALUE && built(mc, sx + dx, y, sz + dz, pos) && !isRailOrPath(mc.level.getBlockState(pos))) cover++; // railings and dirt path do not make a road look like a plaza
			}
		}
		if (cover > 54) { say(mc, cfg, "off: too wide (" + cover + " built blocks in 9x9)"); return; }
		// follow whichever nearby path you are facing most
		traceWhy = null;
		double[] best = null;
		java.util.HashMap<Long, Integer> bestCells = null;
		for (int i = 0; i < 9; i++) {
			if (cand[i] == null) continue;
			double[] r = trace(mc, p, cand[i][0], cand[i][1], cand[i][2], pos);
			if (r != null && (best == null || r[3] > best[3])) { best = r; bestCells = traceCells; }
		}
		if (best == null) { say(mc, cfg, traceWhy != null ? traceWhy : "off: no path 8-11 blocks ahead"); return; }
		if (cfg.debug && (!why.equals("on") || t % 100 == 0)) { // what the guide thinks the path is made of
			var counts = new java.util.TreeMap<String, Integer>();
			for (var e : bestCells.entrySet()) {
				long k = e.getKey();
				var bn = mc.level.getBlockState(pos.set((int) (k >> 32), e.getValue(), (int) k)).getBlock().getDescriptionId().replace("block.minecraft.", "");
				counts.merge(bn, 1, Integer::sum);
			}
			var lg = org.slf4j.LoggerFactory.getLogger("sonar");
			lg.info("PATHMAP me {},{} y {} (digit = floor height relative to your feet, S=start, .=not path; x across, z down)", px, pz, p.getBlockY());
			for (int mz = -R; mz <= R; mz++) {
				StringBuilder row = new StringBuilder();
				for (int mx = -R; mx <= R; mx++) {
					Integer yy = bestCells.get(key(px + mx, pz + mz));
					char ch = yy == null ? '.' : (char) ('0' + Math.max(0, Math.min(9, yy - (p.getBlockY() - 1) + 4)));
					if (px + mx == sx && pz + mz == sz) ch = 'S';
					if (mx == 0 && mz == 0) ch = 'M';
					row.append(ch);
				}
				lg.info("PATHMAP {}", row);
			}
			p.sendSystemMessage(net.minecraft.network.chat.Component.literal("path made of " + bestCells.size() + " cells: " + counts + " | start " + sx + "," + sz + " cover " + cover + " walls " + walls + " roof " + roof));
		}
		say(mc, cfg, "on");
		guideCells = bestCells;
		tx = best[0]; ty = best[1]; tz = best[2]; endMode = best[4] > 0;
		have = true;
		lastFound = t;
	}

	/** Flood the connected path from a start cell; returns {x, y, z, facingScore} of the path 8-11 blocks ahead, or null. */
	private static double[] trace(Minecraft mc, net.minecraft.client.player.LocalPlayer p, int sx, int sz, int sy, BlockPos.MutableBlockPos pos) {
		int px = p.getBlockX(), pz = p.getBlockZ();
		int W = 2 * R + 1;
		int[] dist = new int[W * W], ys = new int[W * W];
		double[] dirx = new double[W * W], dirz = new double[W * W]; // the way the trail is heading at each cell
		java.util.Arrays.fill(dist, -1);
		var q = new ArrayDeque<int[]>();
		int si = (sz - pz + R) * W + (sx - px + R);
		if (si < 0 || si >= dist.length || Math.abs(sx - px) > R || Math.abs(sz - pz) > R) return null;
		dist[si] = 0; ys[si] = sy;
		var seen = new java.util.HashMap<Long, Integer>();
		traceCells = seen;
		seen.put(key(sx, sz), sy);
		q.add(new int[]{sx, sz});
		double yaw = Math.toRadians(p.getYRot()), fx = -Math.sin(yaw), fz = Math.cos(yaw);
		dirx[si] = fx; dirz[si] = fz;
		double sumX = 0, sumZ = 0, sumY = 0;
		var bandCells = new java.util.ArrayList<int[]>();
		int n = 0;
		double reach = 0; // how far from you the connected built floor extends, in any direction
		double fbX = 0, fbY = 0, fbZ = 0, fbD = 0; // farthest path cell ahead, used when the path ends before the normal 8-11 block window
		while (!q.isEmpty()) {
			int[] c = q.poll();
			int ci = (c[1] - pz + R) * W + (c[0] - px + R), cy = ys[ci];
			double ddx = c[0] + 0.5 - p.getX(), ddz = c[1] + 0.5 - p.getZ(), dl = Math.hypot(ddx, ddz);
			if (dl > reach) reach = dl;
				if (dl >= 4.0 && dl > fbD && (ddx * fx + ddz * fz) / dl > 0.7) { fbD = dl; fbX = c[0] + 0.5; fbY = cy + 1; fbZ = c[1] + 0.5; }
			if (dl >= 8.0 && dl <= 11.0 && (ddx * fx + ddz * fz) / dl > 0.7) { sumX += c[0] + 0.5; sumZ += c[1] + 0.5; sumY += cy + 1; n++; bandCells.add(c); }
			int nbuilt = 0; // built neighbours of this cell: a sparse trail (stepping stones, scattered path blocks) has few
			for (int k = 0; k < 4; k++) {
				int nx = c[0] + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = c[1] + (k == 2 ? 1 : k == 3 ? -1 : 0);
				int ddx2 = nx - px, ddz2 = nz - pz;
				if (Math.abs(ddx2) > R || Math.abs(ddz2) > R) continue;
				int ni = (ddz2 + R) * W + (ddx2 + R);
				if (dist[ni] >= 0) { nbuilt++; continue; }
				if (dist[ni] == -2) continue;
				int y = floorY(mc, nx, nz, cy + 1, 3, pos);
				if (y == Integer.MIN_VALUE || Math.abs(y - cy) > 1 || y - sy > MAX_RISE || sy - y > MAX_FALL || !built(mc, nx, y, nz, pos) || !headroom(mc, nx, y, nz, pos) || notFloor(mc, nx, y, nz, pos)) { dist[ni] = -2; continue; }
				nbuilt++;
				dist[ni] = dist[ci] + 1; ys[ni] = y; dirx[ni] = nx - c[0]; dirz[ni] = nz - c[1];
				seen.put(key(nx, nz), y);
				q.add(new int[]{nx, nz});
			}
			if (nbuilt <= 2) { // sparse trail: also reach the next built blocks up to 6 away, in the direction the trail is heading (within about 40 degrees), across plain ground
				int added = 0;
				for (int r = 2; r <= 6 && added < 2; r++) {
					for (int bx = -r; bx <= r && added < 2; bx++) {
						for (int bz = -r; bz <= r && added < 2; bz++) {
							if (Math.max(Math.abs(bx), Math.abs(bz)) != r) continue;
							double bl = Math.hypot(bx, bz);
							if (bl > 6.5 || (bx * dirx[ci] + bz * dirz[ci]) / bl < 0.77) continue;
							int tx = c[0] + bx, tz = c[1] + bz, tdx = tx - px, tdz = tz - pz;
							if (Math.abs(tdx) > R || Math.abs(tdz) > R) continue;
							int ti = (tdz + R) * W + (tdx + R);
							if (dist[ti] != -1) continue;
							int ty = floorY(mc, tx, tz, cy + 2, 6, pos);
							if (ty == Integer.MIN_VALUE || Math.abs(ty - cy) > 2 || ty - sy > MAX_RISE || sy - ty > MAX_FALL || !built(mc, tx, ty, tz, pos) || !headroom(mc, tx, ty, tz, pos) || notFloor(mc, tx, ty, tz, pos) || builtNeighbours(mc, tx, ty, tz, pos) > 1) continue; // a gap is only crossed to reach another stepping stone, never the edge of a patio or building
							dist[ti] = dist[ci] + r; ys[ti] = ty; dirx[ti] = bx / bl; dirz[ti] = bz / bl;
							seen.put(key(tx, tz), ty);
							q.add(new int[]{tx, tz});
							added++;
						}
					}
				}
			}
		}
		if (n > 0) { // a path is narrow: where the bell would play, a 5x5 around each cell is at most about two thirds built. A patio, plaza or broad roof is nearly full all the way across.
			double dense = 0;
			for (int[] c : bandCells) {
				int cnt = 0;
				for (int ax = -2; ax <= 2; ax++) for (int az = -2; az <= 2; az++) {
					Integer ry = seen.get(key(c[0] + ax, c[1] + az));
					if (ry != null && !(isRailOrPath(mc.level.getBlockState(pos.set(c[0] + ax, ry, c[1] + az))) && !mc.level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.DIRT_PATH))) cnt++; // railings are not path width
				}
				dense += cnt;
			}
			if (dense / n > 17.0) { traceWhy = String.format("off: wide area ahead (%.0f of 25 cells built on average)", dense / n); return null; }
		}
		double cx = n > 0 ? sumX / n : fbX, cz = n > 0 ? sumZ / n : fbZ, ox = cx - p.getX(), oz = cz - p.getZ(), ol = Math.max(1e-6, Math.hypot(ox, oz));
		if (reach < MIN_REACH) return null; // too short to be a path (a doorstep, a patch of floor, the foot of a wall); a long path you are near the end of still reaches back past this
		if (n == 0 && fbD == 0) return null; // the path ends right here: nothing ahead to guide to
		return new double[]{cx, n > 0 ? sumY / n : fbY, cz, (ox * fx + oz * fz) / ol, n > 0 ? 0 : 1};
	}

	public static void tick(Minecraft mc, SonarConfig cfg) {
		if (!cfg.enabled || !cfg.pathGuide || mc.level == null || mc.player == null || mc.player.isInWater() || mc.player.isSpectator()) { have = false; guideCells = null; return; }
		t++;
		var p0 = mc.player;
		boolean moved = p0.getBlockX() != lbx || p0.getBlockY() != lby || p0.getBlockZ() != lbz || Math.abs(net.minecraft.util.Mth.wrapDegrees(p0.getYRot() - lyaw)) > 6;
		// re-trace only when you moved or turned (or the last result is getting old); idle with no path is re-checked every second
		if ((have ? t % 5 == 1 : t % 2 == 1) && (moved || (have ? t - lastFound > 25 : t % 20 == 1))) {
			lbx = p0.getBlockX(); lby = p0.getBlockY(); lbz = p0.getBlockZ(); lyaw = p0.getYRot();
			find(mc, cfg);
		}
		if (t - lastFound > 8) have = false; // brief gaps (stairs, jumps) keep the last bearing
		if (!have || t % 12 != 0) return;
		var p = mc.player;
		Vec3 eye = p.getEyePosition();
		// play at the real spot about 3 blocks ahead on the path, bright chime so it is easy to place
		// exaggerate the sideways angle (x2.5) so a small drift off the path is clearly heard left or right, and put the sound 10.5 blocks out
		double yaw = Math.toRadians(p.getYRot()), fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double dx = tx - p.getX(), dz = tz - p.getZ();
		double ang = Math.atan2(dx * rx + dz * rz, dx * fx + dz * fz);
		if (Math.abs(ang) > Math.toRadians(50)) { say(mc, cfg, "silent: target is behind you (" + Math.round(Math.toDegrees(ang)) + " deg)"); return; } // never guide backwards
		// small drifts are exaggerated x2.5, but the bell never moves more than ~25 degrees off straight ahead, so a path that bends away still sounds in front
		double cap = Math.toRadians(25);
		double a2 = cap * Math.tanh(ang * 2.5 / cap), dist = Math.min(10.5, Math.max(4.0, Math.hypot(dx, dz)));
		double px = p.getX() + dist * (Math.cos(a2) * fx + Math.sin(a2) * rx), pz = p.getZ() + dist * (Math.cos(a2) * fz + Math.sin(a2) * rz);
		if (cfg.debug) p.sendSystemMessage(net.minecraft.network.chat.Component.literal(String.format("bell: me %.0f,%.0f yaw %.0f | %s target %.0f,%.0f (%.0f blocks, %.0f deg) | sound %.0f,%.0f", p.getX(), p.getZ(), p.getYRot(), endMode ? "END-OF-PATH" : "ahead", tx, tz, Math.hypot(dx, dz), Math.toDegrees(ang), px, pz)));
		Cues.play(mc, GUIDE, Math.min(1f, cfg.volume * 0.95f * cfg.guideVolume), (t / 12) % 2 == 0 ? 1.1f : 1.6f, px, ty + 0.5, pz);
	}
}
