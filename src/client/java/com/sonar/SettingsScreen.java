package com.sonar;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Sonar Access: the whole of the everyday settings on one small screen. The switch, auto scan, how loud Sonar and its voice are, how fast the voice
 * talks; then Waypoints, the tutorial, and Advanced for the few things most players never touch.
 */
public class SettingsScreen extends SonarScreen {
	public SettingsScreen(Screen parent) {
		super("Sonar Access", parent);
	}

	@Override
	protected void init() {
		SonarConfig c = SonarConfig.cur;
		heading();
		int y = 34;
		toggle(0, y, "Sonar", () -> c.enabled, () -> c.enabled = !c.enabled);
		toggle(1, y, "Auto scan", () -> c.autoScan, () -> { c.autoScan = !c.autoScan; SonarClient.autoTick = 0; });
		y += 24;
		slider(y, 308, "Sonar volume", 0, 2.0, 0.05, c.volume, v -> Math.round(v * 100) + "%", v -> c.volume = (float) v);
		y += 24;
		slider(y, 308, "Voice volume", 0, 100, 5, c.speechVolume, v -> Math.round(v) + "%", v -> c.speechVolume = (int) Math.round(v));
		y += 24;
		slider(y, 308, "Voice speed", 0, 10, 1, c.speechRate, v -> String.valueOf((int) v), v -> c.speechRate = (int) v);
		y += 36;
		if (this.minecraft.level != null) nav(0, y, "Waypoints", () -> new WaypointsScreen(this)); // (waypoints belong to a world)
		if (!Tutorial.active()) this.addRenderableWidget(Tutorial.entryButton(this.minecraft, colX(this.minecraft.level != null ? 1 : 0), y, 150)); // (inside the tutorial it is already running)
		y += 24;
		nav(0, y, "Advanced", () -> new AdvancedScreen(this));
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(colX(1), y, 150, 20).build());
	}

	private void nav(int col, int y, String name, java.util.function.Supplier<Screen> to) {
		this.addRenderableWidget(Button.builder(Component.literal(name), b -> this.minecraft.gui.setScreen(to.get())).bounds(colX(col), y, 150, 20).build());
	}
}
