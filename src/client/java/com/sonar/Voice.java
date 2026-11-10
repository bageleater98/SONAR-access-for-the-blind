package com.sonar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The narrator's voice: lines from assets/sonar/voice/script.json, pre-rendered (tools/voice/render.py) into sounds/voice/<id>.ogg and played through the game's
 * sound engine on the VOICE channel (centred, not placed in the world) at the "Spoken messages" volume. While a line plays, Sonar's own sounds are ducked
 * (Loud.duck); the ledge warning never is. Every line also shows as action-bar text for a sighted helper.
 *
 * NEVER SILENCE: a line whose file is missing, or that names a key the player has rebound ("press R" when scan is now on G), is spoken by the system voice
 * (Speech) with the same text, the key's real name put in.
 */
final class Voice {
	private Voice() {}

	/** One line of the script. */
	record Line(String id, String text, String subtitle, String category, int priority, double cooldown, boolean interruptible, String shortText) {
		boolean instruction() { return category.equals("intro") || category.equals("instruction") || category.startsWith("hint"); }
	}

	static final Map<String, Line> LINES = new HashMap<>();
	/** Seconds of each rendered file (id or id.short), from voice/index.json. */
	static final Map<String, Double> SECONDS = new HashMap<>();
	/** {key:NAME} -> its default key name (what the files say). */
	static final Map<String, String> KEY_DEFAULTS = new HashMap<>();
	private static final Pattern KEY = Pattern.compile("\\{key:(\\w+)\\}");

	static {
		try { load(); } catch (Exception e) { SonarClient.LOG.error("Sonar: could not read the voice script", e); }
	}

	static void load() throws Exception {
		JsonObject s = read("/assets/sonar/voice/script.json");
		for (var e : s.getAsJsonObject("keys").entrySet()) KEY_DEFAULTS.put(e.getKey(), e.getValue().getAsString());
		for (var el : s.getAsJsonArray("lines")) {
			JsonObject o = el.getAsJsonObject();
			String text = o.get("text").getAsString();
			LINES.put(o.get("id").getAsString(), new Line(o.get("id").getAsString(), text, o.has("subtitle") ? o.get("subtitle").getAsString() : text,
				o.has("category") ? o.get("category").getAsString() : "reaction", o.has("priority") ? o.get("priority").getAsInt() : 3,
				o.has("cooldown") ? o.get("cooldown").getAsDouble() : 0, !o.has("interruptible") || o.get("interruptible").getAsBoolean(),
				o.has("short") ? o.get("short").getAsString() : null));
		}
		try {
			for (var e : read("/assets/sonar/voice/index.json").entrySet()) SECONDS.put(e.getKey(), e.getValue().getAsDouble());
		} catch (Exception e) { SonarClient.LOG.warn("Sonar: no voice index, lines will use the system voice"); }
	}

