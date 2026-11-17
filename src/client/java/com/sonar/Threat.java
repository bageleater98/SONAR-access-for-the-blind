package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;

import java.util.HashMap;
import java.util.Map;

/**
 * LIVING THINGS AND LOOT, the two things in the world the block sonar can not show.
 *
 * THE THREAT WARNING: a monster that can see you, within THREAT_RANGE blocks, is named once ("Zombie, behind") and then the nearest one pulses: a low double
 * beat from where it stands, faster and louder as it closes in. One sound means danger, whatever the monster; its name tells you which. Nothing for animals,
 * nothing for monsters behind a wall, nothing in creative mode.
 *
 * THE LOOT TICK: after each ping, the dropped items near you (what you just mined or killed) answer with a light pop from where they lie, nearest first.
 */
final class Threat {
	private Threat() {}

	/** How far a monster is noticed, where the pulse is at its fastest, and the beat's slowest and fastest spacing (ticks). */
	static final double RANGE = 16, CLOSE = 3;
	static final int BEAT_SLOW = 40, BEAT_FAST = 8;
	/** A monster is named again only after this long out of notice; and two names are at least this far apart (ticks). */
	static final int FORGET = 600, NAME_GAP = 60;
	/** Dropped items answer a ping within this many blocks, at most this many of them. */
	static final double LOOT_RANGE = 10;
	static final int LOOT_MAX = 3;

	private static final Map<Integer, Integer> named = new HashMap<>(); // entity id -> the tick it was last in notice
	private static int nextBeat, lastName = -1000;

	static void reset() { named.clear(); nextBeat = 0; }

	/** Ticks between beats for a monster `d` blocks away: BEAT_FAST at CLOSE or nearer, BEAT_SLOW at the edge of notice. */
	static int beat(double d) {
		double f = Math.max(0, Math.min(1, (d - CLOSE) / (RANGE - CLOSE)));
		return (int) Math.round(BEAT_FAST + f * (BEAT_SLOW - BEAT_FAST));
	}

	/** "ahead", "right", "behind" or "left" for a direction `rel` degrees round from where you face (+ = to your right). */
	static String side(double rel) {
		double a = net.minecraft.util.Mth.wrapDegrees(rel);
		return Math.abs(a) <= 45 ? "ahead" : Math.abs(a) >= 135 ? "behind" : a > 0 ? "right" : "left";
	}

	/** Degrees round from where you face to a point (+ = to your right). */
	static double bearing(float yaw, double dx, double dz) {
		return net.minecraft.util.Mth.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)) - yaw);
	}

	static void tick(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		int tick = SonarClient.tick;
		if (!cfg.threatCue || p.getAbilities().invulnerable) return;
		if (tick % 5 != 0 && tick < nextBeat) return;
		LivingEntity nearest = null;
		double best = RANGE;
		for (var e : mc.level.entitiesForRendering()) {
			if (!(e instanceof Enemy) || !(e instanceof LivingEntity le) || !le.isAlive() || le.isInvisible()) continue;
			double d = le.distanceTo(p);
			if (d > RANGE || !p.hasLineOfSight(le)) continue;
			Integer seen = named.put(le.getId(), tick);
			if ((seen == null || tick - seen > FORGET) && tick - lastName >= NAME_GAP) {
				lastName = tick;
				Speech.say(mc, Component.literal(le.getName().getString() + ", " + side(bearing(p.getYRot(), le.getX() - p.getX(), le.getZ() - p.getZ()))));
			} else if (seen == null) named.remove(le.getId()); // (not named yet: its turn comes once the gap has passed)
			if (d < best) { best = d; nearest = le; }
		}
		if (named.size() > 256) named.values().removeIf(t -> tick - t > FORGET);
		if (nearest == null || tick < nextBeat) return;
		nextBeat = tick + beat(best);
		float vol = (float) (cfg.volume * 0.8 * (0.35 + 0.65 * Loud.at(best, CLOSE, RANGE + 4)));
		double x = nearest.getX(), y = nearest.getY() + nearest.getBbHeight() / 2, z = nearest.getZ();
		Cues.play(mc, SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), vol, 0.6f, x, y, z);
		Cues.queue(new Cues.Pending(SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), vol * 0.8f, 0.6f, x, y, z, new int[]{3}));
	}

	/** After a ping: the nearest dropped items pop, one after another, once the ping's own sounds have played. */
	static void loot(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		var items = new java.util.ArrayList<ItemEntity>();
		for (var e : mc.level.entitiesForRendering())
			if (e instanceof ItemEntity ie && ie.distanceTo(p) <= LOOT_RANGE) items.add(ie);
		items.sort(java.util.Comparator.comparingDouble(ie -> ie.distanceTo(p)));
		int start = 4 * Math.max(1, cfg.sweepGap) + 2; // after the sweep's four beats
		for (int i = 0; i < Math.min(LOOT_MAX, items.size()); i++) {
			var ie = items.get(i);
			float vol = (float) (cfg.volume * 0.5 * (0.4 + 0.6 * Loud.at(ie.distanceTo(p), 1, LOOT_RANGE + 2)));
			Cues.queue(new Cues.Pending(SoundEvents.ITEM_PICKUP, vol, 1.7f, ie.getX(), ie.getY() + 0.3, ie.getZ(), new int[]{start + i * 3}));
		}
	}
}
