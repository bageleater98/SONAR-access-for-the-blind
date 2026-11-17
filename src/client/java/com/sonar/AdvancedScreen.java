package com.sonar;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Advanced: each feature's own switch and loudness, the sonar's reach and pace, and Reset. Everything else is tuned and has no button. */
public class AdvancedScreen extends SonarScreen {
	public AdvancedScreen(Screen parent) {
		super("Sonar Advanced", parent);
	}

	@Override
	protected void init() {
		SonarConfig c = SonarConfig.cur;
		heading();
		int y = 34;
		toggle(0, y, "Echo", () -> c.echoCue, () -> c.echoCue = !c.echoCue);
		pct(1, y, "Echo volume", 2.0, c.echoVolume, v -> c.echoVolume = v);
		y += 24;
		toggle(0, y, "Ledge warning", () -> c.autoLedge, () -> c.autoLedge = !c.autoLedge);
		pct(1, y, "Ledge volume", 2.0, c.ledgeVolume, v -> c.ledgeVolume = v);
		y += 24;
		toggle(0, y, "Player jingle", () -> c.playerChime, () -> c.playerChime = !c.playerChime);
		sliderCol(1, y, "Jingle volume", 0, 1.6, 0.04, c.chimeVolume, v -> Math.round(v / 0.8 * 100) + "%", v -> c.chimeVolume = (float) v);
		y += 24;
		toggle(0, y, "Path bell", () -> c.pathGuide, () -> c.pathGuide = !c.pathGuide);
		pct(1, y, "Guide volume", 2.0, c.guideVolume, v -> { c.guideVolume = v; c.waypointVolume = v; }); // the path bell and the waypoint guide
		y += 24;
		toggle(0, y, "Path ripple", () -> c.floorEdge, () -> c.floorEdge = !c.floorEdge);
		toggle(1, y, "Edge brake", () -> c.edgeBrake, () -> c.edgeBrake = !c.edgeBrake);
		y += 24;
		toggle(0, y, "Threat warning", () -> c.threatCue, () -> c.threatCue = !c.threatCue);
		toggle(1, y, "Say items and health", () -> c.announce, () -> c.announce = !c.announce);
		y += 24;
		toggle(0, y, "Say north, east...", () -> c.sayFacing, () -> c.sayFacing = !c.sayFacing);
		toggle(1, y, "Icons for sighted friends", () -> c.guideIcons, () -> c.guideIcons = !c.guideIcons);
		y += 24;
		sliderCol(0, y, "Auto scan every", 0.5, 6.0, 0.1, c.autoPing, v -> String.format("%.1f s", v), v -> c.autoPing = (float) v);
		sliderCol(1, y, "Sonar range", 15, 60, 5, c.range, v -> (int) v + " blocks", v -> c.range = (int) v);
		y += 24;
		toggle(0, y, "Debug log", () -> c.debug, () -> c.debug = !c.debug);
		this.addRenderableWidget(Button.builder(Component.literal("Reset everything"), b -> {
			reset(this.minecraft);
			Menus.speak(this.minecraft, Component.literal("Everything reset"));
			this.minecraft.gui.setScreen(new AdvancedScreen(this.parent));
		}).bounds(colX(1), y, 150, 20).build());
		y += 32;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 102, y, 204, 20).build());
	}

	private void pct(int col, int y, String name, double max, float cur, java.util.function.Consumer<Float> set) {
		sliderCol(col, y, name, 0, max, 0.05, cur, v -> Math.round(v * 100) + "%", v -> set.accept((float) v));
	}

	/** Every setting back to its default, in the object the screens and the game already hold. */
	static void reset(net.minecraft.client.Minecraft mc) {
		SonarConfig cur = SonarConfig.cur, d = new SonarConfig();
		d.directionalAudioDone = true;
		for (var f : SonarConfig.class.getFields()) {
			if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getName().equals("configVersion")) continue; // (the settings layout version is not a setting)
			try { f.set(cur, f.get(d)); } catch (Exception ignored) {}
		}
		mc.options.directionalAudio().set(true); // lives in Minecraft's own options, not sonar.json
		mc.options.save();
		save();
	}
}
