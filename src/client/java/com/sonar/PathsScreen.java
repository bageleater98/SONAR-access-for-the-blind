package com.sonar;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Path following and how the echo behaves indoors. */
public class PathsScreen extends SonarScreen {
	public PathsScreen(Screen parent) {
		super("Paths and Echo", parent);
	}

	@Override
	protected void init() {
		SonarConfig c = SonarConfig.cur;
		heading();
		int y = 34;
		toggle(0, y, "Path guide bell", () -> c.pathGuide, () -> c.pathGuide = !c.pathGuide);
		toggle(1, y, "Path ripple", () -> c.floorEdge, () -> c.floorEdge = !c.floorEdge);
		y += 24;
		toggle(0, y, "Sonar off inside", () -> c.echoReplacesSonar, () -> c.echoReplacesSonar = !c.echoReplacesSonar);
		y += 36;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 102, y, 204, 20).build());
	}
}
