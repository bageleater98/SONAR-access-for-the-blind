package com.sonar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sonar.mixin.OptionInstanceAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * THE TUTORIAL'S SANDBOX: while the tutorial runs, the player's own things are left alone.
 *  - Sonar settings: the tutorial uses its own copy (config/sonar-tutorial/settings.json, written fresh from the defaults when the tutorial starts); the
 *    player's sonar.json is never opened for writing, so even a crash can not change it. Leaving switches back to the player's file.
 *  - Brightness: forced to gamma 100 ("10000%") and LOCKED (an options-screen change is put back), but options.txt is only ever written with the player's
 *    own value (OptionsSaveMixin). The player's value is also kept in config/sonar-tutorial/session.json: if the game stops inside the tutorial, the next
 *    start puts it back (recover) whatever happened.
 * Normal worlds are never touched: only Tutorial calls enter / leave.
 */
public final class Sandbox {
	private Sandbox() {}

	static final double FORCED_GAMMA = 100.0;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** The brightness option, as the sandbox needs it (the game's, or a fake in the self-test). */
	interface Gamma {
		double get();
		/** Set past the slider's 0..1 check (the forced value). */
		void setRaw(double v);
		/** Set through the normal option (the player's own value). */
		void setNormal(double v);
		void save();
	}

	/** Which Sonar settings file is in use (the game's SonarConfig, or a fake in the self-test). */
	interface Settings {
		void useTutorial(Path file, SonarConfig start);
		void usePlayer();
		SonarConfig player();
	}

	static Path dir;
	static Gamma gamma;
	static Settings settings;
	static boolean on;
	private static double real;

	static Path session() { return dir.resolve("session.json"); }
	static Path tutorialSettings() { return dir.resolve("settings.json"); }

	/** The game's own brightness and settings. Called once at start-up. */
	static void useGame(Minecraft mc, Path configDir) {
		dir = configDir.resolve("sonar-tutorial");
		gamma = new Gamma() {
			public double get() { return mc.options.gamma().get(); }
			public void setRaw(double v) { ((OptionInstanceAccessor) (Object) mc.options.gamma()).sonar$setValue(v); }
			public void setNormal(double v) { mc.options.gamma().set(Math.max(0, Math.min(1, v))); }
			public void save() { mc.options.save(); }
		};
		settings = new Settings() {
			public void useTutorial(Path file, SonarConfig start) {
				try { Files.createDirectories(file.getParent()); Files.writeString(file, GSON.toJson(start)); } catch (Exception e) { SonarClient.LOG.error("Sonar tutorial: could not write its settings", e); }
				SonarConfig.useFile(file);
			}
			public void usePlayer() { SonarConfig.useFile(SonarConfig.playerFile()); }
			public SonarConfig player() { return SonarConfig.cur; }
		};
	}

	/** The tutorial's settings: the defaults, keeping only the player's debug switches (so a test session is still logged) and that directional audio was set. */
	static SonarConfig tutorialStart(SonarConfig player) {
		SonarConfig t = new SonarConfig();
		t.debug = player.debug;
		t.debugChat = player.debugChat;
		t.trace = player.trace;
		t.directionalAudioDone = true;
		t.configVersion = SonarConfig.CURRENT;
		return t;
	}

	/** Entering the tutorial world: keep the player's brightness safe, switch to the tutorial settings, force the brightness. */
	static void enter() {
		if (on) return;
		real = gamma.get();
		if (real > 1.0 && Files.exists(session())) real = readSession(real); // (a forced value can never be the player's own)
		try {
			Files.createDirectories(dir);
			JsonObject o = new JsonObject();
			o.addProperty("gamma", real);
			Files.writeString(session(), GSON.toJson(o));
		} catch (Exception e) { SonarClient.LOG.error("Sonar tutorial: could not save the player's brightness", e); }
		settings.useTutorial(tutorialSettings(), tutorialStart(settings.player()));
		on = true;
		gamma.setRaw(FORCED_GAMMA);
		SonarClient.LOG.info("SONAR tutorial: sandbox on (brightness " + real + " kept, tutorial settings in use)");
	}

	/** Leaving the tutorial world (or the game stopping inside it): the player's settings and brightness come back exactly. */
	static void leave() {
		if (!on) return;
		on = false;
		settings.usePlayer();
		gamma.setNormal(real);
		gamma.save();
		try { Files.deleteIfExists(session()); } catch (Exception ignored) {}
		SonarClient.LOG.info("SONAR tutorial: sandbox off (brightness " + real + " and the player's settings back)");
	}

	/** Every tick while inside: the brightness stays forced (a change from the options screen is put back). */
	static void tick() {
		if (on && gamma.get() != FORCED_GAMMA) gamma.setRaw(FORCED_GAMMA);
	}

	/** At start-up: the game stopped inside the tutorial last time, so put the player's brightness back. */
	static void recover() {
		if (on || !Files.exists(session())) return;
		double v = readSession(-1);
		if (v >= 0) { gamma.setNormal(v); gamma.save(); SonarClient.LOG.info("SONAR tutorial: brightness " + v + " restored after the game stopped inside the tutorial"); }
		try { Files.deleteIfExists(session()); } catch (Exception ignored) {}
	}

	private static double readSession(double fallback) {
		try { return GSON.fromJson(Files.readString(session()), JsonObject.class).get("gamma").getAsDouble(); } catch (Exception e) { return fallback; }
	}

	/** OptionsSaveMixin: options.txt gets the player's own brightness... */
	public static void beforeOptionsSave(Options o) {
		if (on) ((OptionInstanceAccessor) (Object) o.gamma()).sonar$setValue(real);
	}

	/** ...and the forced one is back straight after. */
	public static void afterOptionsSave(Options o) {
		if (on) ((OptionInstanceAccessor) (Object) o.gamma()).sonar$setValue(FORCED_GAMMA);
	}
}
