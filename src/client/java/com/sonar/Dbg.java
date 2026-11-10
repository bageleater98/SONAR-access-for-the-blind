package com.sonar;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/** Debug lines always go to the log file (latest.log, prefixed "SONAR"), so they are there even with chat turned off; they show in chat too only when "Debug in chat" is on. */
final class Dbg {
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sonar");

	static void send(Player p, Component c) {
		if (!SonarConfig.cur.debug) return; // nothing is logged with Debug off, whatever the caller forgot
		LOG.info("SONAR " + c.getString());
		if (p != null && SonarConfig.cur.debugChat) p.sendSystemMessage(c);
	}

	/** String.format for debug text that can never crash the game: a wrong placeholder (an int where %.0f expects a decimal) gives a marked line instead of an exception. */
	static String fmt(String f, Object... a) {
		try { return String.format(f, a); } catch (java.util.IllegalFormatException e) { return "[debug format error: " + e.getMessage() + "] " + f; }
	}
}
