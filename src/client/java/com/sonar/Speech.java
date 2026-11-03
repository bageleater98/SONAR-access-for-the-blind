package com.sonar;

import com.sonar.mixin.GameNarratorAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Speaks mod messages without touching the player's Narrator setting. Uses a persistent Windows speech process so the rate is adjustable; falls back to the game narrator. Always off-thread. */
public class Speech {
	private static final ExecutorService EX = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "sonar-speech");
		t.setDaemon(true);
		return t;
	});
	private static Process proc;
	private static Writer out;
	private static boolean failed, hooked;
	private static int sentRate = Integer.MIN_VALUE;

	private static boolean ensure() {
		if (failed) return false;
		if (proc != null && proc.isAlive()) return true;
		try {
			String script = "Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; "
				+ "while (($l = [Console]::In.ReadLine()) -ne $null) { "
				+ "if ($l.StartsWith('#RATE ')) { $s.Rate = [int]$l.Substring(6); continue }; "
				+ "$s.SpeakAsyncCancelAll(); if ($l.Length -gt 0) { [void]$s.SpeakAsync($l) } }";
			String enc = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
			Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc)
				.redirectErrorStream(true).start();
			proc = p;
			out = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);
			sentRate = Integer.MIN_VALUE;
			Thread drain = new Thread(() -> {
				try { p.getInputStream().transferTo(OutputStream.nullOutputStream()); } catch (Exception ignored) {}
			});
			drain.setDaemon(true);
			drain.start();
			if (!hooked) {
				hooked = true;
				Runtime.getRuntime().addShutdownHook(new Thread(() -> { if (proc != null) proc.destroy(); }));
			}
			return true;
		} catch (Exception e) {
			failed = true;
			return false;
		}
	}

	/** Start the speech process at launch so the first message is not delayed. */
	public static void warm() {
		EX.submit(Speech::ensure);
	}

	public static void say(Minecraft mc, Component msg) {
		var n = ((GameNarratorAccessor) mc.getNarrator()).sonar$narrator();
		String text = msg.getString().replace('\n', ' ');
		float vol = mc.options.getFinalSoundSourceVolume(SoundSource.VOICE);
		int rate = Math.max(-10, Math.min(10, SonarConfig.cur.speechRate));
		EX.submit(() -> {
			try {
				if (ensure()) {
					if (rate != sentRate) { out.write("#RATE " + rate + "\n"); sentRate = rate; }
					out.write(text + "\n");
					out.flush();
				} else if (n != null && n.active()) {
					n.clear();
					n.say(text, true, vol);
				}
			} catch (Throwable t) {
				proc = null; // restart on the next message
			}
		});
	}
}
