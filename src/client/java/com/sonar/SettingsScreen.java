package com.sonar;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Main Sonar settings: the everyday switches and sliders. Everything else lives under More. */
public class SettingsScreen extends SonarScreen {
	public SettingsScreen(Screen parent) {
		super("Sonar Settings", parent);
	}

	@Override
	protected void init() {
		SonarConfig c = SonarConfig.cur;
		heading();
		int y = 34;
		toggle(0, y, "Auto scan", () -> c.autoScan, () -> { c.autoScan = !c.autoScan; SonarClient.autoTick = 0; });
		toggle(1, y, "Echo", () -> c.echoCue, () -> c.echoCue = !c.echoCue);
		y += 24;
		toggle(0, y, "Ledge warning", () -> c.autoLedge, () -> c.autoLedge = !c.autoLedge);
		toggle(1, y, "Mob sounds", () -> c.entityCue, () -> c.entityCue = !c.entityCue);
		y += 24;
		toggle(0, y, "Player jingle", () -> c.playerChime, () -> c.playerChime = !c.playerChime);
		toggle(1, y, "Directional audio", () -> this.minecraft.options.directionalAudio().get(),
			() -> { var o = this.minecraft.options.directionalAudio(); o.set(!o.get()); this.minecraft.options.save(); });
		y += 32;
		slider(y, 308, "Echoes per ping", -1, 4, 1, c.echoCount, v -> (int) v < 0 ? "auto" : (int) v == 0 ? "off" : String.valueOf((int) v), v -> c.echoCount = (int) v);
		y += 24;
		slider(y, 308, "Auto scan every", 0.3, 6.0, 0.1, c.autoPing, v -> String.format("%.1f s", v), v -> c.autoPing = (float) v);
		y += 24;
		slider(y, 308, "Ledge warning distance", 2.0, 10.0, 0.5, c.ledgeWarnRange, v -> v + " blocks", v -> c.ledgeWarnRange = (float) v);
		y += 24;
		slider(y, 308, "Speech speed", 0, 10, 1, c.speechRate, v -> String.valueOf((int) v), v -> c.speechRate = (int) v);
		y += 36;
		int w = 75, x = this.width / 2 - 154;
		this.addRenderableWidget(Button.builder(Component.literal("Volumes"), b -> this.minecraft.gui.setScreen(new VolumesScreen(this))).bounds(x, y, w, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Keys"), b -> this.minecraft.gui.setScreen(new KeysScreen(this))).bounds(x + 78, y, w, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("More"), b -> this.minecraft.gui.setScreen(new MoreScreen(this))).bounds(x + 156, y, w, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(x + 234, y, 74, 20).build());
	}
}
