package com.sonar;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * WHAT WREN SAYS, AND WHEN. Lessons and events ask for lines; this decides which one plays:
 *  - one line at a time; a waiting line of higher priority goes first (0 safety, 1 instruction, 2 hint, 3 reaction, 4 idle); a safety line cuts in on
 *    anything that is not itself safety;
 *  - per-line cooldowns (from the script) and "once" lines (the first time you hear the ledge tone...);
 *  - reactions and idle lines are dropped, not queued, once they are stale (STALE_NS): a late "louder!" is wrong;
 *  - never starts on top of a sonar ping or echo (Timeline), and keeps quiet during a listening window (the player is listening for a sound it asked about);
 *  - "repeat" replays the last INSTRUCTION (intro, instruction, hint), never a reaction.
 * Pure logic on a clock and a speaker that can be swapped, so the self-test drives it without the game.
 */
final class Narrator {
	/** Plays a line; returns its length in seconds (or a negative number when it could not be played). */
	interface Speaker { double play(String id, boolean shortV); void stop(); }

	/** `menu`: a line about the menu you are in, so it may play while the game is paused (everything else waits for you to come back). */
	record Item(String id, int priority, boolean shortV, long queued, Runnable after, boolean menu) {
		Item(String id, int priority, boolean shortV, long queued, Runnable after) { this(id, priority, shortV, queued, after, false); }
	}

	static final long STALE_NS = 3_000_000_000L;
	/** Longest a line waits for a quiet moment between pings before it plays anyway. */
	static final long TIMELINE_WAIT_NS = 600_000_000L;

	private final Speaker speaker;
	private final LongSupplier clock;
	private final List<Item> queue = new ArrayList<>();
	private Item current;
	private long currentEnds, quietUntil;
	private final Map<String, Long> lastSaid = new HashMap<>();
	private final Set<String> onceDone = new HashSet<>();
	String lastInstruction;
	boolean lastInstructionShort;

	Narrator(Speaker speaker, LongSupplier clock) { this.speaker = speaker; this.clock = clock; }

	private static int priority(String id) { var l = Voice.LINES.get(id); return l == null ? 3 : l.priority(); }
	private static double cooldown(String id) { var l = Voice.LINES.get(id); return l == null ? 0 : l.cooldown(); }
	private static boolean instruction(String id) { var l = Voice.LINES.get(id); return l != null && l.instruction(); }

	/** Asks for a line (its own priority from the script). `after` runs once it has finished (or was dropped). */
	void say(String id) { say(id, false, null); }
	void say(String id, Runnable after) { say(id, false, after); }

	void say(String id, boolean shortV, Runnable after) { say(id, shortV, after, MENU_LINES.contains(id)); }

	/** A line about the menu you are in: it may play while the game is paused. */
	void sayMenu(String id, boolean shortV, Runnable after) { say(id, shortV, after, true); }

	/** Lines that are always about a menu (said while one is open). */
	static final Set<String> MENU_LINES = Set.of("sys.closemenu");

	private long lastTick;

	void say(String id, boolean shortV, Runnable after, boolean menu) {
		long now = clock.getAsLong();
		Long last = lastSaid.get(id);
		double cd = cooldown(id);
		if (cd > 0 && last != null && now - last < cd * 1e9) { if (after != null) after.run(); return; }
		Item it = new Item(id, priority(id), shortV, now, after, menu);
		if (it.priority() == 0 && current != null && priority(current.id()) != 0) { // safety cuts in
			speaker.stop();
			Item was = current;
			current = null;
			if (was.after() != null) queue.add(0, new Item(was.id(), was.priority(), was.shortV(), now, was.after(), was.menu())); // an interrupted instruction comes back after it
		}
		queue.add(it);
	}

	/** Only the first time ever (this session): "the first time you hear the ledge tone". */
	boolean once(String id) {
		if (!onceDone.add(id)) return false;
		say(id);
		return true;
	}

	/** Nothing but safety lines for this long: the player is listening for a sound. */
	void listen(double seconds) { quietUntil = Math.max(quietUntil, clock.getAsLong() + (long) (seconds * 1e9)); }
	void endListening() { quietUntil = 0; }

