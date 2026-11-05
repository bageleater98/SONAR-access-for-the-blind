package com.sonar;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;

/** Every number setting that is not a volume. */
public class SlidersScreen extends SonarScreen {
	public SlidersScreen(Screen parent) {
		super("Sonar Sliders", parent);
	}

	@Override
	protected void init() {
		SonarConfig c = SonarConfig.cur;
		heading();
		int y = 34;
		slider(y, 308, "Echoes per ping", -1, 8, 1, c.echoCount, v -> (int) v < 0 ? "auto" : (int) v == 0 ? "off" : String.valueOf((int) v), v -> c.echoCount = (int) v);
		y += 24;
		slider(y, 308, "Auto scan every", 0.3, 6.0, 0.1, c.autoPing, v -> String.format("%.1f s", v), v -> c.autoPing = (float) v);
		y += 24;
		slider(y, 308, "Cave echo range", 32, 300, 1, c.caveRange, v -> (int) v + " blocks", v -> c.caveRange = (int) v);
		y += 24;
		slider(y, 308, "Sonar range", 15, 60, 5, c.range, v -> (int) v + " blocks", v -> c.range = (int) v);
		y += 24;
		slider(y, 308, "Direction spread", 1.0, 2.0, 0.05, c.directionSpread, v -> Math.round(v * 100) + "%", v -> c.directionSpread = (float) v);
		y += 24;
		slider(y, 308, "Ledge warning distance", 2.0, 10.0, 0.5, c.ledgeWarnRange, v -> v + " blocks", v -> c.ledgeWarnRange = (float) v);
		y += 24;
		slider(y, 308, "Smallest drop warned", 2, 8, 1, c.ledgeMinDrop, v -> (int) v + " blocks", v -> c.ledgeMinDrop = (int) v);
		y += 24;
		slider(y, 308, "Speech speed", 0, 10, 1, c.speechRate, v -> String.valueOf((int) v), v -> c.speechRate = (int) v);
		y += 36;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 102, y, 204, 20).build());
	}
}
