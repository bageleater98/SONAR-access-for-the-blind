package com.sonar;

import java.util.Arrays;

/** Round 15: a ping plays in four evenly spaced beats by real direction: front, right, left, behind. */
final class SplitTests {
	private SplitTests() {}

	static void run(java.util.function.BiConsumer<Boolean, String> check) {
		double f = 0.1, r = Math.PI / 2, l = -Math.PI / 2, b = Math.PI;
		// the log that showed it: a sound on your left (-88 degrees) played with the one in front, because waves were thirds of the list, not directions
		int[] a = Cues.beatTicks(new double[]{Math.toRadians(1), Math.toRadians(-88), Math.toRadians(90), Math.toRadians(-90), Math.toRadians(-123), Math.toRadians(-173)}, new int[]{1, 1, 1, 1, 1, 1}, 3);
		check.accept(Arrays.equals(a, new int[]{0, 6, 3, 6, 6, 9}), "each sound plays on its own direction's beat: front 0, right 3, left 6, behind 9 " + Arrays.toString(a));
		// evenly spaced, the same rhythm every ping, an empty quarter keeps its silent beat
		int[] e = Cues.beatTicks(new double[]{f, b}, new int[]{1, 1}, 2);
		check.accept(Arrays.equals(e, new int[]{0, 6}), "no sound right or left: behind still lands on the fourth beat (the rhythm never changes) " + Arrays.toString(e));
		// two trees equally far left and right: separate beats, never one sound in the middle
		int[] t = Cues.beatTicks(new double[]{r, l}, new int[]{1, 1}, 1);
		check.accept(t[0] != t[1], "a tree on each side: right then left, never together " + Arrays.toString(t));
		// warnings play at once
		check.accept(Cues.beatTicks(new double[]{b}, new int[]{0}, 3)[0] == 0, "a ledge or lava warning plays at once, whatever its direction");
		// the quarter edges: 45 degrees either side of each direction
		check.accept(Cues.quarter(Math.toRadians(44)) == 0 && Cues.quarter(Math.toRadians(46)) == 1 && Cues.quarter(Math.toRadians(-46)) == 2 && Cues.quarter(Math.toRadians(136)) == 3
			&& Cues.quarter(Math.toRadians(-136)) == 3, "quarters: front, right, left, behind, 45 degrees either side of each");
		check.accept(Cues.beatTicks(new double[0], new int[0], 3).length == 0, "no sounds: fine");

		// ROUND 16: the user faced along a stone wall on the left with blocks front-left and back-left: three equal pings, all heard on the left (stereo can not
		// tell front-left from back-left). Every sound stays where it really is; the diagonals are quieter, so the wall is the loud one.
		double wall = Math.toRadians(-90), frontLeft = Math.toRadians(-35), backLeft = Math.toRadians(-145);
		int[] u = Cues.beatTicks(new double[]{frontLeft, wall, backLeft}, new int[]{1, 1, 1}, 3);
		check.accept(Arrays.equals(u, new int[]{0, 6, 9}), "wall left, blocks front-left and back-left: front, left and behind beats " + Arrays.toString(u));
		double vw = Cues.diagonal(wall), vf = Cues.diagonal(frontLeft), vb = Cues.diagonal(backLeft);
		check.accept(vw > 0.999 && vf < 0.6 && vb < 0.6, Dbg.fmt("the wall straight left is full (%.2f), the front-left and back-left blocks soft (%.2f, %.2f)", vw, vf, vb));
		boolean full = true, floor = true, smooth = true;
		for (int deg : new int[]{0, 90, -90, 180}) full &= Math.abs(Cues.diagonal(Math.toRadians(deg)) - 1) < 1e-9;
		for (int deg : new int[]{45, -45, 135, -135}) floor &= Math.abs(Cues.diagonal(Math.toRadians(deg)) - Tuning.SONAR_DIAGONAL) < 1e-9;
		for (int deg = -180; deg < 180; deg++) {
			double a0 = Cues.diagonal(Math.toRadians(deg)), a1 = Cues.diagonal(Math.toRadians(deg + 1));
			if (Math.abs(a1 - a0) > 0.03 || a0 < Tuning.SONAR_DIAGONAL - 1e-9) smooth = false;
		}
		check.accept(full, "straight ahead, left, right and behind: full volume");
		check.accept(floor, "the four diagonals: " + Math.round(Tuning.SONAR_DIAGONAL * 100) + "%");
		check.accept(smooth, "turning a degree never jumps the volume, and nothing goes below the diagonal level");
		for (double k : new double[]{1.0, 2.0})
			check.accept(Cues.place(frontLeft, 1, k) <= frontLeft + 1e-9 && Cues.place(frontLeft, 1, k) < 0 && (k > 1 || Cues.place(frontLeft, 1, k) == frontLeft),
				"spread " + k + ": the front-left block is heard where it is (or wider), never pulled to the middle");
		check.accept(Cues.place(Math.toRadians(40), 0, 2.0) == Math.toRadians(40), "a ledge or lava warning is heard where it really is");

		// ROUND 16: water and lava as one steady loop each while auto scan is on (FluidLoop), not a splash / pop in every ping
		boolean files = true;
		for (String file : new String[]{"/assets/sonar/sounds/water_loop.ogg", "/assets/sonar/sounds/lava_loop.ogg"})
			try (var in = SplitTests.class.getResourceAsStream(file)) { files &= in != null && in.readAllBytes().length > 20000; } catch (Exception ex) { files = false; }
		String json = "";
		try (var in = SplitTests.class.getResourceAsStream("/assets/sonar/sounds.json")) { json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); } catch (Exception ex) {}
		check.accept(files && json.contains("\"water_loop\"") && json.contains("\"lava_loop\""), "the water and lava loop sounds are in the mod and listed in sounds.json");
		boolean fade = FluidLoop.level(0, 30) == 1 && FluidLoop.level(FluidLoop.CLOSEST, 30) == 1 && FluidLoop.level(30, 30) < 0.01;
		for (double dd = 0; dd < 30; dd += 0.25) fade &= FluidLoop.level(dd + 0.25, 30) <= FluidLoop.level(dd, 30) + 1e-12;
		check.accept(fade, "water/lava loop: full right beside it, quieter step by step as you walk away, silent at the sonar's range");

