package com.sonar;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Waypoints menu: add one where you stand (named), and for each saved one: guide / stop, rename, delete. Everything is spoken. */
public class WaypointsScreen extends SonarScreen {
	private EditBox name;
	private Waypoints.Wp renaming;

	public WaypointsScreen(Screen parent) { super("Waypoints", parent); }

	@Override
	protected void init() {
		var mc = this.minecraft;
		var list = Waypoints.list(mc);
		heading();
		int y = 34;
		name = new EditBox(this.font, this.width / 2 - 154, y, 200, 20, Component.literal("Waypoint name"));
		this.addRenderableWidget(name);
		this.addRenderableWidget(Button.builder(Component.literal(renaming == null ? "Add waypoint" : "Rename"), b -> {
			String n = name.getValue().trim();
			if (n.isEmpty()) { Speech.say(mc, Component.literal("Type a name first")); return; }
			if (renaming != null) { renaming.name = n; Waypoints.save(mc); Speech.say(mc, Component.literal("Renamed to " + n)); renaming = null; }
			else { Waypoints.add(mc, n); Speech.say(mc, Component.literal("Added waypoint " + n)); Tutorial.event(Tutorial.Ev.WAYPOINT_ADDED, 0); if (Tutorial.active()) { mc.gui.setScreen(null); return; } } // (in the tutorial: straight back to the game)
			rebuildWidgets();
		}).bounds(this.width / 2 + 50, y, 104, 20).build());
		y += 28;
		if (WaypointGuide.active()) {
			this.addRenderableWidget(Button.builder(Component.literal("Stop guiding"), b -> { WaypointGuide.stop(); Speech.say(mc, Component.literal("Stopped guiding")); rebuildWidgets(); })
				.bounds(this.width / 2 - 154, y, 308, 20).build());
			y += 24;
		}
		for (Waypoints.Wp w : list) {
			int d = Waypoints.distance(mc, w);
			boolean on = WaypointGuide.target == w;
			String label = w.name + (d < 0 ? ", other dimension" : ", " + d + " blocks") + (on ? " (guiding)" : "");
			this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
				if (on) { WaypointGuide.stop(); Speech.say(mc, Component.literal("Stopped guiding")); }
				else { WaypointGuide.start(mc, w); mc.gui.setScreen(null); return; } // straight back to the game: you want to walk
				rebuildWidgets();
			}).bounds(this.width / 2 - 154, y, 200, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("Rename"), b -> { renaming = w; rebuildWidgets(); name.setValue(w.name); Speech.say(mc, Component.literal("Type a new name for " + w.name + ", then press Rename")); })
				.bounds(this.width / 2 + 50, y, 50, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
				if (WaypointGuide.target == w) WaypointGuide.stop();
				list.remove(w); Waypoints.save(mc); Speech.say(mc, Component.literal("Deleted " + w.name)); rebuildWidgets();
			}).bounds(this.width / 2 + 104, y, 50, 20).build());
			y += 24;
			if (y > this.height - 40) break;
		}
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 102, Math.min(y + 8, this.height - 24), 204, 20).build());
	}

	@Override
	public void added() {
		super.added();
		Tutorial.event(Tutorial.Ev.WAYPOINTS_OPENED, 0);
		if (Tutorial.active() && Tutorial.director != null && Tutorial.director.lesson != null && Tutorial.director.lesson.id.equals("l15")) return; // (the waypoints lesson: Wren explains this menu)
		int n = Waypoints.list(this.minecraft).size();
		Speech.say(this.minecraft, Component.literal("Waypoints, " + n + (n == 1 ? " waypoint" : " waypoints") + ". Type a name and press Add waypoint to save this spot."));
	}

	@Override
	public void onClose() { this.minecraft.gui.setScreen(parent); }
}
