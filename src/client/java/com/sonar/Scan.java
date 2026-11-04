package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.client.multiplayer.ClientLevel;

/** One time-sliced scan. Collects the nearest block per sector and category; Cues turns it into sound. */
public class Scan {
	public static final int YAW = 16, SECTORS = YAW * 3;
	public static final int HAZARD = 0, SOLID = 1, TACTILE = 2, WATER = 3;

	// scratch buffers for the ledge flood-fill, reused between runs (the watcher runs every few ticks)
	private static int[] bufFloor = new int[0], bufQueue = new int[0];
	private static boolean[] bufSeen = new boolean[0];
	private static Scan pool;

	/** A reusable ledge-only scan for the continuous ledge watcher, so no scan objects are allocated every few ticks. */
	public static Scan ledgeScan(Minecraft mc, SonarConfig cfg) {
		if (pool == null || pool.level != mc.level || pool.cfg != cfg) {
			pool = new Scan(mc, cfg, false);
			pool.ledgeOnly = true;
		} else {
			pool.refresh(mc);
		}
		return pool;
	}

	private void refresh(Minecraft mc) {
		var p = mc.player;
		ex = p.getX(); ey = p.getEyeY(); ez = p.getZ();
		px = p.getBlockX(); pz = p.getBlockZ();
		feetY = p.getBlockY();
		yaw = p.getYRot();
		double r = Math.toRadians(yaw);
		fx = -Math.sin(r); fz = Math.cos(r);
		for (Ledge l : ledges) { l.d = Float.MAX_VALUE; l.deep = false; l.drop = 0; }
		for (Ledge l : drops) { l.d = Float.MAX_VALUE; l.drop = 0; }
		chunkKey = Long.MIN_VALUE;
		chunk = null;
		done = false;
	}

	public static final class Cand {
		public float d = Float.MAX_VALUE;
		public int x, y, z;
		public BlockState state;
	}

	public static final class Ledge {
		public float d = Float.MAX_VALUE;
		public int x, y, z;
		public boolean deep;
		public int drop;
	}

	public final Cand[][] best = new Cand[4][SECTORS];
	public final Ledge[] ledges = new Ledge[YAW];
	public final Ledge[] drops = new Ledge[YAW]; // walkable steps down of 2+ blocks (too small to warn about, but worth a brake)
	public final boolean front;
	public final int range;
	public double ex, ey, ez;
	public int feetY, px, pz;
	public float yaw;
	public boolean done;
	public boolean ledgeOnly;
	public boolean withEcho = true;
	public boolean auto; // started by the auto-scan timer, not by a key press
	public Boolean inside; // cached by Cues.insideSpace
	public boolean doorsSearched;
	public final java.util.List<double[]> doors = new java.util.ArrayList<>(); // doors in sight within range: {bearing (atan2 z,x), distance}
	public long nanos;

	private final net.minecraft.world.entity.Entity viewer;
	private net.minecraft.world.level.block.Block floorBlock;
	private final ClientLevel level;
	private final SonarConfig cfg;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private final BlockPos.MutableBlockPos nb = new BlockPos.MutableBlockPos();
	private int dy;
	private double fx, fz;
	private long chunkKey = Long.MIN_VALUE;
	private LevelChunk chunk;

	public Scan(Minecraft mc, SonarConfig cfg, boolean front) {
		this.cfg = cfg;
		this.front = front;
		this.range = front ? cfg.frontRange : cfg.range;
		this.level = mc.level;
		this.viewer = mc.player;
		var p = mc.player;
		ex = p.getX(); ey = p.getEyeY(); ez = p.getZ();
		px = p.getBlockX(); pz = p.getBlockZ();
		feetY = p.getBlockY();
		yaw = p.getYRot();
		double r = Math.toRadians(yaw);
		fx = -Math.sin(r); fz = Math.cos(r);
		for (var a : best) for (int i = 0; i < SECTORS; i++) a[i] = new Cand();
		for (int i = 0; i < YAW; i++) { ledges[i] = new Ledge(); drops[i] = new Ledge(); }
		dy = -range;
		for (int fy = feetY - 1; fy >= feetY - 3 && floorBlock == null; fy--) {
			BlockState fs = get(px, fy, pz);
			if (fs != null && !fs.isAir() && !(fs.getBlock() instanceof LiquidBlock)) floorBlock = fs.getBlock();
		}
	}

