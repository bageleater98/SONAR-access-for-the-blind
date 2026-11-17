package com.sonar;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * THE MOD'S HEARTBEAT: its two keys and the order everything runs in each tick. What each part does lives in its own class:
 * the sonar (Scan, Cues), the echo (Echo), edges (Ledges, LedgeTone, EdgeBrake), walls you walk into (Bump), the guides (PathGuide, PlayerChime,
 * WaypointGuide), what is said aloud (Announce, Identify, Threat) and the menus (Menus, SettingsScreen).
 *
 * Keys: R pings (hold it two seconds: auto scan on or off). V speaks: a tap says what you are facing, holding it says where you are.
 */
public class SonarClient implements ClientModInitializer {
	static KeyMapping scanKey, helpKey;
	static Scan scan, last;
	static int tick, stillTicks, rHeld, autoTick, echoHold;
	private static int vHeld;
	private static boolean describe; // the scan in progress is for "where am I" (hold V): spoken, not played
	private static net.minecraft.client.multiplayer.ClientLevel lastLevel;
	/** The auto echo being worked out, a stage a tick, until it is due to play. */
	private static Echo.Job echoJob;
	private static boolean echoDone;
	private static int stageNow = -1;
	private static boolean shotWasDown;
	private static double px0, pz0;
	private static int rippleTick, ledgeTimer, ledgeInterval = 10;
	/** Ticks before it is due that the auto echo starts its work (it has 5 stages). */
	private static final int ECHO_PREP_TICKS = 5;
	/** Holding R this long switches auto scan; holding V this long asks "where am I" (ticks). */
	static final int AUTO_HOLD = 40, WHERE_HOLD = 12;

	static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sonar");
	private static long lastFail = -1000;
	private static final java.util.Map<String, Integer> pausedUntil = new java.util.HashMap<>();

	/** Debug: logs work that took a noticeable part of one frame (over 4 ms) in a single tick, with how long. */
	static void slowTick(String what, long startNs) {
		double ms = (System.nanoTime() - startNs) / 1e6;
		if (ms > 4 && SonarConfig.cur.debug) Dbg.send(Minecraft.getInstance().player, Component.literal(Dbg.fmt("slow tick: %s took %.1f ms", what, ms)));
	}

