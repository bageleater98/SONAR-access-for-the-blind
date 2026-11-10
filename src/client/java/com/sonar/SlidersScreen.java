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
		// two columns, so every slider fits on screen (round 15: one long column ran off the bottom)
		sliderCol(0, y, "Echoes", -1, 8, 1, c.echoCount, v -> (int) v < 0 ? "auto" : (int) v == 0 ? "off" : String.valueOf((int) v), v -> c.echoCount = (int) v);
		sliderCol(1, y, "Auto scan", 0.3, 6.0, 0.1, c.autoPing, v -> String.format("%.1f s", v), v -> c.autoPing = (float) v);
		y += 24;
		sliderCol(0, y, "Sonar range", 15, 60, 5, c.range, v -> (int) v + " blocks", v -> c.range = (int) v);
		sliderCol(1, y, "Cave echo", 32, 300, 1, c.caveRange, v -> (int) v + " blocks", v -> c.caveRange = (int) v);
		y += 24;
		// the sweep: front, right, left, behind, evenly spaced; slider left slowest, right fastest, the default in the middle;
		// shown as the time between beats (the game's tick, 0.05 s, is the smallest step)
		sliderCol(0, y, "Sweep speed", 1, 5, 1, 6 - c.sweepGap, v -> String.format("%.2f s a beat", (6 - (int) v) * 0.05), v -> c.sweepGap = 6 - (int) v);
		sliderCol(1, y, "Direction spread", 1.0, 2.0, 0.05, c.directionSpread, v -> Math.round(v * 100) + "%", v -> c.directionSpread = (float) v);
		y += 24;
		sliderCol(0, y, "Ledge warn", 2.0, 10.0, 0.5, c.ledgeWarnRange, v -> v + " blocks", v -> c.ledgeWarnRange = (float) v);
		sliderCol(1, y, "Smallest drop", 2, 8, 1, c.ledgeMinDrop, v -> (int) v + " blocks", v -> c.ledgeMinDrop = (int) v);
		y += 24;
		sliderCol(0, y, "Speech speed", 0, 10, 1, c.speechRate, v -> String.valueOf((int) v), v -> c.speechRate = (int) v);
		y += 36;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 102, y, 204, 20).build());
	}
}
