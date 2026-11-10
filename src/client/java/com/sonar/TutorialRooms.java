package com.sonar;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.Consumer;

/**
 * THE TUTORIAL'S ROOMS, defined in code. Each room is a box made purely of barriers (invisible and silent: Sense) with an ordinary vanilla scene inside,
 * built for its lesson: real materials, because the material sounds are part of what is taught. The scene brings its own ground, walls and roofs; nothing
 * the player should hear relies on a barrier. Rooms stand far apart in the void. The same definitions build the real world and the self-test's world.
 *
 * Room coordinates: x 0..w-1, y 0..h-1, z 0..d-1 inside the barrier box (which sits one block outside that). North is -z, east is +x. Unless a room says
 * otherwise the ground's top is y 1, so you stand at y 2. Each room names its SPOTS (where you start, where you are going, the decoys), each with the space
 * Sonar must hear there; the self-test checks every one, and the lessons use them for their trigger boxes.
 */
final class TutorialRooms {
	private TutorialRooms() {}

	/** Rooms are this far apart along x (further than any Sonar sound reaches, and than most render distances). */
	static final int SPACING = 320;
	/** The height the rooms are built at. */
	static final int BASE_Y = 100;

	/** A named place in a room (room coordinates, feet), and what Sonar must hear there: OUTSIDE, ROOM or CAVE. */
	record Spot(String name, double x, double y, double z, Rooms.Space space) {}

	/** One room. Spawn is in room coordinates (feet), yaw as the game counts it (0 south, 90 west, 180 north, -90 east). The LAST spot is the goal. */
	record Room(String id, int index, int w, int h, int d, Rooms.Space space, double sx, double sy, double sz, float yaw, Consumer<RoomBuilder> scene, Spot... spots) {
		int ox() { return index * SPACING; }
		int oy() { return BASE_Y; }
		int oz() { return 0; }
		double spawnX() { return ox() + sx; }
		double spawnY() { return oy() + sy; }
		double spawnZ() { return oz() + sz; }

		/** Writes the room: its scene, then the barrier shell round it. */
		void build(RoomBuilder.Put out) {
			RoomBuilder b = new RoomBuilder(out, ox(), oy(), oz());
			scene.accept(b);
			for (var e : RoomEdits.of(id)) b.set(e.x(), e.y(), e.z(), e.state()); // hand edits made in the world (RoomEdits), on top of the scene
			b.shell(-1, -1, -1, w, h, d);
		}

		/** Writes only the scene (the self-test compares the room with and without its shell). */
		void buildScene(RoomBuilder.Put out) {
			RoomBuilder b = new RoomBuilder(out, ox(), oy(), oz());
			scene.accept(b);
			for (var e : RoomEdits.of(id)) b.set(e.x(), e.y(), e.z(), e.state());
		}

		/** World point x,y,z (feet) inside the room's box. */
		boolean inside(double x, double y, double z) {
			return x >= ox() && x < ox() + w && z >= oz() && z < oz() + d && y >= oy() && y < oy() + h;
		}

		Spot spot(String name) {
			for (Spot s : spots) if (s.name().equals(name)) return s;
			throw new IllegalArgumentException(id + " has no spot " + name);
		}

		/** The spot the lesson leads to. */
		Spot goal() { return spots[spots.length - 1]; }

		/** Room coordinates of a world point. */
		double rx(double x) { return x - ox(); }
		double ry(double y) { return y - oy(); }
		double rz(double z) { return z - oz(); }
	}

	private static Spot spot(String n, double x, double y, double z, Rooms.Space s) { return new Spot(n, x, y, z, s); }
	private static final Rooms.Space OUT = Rooms.Space.OUTSIDE, ROOM = Rooms.Space.ROOM, CAVE = Rooms.Space.CAVE;

	// ---------------------------------------------------------------- L0-L2

	/** L0: a small plank room (ROOM): hello, the controls, the headphone check. */
	static final Room WELCOME = new Room("l0", 0, 9, 6, 9, ROOM, 4.5, 2, 4.5, 180f, b -> {
		b.fill(0, 0, 0, 8, 0, 8, Blocks.STONE);
		b.fill(0, 1, 0, 8, 1, 8, Blocks.OAK_PLANKS);
		b.walls(0, 0, 8, 8, 2, 4, Blocks.SPRUCE_PLANKS);
		b.fill(0, 5, 0, 8, 5, 8, Blocks.SPRUCE_PLANKS);
		b.set(4, 2, 1, Blocks.BOOKSHELF).set(5, 2, 1, Blocks.BOOKSHELF); // something to look at, against the north wall
	}, spot("spawn", 4.5, 2, 4.5, ROOM));