	/** Says the last instruction again (the repeat key). False when there is none yet. */
	boolean repeat() {
		if (lastInstruction == null) return false;
		lastSaid.remove(lastInstruction);
		say(lastInstruction, lastInstructionShort, null);
		return true;
	}

	/** Everything waiting is dropped and the line playing stops (a new lesson, a reset). The finished-callbacks do not run. */
	void clear() {
		queue.clear();
		if (current != null) speaker.stop();
		current = null;
		quietUntil = 0;
	}

	/**
	 * A lesson is passed: reactions and hints still waiting (no callback hangs on them) are dropped, and one playing is cut (unless it is a safety line), so
	 * the success line comes at once.
	 */
	void dropReactions() {
		long fresh = clock.getAsLong() - 1_500_000_000L; // what was asked just now is the reaction to the very thing that passed the lesson (the wall's two thuds): it stays
		queue.removeIf(i -> i.after() == null && i.priority() != 0 && i.queued() < fresh);
		if (current != null && current.after() == null && current.priority() != 0 && current.queued() < fresh) { speaker.stop(); current = null; }
	}

	/** A line is playing or waiting. */
	boolean busy() { return current != null || !queue.isEmpty(); }

	/** Playing or queued: this id. */
	boolean pending(String id) {
		if (current != null && current.id().equals(id)) return true;
		for (Item i : queue) if (i.id().equals(id)) return true;
		return false;
	}

	String current() { return current == null ? null : current.id(); }

	/** Every tick, the game running. */
	void tick(boolean timelineFree) { tick(timelineFree, false); }

	/**
	 * Every tick: finish the line playing, then start the best waiting one when the moment is right. `timelineFree`: no sonar sound is about to play.
	 * `paused`: the game is paused (a menu): the line playing is paused with it (its end moves on), nothing waiting is stale yet, and only menu lines start.
	 */
	void tick(boolean timelineFree, boolean paused) {
		long now = clock.getAsLong();
		long dt = lastTick == 0 ? 0 : now - lastTick;
		lastTick = now;
		if (paused) {
			if (current != null && !current.menu()) currentEnds += dt;
			for (int k = 0; k < queue.size(); k++) { Item i = queue.get(k); queue.set(k, new Item(i.id(), i.priority(), i.shortV(), i.queued() + dt, i.after(), i.menu())); }
			if (quietUntil > 0) quietUntil += dt;
		}
		if (current != null && now >= currentEnds) {
			Item done = current;
			current = null;
			if (done.after() != null) done.after().run();
		}
		if (current != null || queue.isEmpty()) return;
		queue.removeIf(i -> i.priority() >= 3 && now - i.queued() > STALE_NS && i.after() == null); // a stale reaction is wrong, not late
		if (queue.isEmpty()) return;
		Item best = null;
		for (Item i : queue) if ((!paused || i.menu()) && (best == null || i.priority() < best.priority())) best = i;
		if (best == null) return;
		if (best.priority() > 0 && now < quietUntil) return;
		if (best.priority() > 0 && !timelineFree && now - best.queued() < TIMELINE_WAIT_NS) return;
		queue.remove(best);
		if (best.priority() < 4) queue.removeIf(i -> i.priority() >= 4 && i.after() == null); // idle chatter is for a quiet moment: once something else is said, it is gone
		double secs = speaker.play(best.id(), best.shortV());
		lastSaid.put(best.id(), now);
		if (instruction(best.id())) { lastInstruction = best.id(); lastInstructionShort = best.shortV(); }
		if (secs < 0) { if (best.after() != null) best.after().run(); return; }
		current = best;
		currentEnds = now + (long) (secs * 1e9);
	}

	/** The game's narrator: plays through Voice. */
	static Narrator game() {
		return new Narrator(new Speaker() {
			public double play(String id, boolean shortV) {
				Minecraft mc = Minecraft.getInstance();
				if (!Voice.play(mc, id, shortV)) return -1;
				return (Voice.endsNs() - System.nanoTime()) / 1e9;
			}
			public void stop() { Voice.stop(Minecraft.getInstance()); }
		}, System::nanoTime);
	}
}
