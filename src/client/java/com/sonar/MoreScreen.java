package com.sonar;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Less common options. */
public class MoreScreen extends SonarScreen {
	public MoreScreen(Screen parent) {
		super("More Sonar Options", parent);
	}

	@Override
	protected void init() {
		SonarConfig c = SonarConfig.cur;
		heading();
		int y = 34;
		toggle(0, y, "Water and lava", () -> c.waterLavaLoop, () -> c.waterLavaLoop = !c.waterLavaLoop);
		toggle(1, y, "Stuck cue", () -> c.stuckCue, () -> c.stuckCue = !c.stuckCue);
		y += 24;
		toggle(0, y, "Hazard cue", () -> c.hazardCue, () -> c.hazardCue = !c.hazardCue);
		toggle(1, y, "Close-range touch", () -> c.tactileCue, () -> c.tactileCue = !c.tactileCue);
		y += 24;
		toggle(0, y, "Gap and door cues", () -> c.gapCue, () -> c.gapCue = !c.gapCue);
		toggle(1, y, "Debug (names in chat)", () -> c.debug, () -> c.debug = !c.debug);
		y += 24;
		toggle(0, y, "Edge brake (slows you)", () -> c.edgeBrake, () -> c.edgeBrake = !c.edgeBrake);
		toggle(1, y, "Path ripple", () -> c.floorEdge, () -> c.floorEdge = !c.floorEdge);
		y += 24;
		toggle(0, y, "Path guide bell", () -> c.pathGuide, () -> c.pathGuide = !c.pathGuide);
		toggle(1, y, "Sonar off inside", () -> c.echoReplacesSonar, () -> c.echoReplacesSonar = !c.echoReplacesSonar);
		y += 32;
		slider(y, 308, "Direction spread", 1.0, 2.0, 0.05, c.directionSpread, v -> Math.round(v * 100) + "%", v -> c.directionSpread = (float) v);
		y += 24;
		slider(y, 308, "Sonar range", 15, 60, 5, c.range, v -> (int) v + " blocks", v -> c.range = (int) v);
		y += 24;
		slider(y, 308, "Smallest drop warned", 2, 8, 1, c.ledgeMinDrop, v -> (int) v + " blocks", v -> c.ledgeMinDrop = (int) v);
		y += 36;
		int x = this.width / 2 - 154;
		this.addRenderableWidget(Button.builder(Component.literal("Reset all to defaults"), b -> {
			SonarConfig d = new SonarConfig();
			for (var f : SonarConfig.class.getFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
				try { f.set(c, f.get(d)); } catch (Exception ignored) {}
			}
			save();
			this.minecraft.gui.setScreen(new MoreScreen(this.parent));
		}).bounds(x, y, 150, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(x + 158, y, 150, 20).build());
	}
}
