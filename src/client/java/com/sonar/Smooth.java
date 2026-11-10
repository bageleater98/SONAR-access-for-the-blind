package com.sonar;

import net.minecraft.world.phys.Vec3;

/**
 * The one way things glide in the mod: each tick a value moves a fixed share of the way to where it should be (a share of 0.25 settles in about half a second).
 * Used for the guide sounds' positions, the icons, the jingle's distance reading and the ledge tone, so they all move the same way.
 */
final class Smooth {
	private Smooth() {}

	/** `cur` moved `share` (0..1) of the way to `want`. */
	static double toward(double cur, double want, double share) {
		return cur + (want - cur) * share;
	}

	static Vec3 toward(Vec3 cur, Vec3 want, double share) {
		return cur.add(want.subtract(cur).scale(share));
	}

	/** Like toward, but a jump bigger than `snap` moves at once (a new path, turning round): gliding across a big jump would drag the sound through places it is not. */
	static Vec3 towardOrSnap(Vec3 cur, Vec3 want, double share, double snap) {
		return cur.distanceTo(want) >= snap ? want : toward(cur, want, share);
	}
}
