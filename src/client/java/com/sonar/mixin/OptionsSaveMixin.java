package com.sonar.mixin;

import com.sonar.Sandbox;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the tutorial forces the brightness, options.txt is always written with the PLAYER'S own brightness, never the forced one. */
@Mixin(Options.class)
public abstract class OptionsSaveMixin {
	@Inject(method = "save", at = @At("HEAD"))
	private void sonar$realGamma(CallbackInfo ci) { Sandbox.beforeOptionsSave((Options) (Object) this); }

	@Inject(method = "save", at = @At("RETURN"))
	private void sonar$forcedGamma(CallbackInfo ci) { Sandbox.afterOptionsSave((Options) (Object) this); }
}
