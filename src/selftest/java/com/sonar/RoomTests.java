package com.sonar;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * THE TUTORIAL'S ROOMS, each built with the same RoomBuilder definitions as the game into a fake world, and checked:
 *  - every named spot is standable, inside the room's box, and heard as the space it should be (OUTSIDE / ROOM / CAVE) by the shared room and cave tests;
 *  - its barrier box changes nothing Sonar hears (room survey, 64 rays, cave holes the same without it);
 *  - you can walk from the spawn to the goal, and the route never passes through a barrier;
 *  - the detector the lesson teaches fires there, and its decoys stay silent.
 */
final class RoomTests {
	private RoomTests() {}

	static final SonarConfig CFG = new SonarConfig();

	static World world(TutorialRooms.Room r) { World w = new World(); r.build(w::set); return w; }

	static double wx(TutorialRooms.Room r, TutorialRooms.Spot s) { return r.ox() + s.x(); }
	static double wy(TutorialRooms.Room r, TutorialRooms.Spot s) { return r.oy() + s.y(); }
	static double wz(TutorialRooms.Room r, TutorialRooms.Spot s) { return r.oz() + s.z(); }

	/** A whole scan from a spot, facing `yaw` (game yaw). */
	static Scan scan(World w, TutorialRooms.Room r, TutorialRooms.Spot s, float yaw) {
		return new Scan(w, CFG, wx(r, s), wy(r, s) + 1.62, wz(r, s), yaw, false).finish();
	}

	static Rooms.Space space(World w, TutorialRooms.Room r, TutorialRooms.Spot s) {
		Rooms.lastRoom = false; // (the room test's memory of the last spot: each spot is judged on its own)
		return Rooms.space(null, new Scan(w, CFG, wx(r, s), wy(r, s) + 1.62, wz(r, s), r.yaw(), false));
	}

	/** Degrees from where `yaw` faces to world point x,z from the spot (+ right). */
	static double rel(TutorialRooms.Room r, TutorialRooms.Spot s, float yaw, double x, double z) {
		double y = Math.toRadians(yaw), fx = -Math.sin(y), fz = Math.cos(y), rx = -Math.cos(y), rz = -Math.sin(y);
		double dx = x - wx(r, s), dz = z - wz(r, s);
		return Math.toDegrees(Math.atan2(dx * rx + dz * rz, dx * fx + dz * fz));
	}

	/** Degrees from where `yaw` faces to an echo's bearing (atan2 z,x) (+ right). */
	static double relBearing(float yaw, double bearing) {
		double y = Math.toRadians(yaw), fwd = Math.atan2(Math.cos(y), -Math.sin(y));
		return Math.toDegrees(Math.atan2(Math.sin(bearing - fwd), Math.cos(bearing - fwd)));
	}

	static List<double[]> route(World w, TutorialRooms.Room r, TutorialRooms.Spot a, TutorialRooms.Spot b, boolean swim) {
		var s = new PlayerChime.Search(w, wx(r, a), wy(r, a), wz(r, a), new PlayerChime.Tgt(null, wx(r, b), wy(r, b), wz(r, b), wy(r, b) + 1.6), true, true);
		s.swim = swim;
		while (!s.run(400000)) {}
		return s.path();
	}

	/** The route reached its goal (its last point within 1.5 blocks across and 2 up or down). */
	static boolean reaches(List<double[]> route, TutorialRooms.Room r, TutorialRooms.Spot b) {
		if (route == null || route.isEmpty()) return false;
		double[] e = route.get(route.size() - 1);
		return Math.hypot(e[0] - wx(r, b), e[2] - wz(r, b)) < 1.6 && Math.abs(e[1] - wy(r, b)) < 2.5;
	}