	private BlockState get(int x, int y, int z) {
		long k = ChunkPos.pack(x >> 4, z >> 4);
		if (k != chunkKey) {
			chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
			chunkKey = k;
		}
		if (chunk == null || y < level.getMinY() || y >= level.getMaxY()) return null;
		return chunk.getBlockState(nb.set(x, y, z));
	}

	/** Plants, torches and other small decor never ping. */
	private boolean ignored(BlockState s) {
		return s.is(Blocks.PINK_PETALS) || s.is(Blocks.CHERRY_LEAVES) || s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.CROPS)
			|| s.is(BlockTags.WALL_SIGNS) || s.is(BlockTags.STANDING_SIGNS) || s.is(BlockTags.BUTTONS)
			|| s.getShape(level, pos).bounds().getSize() < 0.6;
	}

	/** Ground you could actually stand on or land on: leaves are solid for sound, but a canopy is not a floor, so a drop is measured down to the ground beneath it. */
	private boolean floorSolid(BlockState s, int x, int y, int z) {
		return solid(s, x, y, z) && !s.is(BlockTags.LEAVES);
	}

	private boolean solid(BlockState s, int x, int y, int z) {
		return !s.getCollisionShape(level, pos.set(x, y, z)).isEmpty();
	}

	/** A face is visible if the neighbor covers at most half of it (so path blocks, slabs etc. do not hide it). */
	private boolean open(int x, int y, int z, net.minecraft.core.Direction touching) {
		BlockState s = get(x, y, z);
		if (s == null) return false;
		if (s.isAir()) return true;
		var shape = s.getFaceOcclusionShape(touching);
		if (shape.isEmpty()) return true;
		var b = shape.bounds();
		double area = switch (touching.getAxis()) {
			case X -> b.getYsize() * b.getZsize();
			case Y -> b.getXsize() * b.getZsize();
			case Z -> b.getXsize() * b.getYsize();
		};
		return area <= 0.5;
	}

	public void step() {
		if (done) return;
		if (ledgeOnly) { ledgePass(); done = true; return; }
		long t0 = System.nanoTime();
		int bx = (int) Math.floor(ex), bz = (int) Math.floor(ez), by = (int) Math.floor(ey);
		int r2 = range * range;
		var mv = viewer.getDeltaMovement();
		int layers = cfg.layersPerTick * (mv.x * mv.x + mv.z * mv.z > 0.0144 ? 2 : 1); // moving: scan twice as fast so the picture is not stale when it plays
		for (int n = 0; n < layers && dy <= range; n++, dy++) {
			int y = by + dy;
			for (int dx = -range; dx <= range; dx++) {
				for (int dz = -range; dz <= range; dz++) {
					if (dx * dx + dy * dy + dz * dz > r2) continue;
					int x = bx + dx, z = bz + dz;
					BlockState s = get(x, y, z);
					if (s == null || s.isAir()) continue;
					consider(s, x, y, z);
				}
			}
		}
		if (dy > range) { ledgePass(); entityPass(); done = true; }
		nanos += System.nanoTime() - t0;
	}

	private void consider(BlockState s, int x, int y, int z) {
		if (s.is(BlockTags.DOORS) && s.hasProperty(net.minecraft.world.level.block.DoorBlock.HALF) && s.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) noteDoor(x, y, z);
		boolean liquid = s.getBlock() instanceof LiquidBlock;
		if (!liquid && (ignored(s) || !solid(s, x, y, z))) return;
		// a slab or stair you could simply walk onto (free headroom above it) is part of the floor, not a wall: slab stairways and slab roofs used to ping on every step
		if (cfg.ignoreSteps && !liquid && (s.is(BlockTags.SLABS) || s.is(BlockTags.STAIRS)) && passable(x, y + 1, z) && passable(x, y + 2, z)) return;
		double vx = x + 0.5 - ex, vy = y + 0.5 - ey, vz = z + 0.5 - ez;
		double d = Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (d < 0.8 || d > range) return;
		if (front) {
			double h = Math.sqrt(vx * vx + vz * vz);
			if (h > 0.5 && (vx * fx + vz * fz) / h < 0.64) return;
		}
		// Up to three camera-facing faces (one per axis); the block counts only if one of them is both open and actually visible.
		boolean ox = vx != 0 && open(vx < 0 ? x + 1 : x - 1, y, z, vx < 0 ? net.minecraft.core.Direction.WEST : net.minecraft.core.Direction.EAST);
		boolean oz = vz != 0 && open(x, y, vz < 0 ? z + 1 : z - 1, vz < 0 ? net.minecraft.core.Direction.NORTH : net.minecraft.core.Direction.SOUTH);
		boolean up = vy < 0; // block below the eye shows its top face
		boolean oy = vy != 0 && !(up && !cfg.floorSounds) && open(x, up ? y + 1 : y - 1, z, up ? net.minecraft.core.Direction.DOWN : net.minecraft.core.Direction.UP);
		if (!ox && !oz && !oy) return;
		// the floor you are standing on is texture, not scenery: nearby blocks at or below foot level made of the same material as your floor stay silent
		// (a stair-and-slab floor used to fire a ring of close-range sounds that drowned out everything else). Walls and other materials still sound.
		if (cfg.ignoreFloorBumps && !liquid && y <= feetY && floorBlock != null && s.getBlock() == floorBlock && d < 8) return;

		int cat;
		if (liquid) {
			cat = s.getFluidState().is(FluidTags.LAVA) ? HAZARD : WATER;
		} else if (cfg.tactileCue && d <= cfg.tactileRange && isTactile(s)) {
			cat = TACTILE;
		} else {
			cat = SOLID;
		}
		int sec = sector(vx, vy, vz, d);
		Cand c = best[cat][sec];
		if (d < c.d && (!cfg.lineOfSight
				|| (ox && faceVisible(x, y, z, vx < 0 ? 1 : -1, 0, 0))
				|| (oz && faceVisible(x, y, z, 0, 0, vz < 0 ? 1 : -1))
				|| (oy && faceVisible(x, y, z, 0, up ? 1 : -1, 0)))) {
			c.d = (float) d; c.x = x; c.y = y; c.z = z; c.state = s;
		}
	}

	/** Finds the doors in sight without the block pass (the automatic echo uses a Scan that never ran one): only the 16-block sections whose palette holds a door are searched, so it is cheap. */
	private int doorDbg;

	public void findDoors() {
		doors.clear();
		if (cfg.debug && viewer instanceof net.minecraft.world.entity.player.Player pl) pl.sendSystemMessage(net.minecraft.network.chat.Component.literal("door search started, range " + range));
		int minX = (int) Math.floor(ex) - range, maxX = (int) Math.floor(ex) + range, minZ = (int) Math.floor(ez) - range, maxZ = (int) Math.floor(ez) + range;
		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
				var ch = level.getChunkSource().getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
				if (ch == null) continue;
				var secs = ch.getSections();
				for (int si = 0; si < secs.length; si++) {
					var sec = secs[si];
					if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(st -> st.is(BlockTags.DOORS))) continue;
					int y0 = ch.getMinY() + si * 16;
					for (int lx = 0; lx < 16; lx++) for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) {
						BlockState st = sec.getBlockState(lx, ly, lz);
						if (!st.is(BlockTags.DOORS) || !st.hasProperty(net.minecraft.world.level.block.DoorBlock.HALF)
							|| st.getValue(net.minecraft.world.level.block.DoorBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) continue;
						noteDoor((cx << 4) + lx, y0 + ly, (cz << 4) + lz);
					}
				}
			}
		}
	}

	/** A door you could see from your eyes (a clear line to its middle) within range. */
	private void noteDoor(int x, int y, int z) {
		double vx = x + 0.5 - ex, vy = y + 1.0 - ey, vz = z + 0.5 - ez, d = Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (d < 1.5 || d > range) return;
		boolean dbg = cfg.debug && doorDbg++ < 8 && viewer instanceof net.minecraft.world.entity.player.Player;
		// a door is recessed in its frame and often seen at an angle, so one line to its middle can clip the frame: try 18 points across both halves of the door and count any clear one
		var eye = new net.minecraft.world.phys.Vec3(ex, ey, ez);
		String blockedBy = "";
		boolean seen = false;
		outer:
		for (double ty : new double[]{0.3, 1.0, 1.7}) for (double tx : new double[]{0.15, 0.5, 0.85}) for (double tz : new double[]{0.15, 0.85}) {
			var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, new net.minecraft.world.phys.Vec3(x + tx, y + ty, z + tz), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, viewer));
			if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) { seen = true; break outer; }
			var hp = hit.getBlockPos();
			if (level.getBlockState(hp).is(BlockTags.DOORS) && Math.abs(hp.getX() - x) <= 1 && Math.abs(hp.getZ() - z) <= 1 && Math.abs(hp.getY() - y) <= 1) { seen = true; break outer; }
			blockedBy = level.getBlockState(hp).getBlock().getDescriptionId() + " at " + hp.getX() + "," + hp.getY() + "," + hp.getZ();
		}
		if (!seen) {
			if (dbg) ((net.minecraft.world.entity.player.Player) viewer).sendSystemMessage(net.minecraft.network.chat.Component.literal(String.format("door at %d,%d,%d (%.1f blocks): hidden, last blocked by %s", x, y, z, d, blockedBy)));
			return;
		}
		if (dbg) ((net.minecraft.world.entity.player.Player) viewer).sendSystemMessage(net.minecraft.network.chat.Component.literal(String.format("door at %d,%d,%d (%.1f blocks): visible", x, y, z, d)));
		doors.add(new double[]{Math.atan2(vz, vx), d});
	}

	/** True if you could actually see this face: a straight line from your eyes to just outside the face's centre meets nothing solid. Only run for blocks that would become the nearest in their sector. */
	private boolean faceVisible(int x, int y, int z, int nx, int ny, int nz) {
		var from = new net.minecraft.world.phys.Vec3(ex, ey, ez);
		var to = new net.minecraft.world.phys.Vec3(x + 0.5 + nx * 0.53, y + 0.5 + ny * 0.53, z + 0.5 + nz * 0.53);
		var hit = level.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, viewer));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
	}

	static boolean isTactile(BlockState s) {
		return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.SAND)
			|| s.is(Blocks.GRAVEL) || s.is(Blocks.IRON_BARS);
	}

	public static int yawSector(double dx, double dz) {
		int i = (int) Math.floor((Math.atan2(dz, dx) + Math.PI) / (2 * Math.PI) * YAW);
		return Math.floorMod(i, YAW);
	}

	static int sector(double vx, double vy, double vz, double d) {
		double el = vy / d;
		int band = el < -0.34 ? 0 : el > 0.34 ? 2 : 1;
		return band * YAW + yawSector(vx, vz);
	}

	private static final int NONE = Integer.MIN_VALUE;

	private boolean passable(int x, int y, int z) {
		BlockState s = get(x, y, z);
		return s != null && !solid(s, x, y, z) && !(s.getBlock() instanceof LiquidBlock);
	}

	/** Walkable floor y of a column near the player level (needs 2 blocks of headroom), or NONE. */
	private int standY(int x, int z) {
		for (int y = feetY; y >= feetY - 2; y--) {
			BlockState s = get(x, y, z);
			if (s == null) return NONE;
			if (floorSolid(s, x, y, z)) return (passable(x, y + 1, z) && passable(x, y + 2, z)) ? y : NONE;
		}
		return NONE;
	}

	private static final int UNKNOWN = Integer.MAX_VALUE;

	/** Floor y when walking onto a column from a cell whose floor is ref (up 1 or down 3); NONE if blocked, liquid or no floor. */
	private int stepTo(int x, int z, int ref) {
		for (int y = ref + 1; y >= ref - 3; y--) {
			BlockState s = get(x, y, z);
			if (s == null) return UNKNOWN;
			if (s.getBlock() instanceof LiquidBlock) return NONE;
			if (s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || s.is(BlockTags.FENCE_GATES) || s.is(Blocks.IRON_BARS)) return NONE; // a fence or wall at the edge makes it safe
			if (floorSolid(s, x, y, z)) return (passable(x, y + 1, z) && passable(x, y + 2, z)) ? y : NONE;
		}
		return NONE;
	}

	/** Flood-fill the cells the player can walk to, and report open edges where the ground falls away. */
	private void ledgePass() {
		if (!cfg.ledgeCue || front) return;
		int R = cfg.ledgeRange, W = 2 * R + 1;
		int f0 = standY(px, pz);
		if (f0 == NONE) return;
		if (bufFloor.length < W * W) { bufFloor = new int[W * W]; bufQueue = new int[W * W]; bufSeen = new boolean[W * W]; }
		int[] floor = bufFloor, q = bufQueue;
		boolean[] seen = bufSeen;
		java.util.Arrays.fill(seen, 0, W * W, false);
		int qh = 0, qt = 0, start = R * W + R;
		floor[start] = f0; seen[start] = true; q[qt++] = start;
		while (qh < qt) {
			int i = q[qh++], cx = px + (i % W) - R, cz = pz + (i / W) - R, f = floor[i];
			for (int k = 0; k < 4; k++) {
				int ox = k == 0 ? 1 : k == 1 ? -1 : 0, oz = k == 2 ? 1 : k == 3 ? -1 : 0;
				int nx = cx + ox, nz = cz + oz, ddx = nx - px, ddz = nz - pz;
				if (ddx * ddx + ddz * ddz > R * R) continue;
				int ni = (ddz + R) * W + (ddx + R);
				if (seen[ni]) continue;
				seen[ni] = true;
				floor[ni] = Integer.MIN_VALUE; // not walkable unless set below
				int nf = stepTo(nx, nz, f);
				if (nf == UNKNOWN) continue;
				if (nf != NONE) {
					floor[ni] = nf; q[qt++] = ni;
					if (f - nf >= 2) { // a step down of 2+ blocks
						double sx = cx + 0.5 + ox * 0.5 - ex, sz = cz + 0.5 + oz * 0.5 - ez;
						float sd = (float) Math.sqrt(sx * sx + sz * sz);
						Ledge dl = drops[yawSector(sx, sz)];
						if (sd < dl.d) { dl.d = sd; dl.x = cx; dl.y = f; dl.z = cz; dl.drop = f - nf; }
					}
					if (nf < f && f <= f0 + 2) { // (an edge on ground well above you, like the top of a wall or terrace beside you, is not your edge) steep continuous slope: 3+ blocks lost within 3 cells in a straight line
						int cur = f;
						boolean big = false; // a plain staircase (1 block per step) is not a ledge
						for (int j = 1; j <= 3; j++) {
							int c = stepTo(cx + ox * j, cz + oz * j, cur);
							if (c == NONE || c == UNKNOWN || c > cur) break;
							if (cur - c >= 2) big = true;
							cur = c;
						}
						if ((big && f - cur >= cfg.slopeMinDrop) || f - cur >= 4) {
							double ax = cx + 0.5 + ox * 0.5 - ex, az = cz + 0.5 + oz * 0.5 - ez;
							float d = Math.max(0.5f, (float) Math.sqrt(ax * ax + az * az) - approach(cx, cz, ox, oz, f, floor, seen, W, R));
							Ledge l = ledges[yawSector(ax, az)];
							if (d < l.d) { l.d = d; l.x = cx; l.y = f; l.z = cz; l.deep = false; l.drop = f - cur; }
						}
					}
					continue;
				}
				if (!passable(nx, f + 1, nz) || !passable(nx, f + 2, nz)) continue; // wall, not an edge
					if (f > f0 + 2) continue; // an edge on ground 3+ blocks above you (top of a wall or terrace beside you) does not warn until you are nearly level with it
				int landed = Integer.MIN_VALUE;
				boolean lava = false, water = false, unknown = false;
				for (int y = f; y >= f - 14; y--) {
					BlockState s = get(nx, y, nz);
					if (s == null) { unknown = true; break; }
					if (s.getFluidState().is(FluidTags.LAVA)) { lava = true; landed = y; break; }
					if (s.getFluidState().is(FluidTags.WATER)) { water = true; break; }
					if (floorSolid(s, nx, y, nz)) { landed = y; break; }
				}
				if (unknown || water) continue;
				int drop = landed == Integer.MIN_VALUE ? 99 : f - landed;
				if (drop < cfg.ledgeMinDrop && !lava) continue;
				double ax = cx + 0.5 + ox * 0.5 - ex, az = cz + 0.5 + oz * 0.5 - ez;
				float d = (float) Math.sqrt(ax * ax + az * az);
				// a staircase of 1-2 block steps leading down to the drop is part of the approach: start warning earlier by the length of that run
				int run = approach(cx, cz, ox, oz, f, floor, seen, W, R);
				d = Math.max(0.5f, d - run);
				Ledge l = ledges[yawSector(ax, az)];
				if (d < l.d) { l.d = d; l.x = cx; l.y = f; l.z = cz; l.deep = lava || drop > 6; l.drop = lava ? 99 : drop; }
			}
		}
	}

	/** Cells of stepped ground (rising 1-2 blocks per step, at most one flat cell in between) leading back from an edge cell, up to 8. Warning starts that much sooner. */
	private int approach(int cx, int cz, int ox, int oz, int f, int[] floor, boolean[] seen, int W, int R) {
		int run = 0, prevFloor = f, flats = 0;
		for (int j = 1; j <= 8; j++) {
			int bdx = cx - ox * j - px, bdz = cz - oz * j - pz;
			if (Math.abs(bdx) > R || Math.abs(bdz) > R) break;
			int bi = (bdz + R) * W + (bdx + R);
			int bf = floor[bi];
			if (!seen[bi] || bf == Integer.MIN_VALUE) break;
			int rise = bf - prevFloor;
			if (rise == 0 && flats == 0) flats++;
			else if (rise < 1 || rise > 2) break;
			else flats = 0;
			run++;
			prevFloor = bf;
		}
		return run;
	}

	/** Nearest mob per sector within entityRange. */
	public final net.minecraft.world.entity.Mob[] mobs = new net.minecraft.world.entity.Mob[SECTORS];
	public final float[] mobD = new float[SECTORS];

	private void entityPass() {
		java.util.Arrays.fill(mobD, Float.MAX_VALUE);
		if (!cfg.entityCue) return;
		double max = Math.min(range, cfg.entityRange);
		for (var e : level.entitiesForRendering()) {
			if (!(e instanceof net.minecraft.world.entity.Mob m)) continue;
			double vx = m.getX() - ex, vy = m.getY() + m.getBbHeight() / 2 - ey, vz = m.getZ() - ez;
			double d = Math.sqrt(vx * vx + vy * vy + vz * vz);
			if (d < 0.8 || d > max) continue;
			int sec = sector(vx, vy, vz, d);
			if (d < mobD[sec]) { mobD[sec] = (float) d; mobs[sec] = m; }
		}
	}
}
