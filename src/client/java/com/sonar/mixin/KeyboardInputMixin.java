package com.sonar.mixin;

import com.sonar.SonarClient;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the edge brake is active, your walking input counts for only 10%, so you really do slow to a crawl (scaling your velocity afterwards only slowed you by about half). */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void sonar$brake(CallbackInfo ci) {
		if (SonarClient.brakeTicks > 0) {
			this.moveVector = new Vec2(this.moveVector.x * 0.1f, this.moveVector.y * 0.1f);
		}
	}
}