	private static JsonObject read(String res) throws Exception {
		try (var in = Voice.class.getResourceAsStream(res)) {
			if (in == null) throw new java.io.FileNotFoundException(res);
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	/** The key a {key:NAME} stands for (null = not a key of ours). */
	static net.minecraft.client.KeyMapping keyFor(String name) {
		return switch (name) {
			case "scan" -> SonarClient.scanKey;
			case "echo" -> SonarClient.echoKey;
			case "ledge" -> SonarClient.ledgeKey;
			case "confusing" -> Tutorial.confusingKey;
			default -> null;
		};
	}

	/** The text with each {key:NAME} as the player's real key; `rebound` set when any of them is not the key the file says. */
	static String withKeys(String text, boolean[] rebound) {
		Matcher m = KEY.matcher(text);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			var k = keyFor(m.group(1));
			String def = KEY_DEFAULTS.getOrDefault(m.group(1), m.group(1));
			String real = def;
			if (k != null && !k.isDefault()) { real = k.isUnbound() ? "the " + m.group(1) + " key, which is not set" : k.getTranslatedKeyMessage().getString(); if (rebound != null) rebound[0] = true; }
			m.appendReplacement(sb, Matcher.quoteReplacement(real));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	// ---- playing

	/** The line playing now (or null), its sound (null when the system voice speaks it), and when it ends (System.nanoTime). */
	static String playingId;
	private static SoundInstance playing;
	private static long started, ends;
	private static String subtitle;
	private static long nextSubtitle;
	private static final Map<String, Boolean> hasFile = new HashMap<>();

	/** When the line playing now ends (System.nanoTime). */
	static long endsNs() { return ends; }

	/** True while a line is being spoken (by file or system voice). */
	static boolean speaking() { return playingId != null && System.nanoTime() < ends; }

	/** Plays line `id` (its short variant if `shortV` and it has one). Returns false if there is no such line. */
	static boolean play(Minecraft mc, String id, boolean shortV) {
		Line l = LINES.get(id);
		if (l == null) { SonarClient.LOG.warn("Sonar voice: no line " + id); return false; }
		boolean useShort = shortV && l.shortText() != null;
		String file = useShort ? id + ".short" : id;
		String raw = useShort ? l.shortText() : l.text();
		boolean[] rebound = {false};
		String text = withKeys(raw, rebound);
		stop(mc);
		playingId = id;
		started = System.nanoTime();
		if (rebound[0] && SECONDS.containsKey(file + ".rb")) file = file + ".rb"; // changed keys: the recording that says "your ping key"
		Double secs = SECONDS.get(file);
		if (secs != null && fileExists(mc, file)) {
			float vol = SonarConfig.cur.speechVolume / 100f;
			// a line started while the game is paused (an instruction about the menu you are in) plays on the UI channel, which pausing leaves alone
			playing = new SimpleSoundInstance(Identifier.fromNamespaceAndPath("sonar", "voice." + file), mc.isPaused() ? SoundSource.UI : SoundSource.VOICE, vol, 1f, RandomSource.create(),
				false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
			mc.getSoundManager().play(playing);
			ends = started + (long) ((secs + 0.15) * 1e9);
			SonarClient.LOG.info("SONAR voice: " + file);
		} else {
			playing = null;
			Speech.say(mc, Component.literal(text));
			ends = started + (long) (estimate(text) * 1e9);
			SonarClient.LOG.info("SONAR voice: " + file + " (system voice" + (rebound[0] ? ", key rebound" : ", no file") + ")");
		}
		subtitle = useShort ? withKeys(l.shortText(), null) : withKeys(l.subtitle(), null);
		pieces = null;
		piece = -1;
		nextSubtitle = 0;
		return true;
	}

	/** About how long the system voice takes to say it (seconds): it has no file to measure. */
	static double estimate(String text) {
		return 0.6 + text.split("\\s+").length * 0.36 * Math.pow(0.93, SonarConfig.cur.speechRate - 3);
	}

	private static boolean fileExists(Minecraft mc, String file) {
		return hasFile.computeIfAbsent(file, f -> mc.getResourceManager().getResource(Identifier.fromNamespaceAndPath("sonar", "sounds/voice/" + f + ".ogg")).isPresent());
	}

	/** Stops the line playing now (both kinds of voice). */
	static void stop(Minecraft mc) {
		if (playing != null) mc.getSoundManager().stop(playing);
		else if (playingId != null && speaking()) Speech.say(mc, Component.literal("")); // an empty line cancels what the system voice is saying
		playing = null;
		playingId = null;
		ends = 0;
		if (subtitle != null && mc.gui != null) mc.gui.hud.setOverlayMessage(Component.empty(), false);
		subtitle = null;
	}

	private static long lastTick;
	private static boolean screenWasOpen;

	/**
	 * Every client tick: keeps the subtitle up while the line plays, and notices a line the engine finished early. While the game is paused the line is
	 * paused too (the game pauses the VOICE channel), so its end moves on with the pause. Leaving every menu silences what the menus were saying.
	 */
	static void tick(Minecraft mc) {
		long now = System.nanoTime();
		long dt = lastTick == 0 ? 0 : now - lastTick;
		lastTick = now;
		boolean screen = mc.gui != null && mc.gui.screen() != null;
		if (screenWasOpen && !screen) stopMenu(mc);
		screenWasOpen = screen;
		if (playingId == null) return;
		if (mc.isPaused() && playing != null && dt > 0 && dt < 1_000_000_000L) { ends += dt; started += dt; return; } // (a paused line waits with the game)
		if (playing != null && now - started > 500_000_000L && !mc.getSoundManager().isActive(playing)) ends = Math.min(ends, now); // finished (or never started)
		if (now >= ends) { playingId = null; playing = null; subtitle = null; return; }
		if (subtitle != null && mc.gui != null) {
			// the action bar is one line: a long subtitle is shown a screen-width piece at a time, each while Wren is saying it (by its share of the letters)
			if (pieces == null) pieces = pieces(mc, subtitle);
			int total = 0;
			for (String pc : pieces) total += pc.length();
			double said = Math.max(0, Math.min(1, (now - started) / (double) Math.max(1, ends - started)));
			int k = 0, acc = 0;
			while (k < pieces.size() - 1 && acc + pieces.get(k).length() < said * total) acc += pieces.get(k++).length();
			if (k != piece || now >= nextSubtitle) {
				piece = k;
				mc.gui.hud.setOverlayMessage(Component.literal(pieces.get(k)), false);
				nextSubtitle = now + 2_000_000_000L; // the action bar fades after about 3 s: keep it up for the whole line
			}
		}
	}

	private static java.util.List<String> pieces;
	private static int piece = -1;

	/** The subtitle split at word boundaries into pieces that fit the screen (with a margin each side). */
	static java.util.List<String> pieces(Minecraft mc, String text) {
		int max = Math.max(120, mc.getWindow().getGuiScaledWidth() - 40);
		java.util.List<String> out = new java.util.ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (String w : text.split(" ")) {
			String next = cur.length() == 0 ? w : cur + " " + w;
			if (cur.length() > 0 && mc.font.width(next) > max) { out.add(cur.toString()); cur = new StringBuilder(w); }
			else cur = new StringBuilder(next);
		}
		if (cur.length() > 0) out.add(cur.toString());
		return out;
	}

	// ---- the mod's fixed phrases ("north", "Auto scan on", "door ahead"): the same voice, on their own channel, never cutting the narrator off

	/** Pre-rendered phrases by their text (lower case, no end punctuation): category "phrase" in the script. */
	private static final Map<String, String> PHRASES = new HashMap<>();
	private static SoundInstance phrase;

	static String phraseKey(String text) { return text.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[.!?,]+$", ""); }

	/** The phrase line for this exact text, or null (a name, a number: the system voice says those). */
	static String phraseId(String text) {
		if (PHRASES.isEmpty()) for (Line l : LINES.values()) if (l.category().equals("phrase")) PHRASES.put(phraseKey(l.text()), l.id());
		return PHRASES.get(phraseKey(text));
	}

	/** Says a fixed phrase in the narrator's voice at `volume` percent, without stopping the narrator's line. False when there is no such phrase or file. */
	static boolean phrase(Minecraft mc, String text, int volume) {
		String id = phraseId(text);
		if (id == null || !SECONDS.containsKey(id) || !fileExists(mc, id)) return false;
		if (phrase != null) mc.getSoundManager().stop(phrase);
		boolean menu = mc.gui != null && mc.gui.screen() != null;
		phrase = new SimpleSoundInstance(Identifier.fromNamespaceAndPath("sonar", "voice." + id), menu ? SoundSource.UI : SoundSource.VOICE, volume / 100f, 1f,
			RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
		phraseInMenu = menu;
		mc.getSoundManager().play(phrase);
		return true;
	}

	// ---- menu speech: what a menu says (its name, the button you move to). Its own channel (UI: pausing leaves it alone), never cutting the narrator's
	// line off (that one waits, paused, until you are back in the game), and silenced the moment you leave the menus.

	private static SoundInstance menuSound;
	private static boolean phraseInMenu;

	/** Says line `id` as menu speech. False when there is no such line. */
	static boolean menu(Minecraft mc, String id) {
		Line l = LINES.get(id);
		if (l == null) return false;
		if (menuSound != null) mc.getSoundManager().stop(menuSound);
		menuSound = null;
		boolean[] rebound = {false};
		String text = withKeys(l.text(), rebound);
		String mid = rebound[0] && SECONDS.containsKey(id + ".rb") ? id + ".rb" : id;
		if (SECONDS.containsKey(mid) && fileExists(mc, mid)) {
			menuSound = new SimpleSoundInstance(Identifier.fromNamespaceAndPath("sonar", "voice." + mid), SoundSource.UI, SonarConfig.cur.speechVolume / 100f, 1f,
				RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
			mc.getSoundManager().play(menuSound);
		} else Speech.say(mc, Component.literal(text));
		SonarClient.LOG.info("SONAR voice (menu): " + id);
		return true;
	}

	/** Every menu is closed: stop what the menus were saying (never the narrator's own line, nor a phrase said in the game). */
	static void stopMenu(Minecraft mc) {
		if (menuSound != null) { mc.getSoundManager().stop(menuSound); menuSound = null; }
		if (phraseInMenu && phrase != null) { mc.getSoundManager().stop(phrase); phrase = null; }
		phraseInMenu = false;
		Speech.stopMenu();
	}

	/** How loud Sonar's own sounds are right now: ducked while the narrator speaks (the ledge warning never asks). */
	static float duck() { return speaking() ? Tuning.VOICE_DUCK : 1f; }
}
