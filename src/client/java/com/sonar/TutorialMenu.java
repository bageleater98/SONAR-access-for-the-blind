package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The pause menu inside the tutorial (Escape): a short list, every row says itself when focused (Tab / arrow keys), Enter chooses. In this order: Waypoints (the
 * one menu a lesson uses, so it comes first), back to the game, repeat the last instruction, the narrator's volume, the level menu, restart this level, leave.
 */
final class TutorialMenu extends Screen {
	TutorialMenu() { super(Component.literal("Sonar Tutorial")); }

	/** The narrator's volume (the "Spoken messages" setting), in steps of 5. Says its name when focused, and plays a sample line when it changes. */
	static final class VolumeSlider extends AbstractSliderButton implements Tutorial.Spoken {
		private int last;

		VolumeSlider(int x, int y, int w) {
			super(x, y, w, 20, Component.empty(), SonarConfig.cur.speechVolume / 100.0);
			last = SonarConfig.cur.speechVolume;
			updateMessage();
		}

		private int pct() { return (int) Math.round(value * 20) * 5; }

		@Override protected void updateMessage() { setMessage(Component.literal("Narrator volume:  " + pct() + "%")); }

		@Override protected void applyValue() {
			int p = pct();
			if (p == last) return;
			last = p;
			SonarConfig.cur.speechVolume = p;
			try { SonarConfig.save(); } catch (Exception ignored) {}
			Voice.menu(Minecraft.getInstance(), "menu.volume"); // the sample is the label itself, at the new volume
		}

		@Override public void speak(Minecraft mc) { Voice.menu(mc, "menu.volume"); }
	}

	@Override
	protected void init() {
		var mc = this.minecraft;
		Director d = Tutorial.director;
		Object[][] rows = {
			{"Back to the game", "menu.back", (Runnable) this::onClose},
			{"Waypoints", "menu.waypoints", (Runnable) () -> mc.gui.setScreen(new WaypointsScreen(this))},
			{"Repeat the last instruction", "menu.repeat", (Runnable) () -> { onClose(); if (d != null) d.repeat(); }},
			null, // the volume slider
			{"Levels", "menu.lessons", (Runnable) () -> mc.gui.setScreen(new LessonMenu(this))},
			{"Restart this level", "menu.restart", (Runnable) () -> { onClose(); if (d != null) d.restart(); }},
			{"Leave the tutorial", "menu.leave", (Runnable) () -> Tutorial.leave(mc)},
		};
		int w = 240, top = Math.max(24, this.height / 2 - rows.length * 12);
		for (int i = 0; i < rows.length; i++) {
			int x = this.width / 2 - w / 2, y = top + i * 24;
			Object[] r = rows[i];
			if (r == null) { addRenderableWidget(new VolumeSlider(x, y, w)); continue; }
			Runnable run = (Runnable) r[2];
			addRenderableWidget(new Tutorial.SpokenButton(x, y, w, 20, Component.literal((String) r[0]), b -> run.run(), (String) r[1]));
		}
	}

	@Override
	public void added() {
		super.added();
		if (this.minecraft.isWindowActive()) Voice.menu(this.minecraft, "menu.open"); // (not when the game paused itself because the window lost focus)
	}

	@Override
	public boolean isPauseScreen() { return true; }

	@Override
	public void onClose() { this.minecraft.gui.setScreen(null); }
}