	static void run(BiConsumer<Boolean, String> check) {
		var pos = new BlockPos.MutableBlockPos();
		for (TutorialRooms.Room r : TutorialRooms.ALL) {
			World w = world(r), scene = new World();
			r.buildScene(scene::set);
			BlockGetter heard = Sense.hear(w);
			// the spots
			for (TutorialRooms.Spot s : r.spots()) {
				int x = (int) Math.floor(wx(r, s)), y = (int) Math.floor(wy(r, s)), z = (int) Math.floor(wz(r, s));
				boolean stand = !w.getBlockState(new BlockPos(x, y - 1, z)).getCollisionShape(w, new BlockPos(x, y - 1, z)).isEmpty()
					&& BlockRules.passable(w, x, y, z, pos) && BlockRules.passable(w, x, y + 1, z, pos);
				check.accept(stand && r.inside(wx(r, s), wy(r, s), wz(r, s)), r.id() + " " + s.name() + ": standable, inside the room");
				Rooms.Space sp = space(w, r, s);
				check.accept(sp == s.space(), r.id() + " " + s.name() + ": heard as " + s.space() + (sp == s.space() ? "" : " (got " + sp + ")"));
			}
			check.accept(r.spots()[0].x() == r.sx() && r.spots()[0].z() == r.sz(), r.id() + ": its first spot is its spawn");
			check.accept(Sense.guide(w.getBlockState(new BlockPos(r.ox() - 1, r.oy() + 1, r.oz() + 1))) && Sense.guide(w.getBlockState(new BlockPos(r.ox() + 2, r.oy() + r.h(), r.oz() + 2)))
				&& Sense.guide(w.getBlockState(new BlockPos(r.ox() + r.w(), r.oy() + r.h(), r.oz() + r.d()))), r.id() + ": sealed in a barrier box");
			// the barrier box is not there for Sonar
			var spawn = r.spots()[0];
			Vec3 eye = new Vec3(wx(r, spawn), wy(r, spawn) + 1.62, wz(r, spawn));
			boolean same = java.util.Arrays.equals(Rooms.survey(heard, eye), Rooms.survey(Sense.hear(scene), eye));
			for (int k = 0; k < 16 && same; k++) {
				double a = k * Math.PI / 8;
				for (double dy : new double[]{-0.5, 0, 0.6, 1})
					same = Rooms.ray(heard, eye, Math.cos(a), dy, Math.sin(a), 64, false, false, false) == Rooms.ray(Sense.hear(scene), eye, Math.cos(a), dy, Math.sin(a), 64, false, false, false);
			}
			check.accept(same, r.id() + ": its barrier box changes nothing Sonar hears (room survey and 64 rays the same without it)");
			// the same sounds, room / outside / cave, echoes; and ledges only lost at the box's own edge (the railing rule)
			Scan a = scan(w, r, spawn, r.yaw()), b = new Scan(scene, CFG, wx(r, spawn), wy(r, spawn) + 1.62, wz(r, spawn), r.yaw(), false).finish();
			boolean sounds = true;
			for (int cat = 0; cat < 4; cat++) for (int i = 0; i < Scan.SECTORS; i++) {
				var ca = a.best[cat][i]; var cb = b.best[cat][i];
				if (ca.state != cb.state || (ca.state != null && (ca.x != cb.x || ca.y != cb.y || ca.z != cb.z))) sounds = false;
			}
			check.accept(sounds, r.id() + ": the same sonar sounds with and without its barrier box");
			Rooms.lastRoom = false; Rooms.Space sa = Rooms.space(null, new Scan(w, CFG, wx(r, spawn), wy(r, spawn) + 1.62, wz(r, spawn), r.yaw(), false));
			Rooms.lastRoom = false; Rooms.Space sb = Rooms.space(null, new Scan(scene, CFG, wx(r, spawn), wy(r, spawn) + 1.62, wz(r, spawn), r.yaw(), false));
			check.accept(sa == sb, r.id() + ": heard as the same space with and without its barrier box (" + sa + ")");
			String ea = echoes(a), eb = echoes(b);
			check.accept(ea.equals(eb), r.id() + ": the same echoes (ways out, entrances) with and without its barrier box");
			boolean ledgesOk = true;
			for (int i = 0; i < Scan.YAW; i++) {
				var la = a.ledges[i]; var lb = b.ledges[i];
				if (la.d == lb.d && la.drop == lb.drop) continue;
				boolean atEdge = lb.d != Float.MAX_VALUE && (lb.x + lb.ox < r.ox() || lb.x + lb.ox >= r.ox() + r.w() || lb.z + lb.oz < r.oz() || lb.z + lb.oz >= r.oz() + r.d());
				if (!atEdge && !(la.d == Float.MAX_VALUE && lb.d == Float.MAX_VALUE)) ledgesOk = false;
			}
			check.accept(ledgesOk, r.id() + ": the same ledges with and without its barrier box (except the void past the box's own edge)");
			int fy = (int) Math.floor(wy(r, spawn));
			check.accept(holes(heard, eye, fy, r.yaw()).equals(holes(scene, eye, fy, r.yaw())), r.id() + ": the same cave holes with and without its barrier box");
			// walking to the goal
			if (r.spots().length > 1) {
				var goal = r.goal();
				var rt = route(w, r, spawn, goal, r == TutorialRooms.POND);
				boolean through = false;
				if (rt != null) for (double[] c : rt) for (int dy = 0; dy <= 1; dy++) if (Sense.guide(w.getBlockState(BlockPos.containing(c[0], c[1] + dy, c[2])))) through = true;
				check.accept(reaches(rt, r, goal) && !through, r.id() + ": you can walk from the spawn to " + goal.name() + " (never through a barrier)");
			}
		}
		check.accept(TutorialRooms.ALL.stream().map(TutorialRooms.Room::index).distinct().count() == TutorialRooms.ALL.size()
			&& TutorialRooms.ALL.stream().allMatch(r -> r.index() >= 0 && r.index() < TutorialRooms.SLOTS), "rooms: each in its own slot, all within the slots the world tracks");
		gapRule(check);
		sweep(check);
		materials(check);
		tree(check);
		autoYard(check);
		hedges(check);
		moves(check);
		ledges(check);
		house(check);
		bend(check);
		lawn(check);
		caves(check);
		meadow(check);
		pond(check);
		dummy(check);
		course(check);
		edits(check);
		bridges(check);
	}

