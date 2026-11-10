package com.sonar;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Sonar Access: the everyday switches, and the folders: Sliders, Volumes, Warnings, Paths and Echo, Keys, Advanced. */
public class SettingsScreen extends SonarScreen {
	public SettingsScreen(Screen parent) {
		super("Sonar Access", parent);
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
		y += 36;
		int x = this.width / 2 - 154;
		nav(x, y, "Sliders", () -> new SlidersScreen(this));
		nav(x + 158, y, "Volumes", () -> new VolumesScreen(this));
		y += 24;
		nav(x, y, "Warnings", () -> new WarningsScreen(this));
		nav(x + 158, y, "Paths and Echo", () -> new PathsScreen(this));
		y += 24;
		nav(x, y, "Keys", () -> new KeysScreen(this));
		nav(x + 158, y, "Advanced", () -> new AdvancedScreen(this));
		y += 24;
		if (!Tutorial.active()) this.addRenderableWidget(Tutorial.entryButton(this.minecraft, this.width / 2 - 154, y, 308)); // (inside the tutorial it is already running)
		y += 36;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 102, y, 204, 20).build());
	}

	private void nav(int x, int y, String name, java.util.function.Supplier<Screen> to) {
		this.addRenderableWidget(Button.builder(Component.literal(name), b -> this.minecraft.gui.setScreen(to.get())).bounds(x, y, 150, 20).build());
	}
}