	/** L1: a walled garden (OUTSIDE): turning and the facing words; walk to the north wall. */
	static final Room GARDEN = new Room("l1", 1, 15, 8, 15, OUT, 7.5, 2, 7.5, -90f, b -> {
		ground(b, 15, 15);
		b.fill(0, 2, 0, 14, 4, 0, Blocks.STONE_BRICKS);   // only the north wall (the rest of the garden's edge is the silent barrier: nothing else to hear)
		BlockState poppy = Blocks.POPPY.defaultBlockState(), dandelion = Blocks.DANDELION.defaultBlockState();
		b.set(3, 2, 3, poppy).set(11, 2, 4, dandelion).set(4, 2, 11, dandelion).set(10, 2, 10, poppy).set(2, 2, 8, poppy); // flowers never ping
	}, spot("spawn", 7.5, 2, 7.5, OUT), spot("north wall", 7.5, 2, 1.5, OUT));

	/** L2: a long courtyard (OUTSIDE) with a short cobblestone wall straight ahead of you: ping, walk until it is loud. */
	static final Room COURTYARD = new Room("l2", 2, 15, 8, 23, OUT, 7.5, 2, 17.5, 180f, b -> {
		ground(b, 15, 23);
		b.walls(0, 0, 14, 22, 2, 4, Blocks.STONE_BRICKS);
		b.fill(5, 2, 6, 9, 4, 6, Blocks.COBBLESTONE); // the near wall: its south face is at z 7, 10.5 blocks ahead of the spawn
	}, spot("spawn", 7.5, 2, 17.5, OUT), spot("at the wall", 7.5, 2, 8.5, OUT));

	// ---------------------------------------------------------------- L3 the sweep

	/**
	 * L3: a stone courtyard (OUTSIDE) with ONE opening, on your right (east) as you start facing north; through it a small covered yard (its roof is a hand
	 * edit: RoomEdits), so the opening is also an entrance and the echo clicks from it. The four beats find it: the right beat is the far one, and a gap chime
	 * plays there.
	 */
	static final Room SWEEP = new Room("l3", 3, 19, 8, 13, OUT, 6.5, 2, 6.5, 180f, b -> {
		ground(b, 19, 13);
		b.walls(0, 0, 18, 12, 2, 4, Blocks.STONE_BRICKS);  // the yard's outline (the courtyard shares its north, south and west sides)
		b.fill(1, 2, 0, 1, 4, 12, Blocks.STONE_BRICKS);   // the west wall is two thick
		b.fill(11, 2, 0, 11, 4, 12, Blocks.STONE_BRICKS); // the courtyard's east wall...
		b.fill(11, 2, 5, 11, 4, 7, Blocks.AIR);           // ...with the opening, three wide
	}, spot("spawn", 6.5, 2, 6.5, OUT), spot("through the opening", 14.5, 2, 6.5, ROOM));

	// ---------------------------------------------------------------- L4 materials

	/**
	 * L4: a stone yard (OUTSIDE) with one WOODEN wall section (north, oak planks) among stone brick walls, a cobblestone section in the west wall (stone too, a
	 * different block: the same family, decoy) and a carpet patch on the floor (a soft tick, decoy). Find the wooden wall and touch it.
	 */
	static final Room MATERIALS = new Room("l4", 4, 13, 8, 13, OUT, 6.5, 2, 8.5, -90f, b -> {
		ground(b, 13, 13);
		b.fill(4, 2, 0, 8, 4, 0, Blocks.OAK_PLANKS);  // the wooden wall (logs either side: a hand edit), and no stone border: only a little stone to compare
		b.fill(0, 2, 5, 0, 4, 7, Blocks.COBBLESTONE); // cobblestone in the west wall
		b.fill(3, 2, 9, 4, 2, 10, Blocks.CARPET.red());  // carpet on the floor
	}, spot("spawn", 6.5, 2, 8.5, OUT), spot("at the wooden wall", 6.5, 2, 1.5, OUT));

	// ---------------------------------------------------------------- the tree (after L4)

