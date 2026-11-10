package com.sonar.mixin;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sets an option's value directly, past its slider's range check: the tutorial's brightness (gamma 100, "10000%"). Only Sandbox uses it. */
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {
	@Accessor("value")
	void sonar$setValue(Object value);
}
