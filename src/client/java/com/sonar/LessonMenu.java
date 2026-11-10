package com.sonar;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * THE LESSON MENU: every lesson by name (its title), each saying itself when focused; Enter goes straight there. Every lesson is open from the start.
 * Escape goes back to the tutorial menu.
 */
final class LessonMenu extends Screen {
	private final Screen parent;

	LessonMenu(Screen parent) { super(Component.literal("Levels")); this.parent = parent; }

	@Override
	protected void init() {
		Director d = Tutorial.director;
		int open = d == null ? 0 : d.furthest(), n = d == null ? 0 : d.lessons.size();
		int rows = (n + 1) / 2, w = 200, top = Math.max(16, this.height / 2 - rows * 11);
		for (int i = 0; i < n; i++) {
			int k = i, col = i < rows ? 0 : 1, row = i % rows;
			var les = d.lessons.get(i);
			String title = i + ". " + (les.title != null ? les.title : les.id);
			var b = new Tutorial.SpokenButton(this.width / 2 + (col == 0 ? -w - 4 : 4), top + row * 22, w, 20, Component.literal(title), x -> {
				this.minecraft.gui.setScreen(null);
				d.jump(k);
			}, "title." + d.lessons.get(i).id);
			b.active = i <= open;
			addRenderableWidget(b);
		}
	}

	@Override
	public void added() {
		super.added();
		Voice.menu(this.minecraft, "lessons.open");
	}

	@Override
	public boolean isPauseScreen() { return true; }

	@Override
	public void onClose() { this.minecraft.gui.setScreen(parent); }
}
