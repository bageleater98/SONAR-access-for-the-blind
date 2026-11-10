package com.sonar;

import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;

/**
 * The guide icon for the player jingle: a tiny copy of the player you are being led to (their own skin), about as tall as a nether star.
 * A client-side mannequin (the game's own player-shaped decoration entity, not a player): it only exists on your screen, never in the player list, and has no name label.
 */
final class MiniPlayer extends ClientMannequin {
	/** Scale of a normal player: 1.8 blocks tall x 0.15 = about a quarter of a block. */
	static final double SCALE = 0.15;

	MiniPlayer(Level level, PlayerSkinRenderCache skins, ResolvableProfile profile) {
		super(level, skins);
		if (profile != null) this.entityData.set(DATA_PROFILE, profile);
		var scale = this.getAttribute(Attributes.SCALE);
		if (scale != null) scale.setBaseValue(SCALE);
		this.setNoGravity(true);
		this.noPhysics = true;
	}

	@Override
	public Component belowNameDisplay() {
		return null; // no "NPC" label under it
	}

	@Override
	public boolean shouldShowName() {
		return false;
	}
}