		// ROUND 16: echoes out of sight (round a corner) are marked once by their detector and played muffled and quieter by the one echo player
		Echo.Way seen = Echo.Way.of(new double[]{0.5, 10, 0.8, 1, 1, 0});
		Echo.Way door = Echo.Way.of(Echo.hidden(new double[]{0.5, 10, 0.8, 1, 1, 0}));
		Echo.Way corner = Echo.Way.of(Echo.hidden(new double[]{0.5, 10, 0.8, 1, 1, 0, 0.3}));
		Echo.Way drop = Echo.Way.of(Echo.hidden(new double[]{0.5, 10, 0.8, 1, 1, 0, 10.77}));
		check.accept(!seen.hidden() && door.hidden() && corner.hidden() && drop.hidden(), "an echo you can see is plain; one marked out of sight stays marked");
		check.accept(door.vol() == 0.8 && door.sizeCode() == -1 && Math.abs(corner.size() - 0.3) < 1e-9 && drop.measured() && Math.abs(drop.size() - 0.77) < 1e-9,
			"marking it out of sight keeps its loudness and size (the quieting is done once, when it plays)");
		check.accept(!seen.joined(door).hidden() && !door.joined(seen).hidden() && door.joined(corner).hidden(), "two echoes joined into one: in sight if either is");
		check.accept(Tuning.HIDDEN_ECHO < 1F, "out of sight is quieter than in sight (" + Tuning.HIDDEN_ECHO + "; the user picked the 1000 Hz muffle a tad louder, Nov 9)");
		boolean muff = true;
		for (String file : new String[]{"/assets/sonar/sounds/h/block_note_block_hat_0.ogg", "/assets/sonar/sounds/h/block_note_block_basedrum_0.ogg"})
			try (var in = SplitTests.class.getResourceAsStream(file)) { muff &= in != null && in.readAllBytes().length > 1000; } catch (Exception ex) { muff = false; }
		check.accept(muff && json.contains("\"h.block.note_block.hat\"") && json.contains("\"h.block.note_block.basedrum\""), "the round-the-corner echo sounds are in the mod and listed");
	}
}
