package com.sonar;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Waypoints menu. "Save this spot" works with no typing (it is named "Waypoint 1", "Waypoint 2"...; type a name first to choose your own). Then the saved
 * places, nearest first: press one to be guided to it (the menu closes), with Rename and Delete beside it. The last place you died and the bed you slept
 * in are kept here by Sonar itself. Every button is spoken (Menus); a list too long for the window scrolls (Scroller).
 */
public class WaypointsScreen extends SonarScreen {
	private EditBox name;
	private Waypoints.Wp renaming;

	public WaypointsScreen(Screen parent) { super("Waypoints", parent); }

	private void say(String text) { Speech.say(this.minecraft, Component.literal(text)); }

	@Override
	protected void init() {
		var mc = this.minecraft;
		var list = Waypoints.list(mc);
		heading();
		int y = 34;
		name = new EditBox(this.font, colX(0), y, 150, 20, Component.literal(renaming == null ? "Name, optional" : "New name"));
		name.setHint(Component.literal(renaming == null ? "Name (optional)" : "New name"));
		if (renaming != null) name.setValue(renaming.name);
		this.addRenderableWidget(name);
		this.addRenderableWidget(Button.builder(Component.literal(renaming == null ? "Save this spot" : "Rename"), b -> {
			String n = name.getValue().trim();
			if (renaming != null) {
				if (n.isEmpty()) { say("Type the new name first"); return; }
				renaming.name = n; renaming.auto = false; // (a place of Sonar's own that you rename is yours from then on)
				Waypoints.save(mc);
				say("Renamed to " + n);
				renaming = null;
			} else {
				var w = Waypoints.add(mc, n);
				say("Saved " + w.name);
				Tutorial.event(Tutorial.Ev.WAYPOINT_ADDED, 0);
				if (Tutorial.active()) { mc.gui.setScreen(null); return; } // (in the tutorial: straight back to the game)
			}
			rebuildWidgets();
		}).bounds(colX(1), y, 150, 20).build());
		y += 28;
		if (WaypointGuide.active()) {
			this.addRenderableWidget(Button.builder(Component.literal("Stop guiding to " + WaypointGuide.target.name), b -> { WaypointGuide.stop(); say("Stopped guiding"); rebuildWidgets(); })
				.bounds(colX(0), y, 308, 20).build());
			y += 24;
		}
		for (Waypoints.Wp w : Waypoints.sorted(list, x -> Waypoints.distance(mc, x))) {
			int d = Waypoints.distance(mc, w);
			boolean on = WaypointGuide.target == w;
			String label = w.name + (d < 0 ? ", other dimension" : ", " + d + " blocks") + (on ? " (guiding)" : "");
			this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
				if (on) { WaypointGuide.stop(); say("Stopped guiding"); rebuildWidgets(); }
				else { WaypointGuide.start(mc, w); mc.gui.setScreen(null); } // straight back to the game: you want to walk
			}).bounds(colX(0), y, 196, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("Rename"), b -> { renaming = w; rebuildWidgets(); this.setFocused(name); say("Type a new name for " + w.name + ", then press Rename"); })
				.bounds(colX(0) + 200, y, 52, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
				if (WaypointGuide.target == w) WaypointGuide.stop();
				if (renaming == w) renaming = null;
				list.remove(w); Waypoints.save(mc); say("Deleted " + w.name); rebuildWidgets();
			}).bounds(colX(0) + 256, y, 52, 20).build());
			y += 24;
		}
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width / 2 - 102, y + 8, 204, 20).build());
	}

	@Override
	public void added() {
		super.added();
		Tutorial.event(Tutorial.Ev.WAYPOINTS_OPENED, 0);
		if (Tutorial.active() && Tutorial.director != null && Tutorial.director.lesson != null && Tutorial.director.lesson.id.equals("l15")) return; // (the waypoints lesson: Wren explains this menu)
		int n = Waypoints.list(this.minecraft).size();
		say("Waypoints, " + n + (n == 1 ? " place" : " places") + " saved. Tab to Save this spot, or on to a place to be guided there.");
	}

	@Override
	public void onClose() { this.minecraft.gui.setScreen(parent); }
}
