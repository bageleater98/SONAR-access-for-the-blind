package com.sonar;

/**
 * The shared rules for how a sound says WHERE something is, so the same loudness means the same thing for every sound:
 *  - distance: each sound has its own range (an echo click a few dozen blocks, the player jingle the edge of hearing, a mob a short way), and over that range every sound
 *    fades the same way: full close up, the same share at the same fraction of its range, silent at the very edge;
 *  - direction: in front at full loudness, to the side about half, behind about a quarter (and duller, for the sounds that have a muffled copy).
 * (Cave echoes are the one exception on purpose: they say how far a route is by how DEEP the click is, and their loudness says how big the opening is.)
 */
final class Loud {
	private Loud() {}

	/** Loudness share for something `fraction` (0 close .. 1 at the edge) of the way out to the edge of its sound's range. */
	static double shape(double fraction) {
		double f = Math.max(0, Math.min(1, fraction));
		return Math.pow(1 - f, Tuning.FADE_CURVE);
	}

	/** Loudness share for something `d` blocks away, with a sound whose range is `range` blocks and that is full loudness within `full` blocks. */
	static double at(double d, double full, double range) {
		return shape((d - full) / Math.max(1e-6, range - full));
	}

	/** Loudness share for direction `rel` (radians from where you face, either side): 1 in front, BEHIND_LEVEL straight behind, an even slope between (the side halfway). */
	static double facing(double rel) {
		return Tuning.BEHIND_LEVEL + (1 - Tuning.BEHIND_LEVEL) * (1 + Math.cos(rel)) / 2; // an even slope from front to behind (side = halfway)
	}

	/**
	 * THE GUIDES' direction rule (player jingle, waypoint, path bell): the shared rule above, with the sides dipped well down (behind unchanged). Full only
	 * as you face it, so "turn until it is loudest" is a sharp target; straight to the side about 0.28, behind as before.
	 */
	static double guideFacing(double rel) {
		double s = Math.sin(rel);
		return facing(rel) * (1 - Tuning.GUIDE_SIDE_DIP * s * s);
	}

	/**
	 * EVEN LOUDNESS: some game sounds are much louder or softer than others at the same volume setting, so "louder means closer" would depend on which sound it is.
	 * Each sound's level here evens it out (1 = as recorded). Sounds not listed are 1. Set by ear (Tuning.SOUND_LEVELS).
	 */
	static float level(net.minecraft.sounds.SoundEvent e) {
		Float f = Tuning.SOUND_LEVELS.get(e.location().getPath());
		return f == null ? 1f : f;
	}

	/** How far round behind you, 0 (in front) to 1 (straight behind): how much of the muffled copy to mix in. */
	static double behind(double rel) {
		return (1 - Math.cos(rel)) / 2;
	}
}