	/**
	 * TREE: a small walled yard (OUTSIDE) with one oak tree ahead and to your right (a trunk three logs high, its crown above your head) and a bush on your
	 * left (leaves only: no trunk, nothing to chop). You get an iron axe; chop the trunk down.
	 */
	static final Room TREE = new Room("tree", 18, 15, 9, 16, OUT, 5.5, 2, 12.5, 180f, b -> {
		ground(b, 15, 16); // (no walls: the yard's edge is the silent barrier, so only the tree and the bush answer)
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
		b.fill(7, 5, 2, 11, 6, 6, leaves);                 // the crown, well above your head (lower, it would hide the trunk from the sonar)...
		b.fill(8, 7, 3, 10, 7, 5, leaves);                 // ...and its top
		b.fill(9, 2, 4, 9, 4, 4, Blocks.OAK_LOG);          // the trunk: three logs, all within reach
		b.fill(2, 2, 3, 3, 3, 4, leaves);                  // the bush (decoy)
	}, spot("spawn", 5.5, 2, 12.5, OUT), spot("by the tree", 9.5, 2, 5.5, OUT));

	/** The trunk's logs (room coordinates): the tree is down when none is left. */
	static final int[][] TRUNK = {{9, 2, 4}, {9, 3, 4}, {9, 4, 4}};

	// ---------------------------------------------------------------- L5 auto scan

	/** L5: an open yard (OUTSIDE) with low walls and a stone pillar ahead and to your left: hold R for auto scan, then walk to the pillar by its pings. */
	static final Room AUTO = new Room("l5", 5, 21, 8, 25, OUT, 15.5, 2, 22.5, 180f, b -> {
		ground(b, 21, 25); // (no walls: only the pillar answers)
		b.fill(2, 2, 8, 4, 4, 10, Blocks.STONE);           // the pillar: well to your left and far ahead, not in front of you
	}, spot("spawn", 15.5, 2, 22.5, OUT), spot("by the pillar", 5.5, 2, 9.5, OUT));

	// ---------------------------------------------------------------- the hedge walk (after L5)

	/** HEDGES: a garden walk between hedges (OUTSIDE): three wide, north from the start, then a bend east to a fence gate. Auto scan is on: walk it by ear. */
	static final Room HEDGES = new Room("hedges", 19, 17, 8, 17, OUT, 2.5, 2, 14.5, 180f, b -> {
		ground(b, 17, 17);
		BlockState hedge = Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
		b.fill(0, 2, 0, 16, 3, 16, hedge);                // all hedge...
		b.fill(1, 2, 1, 3, 3, 15, Blocks.AIR);            // ...cut away: the walk north
		b.fill(1, 2, 1, 15, 3, 3, Blocks.AIR);            // ...and the walk east
		// (a grass floor, no path blocks: the path bell is a later lesson)
		b.set(16, 2, 2, Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(net.minecraft.world.level.block.FenceGateBlock.FACING, Direction.EAST));
	}, spot("spawn", 2.5, 2, 14.5, OUT), spot("the bend", 2.5, 2, 2.5, OUT), spot("at the gate", 14.5, 2, 2.5, OUT));

	// ---------------------------------------------------------------- L6 vanilla moves

