package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** Ledge warning: three soft tones (left, ahead, right of where you face) that swell and fade smoothly, so the ledge sounds like a steady aura around that side of you. */
public class LedgeTone extends AbstractTickableSoundInstance {
	public static final int SLOTS = 3; // left, ahead, right of where you face
	private static final LedgeTone[] cur = new LedgeTone[SLOTS];
	private final SoundEvent ev;
	private float tv, tp;
	private double tx, ty, tz;

	private LedgeTone(SoundEvent ev) {
		super(ev, SoundSource.AMBIENT, RandomSource.create());
		this.ev = ev;
		this.looping = true;
	}

	@Override
	public void tick() {
		float target = tv * duck * sneak;
		volume += (target - volume) * (target > volume ? 0.75f : 0.06f); // a quick, sharp ramp in (never a click-on); the fade-out is slower
		pitch += (tp - pitch) * 0.12f;
		x = Smooth.toward(x, tx, 0.15); y = Smooth.toward(y, ty, 0.15); z = Smooth.toward(z, tz, 0.15); // the source glides along the edge instead of jumping
		if (tv <= 0.001f && volume < 0.02f) stop();
	}

	public static void update(Minecraft mc, SoundEvent ev, int slot, float vol, float pitch, double x, double y, double z) {
		// never closer than CLOSEST blocks: right beside your head the game can not tell left from right (an edge under a block away, behind and to the left, was heard
		// as straight in front). Same direction, pushed out to where it can be placed.
		if (mc.player != null) {
			double dx = x - mc.player.getX(), dz = z - mc.player.getZ(), h = Math.hypot(dx, dz);
			if (h > 1e-3 && h < Tuning.LEDGE_CLOSEST) { x = mc.player.getX() + dx / h * Tuning.LEDGE_CLOSEST; z = mc.player.getZ() + dz / h * Tuning.LEDGE_CLOSEST; }
		}
		LedgeTone t = cur[slot];
		if (t != null && t.ev != ev) { mc.getSoundManager().stop(t); t = cur[slot] = null; }
		if (t == null || t.isStopped()) {
			t = cur[slot] = new LedgeTone(ev);
			t.volume = 0.02f; t.pitch = pitch; t.x = x; t.y = y; t.z = z; // starts near silent and ramps up (a sound at exactly 0 would not start)
			mc.getSoundManager().play(t);
		}
		t.tv = vol; t.tp = pitch; t.tx = x; t.ty = y; t.tz = z;
	}

	/** One slot goes quiet (it fades out smoothly). */
	public static void quiet(int slot) {
		if (cur[slot] != null) cur[slot].tv = 0;
	}

	/** One slot goes silent at once (no fade), for when the situation changes and the old tone no longer applies. */
	public static void hush(int slot) {
		if (cur[slot] != null) {
			cur[slot].tv = 0;
			cur[slot].volume = 0f;
		}
	}

	/** Fades every slot from `first` upward (0 = all). */
	public static void fade(int first) {
		for (int i = first; i < SLOTS; i++) if (cur[i] != null) cur[i].tv = 0;
	}

	public static void fade() {
		fade(0);
	}

	/** Standing still: every ledge tone sinks to a fifth of its loudness until you move again (it fades down over about a second, and comes back at once). */
	public static float duckTarget = 1f;
	/** Sneaking while you walk: the warning stays, at Tuning.LEDGE_SNEAK (you can not fall while sneaking, but you still want to know where the edge is). */
	public static float sneak = 1f;
	private static float duck = 1f;

	/** Runs every client tick: moves the shared duck level toward its target; the loops animate themselves. */
	public static void tick(Minecraft mc) {
		duck += (duckTarget - duck) * (duckTarget > duck ? 0.5f : 0.2f); // comes back at once when you move, hushes within half a second
	}
}
