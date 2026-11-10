package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * WATER AND LAVA WHILE AUTO SCAN IS ON (round 16): one steady, unbroken sound each, coming from the nearest water / lava block the last scan found, instead of a
 * short splash or lava pop in every ping (the user: "sooo annoying because it's repeating"). The sounds are seamless loops built from the game's own flowing water
 * and lava ambience (water_loop / lava_loop: copies of the clip laid over a buffer that wraps, so there is no seam or gap). The source glides when the nearest
 * block changes, and the loudness follows how far you are from it every tick, fading to nothing at the sonar's range. With auto scan off the pings carry water
 * and lava as before.
 */
final class FluidLoop extends AbstractTickableSoundInstance {
	static final SoundEvent WATER = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("sonar", "water_loop"));
	static final SoundEvent LAVA = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("sonar", "lava_loop"));
	/** Loudness right beside it (times the sonar volume; lava also times the lava volume slider). Round 16 retest: "way too rapid ... way more quiet": the loops
	 *  were rebuilt sparser and quieter (peak 0.5, was 0.8) and these lowered from 0.35 / 0.6, about a quarter as loud overall. */
	static final float WATER_LEVEL = 0.1F, LAVA_LEVEL = 0.2F; // retest 4: "turn it down a bit more" (was 0.15 / 0.3)
	/** Played this much slower (and lower), a calmer flow. */
	static final float WATER_PITCH = 0.85F, LAVA_PITCH = 0.9F;
	/** Never placed nearer than this (same reason as the ledge tone: right beside your head the game can not tell the side), and full loudness within it. */
	static final double CLOSEST = 2.0;

	/** The nearest water / lava block centre from the last scan, or null: none within range. Set by Cues.emit. */
	static double[] waterAt, lavaAt;
	private static FluidLoop water, lava;

	private float tv;
	private double tx, ty, tz;

	private FluidLoop(SoundEvent ev) {
		super(ev, SoundSource.AMBIENT, RandomSource.create());
		this.looping = true;
		this.delay = 0;
		this.pitch = ev == WATER ? WATER_PITCH : LAVA_PITCH;
	}

	/** Water and lava go to the loops (not into the pings) right now. */
	static boolean on(SonarConfig cfg) {
		return cfg.enabled && cfg.waterLavaLoop && cfg.autoScan;
	}

	@Override
	public void tick() {
		volume += (tv - volume) * (tv > volume ? 0.15f : 0.08f); // fades in and out, never clicks
		x = Smooth.toward(x, tx, 0.1); y = Smooth.toward(y, ty, 0.1); z = Smooth.toward(z, tz, 0.1); // glides to a new nearest block
		if (tv <= 0.001f && volume < 0.01f) stop();
	}

	/** Every client tick. */
	static void tick(Minecraft mc, SonarConfig cfg) {
		boolean go = on(cfg) && mc.player != null && mc.level != null;
		water = drive(mc, water, WATER, go ? waterAt : null, WATER_LEVEL * cfg.volume * (mc.player != null && mc.player.isInWater() ? 0.5F : 1.0F), cfg);
		lava = drive(mc, lava, LAVA, go ? lavaAt : null, LAVA_LEVEL * cfg.volume * cfg.lavaVolume, cfg);
	}

	/** Everything fades out (the master switch, leaving the world). */
	static void quiet() {
		waterAt = null; lavaAt = null;
		if (water != null) water.tv = 0;
		if (lava != null) lava.tv = 0;
	}

	private static FluidLoop drive(Minecraft mc, FluidLoop l, SoundEvent ev, double[] at, float level, SonarConfig cfg) {
		if (at == null) { if (l != null) l.tv = 0; return l == null || l.isStopped() ? null : l; }
		double px = mc.player.getX(), py = mc.player.getEyeY(), pz = mc.player.getZ();
		double dx = at[0] - px, dy = at[1] - py, dz = at[2] - pz, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		float vol = (float) Math.min(1.0, level * Loud.at(d, CLOSEST, cfg.range) * Voice.duck()); // (quieter while the narrator speaks)
		double sx = at[0], sy = at[1], sz = at[2], h = Math.hypot(dx, dz);
		if (h < CLOSEST) { // in it or right beside it: out to CLOSEST the same way (straight ahead if it is right under you)
			double ux = h > 1e-3 ? dx / h : -Math.sin(Math.toRadians(mc.player.getYRot())), uz = h > 1e-3 ? dz / h : Math.cos(Math.toRadians(mc.player.getYRot()));
			sx = px + ux * CLOSEST; sz = pz + uz * CLOSEST;
		}
		if (l == null || l.isStopped()) {
			if (vol < 0.005f) return null;
			l = new FluidLoop(ev);
			l.volume = 0.01f; l.x = sx; l.y = sy; l.z = sz; // starts near silent and fades in (a sound at exactly 0 would not start)
			mc.getSoundManager().play(l);
		}
		l.tv = vol; l.tx = sx; l.ty = sy; l.tz = sz;
		return l;
	}

	/** The loudness rule, without the game (self-test): full within CLOSEST, falling to nothing at the range. */
	static double level(double d, int range) {
		return Loud.at(d, CLOSEST, range);
	}
}