	/** Bridges 1, 2, 3 wide (5 down, running north): the sides measured to the real drop lines, and the warning scaled to the width. */
	static void bridges(BiConsumer<Boolean, String> check) {
		var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
		for (int w = 1; w <= 4; w++) {
			World world = new World().fill(-20, 58, -20, 20, 58, 20, stone).fill(0, 59, -20, w - 1, 63, 20, stone); // ground 5 below, the bridge on top (x 0..w-1)
			double cx = w / 2.0; // the bridge's middle
			Scan sc = new Scan(world, CFG, cx, 65.62, 0.5, 180f, false).finish();
			double yaw = Math.toRadians(180f);
			double[] sd = Ledges.bridgeSides(sc, -Math.sin(yaw), Math.cos(yaw), -Math.cos(yaw), -Math.sin(yaw));
			if (w == 4) { check.accept(sd == null, "bridge 4 wide: a wide path, not a bridge (the corridor rule as before)"); continue; }
			boolean measured = sd != null && Math.abs(sd[0] - w / 2.0) < 0.15 && Math.abs(sd[1] - w / 2.0) < 0.15;
			check.accept(measured, "bridge " + w + " wide: from the middle, each drop line is " + w / 2.0 + " away" + (sd == null ? " (not found)" : String.format(" (%.2f, %.2f)", sd[0], sd[1])));
		}
		// a wall a block away: its sound is placed out where the game can place it (2 blocks), and it is still the loudest
		World wall = new World().fill(-10, 63, -10, 10, 63, 10, stone).fill(-3, 64, -2, 3, 66, -2, stone);
		Scan ws = new Scan(wall, CFG, 0.5, 65.62, 0.5, 180f, false).finish();
		var near = Cues.at(ws, CFG, 1.0f, 0.5, 65.5, -1.5, net.minecraft.sounds.SoundEvents.STONE_BREAK, 1f, 1f, 0);
		var far = Cues.at(ws, CFG, 6.0f, 0.5, 65.5, -5.5, net.minecraft.sounds.SoundEvents.STONE_BREAK, 1f, 1f, 0);
		double nd = Math.sqrt(Math.pow(near.x() - 0.5, 2) + Math.pow(near.y() - 65.62, 2) + Math.pow(near.z() - 0.5, 2));
		check.accept(nd >= 1.99 && near.vol() > far.vol(), "sonar: a wall a block away is placed 2 blocks out (not inside your head), and still louder than a far one (" + String.format("%.2f", nd) + ")");
		var diagNear = Cues.at(ws, CFG, 1.0f, 2.0, 65.5, -1.0, net.minecraft.sounds.SoundEvents.STONE_BREAK, 1f, 1f, 1);
		var diagFar = Cues.at(ws, CFG, 6.0f, 5.0, 65.5, -4.0, net.minecraft.sounds.SoundEvents.STONE_BREAK, 1f, 1f, 1);
		check.accept(Math.abs(diagNear.vol() - Math.min(1f, CFG.volume * Cues.closeness(1.0f, ws.range))) < 0.01 && diagFar.vol() < CFG.volume * Cues.closeness(6.0f, ws.range) * 0.7,
			"sonar: a block close by at an angle is not dimmed (a wall beside you is heard); far off at an angle it is");
		float[] c3 = Ledges.bridgeLevels(1.5, 1.5), d3 = Ledges.bridgeLevels(1.0, 2.0), e3 = Ledges.bridgeLevels(0.5, 2.5);
		check.accept(c3[0] == 0 && c3[1] == 0 && d3[0] > 0 && d3[0] < 0.6 && d3[1] == 0 && e3[0] == 1, "bridge 3 wide: silent centred; drifting left fades the left side in (1.25 from the drop), full at 0.5; the far side stays silent");
		float[] c2 = Ledges.bridgeLevels(1.0, 1.0), d2 = Ledges.bridgeLevels(0.6, 1.4);
		check.accept(c2[0] == 0 && c2[1] == 0 && d2[0] > 0 && d2[1] == 0, "bridge 2 wide: silent centred; a small drift warns on that side");
		float[] c1 = Ledges.bridgeLevels(0.5, 0.5), d1 = Ledges.bridgeLevels(0.25, 0.75);
		check.accept(c1[0] > 0 && c1[0] < 0.05 && c1[1] > 0 && c1[1] < 0.05 && d1[0] > 0.3 && d1[1] < 0.05, "bridge 1 wide: a faint tone both sides centred; drifting brings that side up");
	}

