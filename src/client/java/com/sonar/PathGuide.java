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
	private static final int MAX_RISE = 6, MAX_FALL = 10;
	/** Paths are picked up from this far away (blocks), so one can be found by ear: off the path, the bell leads you onto it first. */
	private static final int NEAR = 4;
	private static final double MIN_REACH = 12.0; // the connected path must be at least this long, measured along the path itself (end to end), not from where you stand
	/** Short, sharp tick (the start of the vanilla ice chime with its ring cut off) so it is easy to follow. */
	static final net.minecraft.sounds.SoundEvent GUIDE = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
		net.minecraft.resources.Identifier.fromNamespaceAndPath("sonar", "guide"));
	private static double tx, tz, ty;
	/** The bell's target after the last find (self-test): {x, y, z, end of path 1/0, found 1/0}. */
	static double[] target() { return new double[]{tx, ty, tz, endMode ? 1 : 0, have ? 1 : 0}; }
	private static boolean have;
	private static int t, lastFound = -1000, lbx, lby, lbz;
	private static float lyaw;
	private static boolean endMode;
	/** The path cells (x,z -> floor y) of the path being followed, for the ledge warning: while the bell is on, the path itself tells where the sides are. */
	private static java.util.HashMap<Long, Integer> guideCells, traceCells;
	/** The cells of the path the bell follows (self-test): key(x, z) -> floor y. */
	static java.util.Map<Long, Integer> cells() { return guideCells == null ? java.util.Map.of() : guideCells; }

	static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	public static boolean active() {
		return guideCells != null && !approach && !(lastTry > lastFound && t - lastFound > Tuning.GRACE_PATH_KEEP); // (lost only when a search since found nothing: standing still you are still on it) // (only while you are ON the path: leading you to one nearby does not count) 3 s grace: the bell flickering off near a junction must not flip the edge warning back and forth
	}

	/** Distances from you to the two sides of the path (left, right; MAX = no drop on that side), measured sideways along (rx,rz) = your right. Each is already reduced by 0.5 so it matches the
	 *  edge-cell distances the ledge scan uses. Null when you are not standing on the path. A side only counts when a drop of `minDrop`+ blocks lies just beyond the path there. */
	public static double[] edges(Minecraft mc, double ex, double ez, int fy, double rx, double rz, int minDrop) {
		var w = Sense.hear(mc);
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
					if (!w.getBlockState(pos.set(x, yy, z)).getCollisionShape(w, pos).isEmpty()) { wall = true; break; }
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
		trailWhy = out.isEmpty() ? "no trail blocks 3-12 ahead within 60 degrees" : Dbg.fmt("%d blocks ahead, %.1f touching neighbours each", out.size(), links / out.size());
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
	private static int floorY(net.minecraft.world.level.BlockGetter w, int x, int z, int y, int depth, BlockPos.MutableBlockPos pos) {
		for (int yy = y; yy >= y - depth; yy--) {
			BlockState s = w.getBlockState(pos.set(x, yy, z));
			if (!s.getCollisionShape(w, pos).isEmpty()) return yy;
		}
		return Integer.MIN_VALUE;
	}

	/** Part of the walkable path: any built block, plus dirt path blocks. */
	private static boolean built(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		BlockState s = w.getBlockState(pos.set(x, y, z));
		var shape = s.getCollisionShape(w, pos);
		if (shape.isEmpty() || shape.max(net.minecraft.core.Direction.Axis.Y) < 0.5 || s.is(net.minecraft.tags.BlockTags.TRAPDOORS) || s.is(net.minecraft.tags.BlockTags.DOORS)) return false; // thin things lying on the ground (trapdoors, carpets, plates, snow) are not path
		if (shape.max(net.minecraft.core.Direction.Axis.Y) > 1.0 || (isRailOrPath(s) && !s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH))) return false; // the top of a fence, wall or railing is not a path, even with room above it
		return !FloorEdge.natural(s) || s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH);
	}

	/** Something you could stand on: needs two free blocks above it, so the side of a house or a wall is never mistaken for path. */
	private static boolean headroom(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		return w.getBlockState(pos.set(x, y + 1, z)).getCollisionShape(w, pos).isEmpty()
			&& w.getBlockState(pos.set(x, y + 2, z)).getCollisionShape(w, pos).isEmpty();
	}

	/** A floor cell inside a building: a roof within 5 blocks overhead and built walls on at least 3 of its 4 sides within 4 blocks. A covered bridge or porch has walls on at most 2 sides.
	 *  The path flood must not run through a doorway into the rooms behind it, or a house's interior floor looks like a long path. */
	/**
	 * A building's room beside this covered path cell, in direction dx,dz: walking sideways from the path you must first go THROUGH a wall (or a door) at head height,
	 * and the open space just past it must be a room by the shared room test (a roof straight overhead, walls under it nearly all round). A porch's house is behind its wall
	 * (1 to 2 blocks thick); a covered bridge's own edge, fence line or plank side is not a room behind a wall, it is the bridge itself. (It used to test the cell two over
	 * with indoors(), and the bridge's own fence line under its own roof passed as a room: the bell went off on a covered bridge. Round 11, 02:42.)
	 */
	static boolean roomBeside(net.minecraft.world.level.BlockGetter w, int x, int floorY, int z, int dx, int dz) {
		var pos = new BlockPos.MutableBlockPos();
		boolean wall = false;
		for (int d = 1; d <= 3; d++) {
			int cx = x + dx * d, cz = z + dz * d;
			BlockState head = w.getBlockState(pos.set(cx, floorY + 2, cz)), feet = w.getBlockState(pos.set(cx, floorY + 1, cz));
			boolean headBlocked = head.is(net.minecraft.tags.BlockTags.DOORS) || !head.getCollisionShape(w, pos.set(cx, floorY + 2, cz)).isEmpty();
			boolean feetOpen = feet.getCollisionShape(w, pos.set(cx, floorY + 1, cz)).isEmpty() && !feet.is(net.minecraft.tags.BlockTags.DOORS);
			if (headBlocked) { wall = true; continue; }
			if (!wall) { if (!feetOpen) wall = true; continue; } // open at head height before any wall: still on the path's own side (a railing is waist high, not a wall)
			return feetOpen && Rooms.roomAt(w, new Vec3(cx + 0.5, floorY + 2.6, cz + 0.5));
		}
		return false;
	}

	private static boolean indoors(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		if (!BlockRules.solidRoof(w, new Vec3(x + 0.5, y + 1.5, z + 0.5), 4)) return false; // a roof 2-5 above: a tree canopy over a garden stairway is not a roof (leaves and trunks do not count)
		int sides = 0;
		for (int k = 0; k < 4; k++) {
			int sx = k == 0 ? 1 : k == 1 ? -1 : 0, sz = k == 2 ? 1 : k == 3 ? -1 : 0;
			side:
			for (int d = 1; d <= 4; d++) for (int dy = 1; dy <= 2; dy++) {
				BlockState ws = w.getBlockState(pos.set(x + sx * d, y + dy, z + sz * d));
				if (ws.getCollisionShape(w, pos).isEmpty() || FloorEdge.natural(ws)) continue;
				sides++;
				break side;
			}
		}
		return sides >= 3;
	}

	/** Workstations, containers, lights and beds are furniture: the top of one is not floor you walk a path on. */
	/** How many of the 4 neighbouring cells are built floor at about the same height. */
	private static int builtNeighbours(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		int n = 0;
		for (int k = 0; k < 4; k++) {
			int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = z + (k == 2 ? 1 : k == 3 ? -1 : 0);
			int ny = floorY(w, nx, nz, y + 1, 3, pos);
			if (ny != Integer.MIN_VALUE && Math.abs(ny - y) <= 1 && built(w, nx, ny, nz, pos) && headroom(w, nx, ny, nz, pos)) n++;
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
	private static boolean roofOverRoom(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		if (!w.getBlockState(pos.set(x, y - 1, z)).getCollisionShape(w, pos).isEmpty()) return false;
		int sides = 0;
		for (int k = 0; k < 4; k++) {
			int sx = k == 0 ? 1 : k == 1 ? -1 : 0, sz = k == 2 ? 1 : k == 3 ? -1 : 0;
			side:
			for (int d = 1; d <= 5; d++) for (int dy = 1; dy <= 2; dy++) {
				BlockState ws = w.getBlockState(pos.set(x + sx * d, y - dy, z + sz * d));
				if (ws.getCollisionShape(w, pos).isEmpty() || FloorEdge.natural(ws)) continue;
				sides++;
				break side;
			}
		}
		return sides >= 3;
	}

	private static boolean notFloor(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		return furniture(w.getBlockState(pos.set(x, y, z))) || indoors(w, x, y, z, pos) || roofOverRoom(w, x, y, z, pos);
	}

	private static boolean isRailOrPath(BlockState s) {
		return s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || s.is(net.minecraft.tags.BlockTags.FENCES) || s.is(net.minecraft.tags.BlockTags.WALLS)
			|| s.is(net.minecraft.tags.BlockTags.FENCE_GATES) || s.is(net.minecraft.world.level.block.Blocks.IRON_BARS);
	}

	private static String why = "", traceWhy, underWhy = "";

	private static void say(Minecraft mc, SonarConfig cfg, String r) {
		if (mc == null) { why = r; return; }
		if (cfg.debug && !r.equals(why)) Dbg.send(mc.player, net.minecraft.network.chat.Component.literal("guide: " + r));
		why = r;
	}

	/** Where the bell listens from: a player, or a point in a self-test world. */
	record Me(double x, double y, double z, double eyeY, float yaw, double vx, double vz) {
		static Me of(net.minecraft.world.entity.player.Player p) { return new Me(p.getX(), p.getY(), p.getZ(), p.getEyeY(), p.getYRot(), p.getDeltaMovement().x, p.getDeltaMovement().z); }
		int bx() { return (int) Math.floor(x); }
		int by() { return (int) Math.floor(y + 1e-6); }
		int bz() { return (int) Math.floor(z); }
		net.minecraft.world.phys.Vec3 eye() { return new net.minecraft.world.phys.Vec3(x, eyeY, z); }
	}

	/** Is there a path to follow from here, in any world (the self-test)? Runs the bell's own search; true when the bell would play. */
	static boolean findIn(net.minecraft.world.level.BlockGetter w, Me p, SonarConfig cfg) {
		have = false;
		guideCells = null;
		find(w, p, cfg, null);
		return have;
	}

	/** (mc: only for debug output and the spoken crossing; null in the self-test.) */
	private static void find(net.minecraft.world.level.BlockGetter w, Me p, SonarConfig cfg, Minecraft mc) {
		var pos = new BlockPos.MutableBlockPos();
		builtMemo.clear(); coreMemo.clear(); plazaMemo.clear();
		bestArms = 0;
		int px = p.bx(), pz = p.bz(), fy = p.by() - 1;
		// not inside a building or under an arch/roof
		// no guide inside buildings or walled areas: count the walls within 7 blocks (8 directions at head height).
		// Under a roof 3 walls is enough (a covered bridge has only 2 sides); in the open it takes 6 (a walled courtyard).
		boolean roof = Rooms.ray(w, p.eye(), 0, 1, 0, 6, true, true, false) < 6;
		int walls = 0;
		for (int i = 0; i < 8; i++) { // only BUILT walls count: the dirt or stone sides of a trench or cliff stairway are not a building
			double a = i * Math.PI / 4;
			for (int d = 1; d <= 7; d++) {
				var wp = pos.set(Math.floor(p.x() + Math.cos(a) * d), Math.floor(p.eyeY()), Math.floor(p.z() + Math.sin(a) * d));
				BlockState ws = w.getBlockState(wp);
				if (ws.getCollisionShape(w, wp).isEmpty()) continue;
				if (!FloorEdge.natural(ws)) walls++;
				break;
			}
		}
		if (Rooms.enclosed(mc, w, p.eye())) { say(mc, cfg, "off: inside a room"); return; } // the same test the echo uses: any roof-and-walls space, whatever the walls are made of
		if (walls >= (roof ? 3 : 6)) { say(mc, cfg, "off: " + (roof ? "inside a building" : "walled area") + " (" + walls + "/8 walls)"); return; }
		// candidate starts: the nearest built floor cell in each of 8 directions (and under you), within 3 blocks. Floor may be a few blocks below while jumping or on stairs.
		int[][] cand = new int[9][];
		int[] candD = new int[9];
		java.util.Arrays.fill(candD, 99);
		int nearest = -1;
		for (int dx = -NEAR; dx <= NEAR; dx++) {
			for (int dz = -NEAR; dz <= NEAR; dz++) {
				int y = floorY(w, px + dx, pz + dz, fy + 2, 6, pos);
				if (y == Integer.MIN_VALUE || Math.abs(y - fy) > 2 || !built(w, px + dx, y, pz + dz, pos) || !headroom(w, px + dx, y, pz + dz, pos) || notFloor(w, px + dx, y, pz + dz, pos) || plaza(w, px + dx, y, pz + dz, pos) || farmEdge(w, px + dx, y, pz + dz, pos)) continue;
				int d = dx * dx + dz * dz;
				int sec = (dx == 0 && dz == 0) ? 8 : Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4)), 8);
				if (d < candD[sec]) { candD[sec] = d; cand[sec] = new int[]{px + dx, pz + dz, y}; }
			}
		}
		for (int i = 0; i < 9; i++) if (cand[i] != null && (nearest < 0 || candD[i] < candD[nearest])) nearest = i;
		if (cfg.debug && cand[8] == null) { // why the block you are standing on is not path (one line, only when it changes)
			int uy = floorY(w, px, pz, fy + 2, 6, pos);
			String r = uy == Integer.MIN_VALUE ? "nothing under you" : Math.abs(uy - fy) > 2 ? "floor too far below" : !built(w, px, uy, pz, pos) ? "not a built block (" + w.getBlockState(pos.set(px, uy, pz)).getBlock().getDescriptionId().replace("block.minecraft.", "") + ")"
				: !headroom(w, px, uy, pz, pos) ? "no headroom above it" : furniture(w.getBlockState(pos.set(px, uy, pz))) ? "furniture" : indoors(w, px, uy, pz, pos) ? "counted as indoors (roof 2-5 above and walls on 3 sides)"
				: roofOverRoom(w, px, uy, pz, pos) ? "counted as the roof of a room below" : plaza(w, px, uy, pz, pos) ? "counted as part of a patio (a 5x5 of solid built floor within 2 blocks)" : farmEdge(w, px, uy, pz, pos) ? "farm edging" : "?";
			if (!r.equals(underWhy)) { underWhy = r; if (mc != null) Dbg.send(mc.player, net.minecraft.network.chat.Component.literal("guide: block under you is not path: " + r)); }
		}
		// standing ON a plaza or square (round 17, 09:17: four paths meeting at a round square, you in the middle, the paths 8 blocks off): the paths leaving it are
		// the candidates, if at least two leave it. A square with one path in (a patio) never rings
		int plazaExits = 0;
		if (nearest < 0) {
			java.util.List<int[]> ex = plazaExits(w, px, pz, fy, pos);
			plazaExits = ex.size();
			if (plazaExits >= 2) {
				java.util.Arrays.fill(candD, 99); java.util.Arrays.fill(cand, null);
				for (int[] e : ex) {
					int dx = e[0] - px, dz = e[1] - pz, sec = Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4)), 8), d = dx * dx + dz * dz;
					if (d < candD[sec]) { candD[sec] = d; cand[sec] = e; }
				}
				for (int i = 0; i < 9; i++) if (cand[i] != null && (nearest < 0 || candD[i] < candD[nearest])) nearest = i;
			}
		}
		if (nearest < 0) { say(mc, cfg, "off: no path block within " + NEAR + " of you (a patio or a square with fewer than two paths leaving it does not count)"); return; }
		int sx = cand[nearest][0], sz = cand[nearest][1];
		// follow whichever nearby path you are facing most
		traceWhy = null;
		double[] best = null;
		java.util.HashMap<Long, Integer> bestCells = null;
		for (int i = 0; i < 9; i++) {
			if (cand[i] == null) continue;
			double[] r = trace(w, p, cand[i][0], cand[i][1], cand[i][2], pos);
			if (r != null && (best == null || r[3] > best[3])) { best = r; bestCells = traceCells; bestArms = traceArms; bestSX = traceSX; bestSZ = traceSZ; }
		}
		if (best == null) { say(mc, cfg, traceWhy != null ? traceWhy : "off: no path 8-11 blocks ahead"); return; }
		if (cfg.debug && (!why.equals("on") || t % 100 == 0)) { // what the guide thinks the path is made of
			var counts = new java.util.TreeMap<String, Integer>();
			for (var e : bestCells.entrySet()) {
				long k = e.getKey();
				var bn = w.getBlockState(pos.set((int) (k >> 32), e.getValue(), (int) k)).getBlock().getDescriptionId().replace("block.minecraft.", "");
				counts.merge(bn, 1, Integer::sum);
			}
			var lg = org.slf4j.LoggerFactory.getLogger("sonar");
			lg.info("PATHMAP me {},{} y {} (digit = floor height relative to your feet, S=start, .=not path; x across, z down)", px, pz, p.by());
			for (int mz = -R; mz <= R; mz++) {
				StringBuilder row = new StringBuilder();
				for (int mx = -R; mx <= R; mx++) {
					Integer yy = bestCells.get(key(px + mx, pz + mz));
					char ch = yy == null ? '.' : (char) ('0' + Math.max(0, Math.min(9, yy - (p.by() - 1) + 4)));
					if (px + mx == sx && pz + mz == sz) ch = 'S';
					if (mx == 0 && mz == 0) ch = 'M';
					row.append(ch);
				}
				lg.info("PATHMAP {}", row);
			}
			if (mc != null) Dbg.send(mc.player, net.minecraft.network.chat.Component.literal("path made of " + bestCells.size() + " cells: " + counts + " | start " + sx + "," + sz + " arms " + bestArms + " walls " + walls + " roof " + roof));
		}
		say(mc, cfg, "on");
		guideCells = bestCells;
		tx = best[0]; ty = best[1]; tz = best[2]; endMode = best[4] > 0;
		// off the path (found from a few blocks away): the bell first leads you onto it, from the nearest path block, wherever that is
		// "off the path" is measured to the NEAREST path block, not to where the trace started: the trace may start from a cell a few blocks behind you (it is the one
		// you face most), and measuring to that put the bell behind you for a moment every few steps while running along the path
		double off = Math.sqrt(candD[nearest]);
		approach = off > 1.6;
		if (approach && plazaExits >= 2) { tx = bestSX + 0.5; tz = bestSZ + 0.5; ty = ys0(w, bestSX, bestSZ, fy, pos) + 1; endMode = false; } // on a square: to the path you face most
		else if (approach) { tx = cand[nearest][0] + 0.5; tz = cand[nearest][1] + 0.5; ty = cand[nearest][2] + 1; endMode = false; }
		if (plazaExits >= 3 && mc != null && (Math.abs(px - lastJx) + Math.abs(pz - lastJz) > 10 || t - lastJt > 400)) { // a square where 3+ paths meet is a crossing
			lastJx = px; lastJz = pz; lastJt = t;
			Speech.say(mc, net.minecraft.network.chat.Component.literal("path intersection"));
			if (cfg.debug) Dbg.send(mc.player, net.minecraft.network.chat.Component.literal("guide: path intersection (a square, " + plazaExits + " paths leave it)"));
		}
		have = true;
		lastFound = t;
		if (mc != null) junction(mc, cfg);
	}

	// ---- per-search memo for the wide-area (patio / plaza) mask ----
	private static final java.util.HashMap<Long, Boolean> builtMemo = new java.util.HashMap<>(), coreMemo = new java.util.HashMap<>(), plazaMemo = new java.util.HashMap<>();

	private static long key3(int x, int y, int z) {
		return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
	}

	/** Built floor (any material, railings excepted) at about height y in column x,z. */
	private static boolean builtFloor(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		Long k = key3(x, y, z);
		Boolean m = builtMemo.get(k);
		if (m != null) return m;
		boolean r = false;
		int fy = floorY(w, x, z, y + 1, 2, pos);
		if (fy != Integer.MIN_VALUE && Math.abs(fy - y) <= 1 && built(w, x, fy, z, pos)) {
			BlockState s = w.getBlockState(pos.set(x, fy, z));
			r = !isRailOrPath(s) || s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH);
		}
		builtMemo.put(k, r);
		return r;
	}

	/** The middle of a wide built area: 24+ of the 25 cells of the 5x5 around it are built floor. A path up to 4 wide never reaches that. */
	private static boolean core(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		Long k = key3(x, y, z);
		Boolean m = coreMemo.get(k);
		if (m != null) return m;
		int n = 0;
		for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (builtFloor(w, x + dx, y, z + dz, pos)) n++;
		coreMemo.put(k, n >= 24); // practically solid: two 3-wide paths crossing fill exactly 21 of the 25, and a 4-wide stair with its railings and landing about as many; neither is a patio
		return n >= 24;
	}

	/** Part of a patio, plaza, porch floor or platform: within 2 cells of the middle of a wide built area. The rim of a patio counts too, so the path flood can not run around its edge,
	 *  and a path that joins a patio simply ends where the patio begins. */
	private static boolean plaza(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		Long k = key3(x, y, z);
		Boolean m = plazaMemo.get(k);
		if (m != null) return m;
		boolean r = false;
		for (int dx = -2; dx <= 2 && !r; dx++) for (int dz = -2; dz <= 2 && !r; dz++) if (core(w, x + dx, y, z + dz, pos)) r = true;
		plazaMemo.put(k, r);
		return r;
	}

	/** The floor height at (x,z) near `fy`, or fy. */
	private static int ys0(net.minecraft.world.level.BlockGetter w, int x, int z, int fy, BlockPos.MutableBlockPos pos) {
		int y = floorY(w, x, z, fy + 3, 8, pos);
		return y == Integer.MIN_VALUE ? fy : y;
	}

	/** Standing on a plaza: the paths leaving it. Walks the plaza's floor (steps of a block) from under you; every built, non-plaza cell it touches is where a path
	 *  leaves. Those cells are grouped (touching cells are one path, at most 3 apart) and the nearest cell of each group returned: {x, z, y}. Empty when you are not on one. */
	static java.util.List<int[]> plazaExits(net.minecraft.world.level.BlockGetter w, int px, int pz, int fy, BlockPos.MutableBlockPos pos) {
		var out = new java.util.ArrayList<int[]>();
		int y0 = floorY(w, px, pz, fy + 2, 4, pos);
		if (y0 == Integer.MIN_VALUE || !built(w, px, y0, pz, pos) || !plaza(w, px, y0, pz, pos)) return out;
		var seen = new java.util.HashMap<Long, Integer>();
		var q = new ArrayDeque<int[]>();
		var edge = new java.util.ArrayList<int[]>();
		seen.put(key(px, pz), y0); q.add(new int[]{px, pz, y0});
		while (!q.isEmpty()) {
			int[] c = q.poll();
			for (int k = 0; k < 4; k++) {
				int nx = c[0] + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = c[1] + (k == 2 ? 1 : k == 3 ? -1 : 0);
				if (Math.abs(nx - px) > R || Math.abs(nz - pz) > R || seen.containsKey(key(nx, nz))) continue;
				int y = floorY(w, nx, nz, c[2] + 1, 3, pos);
				if (y == Integer.MIN_VALUE || Math.abs(y - c[2]) > 1 || !built(w, nx, y, nz, pos) || !headroom(w, nx, y, nz, pos) || notFloor(w, nx, y, nz, pos) || farmEdge(w, nx, y, nz, pos)) continue;
				seen.put(key(nx, nz), y);
				if (plaza(w, nx, y, nz, pos)) q.add(new int[]{nx, nz, y}); else edge.add(new int[]{nx, nz, y});
			}
		}
		// group the edge cells into paths
		boolean[] used = new boolean[edge.size()];
		for (int i = 0; i < edge.size(); i++) {
			if (used[i]) continue;
			used[i] = true;
			int[] best = edge.get(i);
			var st = new ArrayDeque<Integer>(); st.add(i);
			while (!st.isEmpty()) {
				int[] a = edge.get(st.poll());
				if ((a[0] - px) * (a[0] - px) + (a[1] - pz) * (a[1] - pz) < (best[0] - px) * (best[0] - px) + (best[1] - pz) * (best[1] - pz)) best = a;
				for (int j = 0; j < edge.size(); j++) if (!used[j] && Math.abs(edge.get(j)[0] - a[0]) <= 3 && Math.abs(edge.get(j)[1] - a[1]) <= 3) { used[j] = true; st.add(j); }
			}
			out.add(best);
		}
		return out;
	}

	/** Water, farmland or crops right beside a cell (a level below to a level above). */
	private static boolean farmSide(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos, boolean[] crop) {
		boolean any = false;
		for (int yy = y - 1; yy <= y + 1; yy++) {
			BlockState s = w.getBlockState(pos.set(x, yy, z));
			boolean c = s.is(net.minecraft.world.level.block.Blocks.FARMLAND) || s.is(net.minecraft.world.level.block.Blocks.SUGAR_CANE) || s.is(net.minecraft.tags.BlockTags.CROPS)
				|| s.is(net.minecraft.world.level.block.Blocks.BAMBOO) || s.is(net.minecraft.world.level.block.Blocks.CACTUS) || s.is(net.minecraft.world.level.block.Blocks.NETHER_WART);
			if (c) crop[0] = true;
			if (c || s.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) any = true;
		}
		return any;
	}

	/** The edging of a farm: a strip with farm on BOTH sides (water channel one side, sugar cane or crops the other). A path beside a farm has it on one side only,
	 *  and a bridge has water on both sides but no crops, so neither is mistaken for edging. */
	private static boolean farmEdge(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		boolean[] crop = {false};
		boolean ew = farmSide(w, x + 1, y, z, pos, crop) && farmSide(w, x - 1, y, z, pos, crop);
		boolean ns = farmSide(w, x, y, z + 1, pos, crop) && farmSide(w, x, y, z - 1, pos, crop);
		return (ew || ns) && crop[0];
	}

	/** Material family of a floor block, by its sound: 7 = dirt path, 1 wood, 2 metal, 3 glass, 4 wool, 5 loose ground, 6 stone and bricks.
	 *  A path is one family: a stone path never runs on into the wooden frame of a farm or a wooden porch next to it. */
	static int family(BlockState s) {
		if (s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) return 7; // dirt path is its own material: a dirt path never runs on into a farm's frame or a patio
		String p = s.getSoundType().getStepSound().location().getPath();
		if (p.contains("brick")) return 6;
		if (p.contains("wood") || p.contains("bamboo") || p.contains("stem") || p.contains("hyphae") || p.contains("scaffold")) return 1;
		if (p.contains("metal") || p.contains("copper") || p.contains("chain") || p.contains("iron") || p.contains("netherite") || p.contains("anvil")) return 2;
		if (p.contains("glass")) return 3;
		if (p.contains("wool")) return 4;
		if (p.contains("sand") || p.contains("gravel") || p.contains("grass") || p.contains("mud") || p.contains("snow") || p.contains("moss")) return 5;
		return 6;
	}

	private static boolean sameFamily(int a, int b) {
		return a == b;
	}

	/** Stepping from a cell of material `from` onto `to`: the same material always; a new material only straight on, in the way the trail is already heading (a dirt path running
	 *  onto a bridge or into stone steps), never sideways (the wooden frame of a farm, a porch or a different strip running beside the path). */
	static boolean continues(int from, int to, int d, int sx, int sz, double hx, double hz) {
		if (from == to) return true;
		if (d < 1) return false; // the start cell has no heading yet
		double hl = Math.hypot(hx, hz);
		return hl > 1e-6 && (sx * hx + sz * hz) / hl > 0.7;
	}

	/** Results of the last trace (kept for the chosen one): how many arms of real path leave the start, and where the start was. */
	private static int traceArms, bestArms;
	private static int traceSX, traceSZ, bestSX, bestSZ;

	/** Flood the connected path from a start cell and pick the way to guide: the path is split into ARMS (the ways leading away from the start, by walking distance), the arm you face most is
	 *  chosen, and the bell target is the middle of that arm 7-10 walking steps along it. Following walking distance (not straight-line distance) keeps the target ON the path through curves and
	 *  switchbacks: straight-line averaging used to land between two stretches of a bend, off the path. Returns {x, y, z, facingScore, endOfPath 0/1} or null. */
	private static double[] trace(net.minecraft.world.level.BlockGetter w, Me p, int sx, int sz, int sy, BlockPos.MutableBlockPos pos) {
		int px = p.bx(), pz = p.bz();
		int W = 2 * R + 1;
		traceArms = 0; traceSX = sx; traceSZ = sz;
		int[] dist = new int[W * W], ys = new int[W * W], par = new int[W * W];
		double[] dirx = new double[W * W], dirz = new double[W * W]; // the way the trail is heading at each cell
		// cells where the path ran on but the trace stopped only because of the MAX_RISE / MAX_FALL window: the path does NOT end there (round 16, 17:02: a long
		// stone staircase down was called the end of the path, and the bell jumped behind you as it was found again further down)
		boolean[] capped = new boolean[W * W];
		// plaza cells: the flood runs across a plaza or square, but an arm counts only if real path leaves it on the far side (a path that just runs into a patio
		// still ends at the patio's edge)
		boolean[] isPlaza = new boolean[W * W];
		java.util.Arrays.fill(dist, -1);
		var q = new ArrayDeque<int[]>();
		int si = (sz - pz + R) * W + (sx - px + R);
		if (si < 0 || si >= dist.length || Math.abs(sx - px) > R || Math.abs(sz - pz) > R) return null;
		int fam = family(w.getBlockState(pos.set(sx, sy, sz)));
		int[] fams = new int[W * W]; // each cell's own material: a path may change material where it runs straight on (a dirt path onto a bridge), never sideways onto a frame or porch
		fams[si] = fam;
		dist[si] = 0; ys[si] = sy; par[si] = -1;
		var seen = new java.util.HashMap<Long, Integer>();
		traceCells = seen;
		seen.put(key(sx, sz), sy);
		q.add(new int[]{sx, sz});
		var order = new java.util.ArrayList<Integer>();
		double yaw = Math.toRadians(p.yaw()), fx = -Math.sin(yaw), fz = Math.cos(yaw);
		dirx[si] = fx; dirz[si] = fz;
		double reach = 0; // how far from you the connected path extends, in any direction
		while (!q.isEmpty()) {
			int[] c = q.poll();
			int ci = (c[1] - pz + R) * W + (c[0] - px + R), cy = ys[ci];
			order.add(ci);
			double dl = Math.hypot(c[0] + 0.5 - p.x(), c[1] + 0.5 - p.z());
			if (dl > reach) reach = dl;
			int nbuilt = 0; // built neighbours of this cell: a sparse trail (stepping stones, scattered path blocks) has few
			for (int k = 0; k < 4; k++) {
				int nx = c[0] + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = c[1] + (k == 2 ? 1 : k == 3 ? -1 : 0);
				int ddx2 = nx - px, ddz2 = nz - pz;
				if (Math.abs(ddx2) > R || Math.abs(ddz2) > R) continue;
				int ni = (ddz2 + R) * W + (ddx2 + R);
				if (dist[ni] >= 0) { nbuilt++; continue; }
				if (dist[ni] == -2) continue;
				int y = floorY(w, nx, nz, cy + 1, 3, pos);
				if (y != Integer.MIN_VALUE && Math.abs(y - cy) <= 1 && (y - sy > MAX_RISE || sy - y > MAX_FALL) && built(w, nx, y, nz, pos)) capped[ci] = true;
				if (y == Integer.MIN_VALUE || Math.abs(y - cy) > 1 || y - sy > MAX_RISE || sy - y > MAX_FALL || !built(w, nx, y, nz, pos) || !headroom(w, nx, y, nz, pos) || notFloor(w, nx, y, nz, pos)
					|| !continues(fams[ci], family(w.getBlockState(pos.set(nx, y, nz))), dist[ci], nx - c[0], nz - c[1], dirx[ci], dirz[ci]) || farmEdge(w, nx, y, nz, pos)) { dist[ni] = -2; continue; }
				isPlaza[ni] = plaza(w, nx, y, nz, pos);
				nbuilt++;
				dist[ni] = dist[ci] + 1; ys[ni] = y; par[ni] = ci; dirx[ni] = nx - c[0]; dirz[ni] = nz - c[1]; fams[ni] = family(w.getBlockState(pos.set(nx, y, nz)));
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
							int ty = floorY(w, tx, tz, cy + 2, 6, pos);
							if (ty == Integer.MIN_VALUE || Math.abs(ty - cy) > 2 || ty - sy > MAX_RISE || sy - ty > MAX_FALL || !built(w, tx, ty, tz, pos) || !headroom(w, tx, ty, tz, pos) || notFloor(w, tx, ty, tz, pos)
								|| !sameFamily(fams[ci], family(w.getBlockState(pos.set(tx, ty, tz)))) || builtNeighbours(w, tx, ty, tz, pos) > 1) continue; // a gap is only crossed to reach another stepping stone, never the edge of a patio or building
							dist[ti] = dist[ci] + r; ys[ti] = ty; par[ti] = ci; dirx[ti] = bx / bl; dirz[ti] = bz / bl; fams[ti] = fams[ci];
							seen.put(key(tx, tz), ty);
							q.add(new int[]{tx, tz});
							added++;
						}
					}
				}
			}
		}
		// plaza cells only carry the trace across to the path beyond: they are not path themselves (the crossing speech and the map of path cells never see them)
		for (int ci : order) if (isPlaza[ci]) seen.remove(key(px - R + ci % W, pz - R + ci / W));
		{ // how long the path is end to end: the cell furthest from the start, then the cell furthest from that one. (Measuring from where you stand let 5 blocks of buried cobblestone 5 blocks away pass as a path.)
			int far = order.get(0);
			double fd = -1;
			for (int ci : order) { double d = Math.hypot(ci % W - (sx - px + R), ci / W - (sz - pz + R)); if (d > fd) { fd = d; far = ci; } }
			reach = 0;
			for (int ci : order) reach = Math.max(reach, Math.hypot(ci % W - far % W, ci / W - far / W));
		}
		if (reach < MIN_REACH) { traceWhy = "off: path too short (" + Math.round(reach) + " blocks long, needs " + (int) MIN_REACH + ")"; return null; } // a doorstep, a patch of floor, the foot of a wall
		{ // a COVERED WALKWAY along a building (a porch, a veranda) is part of the building, not a path: most of it is under a roof AND it runs along the building's own
			// floor inside. (Its floor matched the house's, so it passed as a long path.) A covered bridge has a roof too, but no building floor beside it, so it stays a path.
			int roofed = 0, n = 0;
			boolean byInterior = false;
			for (int i = 0; i < order.size(); i += 2) {
				int ci = order.get(i), cx = px - R + ci % W, cz = pz - R + ci / W, cy = ys[ci];
				n++;
				if (BlockRules.solidRoof(w, new Vec3(cx + 0.5, cy + 1.5, cz + 0.5), 6)) roofed++;
			}
			if (n > 0 && roofed > 0.6 * n) {
				for (int i = 0; i < order.size() && !byInterior; i += 2) {
					int ci = order.get(i), cx = px - R + ci % W, cz = pz - R + ci / W, cy = ys[ci];
					for (int k = 0; k < 4 && !byInterior; k++) // sideways through a wall into a room: a porch along a house
						if (roomBeside(w, cx, cy, cz, k == 0 ? 1 : k == 1 ? -1 : 0, k == 2 ? 1 : k == 3 ? -1 : 0)) byInterior = true;
				}
				if (byInterior) { traceWhy = "off: a covered walkway along a building, not a path (" + roofed + " of " + n + " spots under a roof)"; return null; }
			}
		}
		if (fam == 6) { // stone or brick: a strip of cobblestone set into a floor of natural stone is a repair or a patch, not a path. A path is laid through grass, dirt or sand.
			int border = 0, rock = 0;
			for (int ci : order) {
				int cx = px - R + ci % W, cz = pz - R + ci / W, cy = ys[ci];
				for (int k = 0; k < 4; k++) {
					int nx = cx + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = cz + (k == 2 ? 1 : k == 3 ? -1 : 0);
					if (seen.containsKey(key(nx, nz))) continue;
					int ny = floorY(w, nx, nz, cy + 1, 2, pos);
					if (ny == Integer.MIN_VALUE || Math.abs(ny - cy) > 1) continue; // a drop or a wall beside the path: not floor around it
					border++;
					BlockState bs = w.getBlockState(pos.set(nx, ny, nz));
					if (bs.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || bs.is(net.minecraft.world.level.block.Blocks.SNOW)) rock++;
				}
			}
			if (border >= 6 && rock * 2 >= border) { traceWhy = "off: stone set into a stone floor (" + rock + " of " + border + " cells beside it are natural stone): a patch, not a path"; return null; }
		}
		{ // round 17 (09:16): the border of stairs along a stone walkway beside a big deck passed as a path (the walkway itself is a plaza: 5x5 solid built floor with
			// the deck). Where the trace touches a plaza, real path must lead AWAY from it: 6+ path cells more than 3 blocks from every plaza cell. A path crossing a
			// square or running into a patio does; the rim of a platform never gets clear of it
			var pl = new java.util.ArrayList<Integer>();
			for (int ci : order) if (isPlaza[ci]) pl.add(ci);
			if (!pl.isEmpty()) {
				int clear = 0;
				for (int ci : order) {
					if (isPlaza[ci]) continue;
					boolean near = false;
					for (int pi : pl) if (Math.abs(pi % W - ci % W) <= 3 && Math.abs(pi / W - ci / W) <= 3) { near = true; break; }
					if (!near && ++clear >= 6) break;
				}
				if (clear < 6) { traceWhy = "off: the rim of a platform or patio, not a path (" + clear + " path cells clear of it)"; return null; }
			}
		}
		// arms: the first cells 3+ walking steps out, grouped when they lie within 2 blocks of each other; every cell further out belongs to the arm it was reached through
		int[] arm = new int[W * W];
		java.util.Arrays.fill(arm, -1);
		var roots = new java.util.ArrayList<Integer>();
		for (int ci : order) if (dist[ci] >= 3 && par[ci] >= 0 && dist[par[ci]] < 3) roots.add(ci);
		int arms = 0;
		for (int i = 0; i < roots.size(); i++) {
			int ri = roots.get(i);
			if (arm[ri] >= 0) continue;
			arm[ri] = arms;
			var stack = new ArrayDeque<Integer>();
			stack.add(ri);
			while (!stack.isEmpty()) {
				int a = stack.poll();
				for (int rj : roots) {
					if (arm[rj] >= 0) continue;
					if (Math.abs(rj % W - a % W) <= 2 && Math.abs(rj / W - a / W) <= 2) { arm[rj] = arms; stack.add(rj); }
				}
			}
			arms++;
		}
		for (int ci : order) if (arm[ci] < 0 && par[ci] >= 0 && arm[par[ci]] >= 0) arm[ci] = arm[par[ci]];
		int[] armMax = new int[arms];
		boolean[] armCapped = new boolean[arms], armReal = new boolean[arms];
		for (int ci : order) if (arm[ci] >= 0 && capped[ci]) armCapped[arm[ci]] = true;
		for (int ci : order) if (arm[ci] >= 0 && !isPlaza[ci]) armReal[arm[ci]] = true;
		double[] armReach = new double[arms]; // straight-line reach from the start cell
		for (int ci : order) {
			int a = arm[ci];
			if (a < 0) continue;
			if (!isPlaza[ci]) armMax[a] = Math.max(armMax[a], dist[ci]); // (how far real path runs: plaza cells alone are not a way on)
			armReach[a] = Math.max(armReach[a], Math.hypot(ci % W - (sx - px + R), ci / W - (sz - pz + R)));
		}
		for (int a = 0; a < arms; a++) if (armReal[a] && armMax[a] >= 7) traceArms++;
		// the target in each arm: the middle of its cells 7-10 steps out (several groups there when the arm splits again: the one you face most), or its far end when it is shorter
		double[] best = null, endAhead = null;
		int bestArm = -1;
		for (int a = 0; a < arms; a++) {
			if (!armReal[a]) continue; // only plaza that way: no path leaves it
			boolean end = armMax[a] < 10 && !armCapped[a];
			int lo = end ? Math.max(3, armMax[a] - 1) : 7, hi = end ? armMax[a] : 10;
			var win = new java.util.ArrayList<Integer>();
			for (int ci : order) if (arm[ci] == a && !isPlaza[ci] && dist[ci] >= lo && dist[ci] <= hi) win.add(ci);
			if (win.isEmpty() && !end) { // across a plaza: the path where it leaves the far side (its first real cells 7+ steps out)
				int first = Integer.MAX_VALUE;
				for (int ci : order) if (arm[ci] == a && !isPlaza[ci] && dist[ci] >= 7) first = Math.min(first, dist[ci]);
				for (int ci : order) if (arm[ci] == a && !isPlaza[ci] && dist[ci] >= first && dist[ci] <= first + 3) win.add(ci);
			}
			if (win.isEmpty()) continue;
			int[] grp = new int[win.size()];
			java.util.Arrays.fill(grp, -1);
			int ng = 0;
			for (int i = 0; i < win.size(); i++) {
				if (grp[i] >= 0) continue;
				grp[i] = ng;
				var st = new ArrayDeque<Integer>();
				st.add(i);
				while (!st.isEmpty()) {
					int u = st.poll(), cu = win.get(u);
					for (int v = 0; v < win.size(); v++) {
						if (grp[v] >= 0) continue;
						int cv = win.get(v);
						if (Math.abs(cv % W - cu % W) <= 2 && Math.abs(cv / W - cu / W) <= 2) { grp[v] = ng; st.add(v); }
					}
				}
				ng++;
			}
			for (int g = 0; g < ng; g++) {
				double gx = 0, gy = 0, gz = 0;
				int n = 0;
				for (int i = 0; i < win.size(); i++) {
					if (grp[i] != g) continue;
					int ci = win.get(i);
					gx += px - R + ci % W + 0.5; gz += pz - R + ci / W + 0.5; gy += ys[ci] + 1; n++;
				}
				gx /= n; gy /= n; gz /= n;
				double ox = gx - p.x(), oz = gz - p.z(), ol = Math.max(1e-6, Math.hypot(ox, oz));
				double score = (ox * fx + oz * fz) / ol;
				if (ol < 2.5) { // a path end right under you is nothing to guide to; but if it lies ahead, you are at the end of the path you are following
					if (end && score > 0.5 && (endAhead == null || score > endAhead[3])) endAhead = new double[]{gx, gy, gz, score, 1};
					continue;
				}
				if (best == null || score > best[3]) { best = new double[]{gx, gy, gz, score, end ? 1 : 0}; bestArm = a; }
			}
		}
		// round 16 (pale-oak bridge): the last few blocks of the path ahead were under 2.5 away, so the only arm left was the one BEHIND you and the bell swung round
		// behind your head. When the way you face ends right ahead and every way on is BEHIND you, that is the end of the path (facing across a wide path is not: round 17, a T-junction): the end, ahead of you (at least 2.5 out, the way it lies)
		if (endAhead == null && (best == null || best[3] < -0.3)) // the last block or two ahead (too near to make an arm): the furthest of them the way you face
			for (int ci : order) {
				if (arm[ci] >= 0 || dist[ci] < 1) continue;
				double gx = px - R + ci % W + 0.5, gz = pz - R + ci / W + 0.5, ox = gx - p.x(), oz = gz - p.z(), ol = Math.hypot(ox, oz);
				if (ol < 0.8) continue;
				double score = (ox * fx + oz * fz) / ol;
				if (score > 0.5 && (endAhead == null || ox * fx + oz * fz > (endAhead[0] - p.x()) * fx + (endAhead[2] - p.z()) * fz)) endAhead = new double[]{gx, ys[ci] + 1, gz, score, 1};
			}
		if (endAhead != null && (best == null || best[3] < -0.3)) {
			double ox = endAhead[0] - p.x(), oz = endAhead[2] - p.z(), ol = Math.max(1e-6, Math.hypot(ox, oz)), k = Math.max(1, 2.5 / ol);
			return new double[]{p.x() + ox * k, endAhead[1], p.z() + oz * k, endAhead[3], 1};
		}
		if (best == null) { traceWhy = "off: the path ends here"; return null; }
		// a blob of path blocks (a repaired patch, a small square) is not a path: the arm must run on past 11 blocks, or be clearly longer than it is wide
		if (armReach[bestArm] < 11) {
			double mx = 0, mz = 0;
			int n = 0;
			for (int ci : order) if ((arm[ci] == bestArm || dist[ci] < 3) && dist[ci] <= 10) { mx += ci % W; mz += ci / W; n++; }
			mx /= n; mz /= n;
			double cxx = 0, czz = 0, cxz = 0;
			for (int ci : order) if ((arm[ci] == bestArm || dist[ci] < 3) && dist[ci] <= 10) { double ax = ci % W - mx, az = ci / W - mz; cxx += ax * ax; czz += az * az; cxz += ax * az; }
			cxx /= n; czz /= n; cxz /= n;
			double tr = (cxx + czz) / 2, dd = Math.sqrt((cxx - czz) * (cxx - czz) / 4 + cxz * cxz);
			double ratio = Math.sqrt((tr + dd) / Math.max(0.05, tr - dd));
			if (ratio < 2.0) { traceWhy = String.format("off: a patch, not a path (%.1f long for each block wide)", ratio); return null; }
		}
		double[] lead = centreLead(p, order, dist, arm, ys, W, px, pz, armMax[bestArm], bestArm, best);
		// the lead on the centre line, unless it has come in under 2.5 blocks from you (near a path's end): then the target found above (2.5+ out) stands
		if (lead != null && Math.hypot(lead[0] - p.x(), lead[2] - p.z()) >= 2.5) { best[0] = lead[0]; best[1] = lead[1]; best[2] = lead[2]; }
		return best;
	}

	/** Spoken once per place: "path intersection" where 3 or more ways meet, "path turns left/right" at a sharp corner (60 degrees or more). Not again for the same place until you have walked
	 *  10 blocks away from it. */
	private static int lastJx = Integer.MIN_VALUE, lastJz, lastJt = -10000;
	private static boolean approach;
	/** The bell's little tune (pitch steps of a rising major arpeggio), and where it is in it. */
	static final float[] BELL_TUNE = {2.0f, 1.78f, 2.0f, 1.68f}; // high, dips a little, back, dips a little further (picked by ear from samples)
	/** The bell's sound: the amethyst chime with its harshest top filtered off (it is played high, where the top end grated). */
	static final net.minecraft.sounds.SoundEvent BELL_CHIME = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.fromNamespaceAndPath("sonar", "chime_soft"));
	private static int blipWait, blipN, lastTry;
	private static double lastBa = Double.NaN, lastBd; // the last ring's direction (from your facing) and distance from you

	/** A new world or dimension: no path, no bell. */
	static void reset() {
		have = false; approach = false; endMode = false;  guideCells = null; traceCells = null; lastBa = Double.NaN;
	}

	/** The ways leaving one path cell: walking distance over the traced path, the cells 4-5 steps out grouped into separate ways (they do not touch), each kept only if it runs on to 10+ steps, is more than a sliver, and does not head the same way (within 45 degrees) as another.
	 *  Returns {number of ways, then the x,z direction to each way's cells 6-8 steps out}. */
	static double[] ways(java.util.Map<Long, Integer> cells, int cx, int cz) {
		var dist = new java.util.HashMap<Long, Integer>();
		var par = new java.util.HashMap<Long, Long>();
		var q = new ArrayDeque<Long>();
		var ord = new java.util.ArrayList<Long>();
		long c0 = key(cx, cz);
		if (!cells.containsKey(c0)) return new double[]{0};
		dist.put(c0, 0); q.add(c0);
		while (!q.isEmpty()) {
			long c = q.poll();
			ord.add(c);
			int d = dist.get(c), x = (int) (c >> 32), z = (int) c;
			if (d >= 12) continue;
			for (int k = 0; k < 4; k++) {
				long n = key(x + (k == 0 ? 1 : k == 1 ? -1 : 0), z + (k == 2 ? 1 : k == 3 ? -1 : 0));
				if (!cells.containsKey(n) || dist.containsKey(n)) continue;
				dist.put(n, d + 1); par.put(n, c); q.add(n);
			}
		}
		var grp = new java.util.HashMap<Long, Integer>();
		int ng = 0;
		for (long c : ord) {
			int d = dist.get(c);
			if (d < 4 || d > 5 || grp.containsKey(c)) continue;
			var st = new ArrayDeque<Long>();
			st.add(c); grp.put(c, ng);
			while (!st.isEmpty()) {
				long u = st.poll();
				int ux = (int) (u >> 32), uz = (int) u;
				for (int ax = -1; ax <= 1; ax++) for (int az = -1; az <= 1; az++) {
					long v = key(ux + ax, uz + az);
					Integer dv = dist.get(v);
					if (dv == null || dv < 4 || dv > 5 || grp.containsKey(v)) continue;
					grp.put(v, ng); st.add(v);
				}
			}
			ng++;
		}
		for (long c : ord) if (!grp.containsKey(c) && dist.get(c) > 5) { Integer g = grp.get(par.get(c)); if (g != null) grp.put(c, g); }
		int[] max = new int[ng];
		double[] sx = new double[ng], sz = new double[ng], nx2 = new double[ng], nz2 = new double[ng];
		int[] n = new int[ng];
		for (var e : grp.entrySet()) {
			int g = e.getValue(), d = dist.get(e.getKey());
			max[g] = Math.max(max[g], d);
			if (d >= 6 && d <= 8) { sx[g] += (int) (e.getKey() >> 32) - cx; sz[g] += (int) (long) e.getKey() - cz; n[g]++; }
			if (d >= 3 && d <= 5) { nx2[g] += (int) (e.getKey() >> 32) - cx; nz2[g] += (int) (long) e.getKey() - cz; }
		}
		java.util.ArrayList<Double> out = new java.util.ArrayList<>(), near = new java.util.ArrayList<>();
		java.util.ArrayList<double[]> kept = new java.util.ArrayList<>();
		for (int g = 0; g < ng; g++) {
			// a way has to run on: 10+ steps, and be more than a single-cell sliver (a notch or a widening on the side of a wide path is not a way)
			if (max[g] < 10 || n[g] < 2) continue;
			double l = Math.max(1e-6, Math.hypot(sx[g], sz[g])), l2 = Math.max(1e-6, Math.hypot(nx2[g], nz2[g]));
			double[] wv = {sx[g] / l, sz[g] / l, nx2[g] / l2, nz2[g] / l2};
			boolean same = false; // two "ways" heading off within 45 degrees of each other are one wide way split by a bump
			for (double[] k : kept) if (k[0] * wv[0] + k[1] * wv[1] > 0.707) same = true;
			if (same) continue;
			kept.add(wv);
			out.add(wv[0]); out.add(wv[1]);
			near.add(wv[2]); near.add(wv[3]);
		}
		out.addAll(near); // {number of ways, far directions (6-8 steps out) of each, then near directions (3-5 steps out) of each}
		double[] r = new double[1 + out.size()];
		r[0] = near.size() / 2;
		for (int i = 0; i < out.size(); i++) r[i + 1] = out.get(i);
		return r;
	}

	/** Every path cell around you within 10 blocks, whatever it is made of: a dirt path meeting a stone path is still a crossing. Same floor tests as the bell (no patios, farms, fence tops). */
	private static java.util.Map<Long, Integer> anyPath(net.minecraft.world.level.BlockGetter w, int px, int pz, int fy) {
		var pos = new BlockPos.MutableBlockPos();
		var cells = new java.util.HashMap<Long, Integer>();
		var q = new ArrayDeque<int[]>();
		for (int r = 0; r <= 3 && q.isEmpty(); r++) // the path cell nearest you (up to 3 blocks off it)
			for (int dx = -r; dx <= r && q.isEmpty(); dx++) for (int dz = -r; dz <= r && q.isEmpty(); dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
				int y = floorY(w, px + dx, pz + dz, fy + 2, 4, pos);
				if (y != Integer.MIN_VALUE && pathCell(w, px + dx, y, pz + dz, pos)) { cells.put(key(px + dx, pz + dz), y); q.add(new int[]{px + dx, pz + dz, y}); }
			}
		while (!q.isEmpty()) {
			int[] c = q.poll();
			for (int k = 0; k < 4; k++) {
				int nx = c[0] + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = c[1] + (k == 2 ? 1 : k == 3 ? -1 : 0);
				if (Math.abs(nx - px) > 10 || Math.abs(nz - pz) > 10 || cells.containsKey(key(nx, nz))) continue;
				int y = floorY(w, nx, nz, c[2] + 1, 3, pos);
				if (y == Integer.MIN_VALUE || Math.abs(y - c[2]) > 1 || !pathCell(w, nx, y, nz, pos)) continue;
				cells.put(key(nx, nz), y);
				q.add(new int[]{nx, nz, y});
			}
		}
		return cells;
	}

	private static boolean pathCell(net.minecraft.world.level.BlockGetter w, int x, int y, int z, BlockPos.MutableBlockPos pos) {
		return built(w, x, y, z, pos) && headroom(w, x, y, z, pos) && !notFloor(w, x, y, z, pos) && !plaza(w, x, y, z, pos) && !farmEdge(w, x, y, z, pos);
	}

	private static void junction(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		var world = Sense.hear(mc);
		if (lastJx != Integer.MIN_VALUE && Math.hypot(p.getX() - lastJx, p.getZ() - lastJz) > 10) lastJx = Integer.MIN_VALUE;
		if (guideCells == null || lastJx != Integer.MIN_VALUE || t - lastJt < 60) return; // (also while being led onto a path: standing just off a crossing, such as on a gazebo floor, still hears it)
		double yaw = Math.toRadians(p.getYRot()), fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		int px = p.getBlockX(), pz = p.getBlockZ();
		var cells = anyPath(world, px, pz, p.getBlockY() - 1);
		// a crossing is announced from up to 2 blocks before it; a corner only once you are at it (on a gentle bend or a curving stair nothing is said)
		int bestN = 0, jx = 0, jz = 0;
		double bestTurn = 0, turnSide = 0;
		for (int ax = -2; ax <= 2; ax++) for (int az = -2; az <= 2; az++) {
			int cx = px + ax, cz = pz + az;
			if ((ax * fx + az * fz) < -0.5 || !cells.containsKey(key(cx, cz))) continue; // not behind you
			double[] w = ways(cells, cx, cz);
			int n = (int) w[0];
			double turn = 0, side = 0;
			if (n == 2 && Math.max(Math.abs(ax), Math.abs(az)) <= 1 && w.length >= 9) {
				double dot = w[1] * w[3] + w[2] * w[4];
				// a real corner has two straight legs: each way points the same 3-5 steps out as 6-8 steps out. A curve keeps turning, so its legs are not straight.
				boolean straightLegs = w[5] * w[1] + w[6] * w[2] > 0.94 && w[7] * w[3] + w[8] * w[4] > 0.94;
				if (straightLegs) turn = 180 - Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
				int out = (w[1] * fx + w[2] * fz) >= (w[3] * fx + w[4] * fz) ? 1 : 3; // the way on is the one more ahead of you
				side = w[out] * rx + w[out + 1] * rz;
			}
			if (n > bestN || (n == bestN && n == 2 && turn > bestTurn)) { bestN = n; bestTurn = turn; turnSide = side; jx = cx; jz = cz; }
		}
		String say = bestN >= 3 ? "path intersection" : null; // turns are no longer announced: the bell already leads you round them
		if (say == null) return;
		lastJx = jx; lastJz = jz; lastJt = t;
		Speech.say(mc, net.minecraft.network.chat.Component.literal(say));
		if (cfg.debug) Dbg.send(p, net.minecraft.network.chat.Component.literal("guide: " + say + " at " + jx + "," + jz + " (" + bestN + " ways" + (bestN == 2 ? Dbg.fmt(", %.0f deg turn", bestTurn) : "") + ")"));
	}

	public static void tick(Minecraft mc, SonarConfig cfg) {
		if (!cfg.enabled || !cfg.pathGuide || mc.level == null || mc.player == null || mc.player.isInWater() || mc.player.isSpectator()) { have = false; guideCells = null;  return; }
		t++;
		var p0 = mc.player;
		boolean moved = p0.getBlockX() != lbx || p0.getBlockY() != lby || p0.getBlockZ() != lbz || Math.abs(net.minecraft.util.Mth.wrapDegrees(p0.getYRot() - lyaw)) > 6;
		// re-trace only when you moved or turned (or the last result is getting old); idle with no path is re-checked every second
		if ((have ? t % 5 == 1 : t % 2 == 1) && (moved || (have ? t - lastFound > 25 : t % 20 == 1))) {
			lbx = p0.getBlockX(); lby = p0.getBlockY(); lbz = p0.getBlockZ(); lyaw = p0.getYRot();
			lastTry = t;
			find(Sense.hear(mc), Me.of(mc.player), cfg, mc);
		}
		// the path is lost only when a search has run since it was last found and found nothing for a moment (brief gaps on stairs or jumps keep the last bearing).
		// Standing still the search is not rerun (nothing changed): the path is still there, so the bell keeps playing. (It used to time out every 8 ticks and
		// wait for the next search, 25 ticks apart: the bell seemed to slow right down whenever you stopped.)
		if (lastTry > lastFound && t - lastFound > Tuning.GRACE_BELL_GAP) have = false;
		// the bell icon: every tick, on the middle of the path ahead of you (the spot the bell leads to, 7-10 steps along the path, which moves on as you walk),
		// floating level at your eye height; it only waits for the ring to play the sound
		if (have && !Double.isNaN(tx)) Markers.showAt(mc, "bell", net.minecraft.world.item.Items.BELL, tx, ty, tz);
		if (!have) {  return; }
		var p = mc.player;
		// the bell plays CONTINUOUSLY from where it leads, worked out every tick (an intermittent ring was hard to follow)
		// exaggerate the sideways angle (x2.5) so a small drift off the path is clearly heard left or right
		double yaw = Math.toRadians(p.getYRot()), fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double dx = tx - p.getX(), dz = tz - p.getZ();
		double ang = Math.atan2(dx * rx + dz * rz, dx * fx + dz * fz);
		if (!approach && Math.abs(ang) > Math.toRadians(100)) { say(mc, cfg, "silent: target is behind you (" + Math.round(Math.toDegrees(ang)) + " deg)");  return; } // never guide backwards
		// small drifts are exaggerated x2.5, but the bell never moves more than ~25 degrees off straight ahead, so a path that bends away still sounds in front
		// (Nov 3: "it should always stay in front of me", and after this: "that's a lot better". Keep it.)
		double cap = Math.toRadians(25);
		double a2 = approach ? ang : cap * Math.tanh(ang * 2.5 / cap), dist = approach ? Math.min(10.5, Math.max(2.0, Math.hypot(dx, dz))) : Math.min(15.0, Math.max(4.0, Math.hypot(dx, dz))); // leading you onto a path: its real direction, from all round
		// glide in YOUR frame (its direction and distance from you), a little each tick: in the world the old spot is already behind you when you run
		if (!Double.isNaN(lastBa) && Math.abs(a2 - lastBa) < Math.toRadians(40) && Math.abs(dist - lastBd) < 6) { a2 = Smooth.toward(lastBa, a2, 0.2); dist = Smooth.toward(lastBd, dist, 0.2); }
		lastBa = a2; lastBd = dist;
		double px = p.getX() + dist * (Math.cos(a2) * fx + Math.sin(a2) * rx), pz = p.getZ() + dist * (Math.cos(a2) * fz + Math.sin(a2) * rz);
		if (cfg.debug && t % 12 == 0) Dbg.send(p, net.minecraft.network.chat.Component.literal(Dbg.fmt("bell: me %.0f,%.0f yaw %.0f | %s target %.0f,%.0f (%.0f blocks, %.0f deg) | sound %.0f,%.0f", p.getX(), p.getZ(), p.getYRot(), endMode ? "END-OF-PATH" : approach ? "TO-THE-PATH" : "ahead", tx, tz, Math.hypot(dx, dz), Math.toDegrees(ang), px, pz)));
		// THE BELL: soft high chimes (the jingle's amethyst chime, an octave up and with its sharp top filtered off) in a gentle little tune, four a second; each
		// from wherever the bell leads at that moment. A blip waits a tick or two while a sonar ping or an echo click plays, so it never covers them.
		if (++blipWait < Tuning.BELL_BLIP_TICKS || !Timeline.free() && blipWait < Tuning.BELL_BLIP_TICKS + 3) return;
		blipWait = 0;
		float note = BELL_TUNE[blipN++ % BELL_TUNE.length] * (approach ? 0.84f : 1f); // a little lower while leading you onto the path (the game plays 2.0 at most)
		float vol = bellVolume(cfg.volume, cfg.guideVolume);
		vol *= (float) Loud.guideFacing(Math.abs(a2)); // the guides' direction rule: quieter to the side
		Cues.play(mc, BELL_CHIME, vol, note, px, ty + 0.5, pz);
	}

	/** The bell's loudness for the master and path-guide sliders. The game plays nothing louder than 1.0, so BELL_LEVEL stays low enough (the
	 *  chime_soft file itself is loud) that every step of the slider, up to 200%, is still louder than the one before. */
	static float bellVolume(float master, float guide) {
		return Math.min(1f, master * guide * Tuning.BELL_LEVEL * Loud.level(BELL_CHIME));
	}

	/**
	 * THE CENTRE LINE of the chosen way, and the bell's LEAD on it. Centre line: at each walking step d along the way, the middle of the path's cells that far along (on a
	 * 3-wide path the cells d steps from you lie across its width, so their middle is the middle of the path); where it forks further on, it follows the branch toward the
	 * chosen way. Lead: as far along that line as BELL_LEAD blocks (more when you run), but only as far as a straight line from you to it stays over the middle of the
	 * path (every centre point before it within a block of that line). On a straight path it is far ahead; round a curve it comes in close by itself.
	 * Returns {x, y, z} of the lead, or null to keep the old target.
	 */
	private static double[] centreLead(Me p, java.util.List<Integer> order, int[] dist, int[] arm, int[] ys, int W, int px, int pz, int max, int bestArm, double[] best) {
		var line = new java.util.ArrayList<double[]>();
		double prevX = p.x(), prevZ = p.z();
		for (int d = 1; d <= max; d++) {
			var at = new java.util.ArrayList<Integer>();
			for (int ci : order) if (dist[ci] == d && (arm[ci] == bestArm || d < 3)) at.add(ci);
			if (at.isEmpty()) continue;
			int[] grp = new int[at.size()];
			java.util.Arrays.fill(grp, -1);
			int ng = 0;
			for (int i = 0; i < at.size(); i++) {
				if (grp[i] >= 0) continue;
				grp[i] = ng;
				var st = new ArrayDeque<Integer>();
				st.add(i);
				while (!st.isEmpty()) {
					int u = st.poll(), cu = at.get(u);
					for (int v = 0; v < at.size(); v++) if (grp[v] < 0 && Math.abs(at.get(v) % W - cu % W) <= 1 && Math.abs(at.get(v) / W - cu / W) <= 1) { grp[v] = ng; st.add(v); }
				}
				ng++;
			}
			double[] pick = null;
			double pickScore = Double.MAX_VALUE;
			for (int g = 0; g < ng; g++) {
				double gx = 0, gy = 0, gz = 0;
				int n = 0;
				for (int i = 0; i < at.size(); i++) if (grp[i] == g) { int ci = at.get(i); gx += px - R + ci % W + 0.5; gz += pz - R + ci / W + 0.5; gy += ys[ci] + 1; n++; }
				gx /= n; gy /= n; gz /= n;
				double sc = Math.hypot(gx - prevX, gz - prevZ) + 0.5 * Math.hypot(gx - best[0], gz - best[2]); // carry on from the last point, toward the chosen way
				if (sc < pickScore) { pickScore = sc; pick = new double[]{gx, gy, gz}; }
			}
			line.add(pick);
			prevX = pick[0]; prevZ = pick[2];
		}
		return leadOnLine(smooth(line), p.x(), p.z(), Math.hypot(p.vx(), p.vz()) * 20);
	}

	/**
	 * The centre line smoothed: each point averaged with the two before and after it, so a ragged edge (a missing block, an uneven border) does not bend the line.
	 * Real bends are much longer than 5 points and survive the averaging.
	 */
	static java.util.List<double[]> smooth(java.util.List<double[]> line) {
		var out = new java.util.ArrayList<double[]>(line.size());
		for (int i = 0; i < line.size(); i++) {
			double x = 0, y = 0, z = 0;
			int n = 0;
			int h = Math.min(2, Math.min(i, line.size() - 1 - i)); // a window centred on the point: at the ends it narrows (a one-sided average pulled the line's end back toward you)
			for (int j = i - h; j <= i + h; j++) { x += line.get(j)[0]; y += line.get(j)[1]; z += line.get(j)[2]; n++; }
			out.add(new double[]{x / n, y / n, z / n});
		}
		return out;
	}

	/** The lead on a centre line (points in order, {x, y, z}) for someone at mx,mz moving at `speed` blocks a second; null when the line is empty or the lead is under you. */
	static double[] leadOnLine(java.util.List<double[]> line, double mx, double mz, double speed) {
		if (line.isEmpty()) return null;
		double reach = Tuning.BELL_LEAD + Math.min(Tuning.BELL_LEAD_MAX, Math.max(0, speed - 4.5) * Tuning.BELL_LEAD_PER_SPEED);
		int j0 = 0;
		double jd = Double.MAX_VALUE;
		for (int i = 0; i < line.size(); i++) { double d = Math.hypot(line.get(i)[0] - mx, line.get(i)[2] - mz); if (d < jd) { jd = d; j0 = i; } }
		double[] lead = line.get(j0);
		double along = 0, leadAlong = 0;
		boolean cut = false;
		for (int k = j0 + 1; k < line.size(); k++) {
			along += Math.hypot(line.get(k)[0] - line.get(k - 1)[0], line.get(k)[2] - line.get(k - 1)[2]);
			if (along > reach) break;
			if (!cut) for (int j = j0 + 1; j < k && !cut; j++) if (segDist(line.get(j)[0], line.get(j)[2], mx, mz, line.get(k)[0], line.get(k)[2]) > 1.0) cut = true;
			// never closer than BELL_MIN_LEAD along the path, even right at a turn: a bell beside your ear says nothing about the way (and falling back to an older,
			// further target made it flip between the two)
			if (cut && leadAlong >= Tuning.BELL_MIN_LEAD) break;
			lead = line.get(k);
			leadAlong = along;
		}
		return lead;
	}

	/** Distance from point (px,pz) to the segment a-b. */
	static double segDist(double px, double pz, double ax, double az, double bx, double bz) {
		double vx = bx - ax, vz = bz - az, l2 = vx * vx + vz * vz;
		double k = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / l2));
		return Math.hypot(px - (ax + vx * k), pz - (az + vz * k));
	}
}