	/**
	 * L6: an obstacle walk east (OUTSIDE, a walled lane) to a cottage (ROOM) on top of a stone base: a block one high to jump onto (one thud), a raised walk, three
	 * stairs up, a landing, a ladder UP the wall at its east end to the top, and the cottage's door up there.
	 */
	static final Room MOVES = new Room("l6", 6, 35, 22, 15, OUT, 2.5, 2, 7.5, -90f, b -> {
		ground(b, 35, 15);
		b.fill(0, 2, 5, 20, 19, 5, Blocks.STONE_BRICKS); // the lane's north wall
		b.fill(0, 2, 9, 20, 19, 9, Blocks.STONE_BRICKS); // and south wall
		b.fill(0, 2, 6, 0, 19, 8, Blocks.STONE_BRICKS);  // and its west end
		b.fill(9, 2, 6, 11, 2, 8, Blocks.STONE);         // one block up (jump onto it), a raised walk 3 long, a good walk from the start
		BlockState st = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
		for (int k = 0; k < 6; k++) { // six stairs up to a landing (its top at y 8: you stand at y 9)
			b.fill(12 + k, 2, 6, 12 + k, 2 + k, 8, Blocks.STONE);
			b.fill(12 + k, 3 + k, 6, 12 + k, 3 + k, 8, st);
		}
		b.fill(18, 2, 6, 20, 8, 8, Blocks.STONE);       // the landing
		b.fill(21, 2, 6, 31, 16, 10, Blocks.STONE);     // the tall base (top y 16: you stand at y 17): the wall the ladder climbs, and the cottage's foundation
		BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST);
		b.fill(20, 9, 6, 20, 16, 8, ladder);             // a ladder eight blocks up the base's west face (x 20, at the landing's end)
		// the cottage on top: floor at y 16, you stand at y 17; a walk of four blocks from the ladder to its door
		b.fill(25, 16, 4, 31, 16, 10, Blocks.OAK_PLANKS);
		b.walls(25, 4, 31, 10, 17, 19, Blocks.SPRUCE_PLANKS);
		b.fill(25, 20, 4, 31, 20, 10, Blocks.SPRUCE_PLANKS);
		b.door(25, 17, 7, Blocks.OAK_DOOR, Direction.EAST);
		b.fill(21, 17, 5, 24, 19, 5, Blocks.STONE_BRICKS); // the lane walls run on, up over the top of the base
		b.fill(21, 17, 9, 24, 19, 9, Blocks.STONE_BRICKS);
	}, spot("spawn", 2.5, 2, 7.5, OUT), spot("on the step", 10.5, 3, 7.5, OUT), spot("top of the stairs", 19.5, 9, 7.5, OUT),
		spot("top of the ladder", 21.5, 17, 7.5, OUT), spot("in the cottage", 28.5, 17, 7.5, ROOM));

	// ---------------------------------------------------------------- L7 ledges

	/**
	 * L7: a terrace (OUTSIDE) five blocks above the lower ground to the north: walk until the edge tone, sneak to the lip. A ridge three blocks wide runs north
	 * off it, a few steps to your right, with drops on both sides (the side tones). Decoy: the east part of the terrace steps down only two blocks (a step,
	 * not a ledge). Falling off is allowed here (and hurts a little): you land on the lower ground, then go back up.
	 */
	static final Room LEDGES = new Room("l7", 7, 21, 13, 21, OUT, 6.5, 7, 16.5, 180f, b -> {
		ground(b, 21, 21);                               // the lower ground (top y 1)
		b.fill(0, 2, 10, 20, 6, 20, Blocks.STONE);       // the terrace (top y 6: you stand at y 7)
		b.fill(0, 6, 10, 20, 6, 20, Blocks.GRASS_BLOCK);
		b.fill(16, 5, 10, 20, 6, 20, Blocks.AIR);        // the east part two lower (top y 4)
		b.fill(16, 4, 10, 20, 4, 20, Blocks.GRASS_BLOCK);
		b.fill(9, 2, 2, 11, 6, 9, Blocks.STONE);         // the ridge, three wide, running north
		b.fill(9, 6, 2, 11, 6, 9, Blocks.GRASS_BLOCK);
		b.fill(12, 7, 10, 12, 10, 20, Blocks.BARRIER);   // an invisible wall right of the ridge's start: walk right and you are stopped there, at the ridge
	}, spot("spawn", 6.5, 7, 16.5, OUT), spot("near the lip", 6.5, 7, 10.5, OUT), spot("on the ridge", 10.5, 7, 5.5, OUT),
		spot("by the low step", 14.5, 7, 15.5, OUT), spot("ridge end", 10.5, 7, 2.5, OUT));

	// ---------------------------------------------------------------- L8 echo indoors

	/**
	 * L8: a house (ROOM) of two rooms. You start in the small west room; its door (in the dividing wall) is the nearest way out. The big east room's way out
	 * is a doorway at the east end of its south wall, to a garden, and the ceiling drops low (one block above your head: the low-ceiling cue) in front of it,
	 * so you walk under it on your way out.
	 */
	static final Room HOUSE = new Room("l8", 8, 19, 8, 17, ROOM, 3.5, 2, 8.5, 180f, b -> {
		ground(b, 19, 17);
		b.fill(0, 1, 0, 18, 1, 12, Blocks.OAK_PLANKS);
		b.walls(0, 0, 18, 12, 2, 4, Blocks.SPRUCE_PLANKS);
		b.fill(0, 5, 0, 18, 5, 12, Blocks.SPRUCE_PLANKS);
		b.fill(6, 2, 0, 6, 4, 12, Blocks.SPRUCE_PLANKS);   // the dividing wall
		b.door(6, 2, 4, Blocks.OAK_DOOR, Direction.EAST);
		b.fill(13, 4, 8, 17, 4, 11, Blocks.SPRUCE_PLANKS); // the low part before the garden doorway: its ceiling at y 4 (headroom just two)
		b.fill(15, 2, 12, 15, 3, 12, Blocks.AIR);           // the doorway to the garden
	}, spot("spawn", 3.5, 2, 8.5, ROOM), spot("big room", 10.5, 2, 6.5, ROOM), spot("low passage", 15.5, 2, 9.5, ROOM), spot("garden", 15.5, 2, 15.5, OUT));

	// ---------------------------------------------------------------- L9 round the corner

	/**
	 * L9: a stone corridor (ROOM) with a bend: north from the start, then east to an opening into a little court. The way out is round the corner: its echo is
	 * muffled.
	 */
	static final Room BEND = new Room("l9", 9, 19, 8, 15, ROOM, 2.5, 2, 12.5, 180f, b -> {
		ground(b, 19, 15);
		b.fill(0, 2, 0, 14, 5, 14, Blocks.STONE_BRICKS); // solid block of stone...
		b.fill(2, 2, 2, 3, 3, 13, Blocks.AIR);           // ...the corridor north (2 wide)
		b.fill(2, 2, 2, 13, 3, 3, Blocks.AIR);           // ...and east
		b.fill(14, 2, 2, 14, 3, 3, Blocks.AIR);          // the opening into the court
		b.fill(2, 1, 2, 13, 1, 13, Blocks.STONE);
	}, spot("spawn", 2.5, 2, 12.5, ROOM), spot("the bend", 2.5, 2, 2.5, ROOM), spot("the court", 16.5, 2, 3.5, OUT));

	// ---------------------------------------------------------------- L10 entrances

	/**
	 * L10: a lawn (OUTSIDE) with three buildings. Straight ahead, near: a shed with no way in (walls only). Off to your right: a gazebo (a roof on posts: roofed,
	 * but not a room). Further off, behind the shed and to the left: a cottage whose doorway is in its NORTH wall, round the far side, so its click comes
	 * muffled. You visit the shed and the gazebo first, then find the way in.
	 */
	static final Room LAWN = new Room("l10", 10, 33, 9, 27, OUT, 16.5, 2, 23.5, 180f, b -> {
		ground(b, 33, 27);
		house(b, 13, 12, 19, 17, Blocks.SPRUCE_PLANKS);          // the shed: no door at all
		house(b, 8, 3, 14, 9, Blocks.OAK_PLANKS);                // the cottage (nearer the gazebo than it was)...
		b.fill(11, 2, 3, 12, 3, 3, Blocks.AIR);                  // ...with a doorway in its north wall
		for (int[] p : new int[][]{{24, 14}, {29, 14}, {24, 19}, {29, 19}}) b.fill(p[0], 2, p[1], p[0], 4, p[1], Blocks.OAK_FENCE); // the gazebo's posts
		b.fill(24, 5, 14, 29, 5, 19, Blocks.OAK_SLAB);           // and its roof
		b.fill(8, 2, 10, 8, 6, 26, Blocks.BARRIER);              // an invisible wall five blocks left of the shed, from behind the start up to the cottage: no walking straight to it
	}, spot("spawn", 16.5, 2, 23.5, OUT), spot("by the shed", 16.5, 2, 18.5, OUT), spot("in the gazebo", 26.5, 2, 16.5, OUT),
		spot("by the doorway", 11.5, 2, 1.5, OUT), spot("in the cottage", 11.5, 2, 6.5, ROOM));

	// ---------------------------------------------------------------- L11 caves

	/**
	 * L11: a cave (CAVE) in solid stone. A cavern; a BIG tunnel east that turns south (round the corner) to a shaft down into a lower cave (a ladder in it); a
	 * SMALL tunnel north to a little chamber; and a shallow alcove west that must NOT echo. The cave floor is at y 4 (you stand at y 5); the lower cave at y 1.
	 */
	static final Room CAVES = new Room("l11", 11, 33, 14, 33, CAVE, 16.5, 5, 17.5, -90f, b -> {
		b.fill(-6, -4, -6, 38, 17, 38, Blocks.STONE);   // rock beyond the room's box too, so up at its edge you are still deep in a cave (the box itself is silent)
		b.fill(0, 0, 0, 32, 13, 32, Blocks.STONE);
		b.fill(4, 9, 4, 28, 9, 28, Blocks.ANDESITE);     // a seam of andesite in the roof, for texture
		b.fill(12, 5, 14, 20, 8, 20, Blocks.AIR);        // the cavern
		b.fill(20, 5, 15, 23, 7, 19, Blocks.AIR);        // the mouth of the big tunnel, wide (a funnel: no corners to catch on)
		b.fill(21, 5, 16, 31, 7, 18, Blocks.AIR);        // the big tunnel east...
		b.fill(29, 5, 19, 31, 7, 31, Blocks.AIR);        // ...turning south (13 long: well out of sight)
		b.fill(29, 1, 29, 30, 4, 30, Blocks.AIR);        // the shaft down, at the passage's end
		b.fill(22, 1, 24, 30, 3, 31, Blocks.AIR);        // the lower cave (x 31 stays rock: the ladder hangs on it)
		b.fill(30, 1, 30, 30, 4, 30, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST)); // a ladder down it (on the rock east of the shaft)
		b.fill(16, 5, 3, 17, 6, 13, Blocks.AIR);         // the small tunnel north (two wide, two high: a one-wide crack close by is not echoed)
		b.fill(14, 5, 1, 18, 7, 3, Blocks.AIR);          // its little chamber
		b.fill(10, 5, 16, 11, 7, 18, Blocks.AIR);        // the alcove (decoy)
	}, spot("spawn", 16.5, 5, 17.5, CAVE), spot("in the big tunnel", 25.5, 5, 17.5, CAVE), spot("round the corner", 30.5, 5, 25.5, CAVE),
		spot("lower cave", 26.5, 1, 27.5, CAVE));

	// ---------------------------------------------------------------- L12 path bell

	/** L12: a meadow (OUTSIDE) with a winding dirt path from the start to a well; off to one side a stone plaza (no bell there: decoy). */
	static final Room MEADOW = new Room("l12", 12, 35, 8, 27, OUT, 3.5, 2, 23.5, 180f, b -> {
		ground(b, 35, 27);
		b.fill(3, 1, 13, 4, 1, 24, Blocks.DIRT_PATH);    // north...
		b.fill(3, 1, 13, 21, 1, 14, Blocks.DIRT_PATH);   // ...east...
		b.fill(20, 1, 4, 21, 1, 14, Blocks.DIRT_PATH);   // ...north...
		b.fill(20, 1, 4, 30, 1, 5, Blocks.DIRT_PATH);    // ...east to the well
		b.walls(30, 2, 32, 4, 2, 2, Blocks.COBBLESTONE); // the well
		b.set(31, 2, 3, Blocks.WATER);
		b.set(31, 1, 3, Blocks.STONE);
		b.fill(8, 1, 18, 14, 1, 24, Blocks.SMOOTH_STONE); // the plaza
	}, spot("spawn", 3.5, 2, 23.5, OUT), spot("on the path", 3.5, 2, 15.5, OUT), spot("on the plaza", 11.5, 2, 21.5, OUT), spot("at the well", 28.5, 2, 4.5, OUT));

	// ---------------------------------------------------------------- L13 water and lava

	/**
	 * L13: a lava pit fenced all round, off to your left (walk up to its fence by its sound: heard, never touched), and a pond (OUTSIDE) to swim across. The
	 * ground is thicker here (top y 3, you stand at y 4).
	 */
	static final Room POND = new Room("l13", 13, 25, 9, 21, OUT, 12.5, 4, 3.5, 0f, b -> {
		b.fill(0, 0, 0, 24, 0, 20, Blocks.STONE);
		b.fill(0, 1, 0, 24, 2, 20, Blocks.DIRT);
		b.fill(0, 3, 0, 24, 3, 20, Blocks.GRASS_BLOCK);
		b.fill(6, 2, 7, 18, 3, 13, Blocks.WATER);        // the pond, two deep
		b.fill(20, 3, 2, 22, 3, 4, Blocks.LAVA);         // the lava pit
		b.fill(20, 2, 2, 22, 2, 4, Blocks.STONE);
		b.walls(19, 1, 23, 5, 4, 4, Blocks.NETHER_BRICK_FENCE); // fenced all round (a fence that lava can not set alight)
		b.fill(5, 4, 6, 5, 7, 14, Blocks.BARRIER);       // invisible sides to the pond: the only way out is the far bank
		b.fill(21, 4, 6, 21, 7, 14, Blocks.BARRIER);
	}, spot("spawn", 12.5, 4, 3.5, OUT), spot("by the lava", 17.5, 4, 3.5, OUT), spot("far bank", 12.5, 4, 16.5, OUT));

	// ---------------------------------------------------------------- L14 player jingle

	/**
	 * L14: a garden (OUTSIDE) cut in two by a long wall with a gap at its east end. Beyond the gap: three stairs up to a plateau, then a ridge two wide and
	 * three up, running west; the test dummy (a jack o'lantern on coal) waits at its far end. A long way round, with a climb and a narrow walk on the way.
	 */
	static final Room DUMMY = new Room("l14", 14, 21, 8, 31, OUT, 5.5, 2, 25.5, 180f, b -> {
		ground(b, 21, 31);
		b.fill(0, 2, 10, 16, 4, 10, Blocks.STONE_BRICKS); // the dividing wall (no outer walls: the gap is its east end, x 17-20)
		BlockState up = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
		for (int k = 0; k < 3; k++) { // three stairs up, northward, at x 18-19: z 9, 8, 7
			if (k > 0) b.fill(18, 2, 9 - k, 19, 1 + k, 9 - k, Blocks.STONE);
			b.fill(18, 2 + k, 9 - k, 19, 2 + k, 9 - k, up);
		}
		b.fill(18, 2, 4, 19, 4, 6, Blocks.STONE);         // the plateau (top y 4: you stand at y 5)
		b.fill(4, 2, 4, 17, 4, 5, Blocks.STONE);          // the ridge west, two wide, with a drop of three on both sides
		b.set(5, 5, 4, Blocks.COAL_BLOCK);
		b.set(5, 6, 4, Blocks.JACK_O_LANTERN);
	}, spot("spawn", 5.5, 2, 25.5, OUT), spot("through the gap", 18.5, 2, 10.5, OUT), spot("on the plateau", 18.5, 6, 4.5, OUT), spot("by the dummy", 6.5, 6, 4.5, OUT));

	// ---------------------------------------------------------------- L15 waypoints

	/**
	 * L15: a market square (OUTSIDE): cobblestone round a fountain, a few stalls. Save a waypoint at the fountain, then follow it back from the far corner,
	 * which a long wall cuts off: the guide leads you round its east end.
	 */
	static final Room SQUARE = new Room("l15", 15, 31, 9, 31, OUT, 15.5, 2, 12.5, 0f, b -> {
		ground(b, 31, 31);
		b.fill(4, 1, 4, 26, 1, 26, Blocks.COBBLESTONE);
		b.walls(13, 13, 17, 17, 2, 2, Blocks.STONE_BRICKS); // the fountain's rim
		b.fill(14, 1, 14, 16, 1, 16, Blocks.STONE);
		b.fill(14, 2, 14, 16, 2, 16, Blocks.WATER);
		b.fill(0, 2, 21, 20, 4, 21, Blocks.STONE_BRICKS);  // the wall cutting the far corner off
		for (int[] s : new int[][]{{6, 6}, {22, 6}, {24, 24}}) { // stalls: four posts and a wool roof
			for (int[] p : new int[][]{{0, 0}, {2, 0}, {0, 2}, {2, 2}}) b.fill(s[0] + p[0], 2, s[1] + p[1], s[0] + p[0], 3, s[1] + p[1], Blocks.OAK_FENCE);
			b.fill(s[0], 4, s[1], s[0] + 2, 4, s[1] + 2, Blocks.WOOL.red());
		}
	}, spot("spawn", 15.5, 2, 12.5, OUT), spot("far corner", 3.5, 2, 27.5, OUT), spot("by the fountain", 15.5, 2, 11.5, OUT));


	// ---------------------------------------------------------------- L17 graduation

	/**
	 * L17: everything together. A dirt path north (the bell), over a raised walk with drops on both sides (the ledge warning), to a cottage (through it: the
	 * echo finds its back door), on through a cave in a hill (the cave echo), out of the hill's west side, where the test dummy waits (the jingle). The hand
	 * edits (RoomEdits) add silent railings along the path.
	 */
	static final Room COURSE = new Room("l17", 17, 41, 16, 91, OUT, 19.5, 2, 89.5, 180f, b -> {
		ground(b, 41, 91);
		b.fill(19, 1, 26, 20, 1, 89, Blocks.DIRT_PATH);          // the path, 50 blocks longer than it was: it starts far back...
		b.fill(15, 2, 41, 15, 9, 89, Blocks.BARRIER);            // ...with the same silent railings the hand edits put along its end
		b.fill(24, 2, 41, 24, 9, 89, Blocks.BARRIER);
		b.fill(19, 1, 27, 20, 1, 38, Blocks.DIRT);               // ...plain dirt under the raised walk (a path block with a block on it turns to dirt by itself)
		// a raised walk across it, four wide and four up (a real drop on both sides: the ledge warning), stairs at each end
		b.fill(18, 2, 31, 21, 5, 34, Blocks.STONE);
		BlockState up = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
		BlockState down = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
		for (int k = 0; k < 4; k++) {
			if (k > 0) { b.fill(18, 2, 38 - k, 21, 1 + k, 38 - k, Blocks.STONE); b.fill(18, 2, 27 + k, 21, 1 + k, 27 + k, Blocks.STONE); }
			b.fill(18, 2 + k, 38 - k, 21, 2 + k, 38 - k, up);   // up, northward: z 38 .. 35
			b.fill(18, 2 + k, 27 + k, 21, 2 + k, 27 + k, down); // down, northward: z 30 .. 27
		}
		b.fill(15, 1, 18, 24, 1, 25, Blocks.OAK_PLANKS);         // the cottage
		b.walls(15, 18, 24, 25, 2, 4, Blocks.SPRUCE_PLANKS);
		b.fill(15, 5, 18, 24, 5, 25, Blocks.SPRUCE_PLANKS);
		b.door(19, 2, 25, Blocks.OAK_DOOR, Direction.NORTH);     // front door (south wall)
		b.door(20, 2, 18, Blocks.OAK_DOOR, Direction.NORTH);     // back door (north wall)
		b.fill(20, 1, 16, 27, 1, 17, Blocks.DIRT_PATH);           // round to the hill's tunnel (east of the door: from the cavern the door is out of sight)
		b.fill(6, 1, 1, 34, 12, 15, Blocks.STONE);               // the hill (its floor rock too: a cave)
		b.fill(25, 2, 9, 27, 4, 15, Blocks.AIR);                 // the tunnel in from the south
		b.fill(14, 2, 4, 27, 5, 9, Blocks.AIR);                  // the cavern
		b.fill(6, 2, 5, 13, 3, 6, Blocks.AIR);                   // the tunnel out to the west
		b.set(3, 2, 6, Blocks.COAL_BLOCK);                       // the dummy waiting outside
		b.set(3, 3, 6, Blocks.JACK_O_LANTERN);
	}, spot("spawn", 19.5, 2, 89.5, OUT), spot("on the raised walk", 19.5, 6, 32.5, OUT), spot("in the cottage", 19.5, 2, 21.5, ROOM), spot("in the cavern", 17.5, 2, 6.5, CAVE), spot("finish", 1.5, 2, 7.5, OUT));

	/** All rooms, in lesson order. */
	static final List<Room> ALL = List.of(WELCOME, GARDEN, COURTYARD, SWEEP, MATERIALS, TREE, AUTO, HEDGES, LEDGES, BEND, HOUSE, LAWN, MOVES, CAVES, MEADOW, POND,
		DUMMY, SQUARE, COURSE);

	/** Room slots (a room's index is its place along x, not its lesson number: new rooms take new slots, so old ones never move): one past the highest. */
	static final int SLOTS = ALL.stream().mapToInt(Room::index).max().orElse(0) + 1;

	static Room byId(String id) {
		for (Room r : ALL) if (r.id().equals(id)) return r;
		return null;
	}

	/** Grass on dirt, the whole room. */
	private static void ground(RoomBuilder b, int w, int d) {
		b.fill(0, 0, 0, w - 1, 0, d - 1, Blocks.DIRT);
		b.fill(0, 1, 0, w - 1, 1, d - 1, Blocks.GRASS_BLOCK);
	}

	/** A small building: a floor, walls three high, a flat roof (no way in until one is cut). */
	private static void house(RoomBuilder b, int x0, int z0, int x1, int z1, Block wall) {
		b.fill(x0, 1, z0, x1, 1, z1, Blocks.OAK_PLANKS);
		b.walls(x0, z0, x1, z1, 2, 4, wall);
		b.fill(x0, 5, z0, x1, 5, z1, wall);
	}

	private static String hash;

	/** A fingerprint of every block of every room: the saved tutorial world is rebuilt when it changes. */
	static synchronized String hash() {
		if (hash != null) return hash;
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			for (Room r : ALL) {
				md.update((r.id() + ":" + r.index() + "\n").getBytes(StandardCharsets.UTF_8));
				r.build((x, y, z, s) -> md.update((x + "," + y + "," + z + "=" + s + "\n").getBytes(StandardCharsets.UTF_8)));
			}
			StringBuilder sb = new StringBuilder();
			for (byte by : md.digest()) sb.append(String.format("%02x", by));
			return hash = sb.toString();
		} catch (Exception e) { throw new IllegalStateException(e); }
	}
}