	/** The echoes a ping from this scan gives (ways out inside, entrances outside), as text to compare. */
	private static String echoes(Scan s) {
		StringBuilder sb = new StringBuilder();
		for (double[] e : ExitGuide.find(null, s)) sb.append(java.util.Arrays.toString(e));
		sb.append('|');
		for (double[] e : ExitGuide.entrances(null, s)) sb.append(java.util.Arrays.toString(e));
		return sb.toString();
	}

	private static String holes(BlockGetter w, Vec3 eye, int fy, float yaw) {
		StringBuilder sb = new StringBuilder();
		for (double[] d : Echo.caveDrops(w, eye.x, eye.y, eye.z, fy, yaw, 32)) sb.append(java.util.Arrays.toString(d));
		return sb.toString();
	}

	// ---------------------------------------------------------------- the lessons' detectors

	/** L3: the gap chime plays on your right, where the opening is, and nowhere else (the notch is too narrow). */
	static void sweep(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.SWEEP;
		World w = world(r);
		var s = scan(w, r, r.spot("spawn"), r.yaw());
		List<Cues.Cue> cues = new ArrayList<>();
		Cues.extraCues(s, CFG, cues);
		List<Long> gaps = new ArrayList<>();
		for (var c : cues) if (c.snd() == SoundEvents.AMETHYST_BLOCK_CHIME) gaps.add(Math.round(Math.toDegrees(c.rel())));
		boolean right = gaps.size() == 1 && gaps.get(0) > 60 && gaps.get(0) < 120;
		check.accept(right, "l3: one gap chime, on your right at the opening " + gaps);
		var sp = r.spot("spawn");
		var ins = ExitGuide.entrances(null, s);
		double want = Math.atan2(r.oz() + 6.5 - wz(r, sp), r.ox() + 11.5 - wx(r, sp));
		boolean click = ins.size() == 1 && Math.abs(Math.atan2(Math.sin(ins.get(0)[0] - want), Math.cos(ins.get(0)[0] - want))) < 0.4;
		check.accept(click, "l3: the echo clicks from the opening (the covered yard's way in), and only there (" + ins.size() + ")");
	}

	/** The gap rule on its own: a doorway close by is a gap; open ground is not; a wide-open side is not. */
	static void gapRule(BiConsumer<Boolean, String> check) {
		var st = Blocks.STONE_BRICKS.defaultBlockState();
		// a room 7x7 (walls 3 from you), one doorway (1 wide) in its east wall, open ground beyond
		World w = new World().fill(-20, 63, -20, 20, 63, 20, Blocks.STONE.defaultBlockState()).fill(-3, 64, -3, 3, 66, 3, st).fill(-2, 64, -2, 2, 66, 2, Blocks.AIR.defaultBlockState()).fill(3, 64, 0, 3, 65, 0, Blocks.AIR.defaultBlockState());
		var g = Cues.gaps(new Scan(w, CFG, 0.5, 65.62, 0.5, -90f, false).finish());
		check.accept(g.size() == 1 && Math.abs(Math.toDegrees(g.get(0))) < 25, "gap cue: a doorway one wide, three blocks away, is a gap (" + g.size() + ")");
		World open = new World().fill(-20, 63, -20, 20, 63, 20, Blocks.STONE.defaultBlockState());
		check.accept(Cues.gaps(new Scan(open, CFG, 0.5, 65.62, 0.5, -90f, false).finish()).isEmpty(), "gap cue: open ground is not a gap");
		World side = new World().fill(-20, 63, -20, 20, 63, 20, Blocks.STONE.defaultBlockState()).fill(-3, 64, -3, -3, 66, 3, st);
		check.accept(Cues.gaps(new Scan(side, CFG, 0.5, 65.62, 0.5, -90f, false).finish()).isEmpty(), "gap cue: one wall beside you and open all round is not a gap");
	}

