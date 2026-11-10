package com.sonar;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/**
 * THE TUTORIAL, from the outside: its entry points (a narrated button on the title screen, rows in the Sonar settings and the pause menu), its two keys
 * (repeat; "this was confusing"), the events Sonar's own code publishes to it, and entering / leaving its world (Sandbox). The lessons themselves are run
 * by the Director. Singleplayer only; nothing here acts outside the tutorial's own save (TutorialWorld's marker).
 */
public final class Tutorial {
	private Tutorial() {}

	/** What Sonar's own code tells the tutorial. The value: FACING 0 south 1 west 2 north 3 east; BUMP 1 jumpable / 2 too tall; PING 0 your own / 1 auto. */
	enum Ev { FACING, BUMP, SQUARED, GUIDE_PUSH, PING, AUTO_TOGGLED, ECHO_TOGGLED, LEDGE_TOGGLED, ECHO, LEDGE_TONE, BRAKE, SETTING, WATER_IN, WATER_OUT,
		WAYPOINT_ADDED, WAYPOINT_GUIDING, WAYPOINT_ARRIVED, WAYPOINTS_OPENED, HEAD_BONK }

	static KeyMapping confusingKey; // (repeating the last instruction is in the tutorial menu only: no key)
	/** In the tutorial world right now, with the director running. */
	private static boolean inWorld;

