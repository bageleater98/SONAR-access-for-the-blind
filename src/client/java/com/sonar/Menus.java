package com.sonar;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * SONAR IN THE GAME'S OWN MENUS: two rows in the pause menu ("Sonar Access" with its ON/OFF switch, and "Waypoints"), and the rule that makes Sonar's
 * screens usable without sight: every button says its name when you Tab to it and its new value when you change it, in Sonar's own voice, unless the
 * game's Narrator is on (then the Narrator reads them, and Sonar stays quiet so nothing is said twice).
 */
final class Menus {
	private Menus() {}

	static void init() {
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (screen instanceof PauseScreen ps && ps.showsPauseMenu() && !Tutorial.active()) pauseRows(mc, screen, w);
		});
	}

	/** Sonar's screens speak for themselves when the game's Narrator is off. */
	static boolean selfVoiced(Minecraft mc) {
		return mc.options.narrator().get() == net.minecraft.client.NarratorStatus.OFF;
	}

	/** Says a widget's label (its name and value) when Sonar's screens speak for themselves. */
	static void speak(Minecraft mc, Component label) {
		if (selfVoiced(mc)) Speech.say(mc, label);
	}

	private static void pauseRows(Minecraft mc, Screen screen, int w) {
		AbstractWidget opt = null;
		for (var ch : screen.children())
			if (ch instanceof Button b && b.getMessage().equals(Component.translatable("menu.options"))) opt = b;
		if (opt == null) return;
		int rowY = opt.getY();
		for (var ch : screen.children())
			if (ch instanceof AbstractWidget aw && aw.getY() > rowY) aw.setY(aw.getY() + 48); // two rows of ours under the Options row
		// one bar: "Sonar Access" opens the settings, the ON/OFF part on its right end is the master switch
		Supplier<Component> state = () -> Component.literal(SonarConfig.cur.enabled ? "ON" : "OFF").withStyle(SonarConfig.cur.enabled ? ChatFormatting.GREEN : ChatFormatting.RED);
		var widgets = Screens.getWidgets(screen);
		widgets.add(Button.builder(Component.literal("Sonar Access"), b -> mc.gui.setScreen(new SettingsScreen(screen))).bounds(w / 2 - 102, rowY + 24, 150, 20).build());
		widgets.add(Button.builder(state.get(), b -> {
			SonarConfig.cur.enabled = !SonarConfig.cur.enabled;
			try { SonarConfig.save(); } catch (Exception ignored) {}
			b.setMessage(state.get());
			Speech.say(mc, Component.literal(SonarConfig.cur.enabled ? "Sonar on" : "Sonar off"));
		}).bounds(w / 2 + 50, rowY + 24, 52, 20).build());
		widgets.add(Button.builder(Component.literal("Waypoints"), b -> mc.gui.setScreen(new WaypointsScreen(screen))).bounds(w / 2 - 102, rowY + 48, 204, 20).build());
	}
}