	/** L4: the wooden wall sounds as wood, on your left (north) as you start facing east; cobblestone and bricks group as stone; the carpet is heard. */
	static void materials(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.MATERIALS;
		World w = world(r);
		var sp = r.spot("spawn");
		var s = scan(w, r, sp, r.yaw());
		boolean wood = false, carpet = false;
		for (var c : s.best[Scan.SOLID]) {
			if (c.state == null) continue;
			if (c.state.is(Blocks.OAK_PLANKS) && rel(r, sp, r.yaw(), c.x + 0.5, c.z + 0.5) < -45) wood = true;
			if (c.state.is(Blocks.CARPET.red())) carpet = true;
		}
		check.accept(wood, "l4: the wooden wall is heard, to your left (north)");
		check.accept(carpet, "l4: the carpet patch is heard (a soft tick)");
		check.accept(!Cues.groupKey(Blocks.OAK_PLANKS.defaultBlockState(), CFG).equals(Cues.groupKey(Blocks.STONE_BRICKS.defaultBlockState(), CFG)), "l4: wood and stone are different groups");
		check.accept(Cues.blockSound(Blocks.OAK_PLANKS.defaultBlockState(), CFG) != Cues.blockSound(Blocks.STONE_BRICKS.defaultBlockState(), CFG), "l4: wood and stone have different sounds");
		check.accept(Cues.materialSound(Blocks.OAK_PLANKS.defaultBlockState()) == SoundEvents.CHISELED_BOOKSHELF_BREAK && Cues.materialSound(Blocks.CRIMSON_PLANKS.defaultBlockState()) == SoundEvents.CHISELED_BOOKSHELF_BREAK
			&& Cues.materialSound(Blocks.CHERRY_DOOR.defaultBlockState()) == SoundEvents.CHISELED_BOOKSHELF_BREAK && Cues.materialSound(Blocks.BAMBOO_PLANKS.defaultBlockState()) == SoundEvents.CHISELED_BOOKSHELF_BREAK
			&& Cues.materialSound(Blocks.SPRUCE_FENCE.defaultBlockState()) == SoundEvents.CHISELED_BOOKSHELF_BREAK && Cues.materialSound(Blocks.OAK_LOG.defaultBlockState()) == SoundEvents.WOOD_BREAK
			&& Cues.materialSound(Blocks.CRIMSON_STEM.defaultBlockState()) != SoundEvents.CHISELED_BOOKSHELF_BREAK, "wood: every kind of made wood sounds as the bookshelf knock; logs and stems keep their own sound");
	}

	/** The tree: its trunk is heard as wood among the leaves, ahead and to the right; the bush on the left is leaves only. Two logs make the trunk. */
	static void tree(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.TREE;
		World w = world(r);
		var sp = r.spot("spawn");
		var s = scan(w, r, sp, r.yaw());
		boolean wood = false, leavesLeft = false;
		StringBuilder heard = new StringBuilder();
		for (var c : s.best[Scan.SOLID]) {
			if (c.state == null) continue;
			double a = rel(r, sp, r.yaw(), c.x + 0.5, c.z + 0.5);
			heard.append(String.format(" %s@%.0f,%d", c.state.getBlock().getDescriptionId().replace("block.minecraft.", ""), a, c.y - r.oy()));
			if (c.state.is(net.minecraft.tags.BlockTags.LOGS) && a > 0 && a < 80) wood = true;
			if (c.state.is(net.minecraft.tags.BlockTags.LEAVES) && a < -10) leavesLeft = true;
		}
		check.accept(wood, "tree: the trunk is heard as wood, ahead and to your right" + (wood ? "" : heard));
		check.accept(leavesLeft, "tree: the bush is heard on your left (leaves: nothing to chop)");
		boolean trunk = true;
		for (int[] t : TutorialRooms.TRUNK) trunk &= w.getBlockState(new BlockPos(r.ox() + t[0], r.oy() + t[1], r.oz() + t[2])).is(net.minecraft.tags.BlockTags.LOGS);
		check.accept(trunk && TutorialRooms.TRUNK.length == 3, "tree: the trunk is three logs, within reach from the ground");
	}

	/** L5: the pillar is heard ahead and to the left of the spawn. */
	static void autoYard(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.AUTO;
		World w = world(r);
		var sp = r.spot("spawn");
		var s = scan(w, r, sp, r.yaw());
		boolean pillar = false;
		for (var c : s.best[Scan.SOLID]) if (c.state != null && c.state.is(net.minecraft.world.level.block.Blocks.STONE) && rel(r, sp, r.yaw(), c.x + 0.5, c.z + 0.5) < 0 && rel(r, sp, r.yaw(), c.x + 0.5, c.z + 0.5) > -45) pillar = true;
		check.accept(pillar, "l5: the stone pillar is heard ahead and to your left");
	}

	/** L17: on the raised walk, ledges on both sides; the hand-made railings keep the path. */
	static void course(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.COURSE;
		World w = world(r);
		var on = r.spot("on the raised walk");
		var s = scan(w, r, on, 180f);
		boolean west = false, east = false;
		for (var l : s.ledges) {
			if (l.d == Float.MAX_VALUE || l.drop < 4 || l.d > 3) continue;
			if (l.ox < 0) west = true;
			if (l.ox > 0) east = true;
		}
		check.accept(west && east, "l17: the raised walk has a real drop (4) on both sides");
	}

