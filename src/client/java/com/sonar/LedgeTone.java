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
		volume += (tv - volume) * (tv > volume ? 0.8f : 0.06f); // follows how close the edge is straight away; only the fade-out is slow
		pitch += (tp - pitch) * 0.12f;
		x += (tx - x) * 0.15;
		y += (ty - y) * 0.15;
		z += (tz - z) * 0.15; // the source glides along the edge instead of jumping
		if (tv <= 0.001f && volume < 0.02f) stop();
	}

	public static void update(Minecraft mc, SoundEvent ev, int slot, float vol, float pitch, double x, double y, double z) {
		LedgeTone t = cur[slot];
		if (t != null && t.ev != ev) { mc.getSoundManager().stop(t); t = cur[slot] = null; }
		if (t == null || t.isStopped()) {
			t = cur[slot] = new LedgeTone(ev);
			t.volume = vol; t.pitch = pitch; t.x = x; t.y = y; t.z = z;
			mc.getSoundManager().play(t);
		}
		t.tv = vol; t.tp = pitch; t.tx = x; t.ty = y; t.tz = z;
	}

	/** One slot goes quiet (it fades out smoothly). */
	public static void quiet(int slot) {
		if (cur[slot] != null) cur[slot].tv = 0;
	}

	/** Fades every slot from `first` upward (0 = all). */
	public static void fade(int first) {
		for (int i = first; i < SLOTS; i++) if (cur[i] != null) cur[i].tv = 0;
	}

	public static void fade() {
		fade(0);
	}

	/** Kept so the client tick can call it; the loops animate themselves. */
	public static void tick(Minecraft mc) {
	}
}
