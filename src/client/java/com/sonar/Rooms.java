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
import static com.sonar.Echo.*;
import static com.sonar.Ledges.*;

/** Where you are: inside a room or a cave, and the ray and roof tests behind that (the echo replaces the sonar in those places). (Split out of Cues.) */
final class Rooms {
	private Rooms() {}

	/** Distance to the first solid block along a ray. Doors and trapdoors never stop it (the echo passes through them), and leaves do not when ignoreLeaves is set. */
	static double rayLen(Minecraft mc, Vec3 from, double dx, double dy, double dz, double len, boolean ignoreLeaves) {
		return rayLen(mc, from, dx, dy, dz, len, ignoreLeaves, true);
	}

	/** passDoors false: closed doors and trapdoors are walls (the room test). */
	/** The room test's ray: closed doors are walls, and trees (leaves, trunks, branches) are seen through: a grove is not a room. */
	static double roomRay(Minecraft mc, Vec3 from, double dx, double dy, double dz, double len) {
		return rayLen(mc, from, dx, dy, dz, len, true, false, true);
	}

	static double rayLen(Minecraft mc, Vec3 from, double dx, double dy, double dz, double len, boolean ignoreLeaves, boolean passDoors) {
		return rayLen(mc, from, dx, dy, dz, len, ignoreLeaves, passDoors, false);
	}

	static double rayLen(Minecraft mc, Vec3 from, double dx, double dy, double dz, double len, boolean ignoreLeaves, boolean passDoors, boolean passTrees) {
		return ray(Sense.hear(mc), from, dx, dy, dz, len, ignoreLeaves, passDoors, passTrees);
	}