	/** The hand edits (RoomEdits) are in the rooms: the sweep's yard roof, the meadow path starting ahead of you, the graduation's railings. */
	static void edits(BiConsumer<Boolean, String> check) {
		check.accept(!RoomEdits.of("l3").isEmpty() && !RoomEdits.of("l17").isEmpty(), "room edits: the exported hand edits load");
		World w = world(TutorialRooms.SWEEP);
		var r = TutorialRooms.SWEEP;
		check.accept(w.getBlockState(new BlockPos(r.ox() + 14, r.oy() + 5, r.oz() + 6)).is(net.minecraft.world.level.block.Blocks.STONE_BRICKS), "room edits: the sweep's covered yard has its roof");
		var m = TutorialRooms.MEADOW;
		World mw = world(m);
		check.accept(!mw.getBlockState(new BlockPos(m.ox() + 3, m.oy() + 1, m.oz() + 23)).is(net.minecraft.world.level.block.Blocks.DIRT_PATH)
			&& mw.getBlockState(new BlockPos(m.ox() + 3, m.oy() + 1, m.oz() + 15)).is(net.minecraft.world.level.block.Blocks.DIRT_PATH), "room edits: the meadow path starts ahead of you, not under you");
		check.accept(RoomEdits.text(RoomEdits.parse("minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]")).equals("minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]")
			&& RoomEdits.parse("minecraft:stone").is(net.minecraft.world.level.block.Blocks.STONE), "room edits: a block written as text reads back the same");
	}

