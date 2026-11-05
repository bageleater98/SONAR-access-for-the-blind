package com.sonar;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Danger and obstacle cues: hazards, liquids, gaps, getting stuck, touch, and the edge brake. */
public class WarningsScreen extends SonarScreen {
	public WarningsScreen(Screen parent) {
		super("Sonar Warnings", parent);
	}

	@Override
	protected void init() {
		SonarConfig c = SonarConfig.cur;
		heading();
		int y = 34;
		toggle(0, y, "Hazard cue", () -> c.hazardCue, () -> c.hazardCue = !c.hazardCue);
		toggle(1, y, "Water and lava", () -> c.waterLavaLoop, () -> c.waterLavaLoop = !c.waterLavaLoop);
		y += 24;
		toggle(0, y, "Gap and door cues", () -> c.gapCue, () -> c.gapCue = !c.gapCue);
		toggle(1, y, "Stuck cue", () -> c.stuckCue, () -> c.stuckCue = !c.stuckCue);
		y += 24;
		toggle(0, y, "Close-range touch", () -> c.tactileCue, () -> c.tactileCue = !c.tactileCue);
		toggle(1, y, "Edge brake (slows you)", () -> c.edgeBrake, () -> c.edgeBrake = !c.edgeBrake);
		y += 24;
		toggle(0, y, "Center after 2 bumps", () -> c.centerOnBump, () -> c.centerOnBump = !c.centerOnBump);
		y += 36;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 102, y, 204, 20).build());
	}
}