	@Override
	public void onInitializeClient() {
		LOG.info("Sonar " + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("sonar").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?") + " starting (settings: config/sonar.json)"); // the version at the top of the log, for bug reports
		Menus.init();
		Tutorial.init();
		Scroller.init();
		var cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sonar", "sonar"));
		scanKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.scan", InputConstants.KEY_R, cat));
		helpKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.help", InputConstants.KEY_V, cat));
		Speech.warm();
		SonarConfig.reloadIfChanged();
		Scan.warm(SonarConfig.cur.range);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		// opening a door (anywhere, any world) centres you on your block and squares you up, as a bump does, so you stand directly in front of it
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() && SonarConfig.cur.enabled && player instanceof net.minecraft.client.player.LocalPlayer lp && level.getBlockState(hit.getBlockPos()).is(net.minecraft.tags.BlockTags.DOORS)) Bump.centerOnDoor(lp, hit.getBlockPos());
			return net.minecraft.world.InteractionResult.PASS;
		});
	}

	/**
	 * Runs one feature so that a bug in it can not take the others down: if it throws, the error is logged (with the feature's name, Debug on or off) and that feature
	 * alone rests for 5 seconds, then tries again. The sonar, echo and the rest carry on meanwhile.
	 */
	static void guard(String feature, Runnable r) {
		Integer until = pausedUntil.get(feature);
		if (until != null && tick < until) return;
		try { r.run(); }
		catch (Throwable e) {
			pausedUntil.put(feature, tick + 100);
			LOG.error("Sonar: " + feature + " failed and rests for 5 s", e);
		}
	}

	/** A bug in one feature must never crash the game: log (at most every 10 s) and carry on. */
	void onTick(Minecraft mc) {
		try {
			tickInner(mc);
		} catch (Throwable t) {
			scan = null;
			if (tick - lastFail > 200) { lastFail = tick; LOG.error("Sonar tick failed", t); }
		}
	}

	/** You are in the world as a body that walks, swims, rides or flies: not dead, not a spectator. Otherwise Sonar is silent. */
	static boolean present(net.minecraft.client.player.LocalPlayer p) {
		return p != null && !p.isDeadOrDying() && !p.isSpectator();
	}

	void tickInner(Minecraft mc) {
		Budget.start();
		if (mc.player == null || mc.level == null) { scan = null; return; }
		if (mc.level != lastLevel) { // a new world or dimension: nothing found in the old one (routes, queued echoes, icons, the path) may carry over
			lastLevel = mc.level;
			scan = null; describe = false;
			echoJob = null; echoDone = false;
			Cues.pending.clear(); Echo.echoTail = 0; echoHold = 0;
			PlayerChime.reset(); PathGuide.reset(); Markers.reset(); Timeline.reset(); Echo.forget(); WaypointGuide.reset(); Threat.reset(); Announce.reset();
			guard("walked ground", () -> Known.switchTo(mc));
		}
		Known.tick(mc);
		if (++tick % 20 == 0) SonarConfig.reloadIfChanged();
		Timeline.tick(tick);
		SonarConfig cfg = SonarConfig.cur;
		guard("places", () -> Waypoints.tick(mc)); // (the last death and the bed are remembered even with the sounds switched off)
		if (cfg.enabled) guard("announcements", () -> Announce.tick(mc, cfg));
		guard("headBonk", () -> Cues.headBonk(mc, cfg));
		// F2 with Debug on: also save the blocks around you, named like the screenshot. The game handles the screenshot key itself and never marks it as held,
		// so the key's own state is read straight from the keyboard
		var shotKey = KeyMappingHelper.getBoundKeyOf(mc.options.keyScreenshot);
		boolean shot = shotKey.getType() == InputConstants.Type.KEYBOARD && InputConstants.isKeyDown(shotKey.getValue());
		if (shot && !shotWasDown && cfg.debug) guard("spot save", () -> Spot.save(mc));
		shotWasDown = shot;
		if (!cfg.enabled || Tutorial.holdSonar() || !present(mc.player)) { // master switch off, a tutorial room still loading, or you are dead: silence everything that loops and skip all cues
			LedgeTone.fade(); FluidLoop.quiet(); FluidLoop.tick(mc, cfg); Ledges.ledgeActive = false; EdgeBrake.cancel(); rHeld = 0; vHeld = 0;
			scan = null; describe = false; echoJob = null; echoDone = false;
			pressed(scanKey); pressed(helpKey); // (presses made meanwhile are not kept for later)
			PathGuide.tick(mc, cfg);
			WaypointGuide.tick(mc, cfg);
			return;
		}
		double mdx = mc.player.getX() - px0, mdz = mc.player.getZ() - pz0;
		px0 = mc.player.getX(); pz0 = mc.player.getZ();
		stillTicks = Math.hypot(mdx, mdz) > 0.03 ? 0 : stillTicks + 1;

		// R: a short press pings when you let go; held for two seconds it switches auto scan (and never pings)
		boolean ping = false;
		if (scanKey.isDown()) {
			if (++rHeld == AUTO_HOLD) toggleAuto(mc, cfg);
		} else {
			ping = rHeld > 0 && rHeld < AUTO_HOLD;
			rHeld = 0;
		}
		pressed(scanKey); // (drain the clicks: R acts on release)
		// V: a tap says what you are facing; held a little longer it says where you are
		boolean where = false;
		if (helpKey.isDown()) {
			if (++vHeld == WHERE_HOLD) where = true;
		} else {
			if (vHeld > 0 && vHeld < WHERE_HOLD) guard("identify", () -> Identify.facing(mc));
			vHeld = 0;
		}
		pressed(helpKey);

		if (!cfg.directionalAudioDone) { // once: turn on Directional Audio (binaural 3D), still changeable afterwards
			cfg.directionalAudioDone = true;
			mc.options.directionalAudio().set(true);
			mc.options.save();
			try { SonarConfig.save(); } catch (Exception ignored) {}
		}
		Cues.tickPending(mc);
		LedgeTone.duckTarget = stillTicks >= 10 ? 0.4f : 1f; // not moved for half a second: the edge warning mostly quiets until you move again
		guard("ledge tone", () -> LedgeTone.tick(mc));
		guard("guide icons", () -> Markers.tick(mc));
		guard("threat", () -> Threat.tick(mc, cfg));
		long g0 = System.nanoTime();
		guard("path bell", () -> PathGuide.tick(mc, cfg));
		slowTick("path bell", g0);
		// path ripple: while you are on a guided path it repeats by itself at the auto-scan interval (with auto scan on, each auto ping already carries it)
		if (cfg.floorEdge && !cfg.autoScan && PathGuide.active() && rHeld == 0) {
			if (++rippleTick >= Math.max(6, Math.round(cfg.autoPing * 20))) { rippleTick = 0; guard("path ripple", () -> FloorEdge.ping(mc, cfg)); }
		} else rippleTick = 0;

		// auto cycle: sonar on the first second, echo one second later, then quiet until the next cycle (paused while R is held)
		boolean autoScanNow = false, autoEchoNow = false, echoPrepNow = false;
		if (cfg.autoScan && rHeld == 0) {
			int period = Math.max(6, Math.round(cfg.autoPing * 20));
			int echoAt = (1 + Math.min(Tuning.ECHO_DELAY_MAX, Math.max(2, period / 2))) % period; // the echo starts once this ping's sonar sounds have rung out (about 0.55 s), never on top of them
			if (echoHold > 0) echoHold--;
			if (!(echoHold > 0 && (autoTick + 1) % period == 1)) autoTick++; // the next ping waits while echoes are still playing
			if (autoTick % period == 1 && echoHold <= 0) autoScanNow = true;
			else if (cfg.echoCue && autoTick % period == echoAt) autoEchoNow = true;
			// the echo's work starts a few ticks early (one stage a tick, in the quiet after the ping) so no single tick carries all of it
			if (cfg.echoCue && autoTick % period == Math.max(2, echoAt - ECHO_PREP_TICKS)) echoPrepNow = true;
		}
		if (autoScanNow && cfg.echoCue && cfg.echoCount != 0) prepareEcho(mc, cfg); // start the echo's slow work now, a little each tick
		CaveEcho.tick(mc);
		if (!cfg.autoScan || rHeld != 0 || !cfg.echoCue) { echoJob = null; echoDone = false; } // auto paused or echo off: drop a half-done echo, never play it late
		if (echoPrepNow && echoJob == null) echoJob = new Echo.Job(new Scan(mc, cfg), cfg);
		if (echoJob != null) {
			long e0 = System.nanoTime();
			if (autoEchoNow) { // due now: finish any stages left, then play
				while (!echoJob.step(mc)) { }
				Echo.echoTail = 0; echoJob.play(mc); echoHold = Echo.echoTail;
				Tutorial.event(Tutorial.Ev.ECHO, 1);
				echoJob = null;
			} else if (!echoDone) { stageNow = echoJob.stage; echoDone = echoJob.step(mc); }
			if (echoJob == null) echoDone = false;
			slowTick("echo stage " + stageNow + (stageNow == 1 ? " (routes / doorways / openings)" : stageNow == 2 ? " (round corners / entrances)" : stageNow == 3 ? " (cave drop)" : stageNow == 4 ? " (doors in sight)" : stageNow == 0 ? " (room / cave test)" : " (finish and play)"), e0);
			stageNow = -1;
		} else if (autoEchoNow) { // (the prep tick was skipped, e.g. auto scan just switched on): all at once
			long e0 = System.nanoTime();
			Echo.echoTail = 0; Echo.echo(mc, new Scan(mc, cfg), cfg); echoHold = Echo.echoTail;
			Tutorial.event(Tutorial.Ev.ECHO, 1);
			slowTick("echo", e0);
		}

		boolean own = ping || where; // a key press: it cancels an auto scan in progress and gives you your own
		if (own && scan != null && (scan.auto || where)) { scan = null; describe = false; }
		if (scan == null && (own || autoScanNow)) {
			scan = new Scan(mc, cfg);
			scan.auto = !own;
			describe = where;
			scan.withEcho = own && !where; // auto sonar leaves the echo to its own slot; "where am I" is spoken, not played
			if (scan.withEcho && cfg.echoCue && cfg.echoCount != 0) prepareEcho(mc, cfg);
		}
		if (scan != null) {
			long s0 = System.nanoTime();
			scan.step();
			slowTick("sonar scan step", s0);
			if (scan.done) {
				Scan done = scan;
				last = scan;
				scan = null;
				if (describe) {
					describe = false;
					guard("where am I", () -> Identify.where(mc, done));
				} else {
					long m0 = System.nanoTime();
					Cues.emit(mc, done, cfg);
					if (cfg.lootCue) guard("loot", () -> Threat.loot(mc, cfg));
					Tutorial.event(Tutorial.Ev.PING, done.auto ? 1 : 0);
					slowTick("sonar sounds (emit)", m0);
				}
			}
		}
		autoLedge(mc, cfg);
		EdgeBrake.tick(mc.player);
		Bump.tick(mc, cfg);
		guard("water and lava", () -> FluidLoop.tick(mc, cfg));
		// the player jingle LAST: its route search is the one job that can pause and carry on next tick, so it gets the time the rest of this tick left (Budget)
		long c0 = System.nanoTime();
		guard("waypoint guide", () -> WaypointGuide.tick(mc, cfg));
		guard("player jingle", () -> PlayerChime.tick(mc, cfg));
		slowTick("player jingle", c0);
	}

	/** The echo's slow work, started at the ping: underground the route rays (then the corner walk), outside the walk for holes. */
	private static void prepareEcho(Minecraft mc, SonarConfig cfg) {
		if (CaveEcho.inCave(mc, mc.player.getEyePosition())) CaveEcho.prepare(mc, mc.player.getEyePosition(), cfg.caveRange);
		else CaveEcho.prepareHoles(mc, mc.player.getEyePosition());
	}

	/** While walking on the ground, keep warning about edges ahead. Keeps going for 4 s after you stop, then pauses; sneaking it is quieter (LEDGE_SNEAK), and sneaking still it is silent. */
	static void autoLedge(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		var v = p.getDeltaMovement();
		boolean afoot = !p.isPassenger() && !p.isFallFlying() && !p.getAbilities().flying && !p.isSleeping(); // riding, gliding and flying: an edge is no danger, and the brake must not touch you
		if (afoot && !p.onGround() && Ledges.ledgeActive && cfg.autoLedge) return; // jumping near an edge: hold the tone until you land
		LedgeTone.sneak = p.isShiftKeyDown() ? Tuning.LEDGE_SNEAK : 1f;
		if (!afoot || !cfg.autoLedge || !cfg.ledgeCue || !p.onGround() || (stillTicks >= 80 && !Ledges.ledgeActive)) { // standing still pauses it after 4 s, unless you are right at an edge
			LedgeTone.fade();
			if (!afoot) { Ledges.ledgeActive = false; EdgeBrake.cancel(); }
			ledgeTimer = ledgeInterval; // ready to fire the moment you move again
			return;
		}
		if (++ledgeTimer < ledgeInterval) return;
		ledgeTimer = 0;
		Scan ls = Scan.ledgeScan(mc, cfg);
		ls.step();
		double vx = v.x, vz = v.z;
		if (Math.hypot(vx, vz) < 0.03) { var la = p.getLookAngle(); vx = la.x; vz = la.z; } // standing: use where you face
		boolean wasTone = Ledges.ledgeActive;
		ledgeInterval = Ledges.watchLedges(mc, ls, cfg, vx, vz);
		if (Ledges.ledgeActive && !wasTone) Tutorial.event(Tutorial.Ev.LEDGE_TONE, 0);
		EdgeBrake.consider(mc, cfg, p, v);
	}

	private static final java.util.Map<KeyMapping, Boolean> wasDown = new java.util.HashMap<>();

	/** True only on the first tick of a press. Ignores OS key-repeat, which Minecraft reports as fresh clicks while a key is held. */
	static boolean pressed(KeyMapping k) {
		boolean clicked = false;
		while (k.consumeClick()) clicked = true;
		boolean down = k.isDown();
		boolean was = wasDown.getOrDefault(k, false);
		wasDown.put(k, down);
		return (clicked || down) && !was;
	}

	static void toggleAuto(Minecraft mc, SonarConfig cfg) {
		cfg.autoScan = !cfg.autoScan;
		autoTick = 0;
		try { SonarConfig.save(); } catch (Exception ignored) {}
		if (!Tutorial.toggleSpoken()) Speech.say(mc, Component.literal(cfg.autoScan ? "Auto scan on" : "Auto scan off"));
		Tutorial.event(Tutorial.Ev.AUTO_TOGGLED, cfg.autoScan ? 1 : 0);
	}
}