	/** L5: the hedges are heard on both sides of the walk. */
	static void hedges(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.HEDGES; // (the hedge walk)
		World w = world(r);
		var sp = r.spot("spawn");
		var s = scan(w, r, sp, r.yaw());
		boolean left = false, right = false;
		for (var c : s.best[Scan.SOLID]) {
			if (c.state == null || !c.state.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
			double a = rel(r, sp, r.yaw(), c.x + 0.5, c.z + 0.5);
			if (a < -45 && a > -135) left = true;
			if (a > 45 && a < 135) right = true;
		}
		check.accept(left && right, "hedges: hedges heard on your left and your right");
	}

	/** L6: the first obstacle is one block high (you can jump it: one thud); the cottage's door is its way out. */
	static void moves(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.MOVES;
		World w = world(r);
		double top = SonarClient.wallTop(Sense.hear(w), r.ox() + 9, r.oy() + 2, r.oz() + 7) - (r.oy() + 2);
		check.accept(Math.abs(top - 1) < 1e-9, "l6: the first obstacle is one block high (" + top + "): one thud, jump onto it");
		double wall = SonarClient.wallTop(Sense.hear(w), r.ox() + 3, r.oy() + 2, r.oz() + 5) - (r.oy() + 2);
		check.accept(wall > 1.1, "l6: the lane's wall is too tall to jump (two thuds)");
		var in = r.spot("in the cottage");
		var s = scan(w, r, in, 90f); // facing west, toward the door
		var ways = ExitGuide.find(null, s);
		boolean door = ways.stream().anyMatch(e -> Math.abs(relBearing(90f, e[0])) < 30);
		check.accept(door, "l6: inside the cottage, the echo finds its door (" + ways.size() + " ways out)");
	}

	/** L7: the edge tone's detector: the drop ahead of the spawn, both sides of the ridge, and the two-block step is NOT a ledge (only a step down). */
	static void ledges(BiConsumer<Boolean, String> check) {
		{ var r0 = TutorialRooms.LEDGES; World w0 = world(r0);
		  var drops = Echo.caveDrops(w0, r0.ox() + 9.98, r0.oy() + 7 + 1.62, 8.14, r0.oy() + 7, 86f, 32);
		  check.accept(drops.isEmpty(), "l7: on the terrace, a cliff (and the small overhang at the ridge's end) is not a hole into a cave: no echo (" + drops.size() + ")"); }
		var r = TutorialRooms.LEDGES;
		World w = world(r);
		var sp = r.spot("spawn");
		var s = scan(w, r, sp, r.yaw());
		boolean ahead = false;
		for (var l : s.ledges) if (l.d != Float.MAX_VALUE && l.drop >= 4 && Math.abs(rel(r, sp, r.yaw(), l.x + 0.5 + l.ox * 0.5, l.z + 0.5 + l.oz * 0.5)) < 30 && l.d < 8) ahead = true;
		check.accept(ahead, "l7: a ledge (5 down) ahead of the spawn");
		var ridge = r.spot("on the ridge");
		var sr = scan(w, r, ridge, 180f);
		boolean west = false, east = false;
		for (var l : sr.ledges) {
			if (l.d == Float.MAX_VALUE || l.drop < 4 || l.d > 2) continue;
			if (l.ox < 0) west = true;
			if (l.ox > 0) east = true;
		}
		check.accept(west && east, "l7: on the ridge, ledges on both sides");
		var low = r.spot("by the low step");
		var sl = scan(w, r, low, -90f);
		boolean ledgeEast = false, stepEast = false;
		for (var l : sl.ledges) if (l.d != Float.MAX_VALUE && l.ox > 0 && l.d < 5) ledgeEast = true;
		for (var l : sl.drops) if (l.d != Float.MAX_VALUE && l.drop <= 2 && l.d < 5) stepEast = true;
		check.accept(!ledgeEast, "l7: the low east part is only a step down, not a ledge (decoy)" + (stepEast ? "" : " (no step heard either)"));
	}

	/** L8: from the small room the door is a way out; the low passage has the low-ceiling cue; the big room hears the garden doorway too. */
	static void house(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.HOUSE;
		World w = world(r);
		var sp = r.spot("spawn");
		var ways = ExitGuide.find(null, scan(w, r, sp, r.yaw()));
		double doorBearing = Math.atan2(r.oz() + 4.5 - wz(r, sp), r.ox() + 6.5 - wx(r, sp));
		boolean door = ways.stream().anyMatch(e -> Math.abs(Math.atan2(Math.sin(e[0] - doorBearing), Math.cos(e[0] - doorBearing))) < 0.4);
		check.accept(door, "l8: from the small room the echo finds the door (" + ways.size() + " ways out)");
		var low = r.spot("low passage");
		Vec3 e1 = new Vec3(wx(r, low), wy(r, low) + 1.62, wz(r, low));
		var big = r.spot("big room");
		Vec3 e2 = new Vec3(wx(r, big), wy(r, big) + 1.62, wz(r, big));
		check.accept(Rooms.ray(Sense.hear(w), e1, 0, 1, 0, 3, true, true, false) < 1.1 && Rooms.ray(Sense.hear(w), e2, 0, 1, 0, 3, true, true, false) < 1.1,
			"l8: the second room's ceiling is low (a jump in it bumps your head), the user's edit");
		var bw = ExitGuide.find(null, scan(w, r, big, 0f));
		check.accept(bw.size() >= 2, "l8: from the big room, two ways out: the door and the garden doorway (" + bw.size() + ")");
	}

	/** L9: a roofed corridor (the user's layout): the way out, east into the open yard, is round the corner, so its echo is muffled and comes from the corner you walk to. */
	static void bend(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.BEND;
		World w = world(r);
		var sc = scan(w, r, r.spot("spawn"), r.yaw());
		var ways = ExitGuide.find(null, sc);
		boolean hidden = ways.size() == 1 && ways.get(0).length > 7 && ways.get(0)[7] == 1;
		check.accept(hidden, "l9: the way out at the corridor's end echoes, muffled (" + ways.size() + " ways out)");
		// heard from the corner, not straight through the wall: the corridor runs north, the yard is to the east, so the click comes from ahead (north)
		double north = -Math.PI / 2;
		boolean ahead = !ways.isEmpty() && Math.abs(Math.atan2(Math.sin(ways.get(0)[0] - north), Math.cos(ways.get(0)[0] - north))) < Math.toRadians(35);
		check.accept(ahead, "l9: the muffled click comes from the corner you walk to (north), not straight through the walls");
	}

	/** L10: outside, exactly one entrance echoes: the cottage's doorway (not the shed with no door, not the gazebo). */
	private static Scan s2(World w, TutorialRooms.Room r, TutorialRooms.Spot sp) { return scan(w, r, sp, 180f); }

	static void lawn(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.LAWN;
		World w = world(r);
		var sp = r.spot("spawn");
		var ins = ExitGuide.entrances(null, scan(w, r, sp, r.yaw()));
		check.accept(ins.size() == 1, "l10: from the spawn one entrance echoes, the cottage's (not the shed with no door, not the gazebo) (" + ins.size() + " found)");
		// the gazebo: a ping inside it hears its wooden posts and the slab roof (no click, but not silence)
		var gz = r.spot("in the gazebo");
		boolean posts = false, roof = false;
		StringBuilder heardAt = new StringBuilder();
		for (int band = 0; band < s2(w, r, gz).best.length; band++)
			for (var c : s2(w, r, gz).best[band]) {
				if (c.state == null) continue;
				heardAt.append(c.state.getBlock().getDescriptionId().replace("block.minecraft.", "")).append(' ');
				if (c.state.is(Blocks.OAK_FENCE)) posts = true;
				if (c.state.is(Blocks.OAK_SLAB)) roof = true;
			}
		check.accept(posts, "l10: in the gazebo the wooden posts are heard [" + heardAt + "]");
		check.accept(roof, "l10: in the gazebo the slab roof is heard [" + heardAt + "]");
		var gz0 = r.spot("in the gazebo");
		var fromGazebo = ExitGuide.entrances(null, scan(w, r, gz0, 180f));
		check.accept(fromGazebo.size() == 1, "l10: from the gazebo the cottage's doorway still echoes (" + fromGazebo.size() + ", about 22 blocks off and round the corner)");
		var by = r.spot("by the doorway");
		var near = ExitGuide.entrances(null, scan(w, r, by, 180f));
		double want = Math.atan2(r.oz() + 3.5 - wz(r, by), r.ox() + 12.0 - wx(r, by));
		boolean door = near.size() == 1 && Math.abs(Math.atan2(Math.sin(near.get(0)[0] - want), Math.cos(near.get(0)[0] - want))) < 0.5;
		check.accept(door, "l10: round the far side, the click comes from the cottage's doorway (" + near.size() + ")");
	}

	/** L11: the cave routes (the big tunnel east, the small one north, never the alcove west), the passage round the corner, and the hole down. */
	static void caves(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.CAVES;
		World w = world(r);
		BlockGetter h = Sense.hear(w);
		var sp = r.spot("spawn");
		Vec3 eye = new Vec3(wx(r, sp), wy(r, sp) + 1.62, wz(r, sp));
		double[][] p = CaveEcho.profile(h, eye, 175);
		var routes = CaveEcho.peaks(p[0], p[1]);
		List<Double> dirs = new ArrayList<>();
		for (double[] o : routes) dirs.add((double) Math.round(Math.toDegrees(Math.atan2(Math.sin(o[0]), Math.cos(o[0]))))); // (bearings come as 0..360: -180..180 here; 0 east, -90 north)
		boolean east = dirs.stream().anyMatch(a -> Math.abs(a) < 25);
		boolean north = dirs.stream().anyMatch(a -> Math.abs(a + 90) < 25);
		boolean west = dirs.stream().anyMatch(a -> Math.abs(Math.abs(a) - 180) < 25);
		check.accept(east && north, "l11: cave routes east (big tunnel) and north (small tunnel) " + dirs);
		check.accept(!west, "l11: the alcove west is not a route (decoy)");
		var wk = new CaveEcho.Walk(h, null, eye, p[0], p[1], 175);
		wk.run(Long.MAX_VALUE);
		var corners = CaveEcho.corners(h, wk, eye, List.of()); // echoes: {bearing (atan2 z,x), distance, ...}
		boolean south = corners.stream().anyMatch(c -> { double a = Math.toDegrees(Math.atan2(Math.sin(c[0]), Math.cos(c[0]))); return a > 5 && a < 70 && c[1] > 10; });
		check.accept(south, "l11: the passage round the corner (south off the big tunnel) is found (" + corners.size() + " corners)");
		var rc = r.spot("round the corner");
		var drops = Echo.caveDrops(h, wx(r, rc), wy(r, rc) + 1.62, wz(r, rc), (int) wy(r, rc), 180f, 32);
		check.accept(!drops.isEmpty(), "l11: the shaft is a hole into the lower cave (" + drops.size() + ")");
	}

	/** L12: on the path the bell plays; on the stone plaza it does not. */
	static void meadow(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.MEADOW;
		World w = world(r);
		BlockGetter h = Sense.hear(w);
		var sp = r.spot("on the path");
		boolean on = PathGuide.findIn(h, new PathGuide.Me(wx(r, sp), wy(r, sp), wz(r, sp), wy(r, sp) + 1.62, 180f, 0, 0), CFG);
		var pl = r.spot("on the plaza");
		boolean plaza = PathGuide.findIn(h, new PathGuide.Me(wx(r, pl), wy(r, pl), wz(r, pl), wy(r, pl) + 1.62, 180f, 0, 0), CFG);
		check.accept(on, "l12: the path bell plays on the path");
		check.accept(!plaza, "l12: no bell on the stone plaza (decoy)");
	}

	/** L13: the pond is heard as water and the lava as a hazard; the lava can not be walked into (fenced). */
	static void pond(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.POND;
		World w = world(r);
		var sp = r.spot("spawn");
		var s = scan(w, r, sp, r.yaw());
		boolean water = false, lava = false;
		for (var c : s.best[Scan.WATER]) if (c.state != null) water = true;
		for (var c : s.best[Scan.HAZARD]) if (c.state != null) lava = true;
		check.accept(water && lava, "l13: the pond is heard as water, the pit as lava (water " + water + ", lava " + lava + ")");
		var pos = new BlockPos.MutableBlockPos();
		boolean fenced = true;
		for (int x = 19; x <= 23; x++) for (int z = 1; z <= 5; z++) {
			if (x > 19 && x < 23 && z > 1 && z < 5) continue;
			int ref = r.oy() + 3;
			if (BlockRules.stepTo(w, r.ox() + x, r.oz() + z, ref, pos) != BlockRules.NONE) fenced = false;
		}
		check.accept(fenced, "l13: the lava pit's fence can not be stepped over");
	}

	/** L14: the jingle's route to the dummy goes round the wall, through the gap. */
	static void dummy(BiConsumer<Boolean, String> check) {
		var r = TutorialRooms.DUMMY;
		World w = world(r);
		var rt = route(w, r, r.spot("spawn"), r.goal(), false);
		boolean viaGap = rt != null && rt.stream().anyMatch(c -> c[0] >= r.ox() + 17);
		check.accept(reaches(rt, r, r.goal()) && viaGap, "l14: the way to the dummy goes round the wall, through the gap");
	}
}
