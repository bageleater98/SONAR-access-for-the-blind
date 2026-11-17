package com.sonar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** 2.0: the helper key's words, what Sonar says by itself (items, health), the threat pulse, the places kept automatically, and the settings upgrade. */
final class V2Tests {
	private V2Tests() {}

	static void run(BiConsumer<Boolean, String> check) {
		// the helper key
		check.accept(Identify.facingLine("Oak Door", 3.2).equals("Oak Door, 3 blocks") && Identify.facingLine("Zombie", 1.3).equals("Zombie, 1 block")
			&& Identify.facingLine("Stone", 0.3).equals("Stone, right here") && Identify.facingLine(null, 99).startsWith("Nothing within"), "helper key: name and distance, singular block, right here, nothing in reach");
		check.accept(Identify.timeOfDay(1000).equals("Morning") && Identify.timeOfDay(6000).equals("Midday") && Identify.timeOfDay(18000).equals("Night")
			&& Identify.timeOfDay(24000 + 12500).equals("Sunset") && Identify.timeOfDay(-1000).equals("Sunrise"), "helper key: the time of day from the clock, on any day");
		check.accept(Announce.compass(0).equals("south") && Announce.compass(180).equals("north") && Announce.compass(-90).equals("east") && Announce.compass(100).equals("west")
			&& Announce.compass(350).equals("south"), "facing word: the game's yaw as north, east, south, west");

		// the item in your hand
		check.accept(Announce.itemLine("Torch", 12, false).equals("Torch, 12") && Announce.itemLine("Stone Pickaxe", 1, false).equals("Stone Pickaxe") && Announce.itemLine("Air", 0, true).equals("Empty hand"),
			"hand: a stack says its count, a single thing only its name, nothing is an empty hand");

		// health: each line once, on the way down
		check.accept(Announce.healthLine(20, 0) == 0 && Announce.healthLine(6, 0) == 1 && Announce.healthLine(5, 1) == 0 && Announce.healthLine(3, 1) == 2
			&& Announce.healthLine(2, 2) == 0 && Announce.healthLine(2, 0) == 2 && Announce.healthLine(0, 0) == 0, "health: low at 3 hearts, very low at one and a half, each said once, nothing when dead");

		// the threat pulse
		check.accept(Threat.beat(1) == Threat.BEAT_FAST && Threat.beat(Threat.RANGE) == Threat.BEAT_SLOW && Threat.beat(6) < Threat.beat(12), "threat: the beat quickens as the monster closes in");
		check.accept(Threat.side(0).equals("ahead") && Threat.side(90).equals("right") && Threat.side(-90).equals("left") && Threat.side(180).equals("behind") && Threat.side(-170).equals("behind"),
			"threat: the side a monster is on");
		// facing south (yaw 0, +z): east (+x) is to your LEFT in the game; facing north (yaw 180): east is to your right
		check.accept(Threat.side(Threat.bearing(0, 0, 5)).equals("ahead") && Threat.side(Threat.bearing(0, 5, 0)).equals("left") && Threat.side(Threat.bearing(0, -5, 0)).equals("right")
			&& Threat.side(Threat.bearing(180, 5, 0)).equals("right") && Threat.side(Threat.bearing(180, 0, 5)).equals("behind") && Threat.side(Threat.bearing(-90, 5, 0)).equals("ahead"),
			"threat: bearings follow the game's compass (facing south, east is on your left)");

		// waypoints: names, Sonar's own places, the menu order, the call-outs
		List<Waypoints.Wp> l = new ArrayList<>();
		l.add(new Waypoints.Wp("Waypoint 1", "minecraft:overworld", 0, 65, 0));
		l.add(new Waypoints.Wp("waypoint 3", "minecraft:overworld", 50, 65, 0));
		check.accept(Waypoints.freeName(l).equals("Waypoint 2"), "waypoints: a spot saved without a name takes the first free number");
		Waypoints.Wp d1 = Waypoints.keep(l, Waypoints.DEATH, "minecraft:overworld", 10, 65, 10);
		Waypoints.Wp d2 = Waypoints.keep(l, Waypoints.DEATH, "minecraft:the_nether", 20, 40, 20);
		check.accept(d1 == d2 && d1.x == 20 && d1.dim.equals("minecraft:the_nether") && l.size() == 3 && d1.auto, "waypoints: the last death is one place that moves (a guide heading there follows)");
		l.add(new Waypoints.Wp("Last death", "minecraft:overworld", 1, 65, 1)); // one you named that yourself: yours, never moved
		Waypoints.keep(l, Waypoints.DEATH, "minecraft:overworld", 30, 65, 30);
		check.accept(l.get(l.size() - 1).x == 1 && d1.x == 30, "waypoints: a place you named 'Last death' yourself is left alone");
		List<Waypoints.Wp> round = Waypoints.read(Waypoints.write(l));
		check.accept(round.size() == l.size() && round.get(0).auto && !round.get(1).auto, "waypoints: Sonar's own places survive saving and loading");
		var order = Waypoints.sorted(l, w -> w.dim.equals("minecraft:overworld") ? Math.abs(w.x) : -1);
		check.accept(order.get(0).x == 0 && order.get(1).x == 1 && order.get(2).x == 30 && order.get(3).x == 50, "waypoints: nearest first, in order (0, 1, 30, 50)");
		check.accept(WaypointGuide.callOut(61, 60) == 0 && WaypointGuide.callOut(59.6, 60) == 60 && WaypointGuide.callOut(10, 15) == 0, "waypoint guide: the distance is called out when it has halved, not once you are close");

		// the step rule under low branches and ceilings (found by the field test: a spruce branch over a one-block step down)
		var STONE = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
		var LEAF = net.minecraft.world.level.block.Blocks.SPRUCE_LEAVES.defaultBlockState();
		var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		World down = new World().fill(-5, 60, -5, 5, 64, -1, STONE).fill(-5, 60, 0, 5, 63, 5, STONE); // floor 64 north of z 0, one block lower from z 0 on
		check.accept(BlockRules.stepTo(down, 0, 0, 64, pos) == 63, "step rule: a one-block step down in the open is a step");
		down.set(0, 66, 0, LEAF); // a branch two blocks over the LOWER floor: room to stand under it, but your head hits it coming from the upper floor
		check.accept(BlockRules.stepTo(down, 0, 0, 64, pos) == BlockRules.NONE, "step rule: no stepping down under a branch that is at head height where you come from");
		check.accept(BlockRules.stepTo(down, 0, 1, 63, pos) == 63 && BlockRules.stepTo(down, 0, 0, 63, pos) == 63, "step rule: walking under that branch on the lower floor is fine");
		World up = new World().fill(-5, 60, -5, 5, 64, -1, STONE).fill(-5, 60, 0, 5, 65, 5, STONE); // a one-block step up at z 0
		check.accept(BlockRules.stepFrom(up, 0, -1, 0, 0, 64, pos) == 65, "step rule: a one-block step up in the open is a jump you can make");
		up.set(0, 67, -1, LEAF); // a branch right over your head where you would jump from
		check.accept(BlockRules.stepFrom(up, 0, -1, 0, 0, 64, pos) == BlockRules.NONE && BlockRules.stepFrom(up, 1, -1, 1, 0, 64, pos) == 65, "step rule: no jumping up with a branch right over your head (beside it you can)");
		up.set(0, 65, 0, net.minecraft.world.level.block.Blocks.STONE_SLAB.defaultBlockState());
		check.accept(BlockRules.stepFrom(up, 0, -1, 0, 0, 64, pos) == 65, "step rule: a slab is walked onto without a jump, branch or not");

		// settings written by 1.x: the removed switches and sliders go back to the tuned defaults, what still has a button is kept
		SonarConfig old = new SonarConfig();
		old.configVersion = 3; old.sweepGap = 5; old.directionSpread = 1.0f; old.gapCue = false; old.ledgeMinDrop = 8; old.echoCount = 2; old.volume = 1.4f; old.autoScan = true; old.guideVolume = 0.5f; old.waypointVolume = 1.7f;
		boolean changed = old.upgrade();
		SonarConfig def = new SonarConfig();
		check.accept(changed && old.configVersion == SonarConfig.CURRENT && old.sweepGap == def.sweepGap && old.directionSpread == def.directionSpread && old.gapCue && old.ledgeMinDrop == def.ledgeMinDrop
			&& old.echoCount == def.echoCount && old.volume == 1.4f && old.autoScan && old.waypointVolume == 0.5f, "settings from 1.x: hidden settings back to defaults, your volume and auto scan kept, one guide volume");
		check.accept(!old.upgrade(), "settings: an up-to-date file is not upgraded again");
	}
}
