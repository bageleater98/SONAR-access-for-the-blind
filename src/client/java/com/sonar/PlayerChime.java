package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;

/** Other players carry a constant, easy-to-follow amethyst footstep jingle that plays from where they are. */
public class PlayerChime {
	/** Short looping tune (pitch multipliers, 0 = rest), one step every STEP ticks. */
	private static final float[] TUNE = {1.0f, 1.26f, 1.5f, 2.0f, 1.5f, 1.26f};
	private static final int STEP = 5;
	private static int t;
	/** Amethyst footstep with the low thud filtered out (bundled in the mod). */
	private static final net.minecraft.sounds.SoundEvent STEP_SND = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
		net.minecraft.resources.Identifier.fromNamespaceAndPath("sonar", "step"));
	/** The vanilla amethyst chime (shimmer) at full volume; vanilla plays it at only 20%. */
	private static final net.minecraft.sounds.SoundEvent JINGLE = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
		net.minecraft.resources.Identifier.fromNamespaceAndPath("sonar", "chime"));

	public static void tick(Minecraft mc, SonarConfig cfg) {
		if (!cfg.playerChime || mc.level == null || mc.player == null) return;
		if (++t % STEP != 0) return;
		float pitch = TUNE[(t / STEP) % TUNE.length];
		var me = mc.player.getEyePosition();
		for (Player p : mc.level.players()) {
			if (p == mc.player || p.isSpectator()) continue;
			double dx = p.getX() - me.x, dy = p.getEyeY() - me.y, dz = p.getZ() - me.z;
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d > cfg.playerChimeRange) continue;
			double ad = 1.0 + Math.min(d, cfg.playerChimeRange) / cfg.playerChimeRange * 12.0; // compressed so far players still sound near
			float c = 1f - (float) Math.min(d, cfg.playerChimeRange) / cfg.playerChimeRange;
			float q = 0.03f + 0.97f * c * c * c; // steeper falloff: far players are faint
			double k = ad / Math.max(1e-6, d);
			double px = me.x + dx * k, py = me.y + dy * k, pz = me.z + dz * k;
			// the walking-on-amethyst footstep, with the soft chime layered underneath it
			mc.getSoundManager().play(new SimpleSoundInstance(STEP_SND, SoundSource.AMBIENT,
				Math.min(1f, cfg.volume * q * 0.9f * cfg.chimeVolume), pitch, RandomSource.create(), px, py, pz));
			mc.getSoundManager().play(new SimpleSoundInstance(JINGLE, SoundSource.AMBIENT,
				Math.min(1f, 0.6f * q * cfg.chimeVolume), pitch, RandomSource.create(), px, py, pz));
		}
	}
}
