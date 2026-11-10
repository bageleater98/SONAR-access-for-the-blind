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
	/** YAW: directions round you for ledges. The sonar's own slices are finer: BLOCK_YAW round you and BANDS up and down, each playing its nearest block. (Round 15:
	 *  with 16 x 3 slices the middle band ran from 20 degrees down to 20 up, so the edge of a grass step in front of a tree trunk hid the trunk, plainly visible above it.
	 *  How many sounds a ping makes is now set by the grouping rule, not by the number of slices.) */
	public static final int YAW = 16, BLOCK_YAW = 32, BANDS = 5, SECTORS = BLOCK_YAW * BANDS;
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
		for (Ledge l : ledges) { l.d = Float.MAX_VALUE; l.deep = false; l.drop = 0; l.ox = 0; l.oz = 0; }
		for (Ledge l : drops) { l.d = Float.MAX_VALUE; l.drop = 0; }
		chunkKey = Long.MIN_VALUE;
		chunk = null;
		resetChunks();
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
		public int ox, oz; // the way the ground falls away (from the standing cell toward the drop)
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
	public Rooms.Space space; // cached by Rooms.space
	public Boolean cave; // cached by Rooms.cave
	public boolean doorsSearched;
	public final java.util.List<double[]> doors = new java.util.ArrayList<>(); // doors in sight within range: {bearing (atan2 z,x), distance}
	public long nanos;

	private final net.minecraft.world.entity.Entity viewer;
	private net.minecraft.world.level.block.Block floorBlock;
	/** The game world (its chunks are read directly, the fast way), or null when this scan reads another world (the self-test). */
	private final ClientLevel level;
	/** The world scanned (the game's, or a self-test world). */
	private final net.minecraft.world.level.BlockGetter world;
	/** The world as Sonar hears it (guide blocks are air): every shape and line-of-sight test reads this. */
	private final net.minecraft.world.level.BlockGetter heard;
	/** How rays treat blocks for you (the player's, or an empty one for a scan without a player). */
	private final net.minecraft.world.phys.shapes.CollisionContext collision;

	/** The world as Sonar hears it, for the detectors that work from this scan (the echo's ways out, the room test...). */
	net.minecraft.world.level.BlockGetter world() { return heard; }
	net.minecraft.world.phys.shapes.CollisionContext collision() { return collision; }
	/** A scan of the game world (not a self-test world). */
	boolean live() { return level != null; }

	/**
	 * A scan of ANY world from an eye position (the self-test: the tutorial rooms and F2 spots), exactly as in play but without a player: no mobs, standing still.
	 * eyeY is the eye height (feet + 1.62).
	 */
	Scan(net.minecraft.world.level.BlockGetter w, SonarConfig cfg, double eyeX, double eyeY, double eyeZ, float yaw, boolean front) {
		this.cfg = cfg;
		this.front = front;
		this.range = front ? cfg.frontRange : cfg.range;
		this.level = null;
		this.world = w;
		this.heard = Sense.hear(w);
		this.collision = net.minecraft.world.phys.shapes.CollisionContext.empty();
		this.viewer = null;
		ex = eyeX; ey = eyeY; ez = eyeZ;
		px = (int) Math.floor(eyeX); pz = (int) Math.floor(eyeZ);
		feetY = (int) Math.floor(eyeY - 1.62 + 0.01);
		this.yaw = yaw;
		double r = Math.toRadians(yaw);
		fx = -Math.sin(r); fz = Math.cos(r);
		init();
	}

	/** Runs the whole scan at once (the self-test). */
	Scan finish() { while (!done) step(); return this; }
	private final SonarConfig cfg;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private final BlockPos.MutableBlockPos nb = new BlockPos.MutableBlockPos();
	private double fx, fz;
	private long chunkKey = Long.MIN_VALUE;
	private LevelChunk chunk;

	public Scan(Minecraft mc, SonarConfig cfg, boolean front) {
		this.cfg = cfg;
		this.front = front;
		this.range = front ? cfg.frontRange : cfg.range;
		this.level = mc.level;
		this.world = mc.level;
		this.heard = Sense.hear(mc);
		this.collision = net.minecraft.world.phys.shapes.CollisionContext.of(mc.player);
		this.viewer = mc.player;
		var p = mc.player;
		ex = p.getX(); ey = p.getEyeY(); ez = p.getZ();
		px = p.getBlockX(); pz = p.getBlockZ();
		feetY = p.getBlockY();
		yaw = p.getYRot();
		double r = Math.toRadians(yaw);
		fx = -Math.sin(r); fz = Math.cos(r);
		init();
	}

	private void init() {
		for (var a : best) for (int i = 0; i < SECTORS; i++) a[i] = new Cand();
		for (int i = 0; i < YAW; i++) { ledges[i] = new Ledge(); drops[i] = new Ledge(); }
		resetChunks();
		for (int fy = feetY - 1; fy >= feetY - 3 && floorBlock == null; fy--) {
			BlockState fs = get(px, fy, pz);
			if (fs != null && !fs.isAir() && !(fs.getBlock() instanceof LiquidBlock)) floorBlock = fs.getBlock();
		}
	}

	// the chunks around you, looked up once per scan (nearest-first order jumps between chunks all the time)
	private LevelChunk[] chunks = new LevelChunk[0];
	private boolean[] chunkTried = new boolean[0];
	private int cx0, cz0, cw;

	private void resetChunks() {
		int r = Math.max(range, cfg.ledgeRange + 12) + 2;
		cx0 = (px - r) >> 4; cz0 = (pz - r) >> 4;
		cw = ((px + r) >> 4) - cx0 + 1;
		if (chunks.length != cw * cw) { chunks = new LevelChunk[cw * cw]; chunkTried = new boolean[cw * cw]; }
		java.util.Arrays.fill(chunks, null);
		java.util.Arrays.fill(chunkTried, false);
	}

	/** The block Sonar hears at x,y,z: a guide block (barrier) reads as air (Sense). Null outside the loaded world. */
	private BlockState get(int x, int y, int z) {
		BlockState s = raw(x, y, z);
		return s != null && Sense.guide(s) ? Sense.hollow(s) : s;
	}

	/** A guide block somewhere in column x,z from y0 up to y1: a silent railing for the ledge flood (Sense). */
	private boolean guideIn(int x, int z, int y0, int y1) {
		for (int y = y0; y <= y1; y++) { BlockState s = raw(x, y, z); if (s != null && Sense.guide(s)) return true; }
		return false;
	}

	/** The real block at x,y,z (guide blocks included), from the chunks around you. */
	private BlockState raw(int x, int y, int z) {
		if (level == null) return y < world.getMinY() || y >= world.getMinY() + world.getHeight() ? null : world.getBlockState(nb.set(x, y, z));
		int cx = (x >> 4) - cx0, cz = (z >> 4) - cz0;
		LevelChunk c;
		if (cx >= 0 && cz >= 0 && cx < cw && cz < cw) {
			int i = cz * cw + cx;
			if (!chunkTried[i]) { chunkTried[i] = true; chunks[i] = level.getChunkSource().getChunk(x >> 4, z >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false); }
			c = chunks[i];
		} else {
			long k = ChunkPos.pack(x >> 4, z >> 4);
			if (k != chunkKey) {
				chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
				chunkKey = k;
			}
			c = chunk;
		}
		if (c == null || y < level.getMinY() || y >= level.getMaxY()) return null;
		return c.getBlockState(nb.set(x, y, z));
	}

	/** Plants, torches and other small decor never ping. */
	private boolean ignored(BlockState s, int x, int y, int z) {
		if (s.is(Blocks.PINK_PETALS) || s.is(Blocks.CHERRY_LEAVES) || s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS) || s.is(BlockTags.CROPS)
			|| s.is(BlockTags.WALL_SIGNS) || s.is(BlockTags.STANDING_SIGNS) || s.is(BlockTags.BUTTONS)) return true;
		if (s.is(BlockTags.FENCES) || s.is(Blocks.LADDER)) return false; // a ladder is thin too, but it is a ladder: it pings (climbing is by ear)
		if (s.is(BlockTags.FENCES)) return false; // a fence post is thin (a quarter block) but it is a wooden structure, not decor: a gazebo's posts and a paddock's rails ping
		var shape = s.getShape(heard, pos.set(x, y, z)); // the shape of this block (pos used to be left pointing at another block), and an empty shape has no size to measure
		return shape.isEmpty() || shape.bounds().getSize() < 0.6;
	}

	/** Ground you could actually stand on or land on: leaves are solid for sound, but a canopy is not a floor, so a drop is measured down to the ground beneath it. */
	private boolean floorSolid(BlockState s, int x, int y, int z) {
		return solid(s, x, y, z) && !s.is(BlockTags.LEAVES);
	}

	private boolean solid(BlockState s, int x, int y, int z) {
		return !s.getCollisionShape(heard, pos.set(x, y, z)).isEmpty();
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

	/** Every block offset within a range, nearest first (packed dx,dy,dz, 8 bits each, +128). Built once per range. Scanning nearest first means the first visible block
	 *  in a direction is found early, so the costly line-of-sight check almost never runs for blocks further out behind it. */
	private static final java.util.Map<Integer, int[]> orders = new java.util.concurrent.ConcurrentHashMap<>(); // the full scan and the front scan use different ranges: keep both

	/** Builds the scan orders in the background at start-up, so the first ping does not pause to sort them. */
	public static void warm(int... ranges) {
		Thread t = new Thread(() -> { for (int r : ranges) order(r); }, "sonar-warm");
		t.setDaemon(true);
		t.start();
	}

	private static int[] order(int r) {
		int[] cached = orders.get(r);
		if (cached != null) return cached;
		int n = 0, r2 = r * r;
		long[] keys = new long[(2 * r + 1) * (2 * r + 1) * (2 * r + 1)];
		for (int x = -r; x <= r; x++) for (int y = -r; y <= r; y++) for (int z = -r; z <= r; z++) {
			int d2 = x * x + y * y + z * z;
			if (d2 > r2) continue;
			keys[n++] = ((long) d2 << 24) | ((x + 128) << 16) | ((y + 128) << 8) | (z + 128);
		}
		keys = java.util.Arrays.copyOf(keys, n);
		java.util.Arrays.sort(keys);
		int[] o = new int[n];
		for (int i = 0; i < n; i++) o[i] = (int) (keys[i] & 0xFFFFFF);
		if (orders.size() > 4) orders.clear();
		orders.put(r, o);
		return o;
	}

	private int idx, steps;

	public void step() {
		if (done) return;
		if (ledgeOnly) { ledgePass(); done = true; return; }
		long t0 = System.nanoTime();
		int bx = (int) Math.floor(ex), bz = (int) Math.floor(ez), by = (int) Math.floor(ey);
		int[] o = order(range);
		var mv = viewer == null ? net.minecraft.world.phys.Vec3.ZERO : viewer.getDeltaMovement();
		int layers = cfg.layersPerTick * (mv.x * mv.x + mv.z * mv.z > 0.0144 ? 2 : 1); // moving: scan twice as fast so the picture is not stale when it plays
		int per = (int) Math.ceil((double) o.length * layers / (2 * range + 1)); // the same number of ticks per scan as the old layer-by-layer pass
		int end = Math.min(o.length, idx + per);
		// the AUTO scan holds each step to SCAN_TICK_NS (round 16: 5-7 ms a step starved the route searches), taking up to twice its usual ticks; a key press never waits
		boolean capped = auto && ++steps <= 2 * Math.ceil((2.0 * range + 1) / layers);
		for (; idx < end; idx++) {
			if (capped && (idx & 63) == 0 && System.nanoTime() - t0 > Tuning.SCAN_TICK_NS) break;
			int k = o[idx], x = bx + ((k >> 16) & 0xFF) - 128, y = by + ((k >> 8) & 0xFF) - 128, z = bz + (k & 0xFF) - 128;
			BlockState s = get(x, y, z);
			if (s == null || s.isAir() || buried(x, y, z)) continue;
			consider(s, x, y, z);
		}
		if (idx >= o.length) { ledgePass(); entityPass(); done = true; }
		nanos += System.nanoTime() - t0;
	}

	/** Cheap first test: every face of this block that looks toward you is covered by a full opaque block, so it can not be seen or heard. Most blocks in the ground are like this,
	 *  and they used to go through all the tag, shape and visibility checks one by one (the main cost of a ping). Mirrors the face test in consider(). */
	private boolean buried(int x, int y, int z) {
		double vx = x + 0.5 - ex, vy = y + 0.5 - ey, vz = z + 0.5 - ez;
		if (vx != 0 && !covered(vx < 0 ? x + 1 : x - 1, y, z)) return false;
		if (vz != 0 && !covered(x, y, vz < 0 ? z + 1 : z - 1)) return false;
		boolean up = vy < 0;
		return vy == 0 || (up && !cfg.floorSounds) || covered(x, up ? y + 1 : y - 1, z);
	}

	private boolean covered(int x, int y, int z) {
		BlockState s = get(x, y, z);
		return s == null || s.isSolidRender();
	}

	/**
	 * A slab or stair you could really step onto: some neighbouring cell at its own level is somewhere you can stand (free headroom, ground under it). A slab
	 * roof on posts has none: nobody can get onto it from the side, so it is a roof and it sounds.
	 */
	private boolean steppable(int x, int y, int z) {
		for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
			if (passable(x + d[0], y, z + d[1]) && passable(x + d[0], y + 1, z + d[1]) && !passable(x + d[0], y - 1, z + d[1])) return true;
		return false;
	}

	private void consider(BlockState s, int x, int y, int z) {
		if (s.is(BlockTags.DOORS) && s.hasProperty(net.minecraft.world.level.block.DoorBlock.HALF) && s.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) noteDoor(x, y, z);
		boolean liquid = s.getBlock() instanceof LiquidBlock;
		if (!liquid && (ignored(s, x, y, z) || !solid(s, x, y, z))) return;
		// a slab or stair you could simply walk onto (free headroom above it) is part of the floor, not a wall: slab stairways and slab roofs used to ping on every step
		if (cfg.ignoreSteps && !liquid && (s.is(BlockTags.SLABS) || s.is(BlockTags.STAIRS)) && passable(x, y + 1, z) && passable(x, y + 2, z) && steppable(x, y, z)) return;
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
		} else { // (round 15: the separate "touch" sound for leaves, logs, sand and gravel within reach is gone: they sound as what they are, like every block)
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
		if (level == null) { // any world: look at every block near your height (the self-test; the game uses the chunk palettes below)
			int bx = (int) Math.floor(ex), bz = (int) Math.floor(ez);
			for (int x = bx - range; x <= bx + range; x++) for (int z = bz - range; z <= bz + range; z++) for (int y = feetY - 6; y <= feetY + 6; y++) {
				BlockState st = get(x, y, z);
				if (st != null && st.is(BlockTags.DOORS) && st.hasProperty(net.minecraft.world.level.block.DoorBlock.HALF)
					&& st.getValue(net.minecraft.world.level.block.DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) noteDoor(x, y, z);
			}
			return;
		}
		if (cfg.debug && viewer instanceof net.minecraft.world.entity.player.Player pl) Dbg.send(pl, net.minecraft.network.chat.Component.literal("door search started, range " + range));
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
			var hit = heard.clip(new net.minecraft.world.level.ClipContext(eye, new net.minecraft.world.phys.Vec3(x + tx, y + ty, z + tz), net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, collision));
			if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) { seen = true; break outer; }
			var hp = hit.getBlockPos();
			if (heard.getBlockState(hp).is(BlockTags.DOORS) && Math.abs(hp.getX() - x) <= 1 && Math.abs(hp.getZ() - z) <= 1 && Math.abs(hp.getY() - y) <= 1) { seen = true; break outer; }
			blockedBy = heard.getBlockState(hp).getBlock().getDescriptionId() + " at " + hp.getX() + "," + hp.getY() + "," + hp.getZ();
		}
		if (!seen) {
			if (dbg) Dbg.send((net.minecraft.world.entity.player.Player) viewer, net.minecraft.network.chat.Component.literal(Dbg.fmt("door at %d,%d,%d (%.1f blocks): hidden, last blocked by %s", x, y, z, d, blockedBy)));
			return;
		}
		if (dbg) Dbg.send((net.minecraft.world.entity.player.Player) viewer, net.minecraft.network.chat.Component.literal(Dbg.fmt("door at %d,%d,%d (%.1f blocks): visible", x, y, z, d)));
		doors.add(new double[]{Math.atan2(vz, vx), d});
	}

	/** True if you could actually see this face: a straight line from your eyes to just outside the face's centre meets nothing solid. Only run for blocks that would become the nearest in their sector. */
	private boolean faceVisible(int x, int y, int z, int nx, int ny, int nz) {
		var from = new net.minecraft.world.phys.Vec3(ex, ey, ez);
		var to = new net.minecraft.world.phys.Vec3(x + 0.5 + nx * 0.53, y + 0.5 + ny * 0.53, z + 0.5 + nz * 0.53);
		var hit = heard.clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, collision));
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
		double el = vy / d; // the sine of the angle up: bands below -30, -30 to -10, -10 to 10, 10 to 30, above 30 degrees
		int band = el < -0.5 ? 0 : el < -0.17 ? 1 : el <= 0.17 ? 2 : el <= 0.5 ? 3 : 4;
		int i = (int) Math.floor((Math.atan2(vz, vx) + Math.PI) / (2 * Math.PI) * BLOCK_YAW);
		return band * BLOCK_YAW + Math.floorMod(i, BLOCK_YAW);
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
			if (guideIn(x, z, y, y)) return NONE; // a guide block is a silent railing: never stepped into or onto (Sense)
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
						boolean fall = false; // one step down you would fall, not walk (3+ blocks)
						for (int j = 1; j <= 3; j++) {
							int c = stepTo(cx + ox * j, cz + oz * j, cur);
							if (c == NONE || c == UNKNOWN || c > cur) break;
							if (cur - c >= Tuning.LEDGE_FALL_STEP) fall = true;
							cur = c;
						}
						// a slope is a ledge only when it is steeper than you can walk down step by step: a terraced hillside of 1 and 2 block steps lost 4 blocks in 3 steps
						// and used to warn (and brake) right in front of you though nothing there would hurt you. Now: a step you would fall, or 6+ blocks lost in 3 steps.
						if ((fall && f - cur >= Math.max(cfg.slopeMinDrop, cfg.ledgeMinDrop)) || f - cur >= Tuning.LEDGE_SLOPE_DROP) {
							double ax = cx + 0.5 + ox * 0.5 - ex, az = cz + 0.5 + oz * 0.5 - ez;
							float d = Math.max(0.5f, (float) Math.sqrt(ax * ax + az * az) - approach(cx, cz, ox, oz, f, floor, seen, W, R));
							Ledge l = ledges[yawSector(ax, az)];
							if (d < l.d) { l.d = d; l.x = cx; l.y = f; l.z = cz; l.deep = false; l.drop = f - cur; l.ox = ox; l.oz = oz; }
						}
					}
					continue;
				}
				if (!passable(nx, f + 1, nz) || !passable(nx, f + 2, nz)) continue; // wall, not an edge
				if (guideIn(nx, nz, f, f + 2)) continue; // a guide block at the lip (where you would walk off): a railing, so not an edge (Sense). Lower down it is only where you would land.
					if (f > f0 + 2) continue; // an edge on ground 3+ blocks above you (top of a wall or terrace beside you) does not warn until you are nearly level with it
				int landed = Integer.MIN_VALUE;
				boolean lava = false, water = false, unknown = false;
				for (int y = f; y >= f - 14; y--) {
					BlockState s = raw(nx, y, nz);
					if (s != null && Sense.guide(s)) { landed = y; break; } // you would land on a guide block: it holds you up (the drop is measured to it)
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
				if (d < l.d) { l.d = d; l.x = cx; l.y = f; l.z = cz; l.deep = lava || drop > 6; l.drop = lava ? 99 : drop; l.ox = ox; l.oz = oz; }
			}
		}
	}

	/** How much sooner to warn because of a staircase leading down to this edge (you come down a staircase fast). Built stair and slab steps count in full (up to 8);
	 *  natural ground that happens to step down (a cave floor, terraced hillside) counts half, at most 2: those uneven floors used to make a far edge sound as if it were right at your feet. */
	private int approach(int cx, int cz, int ox, int oz, int f, int[] floor, boolean[] seen, int W, int R) {
		int built = 0, natural = 0, prevFloor = f, flats = 0;
		for (int j = 1; j <= 8; j++) {
			int bx = cx - ox * j, bz = cz - oz * j, bdx = bx - px, bdz = bz - pz;
			if (Math.abs(bdx) > R || Math.abs(bdz) > R) break;
			int bi = (bdz + R) * W + (bdx + R);
			int bf = floor[bi];
			if (!seen[bi] || bf == Integer.MIN_VALUE) break;
			int rise = bf - prevFloor;
			if (rise == 0 && flats == 0) flats++;
			else if (rise < 1 || rise > 2) break;
			else flats = 0;
			BlockState st = get(bx, bf, bz);
			if (st != null && (st.is(BlockTags.STAIRS) || st.is(BlockTags.SLABS))) built++;
			else natural++;
			prevFloor = bf;
		}
		return built + Math.min(2, natural / 2);
	}

	/** Nearest mob per sector within entityRange. */
	public final net.minecraft.world.entity.Mob[] mobs = new net.minecraft.world.entity.Mob[SECTORS];
	public final float[] mobD = new float[SECTORS];

	private void entityPass() {
		java.util.Arrays.fill(mobD, Float.MAX_VALUE);
		if (!cfg.entityCue || level == null) return;
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