	/** A ray in any world (the game, or a self-test world): how far it goes before something stops it (leaves, doors and trees passed through as asked). */
	static double ray(net.minecraft.world.level.BlockGetter w, Vec3 from, double dx, double dy, double dz, double len, boolean ignoreLeaves, boolean passDoors, boolean passTrees) {
		Vec3 end = from.add(dx * len, dy * len, dz * len), start = from;
		for (int i = 0; i < 64; i++) { // (64: a ray through a thick tree canopy passes dozens of leaf blocks; at 16 it gave up and reported "nothing there" behind them)
			BlockHitResult hit = w.clip(new ClipContext(start, end, Block.COLLIDER, Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
			if (hit.getType() == Type.MISS) return len;
			BlockState bs = w.getBlockState(hit.getBlockPos());
			if (!((passDoors && (bs.is(BlockTags.DOORS) || bs.is(BlockTags.TRAPDOORS))) || (ignoreLeaves && bs.is(BlockTags.LEAVES))
				|| (passTrees && BlockRules.treeLog(w, bs, hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ())))) return from.distanceTo(hit.getLocation());
			Vec3 d = end.subtract(start), step = d.normalize().scale(0.02);
			start = hit.getLocation().add(step);
			if (start.distanceToSqr(end) < 1e-4) return len;
			// leaves/doors are skipped only for the cell they occupy; the next clip starts just past the hit point
		}
		return len;
	}

	/**
	 * THE ROOM SURVEY, used by every room test: {roof straight overhead 0/1, roof rays that hit (of 5), walls under the roof (of 16)}. Trees, leaves and closed doors
	 * are seen as they are for a room (trees passed through, doors are walls).
	 * A WALL counts only where a real roof covers the space between you and it: a hillside or a tree trunk beside you, out in the open, is not a room's wall.
	 * (Walls within 3 blocks used to count with or without a roof, and the slanted roof rays could hit a hillside: a cherry grove on a slope passed as a room.)
	 */
	static int[] survey(net.minecraft.world.level.BlockGetter w, Vec3 from) {
		double k = Math.sqrt(0.5);
		int up = ray(w, from, 0, 1, 0, 40.0, true, false, true) < 40.0 ? 1 : 0;
		int roof = up;
		for (double[] v : new double[][]{{k, k, 0.0}, {-k, k, 0.0}, {0.0, k, k}, {0.0, k, -k}}) if (ray(w, from, v[0], v[1], v[2], 40.0, true, false, true) < 40.0) roof++;
		int walls = 0;
		if (up == 1 && roof >= 3) {
			for (int i = 0; i < 16; i++) {
				double a = i * Math.PI / 8, dx = Math.cos(a), dz = Math.sin(a);
				double h = ray(w, from, dx, 0.0, dz, 32.0, true, false, true);
				if (h < 32.0 && BlockRules.solidRoof(w, from.add(dx * h * 0.5, 0.0, dz * h * 0.5), 40)) walls++;
			}
		}
		return new int[]{up, roof, walls};
	}

	/** A room at this spot (just inside a doorway): a real roof straight overhead, most of the roof rays covered, and walls under that roof nearly all round
	 *  (a doorway itself is one open side, so one wall fewer than standing inside). */
	static boolean roomAt(net.minecraft.world.level.BlockGetter w, Vec3 from) {
		int[] r = survey(w, from);
		return r[0] == 1 && r[1] >= 3 && r[2] >= 9;
	}

	/** What kind of space you are in: decided ONCE per scan and used by both the sonar (quiet indoors and underground) and the echo (which mode). */
	enum Space { OUTSIDE, ROOM, CAVE }

	/**
	 * Underground (the cave test) is a CAVE, unless it is also a room that is a home: a door in sight or a built floor under you (a base dug into the rock is a house).
	 * Otherwise an enclosed room is a ROOM. Anything else is OUTSIDE. The sonar and the echo used to decide this separately and could disagree.
	 */
	/** (mc may be null: the self-test; it is only used for debug output.) */
	static Space space(Minecraft mc, Scan s) {
		if (s.space == null) {
			boolean room = enclosed(mc, s), cave = cave(mc, s);
			s.space = cave && !(room && (doorNear(s) || builtFloor(mc, s))) ? Space.CAVE : room || cave ? Space.ROOM : Space.OUTSIDE;
		}
		return s.space;
	}

	/** Where the echo replaces the sonar: anywhere but outside. */
	static boolean insideSpace(Minecraft mc, Scan s) {
		return space(mc, s) != Space.OUTSIDE;
	}

	/** See BlockRules.solidRoof. */
	static boolean solidRoof(Minecraft mc, Vec3 pt, int h) { return BlockRules.solidRoof(Sense.hear(mc), pt, h); }

	/** See BlockRules.treeLog. */
	static boolean treeLog(Minecraft mc, BlockState bs, int x, int y, int z) { return BlockRules.treeLog(Sense.hear(mc), bs, x, y, z); }

	/** See BlockRules.caveRoof. */
	static boolean caveRoof(Minecraft mc, Vec3 pt, int h) { return BlockRules.caveRoof(Sense.hear(mc), pt, h); }

	static String lastRoomMsg = "";

	static int offCorridorChecks;

	static boolean enclosed(Minecraft mc, Scan s) {
		return enclosed(mc, s.world(), new Vec3(s.ex, s.ey, s.ez));
	}

	static boolean lastRoom;

	static Vec3 lastRoomAt = Vec3.ZERO;

	static long lastRoomNs;

	/**
	 * The room test, shared by the echo, the sonar and the path bell: a roof over you, and walls around you UNDER that roof.
	 * 16 directions at eye height, out to 32 blocks (big halls, glass domes): a direction is closed when the ray meets a wall and the floor halfway to that wall is roofed too.
	 * That second part keeps a gazebo or a porch in a village from counting the houses across the street as its walls. Once inside, it takes clearly fewer walls
	 * to drop back out, so walking past a doorway or a wide window does not flip the mode back and forth.
	 */
	/** The room test without memory or debug output, for a spot you are not standing at (just inside a doorway): roof over it and walls under that roof all round. */
	static boolean roomAt(Minecraft mc, Vec3 from) {
		return roomAt(Sense.hear(mc), from);
	}

	static boolean enclosed(Minecraft mc, Vec3 from) { return enclosed(mc, Sense.hear(mc), from); }

	/** The room test in world w (as Sonar hears it); mc only for the debug line (may be null). */
	static boolean enclosed(Minecraft mc, net.minecraft.world.level.BlockGetter w, Vec3 from) {
		boolean was = lastRoom && System.nanoTime() - lastRoomNs < Tuning.GRACE_ROOM_NS && from.distanceToSqr(lastRoomAt) < 16.0;
		int[] sv = survey(w, from);
		int roof = sv[0] == 1 ? sv[1] : 0, walls = sv[2]; // no roof straight overhead: not a room, however much is around
		int need = was ? Tuning.ROOM_WALLS_INSIDE : (roof >= 5 ? Tuning.ROOM_WALLS_FULL_ROOF : Tuning.ROOM_WALLS_PART_ROOF);
		boolean room = roof >= 3 && walls >= need;
		if (SonarConfig.cur.debug && mc != null && mc.player != null) {
			String rm = "room test: " + room + " (roof " + roof + "/5 need 3, walls under the roof " + walls + "/16 need " + need + (was ? ", already inside" : "") + ")";
			if (!rm.equals(lastRoomMsg)) { lastRoomMsg = rm; Dbg.send(mc.player, Component.literal(rm)); }
		}
		lastRoom = room; lastRoomAt = from; lastRoomNs = System.nanoTime();
		return room;
	}

	/** You stand on a built floor (planks, bricks, slabs, tiles...), not natural ground: a room dug into rock with a laid floor is a home, not a cave. */
	static boolean builtFloor(Minecraft mc, Scan s) {
		var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		for (int y = s.feetY; y >= s.feetY - 2; y--) {
			BlockState bs = s.world().getBlockState(pos.set(s.px, y, s.pz));
			if (bs.getCollisionShape(s.world(), pos).isEmpty()) continue;
			return !FloorEdge.natural(bs) && !CaveEcho.caveRock(bs);
		}
		return false;
	}

	/** A door you can see within 16 blocks. */
	static boolean doorNear(Scan s) {
		if (s.doors.isEmpty() && !s.doorsSearched) { s.doorsSearched = true; s.findDoors(); }
		for (double[] d : s.doors) if (d[1] <= 16) return true;
		return false;
	}

	/** Underground (rock all around): the cave echo. Cached per scan. */
	static boolean cave(Minecraft mc, Scan s) {
		if (s.cave == null) s.cave = CaveEcho.inCave(mc, s.world(), new Vec3(s.ex, s.ey, s.ez), s.collision());
		return s.cave;
	}
}
