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
		slider(y, 250, "Player jingle", 0, 0.8, 0.02, c.chimeVolume, v -> Math.round(v / 0.4 * 100) + "%", v -> c.chimeVolume = (float) v);
		y += 24;
		slider(y, 250, "Path guide", 0, 2.0, 0.05, c.guideVolume, v -> Math.round(v * 100) + "%", v -> c.guideVolume = (float) v);
		y += 24;
		slider(y, 250, "Lava warning", 0, 2.0, 0.05, c.lavaVolume, v -> Math.round(v * 100) + "%", v -> c.lavaVolume = (float) v);
		y += 24;
		slider(y, 250, "Jingle hearing distance", 16, 128, 8, c.playerChimeRange, v -> (int) v + " blocks", v -> c.playerChimeRange = (int) v);
		y += 36;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 102, y, 204, 20).build());
	}
}
