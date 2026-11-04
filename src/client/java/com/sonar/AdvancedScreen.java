package com.sonar;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Debug output and the reset button. */
public class AdvancedScreen extends SonarScreen {
	public AdvancedScreen(Screen parent) {
		super("Sonar Advanced", parent);
	}

	@Override
	protected void init() {
		SonarConfig c = SonarConfig.cur;
		heading();
		int y = 34;
		toggle(0, y, "Debug (names in chat)", () -> c.debug, () -> c.debug = !c.debug);
		y += 24;
		this.addRenderableWidget(Button.builder(Component.literal("Reset all to defaults"), b -> {
			SonarConfig cur = SonarConfig.cur, d = new SonarConfig();
			d.directionalAudioDone = cur.directionalAudioDone;
			for (var f : SonarConfig.class.getFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				try { f.set(cur, f.get(d)); } catch (Exception ignored) {}
			}
			this.minecraft.options.directionalAudio().set(true); // lives in Minecraft's own options, not sonar.json, so the loop above never touched it
			this.minecraft.options.save();
			save();
			this.minecraft.gui.setScreen(new AdvancedScreen(this.parent));
		}).bounds(colX(1), y - 24, 150, 20).build());
		y += 36;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 102, y, 204, 20).build());
	}
}