	static void init() {
		var cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sonar", "tutorial"));
		confusingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.confusing", InputConstants.KEY_K, cat));
		var cfgDir = FabricLoader.getInstance().getConfigDir();
		Progress.file = cfgDir.resolve("sonar-tutorial").resolve("progress.json");
		LessonLog.dir = cfgDir.resolve("sonar-tutorial");
		Minecraft mc = Minecraft.getInstance();
		Sandbox.useGame(mc, cfgDir);
		ServerLifecycleEvents.SERVER_STARTED.register(TutorialWorld::serverStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(TutorialWorld::serverStopped);
		ServerTickEvents.END_SERVER_TICK.register(TutorialWorld::serverTick);
		ClientTickEvents.END_CLIENT_TICK.register(Tutorial::tick);
		ScreenEvents.AFTER_INIT.register(Tutorial::screenOpened);
	}

	/** Once the game's options are loaded: a brightness left forced by a crash inside the tutorial is put back. */
	private static boolean recovered, hidden;

	/** True while the director runs in the tutorial world. */
	static boolean active() { return inWorld; }

	/** Sonar holds its sounds (the room is still loading). */
	static boolean holdSonar() { return inWorld && director != null && director.holding(); }

	/** Wren says "auto scan on" / "ledge warning on" herself in the first step of those two levels: the plain announcement stays silent that once. */
	static boolean toggleSpoken() {
		return inWorld && director != null && director.lesson != null && director.step == 0 && (director.lesson.id.equals("l5") || director.lesson.id.equals("l7"));
	}

	/** The director of the tutorial running now (null outside it). */
	static Director director;

	/** From Sonar's own code. Cheap when the tutorial is not running. */
	static void event(Ev e, double v) { event(e, v, null); }

	/** An event with a name (SETTING: the setting's label). */
	static void event(Ev e, double v, String s) {
		if (!inWorld || director == null) return;
		director.event(e, v, s);
	}

	private static void tick(Minecraft mc) {
		try {
			if (!recovered && mc.options != null) { recovered = true; Sandbox.recover(); }
			if (!hidden && System.getenv("SONAR_HIDDEN_WINDOW") != null && mc.getWindow() != null) { hidden = true; org.lwjgl.sdl.SDLVideo.SDL_HideWindow(mc.getWindow().handle()); } // test runs only: a window nobody sees or clicks into
			Voice.tick(mc);
			spokenFocus(mc);
			boolean nowIn = mc.player != null && mc.level != null && TutorialWorld.server != null && mc.getSingleplayerServer() == TutorialWorld.server;
			if (nowIn && !inWorld) enter(mc);
			else if (!nowIn && inWorld) exit(mc);
			if (!inWorld) return;
			Sandbox.tick();
			while (confusingKey.consumeClick()) director.confusing();
			director.tick();
			if (AutoPlay.ON) AutoPlay.tick(mc); // (test runs only)
		} catch (Throwable t) {
			SonarClient.LOG.error("Sonar tutorial: tick failed", t);
		}
	}

	private static void enter(Minecraft mc) {
		inWorld = true;
		SonarClient.LOG.info("SONAR tutorial: entered the tutorial world");
		Sandbox.enter();
		director = new Director(new GameHost(mc), Narrator.game(), Lessons.all());
		director.begin(Progress.load());
	}

	private static boolean leaving;

	private static void exit(Minecraft mc) {
		inWorld = false;
		if (director != null) director.stop();
		director = null;
		Voice.stop(mc);
		Sandbox.leave();
		if (leaving) { leaving = false; Voice.menu(mc, "sys.leave"); } // said on the title screen, once everything is back
		SonarClient.LOG.info("SONAR tutorial: left the tutorial world");
	}

	// ---------------------------------------------------------------- starting and leaving

	/** Connected to a server (not your own singleplayer world). (isMultiplayerServer is also true on the title screen, with no world at all.) */
	static boolean onServer(Minecraft mc) { return mc.level != null && !mc.hasSingleplayerServer(); }

	/** The tutorial button: leaves a world you are in (a normal singleplayer world is saved first), then opens the tutorial world. */
	static void start(Minecraft mc) {
		if (onServer(mc)) {
			Voice.menu(mc, "sys.server");
			return;
		}
		if (inWorld) return;
		if (mc.level != null) mc.disconnectWithSavingScreen();
		TutorialWorld.open(mc);
	}

	/** Leave the tutorial: save and quit to the title screen (the sandbox puts everything back as the world closes). */
	static void leave(Minecraft mc) {
		if (!inWorld) return;
		SonarClient.LOG.info("SONAR tutorial: leaving");
		if (director != null) director.stop();
		leaving = true;
		mc.disconnectWithSavingScreen();
		mc.gui.setScreen(new TitleScreen());
	}

	// ---------------------------------------------------------------- buttons

	/** A button that says what it is when keyboard focus lands on it: a line of Wren's, or its own label by the system voice. */
	/** A widget that says itself (in Wren's voice) when it gets keyboard focus. */
	interface Spoken { void speak(Minecraft mc); }

	static final class SpokenButton extends Button.Plain implements Spoken {
		final String line;
		SpokenButton(int x, int y, int w, int h, Component msg, OnPress press, String line) {
			super(x, y, w, h, msg, press, DEFAULT_NARRATION);
			this.line = line;
		}
		public void speak(Minecraft mc) {
			if (line == null || !Voice.menu(mc, line)) Speech.say(mc, getMessage());
		}
	}

	private static GuiEventListener lastFocus;
	private static boolean autoStarted;

	private static void spokenFocus(Minecraft mc) {
		Screen s = mc.gui == null ? null : mc.gui.screen();
		GuiEventListener f = s == null ? null : s.getFocused();
		if (f == lastFocus) return;
		lastFocus = f;
		if (f instanceof Spoken b && ((net.minecraft.client.gui.components.AbstractWidget) f).isFocused()) b.speak(mc);
	}

	private static void screenOpened(Minecraft mc, Screen screen, int w, int h) {
		if (System.getenv("SONAR_TUTORIAL_AUTOSTART") != null) testScreen(mc, screen);
		if (screen instanceof TitleScreen) {
			titleButton(mc, screen, w);
			// test runs only (environment variable SONAR_TUTORIAL_AUTOSTART): start the tutorial by itself, once, so a smoke test needs no clicking
			if (!autoStarted && System.getenv("SONAR_TUTORIAL_AUTOSTART") != null) { autoStarted = true; mc.execute(() -> start(mc)); }
		}
		else if (screen instanceof PauseScreen ps && ps.showsPauseMenu() && inWorld) mc.execute(() -> { if (mc.gui.screen() == ps) mc.gui.setScreen(new TutorialMenu()); });
	}

	/** Test runs only (hidden window): logs every screen with its buttons, and confirms a load warning (missing data packs, experimental...) by itself. */
	private static void testScreen(Minecraft mc, Screen screen) {
		var labels = new java.util.ArrayList<String>();
		Button yes = null;
		for (var ch : screen.children())
			if (ch instanceof Button b) {
				String t = b.getMessage().getString();
				labels.add(t);
				if (yes == null && t.matches("(?i)(proceed|yes|load anyway|i know what i'm doing!?|continue|ok)")) yes = b;
			}
		SonarClient.LOG.info("SONAR test: screen " + screen.getClass().getSimpleName() + " '" + screen.getTitle().getString() + "' buttons " + labels);
		if (yes != null && !(screen instanceof TitleScreen) && !(screen instanceof PauseScreen) && !(screen instanceof TutorialMenu)) {
			Button b = yes;
			SonarClient.LOG.info("SONAR test: pressing '" + b.getMessage().getString() + "'");
			mc.execute(() -> b.keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0)));
		}
	}

	/** "Sonar Access Tutorial" on the title screen, above Singleplayer (the rows below move down one). */
	private static void titleButton(Minecraft mc, Screen screen, int w) {
		AbstractWidget single = null;
		for (var ch : screen.children())
			if (ch instanceof Button b && b.getMessage().equals(Component.translatable("menu.singleplayer"))) single = b;
		int y = single != null ? single.getY() : screen.height / 4 + 48;
		for (var ch : screen.children())
			if (ch instanceof AbstractWidget aw && aw.getY() >= y) aw.setY(aw.getY() + 48); // (two rows: the tutorial, Sonar's settings)
		var button = new SpokenButton(w / 2 - 100, y, 200, 20, Component.literal("Sonar Access Tutorial"), b -> start(mc), "sys.button");
		Screens.getWidgets(screen).add(button);
		// Sonar's settings are reachable from the title screen too, not only from inside a world
		Screens.getWidgets(screen).add(new SpokenButton(w / 2 - 100, y + 24, 200, 20, Component.literal("Sonar Access settings"), b -> mc.gui.setScreen(new SettingsScreen(screen)), "menu.sonar"));
		// the very first time the mod runs: one spoken offer, with the button focused so Enter starts it. Never asked again (the button stays).
		Progress p = Progress.load();
		if (!p.offered && System.getenv("SONAR_TUTORIAL_AUTOSTART") == null) {
			p.offered = true;
			p.save();
			screen.setFocused(button);
			lastFocus = button; // (its own name is not said over the offer)
			Voice.menu(mc, "first.offer");
			SonarClient.LOG.info("SONAR tutorial: first-launch offer made");
		}
	}

	/** The row for the Sonar settings screen and the pause menu of a normal world. */
	static SpokenButton entryButton(Minecraft mc, int x, int y, int w) {
		boolean server = onServer(mc);
		return new SpokenButton(x, y, w, 20, Component.literal(server ? "Tutorial (leave the server first)" : "Sonar Access Tutorial"), b -> start(mc), server ? "sys.server" : "sys.button");
	}
}
