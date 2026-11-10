package com.sonar;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;

/** Volume sliders (percent; the extra ones go up to 200% of their default level). */
public class VolumesScreen extends SonarScreen {
	public VolumesScreen(Screen parent) {
		super("Sonar Volumes", parent);
	}

	@Override
	protected void init() {
		SonarConfig c = SonarConfig.cur;
		heading();
		int y = 34;
		slider(y, 250, "Sonar", 0, 2.0, 0.05, c.volume, v -> Math.round(v * 100) + "%", v -> c.volume = (float) v);
		y += 24;
		slider(y, 250, "Echo", 0, 2.0, 0.05, c.echoVolume, v -> Math.round(v * 100) + "%", v -> c.echoVolume = (float) v);
		y += 24;
		slider(y, 250, "Ledge warning", 0, 2.0, 0.05, c.ledgeVolume, v -> Math.round(v * 100) + "%", v -> c.ledgeVolume = (float) v);
		y += 24;
		slider(y, 250, "Player jingle", 0, 1.6, 0.04, c.chimeVolume, v -> Math.round(v / 0.8 * 100) + "%", v -> c.chimeVolume = (float) v);
		y += 24;
		slider(y, 250, "Path guide", 0, 2.0, 0.05, c.guideVolume, v -> Math.round(v * 100) + "%", v -> c.guideVolume = (float) v);
		y += 24;
		slider(y, 250, "Waypoint guide", 0, 2.0, 0.05, c.waypointVolume, v -> Math.round(v * 100) + "%", v -> c.waypointVolume = (float) v);
		y += 24;
		slider(y, 250, "Lava warning", 0, 2.0, 0.05, c.lavaVolume, v -> Math.round(v * 100) + "%", v -> c.lavaVolume = (float) v);
		y += 24;
		slider(y, 250, "Spoken messages", 0, 100, 5, c.speechVolume, v -> Math.round(v) + "%", v -> c.speechVolume = (int) Math.round(v));
		y += 24;
		slider(y, 250, "North/east/south/west", 0, 100, 5, c.facingVolume, v -> Math.round(v) + "%", v -> c.facingVolume = (int) Math.round(v));
		y += 24;
		y += 12;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 102, y, 204, 20).build());
	}
}
