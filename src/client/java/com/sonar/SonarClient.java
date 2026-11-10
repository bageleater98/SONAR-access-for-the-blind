package com.sonar;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import com.mojang.blaze3d.platform.InputConstants;

public class SonarClient implements ClientModInitializer {
	static KeyMapping scanKey, frontKey, summaryKey, identifyKey, echoKey, ledgeKey;
	static Scan scan, last;
	static int sinceScan = 1000, tick, stuckTicks;
	static boolean stuckLatched, pendingSummary;
	private static net.minecraft.client.multiplayer.ClientLevel lastLevel;
	/** The auto echo being worked out, a stage a tick, until it is due to play. */
	private static Echo.Job echoJob;
	private static boolean echoDone;
	private static int stageNow = -1;
	private static boolean shotWasDown;
	private static int lastCentered = -1000;
	/** Ticks before it is due that the auto echo starts its work (it has 5 stages). */
	private static final int ECHO_PREP_TICKS = 5;

	/** Debug: logs work that took a noticeable part of one frame (over 4 ms) in a single tick, with how long. */
	static void slowTick(String what, long startNs) {
		double ms = (System.nanoTime() - startNs) / 1e6;
		if (ms > 4 && SonarConfig.cur.debug) Dbg.send(Minecraft.getInstance().player, net.minecraft.network.chat.Component.literal(Dbg.fmt("slow tick: %s took %.1f ms", what, ms)));
	}
	static double lx, lz;
	static void hookPauseMenu() {
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (!(screen instanceof net.minecraft.client.gui.screens.PauseScreen)) return;
			net.minecraft.client.gui.components.AbstractWidget opt = null;
			for (var ch : screen.children()) {
				if (ch instanceof net.minecraft.client.gui.components.Button b && b.getMessage().equals(Component.translatable("menu.options"))) opt = b;
			}
			if (opt == null) return;
			int rowY = opt.getY();
			for (var ch : screen.children()) {
				if (ch instanceof net.minecraft.client.gui.components.AbstractWidget aw && aw.getY() > rowY) aw.setY(aw.getY() + 24);
			}
			// one bar: "Sonar Access" opens the settings, the ON/OFF part on its right end is the master switch
			var btn = net.minecraft.client.gui.components.Button.builder(Component.literal("Sonar Access"),
				bb -> mc.gui.setScreen(new SettingsScreen(screen))).bounds(w / 2 - 102, rowY + 24, 150, 20).build();
			java.util.function.Supplier<Component> lbl = () -> Component.literal(SonarConfig.cur.enabled ? "ON" : "OFF")
				.withStyle(SonarConfig.cur.enabled ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED);
			var sw = net.minecraft.client.gui.components.Button.builder(lbl.get(), bb -> {
				SonarConfig.cur.enabled = !SonarConfig.cur.enabled;
				try { SonarConfig.save(); } catch (Exception ignored) {}
				bb.setMessage(lbl.get());
			}).bounds(w / 2 + 50, rowY + 24, 52, 20).build();
			net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(btn);
			net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(sw);
			// the Waypoints bar on the row below, the same way: "Waypoints" opens the list, ON/OFF switches waypoints on and off
			for (var ch : screen.children()) {
				if (ch instanceof net.minecraft.client.gui.components.AbstractWidget aw && aw != btn && aw != sw && aw.getY() > rowY + 24) aw.setY(aw.getY() + 24);
			}
			var wbtn = net.minecraft.client.gui.components.Button.builder(Component.literal("Waypoints"),
				bb -> mc.gui.setScreen(new WaypointsScreen(screen))).bounds(w / 2 - 102, rowY + 48, 150, 20).build();
			java.util.function.Supplier<Component> wl = () -> Component.literal(SonarConfig.cur.waypoints ? "ON" : "OFF")
				.withStyle(SonarConfig.cur.waypoints ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED);
			var wsw = net.minecraft.client.gui.components.Button.builder(wl.get(), bb -> {
				SonarConfig.cur.waypoints = !SonarConfig.cur.waypoints;
				try { SonarConfig.save(); } catch (Exception ignored) {}
				bb.setMessage(wl.get());
				Speech.say(mc, Component.literal("Waypoints " + (SonarConfig.cur.waypoints ? "on" : "off")));
			}).bounds(w / 2 + 50, rowY + 48, 52, 20).build();
			net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(wbtn);
			net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(wsw);
			// the tutorial on the row below (not inside the tutorial itself: there Escape opens the tutorial's own menu)
			if (!Tutorial.active()) {
				for (var ch : screen.children()) {
					if (ch instanceof net.minecraft.client.gui.components.AbstractWidget aw && aw != btn && aw != sw && aw != wbtn && aw != wsw && aw.getY() > rowY + 48) aw.setY(aw.getY() + 24);
				}
				net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(Tutorial.entryButton(mc, w / 2 - 102, rowY + 72, 204));
			}
		});
	}

	static int ledgeTimer, ledgeInterval = 10, stillTicks, rHeld, autoTick, echoHold;
	static double px0, pz0;
	static int rippleTick;

	@Override
	public void onInitializeClient() {
		LOG.info("Sonar " + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("sonar").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?") + " starting (settings: config/sonar.json)"); // the version at the top of the log, for bug reports
		hookPauseMenu();
		Tutorial.init();
		Scroller.init();
		var cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sonar", "sonar"));
		scanKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.scan", InputConstants.KEY_R, cat));
		frontKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.front", InputConstants.KEY_V, cat));
		summaryKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.summary", InputConstants.KEY_B, cat));
		identifyKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.identify", InputConstants.KEY_N, cat));
		echoKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.echo", InputConstants.KEY_H, cat));
		ledgeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.ledge", InputConstants.KEY_J, cat));
		Speech.warm();
		SonarConfig.reloadIfChanged();
		Scan.warm(SonarConfig.cur.range, SonarConfig.cur.frontRange);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sonar");
	private static long lastFail = -1000;
	private static final java.util.Map<String, Integer> pausedUntil = new java.util.HashMap<>();

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

	void tickInner(Minecraft mc) {
		Budget.start();
		if (mc.player == null || mc.level == null) { scan = null; return; }
		if (mc.level != lastLevel) { // a new world or dimension: nothing found in the old one (routes, queued echoes, icons, the path) may carry over
			lastLevel = mc.level;
			scan = null;
			echoJob = null; echoDone = false;
			echoJob = null; echoDone = false;
			Cues.pending.clear(); Cues.mobSeen.clear(); Echo.echoTail = 0; echoHold = 0;
			PlayerChime.reset(); PathGuide.reset(); Markers.reset(); Timeline.reset(); Echo.forget(); WaypointGuide.reset();
		}
		if (++tick % 20 == 0) SonarConfig.reloadIfChanged();
		Timeline.tick(tick);
		guard("facing", () -> sayFacing(mc));
		guard("headBonk", () -> Cues.headBonk(mc, SonarConfig.cur));
		// F2 with Debug on: also save the blocks around you, named like the screenshot. The game handles the screenshot key itself and never marks it as held,
		// so the key's own state is read straight from the keyboard
		var shotKey = KeyMappingHelper.getBoundKeyOf(mc.options.keyScreenshot);
		boolean shot = shotKey.getType() == InputConstants.Type.KEYBOARD && InputConstants.isKeyDown(shotKey.getValue());
		if (shot && !shotWasDown && SonarConfig.cur.debug) guard("spot save", () -> Spot.save(mc));
		shotWasDown = shot;
		SonarConfig cfg = SonarConfig.cur;
		if (!cfg.enabled || Tutorial.holdSonar()) { // master switch off (or a tutorial room still loading): silence everything that loops and skip all cues
			LedgeTone.fade(); FluidLoop.quiet(); FluidLoop.tick(mc, cfg); Ledges.ledgeActive = false; brakeTicks = 0; rHeld = 0;
			PathGuide.tick(mc, cfg);
			return;
		}
		sinceScan++;
		double mdx = mc.player.getX() - px0, mdz = mc.player.getZ() - pz0;
		px0 = mc.player.getX(); pz0 = mc.player.getZ();
		stillTicks = Math.hypot(mdx, mdz) > 0.03 ? 0 : stillTicks + 1;
		boolean releasedTap = false;
		if (scanKey.isDown()) {
			if (++rHeld == 40) toggleAuto(mc, cfg); // hold R for 2 seconds
		} else {
			releasedTap = rHeld > 0 && rHeld < 40; // a short press scans on release, a long hold never does
			rHeld = 0;
		}
		if (pressed(echoKey)) {
			cfg.echoCue = !cfg.echoCue;
			try { SonarConfig.save(); } catch (Exception ignored) {}
			Speech.say(mc, Component.literal(cfg.echoCue ? "Echo on" : "Echo off"));
			Tutorial.event(Tutorial.Ev.ECHO_TOGGLED, cfg.echoCue ? 1 : 0);
		}
		if (!cfg.directionalAudioDone) { // once: turn on Directional Audio (binaural 3D), still changeable afterwards
			cfg.directionalAudioDone = true;
			mc.options.directionalAudio().set(true);
			mc.options.save();
			try { SonarConfig.save(); } catch (Exception ignored) {}
		}
		Cues.tickPending(mc);
		LedgeTone.duckTarget = stillTicks >= 10 ? 0.2f : 1f; // not moved for half a second: the edge warning mostly quiets until you move again
		guard("ledge tone", () -> LedgeTone.tick(mc));
		guard("guide icons", () -> Markers.tick(mc));
		guard("door ahead", () -> doorAhead(mc));
		long g0 = System.nanoTime();
		guard("path bell", () -> PathGuide.tick(mc, cfg));
		slowTick("path bell", g0);
		// path ripple: while you are on a guided path it repeats by itself at the auto-scan interval (with auto scan on, each auto ping already carries it)
		if (cfg.floorEdge && !cfg.autoScan && PathGuide.active() && rHeld == 0) {
			if (++rippleTick >= Math.max(6, Math.round(cfg.autoPing * 20))) { rippleTick = 0; guard("path ripple", () -> FloorEdge.ping(mc, cfg)); }
		} else rippleTick = 0;
		if (pressed(ledgeKey)) {
			cfg.autoLedge = !cfg.autoLedge;
			try { SonarConfig.save(); } catch (Exception ignored) {}
			if (!Tutorial.toggleSpoken()) Speech.say(mc, Component.literal(cfg.autoLedge ? "Ledge warning on" : "Ledge warning off"));
			Tutorial.event(Tutorial.Ev.LEDGE_TOGGLED, cfg.autoLedge ? 1 : 0);
		}
		if (pressed(identifyKey)) Identify.run(mc);
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
		if (autoScanNow && cfg.echoCue && cfg.echoCount != 0) { // start the echo's slow work now, a little each tick: underground the route rays (then the corner walk), outside the walk for holes
			if (CaveEcho.inCave(mc, mc.player.getEyePosition())) CaveEcho.prepare(mc, mc.player.getEyePosition(), cfg.caveRange); else CaveEcho.prepareHoles(mc, mc.player.getEyePosition());
		}
		CaveEcho.tick(mc);
		if (!cfg.autoScan || rHeld != 0 || !cfg.echoCue) { echoJob = null; echoDone = false; } // auto paused or echo off: drop a half-done echo, never play it late
		if (echoPrepNow && echoJob == null) echoJob = new Echo.Job(new Scan(mc, cfg, false), cfg);
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
			Echo.echoTail = 0; Echo.echo(mc, new Scan(mc, cfg, false), cfg); echoHold = Echo.echoTail;
			Tutorial.event(Tutorial.Ev.ECHO, 1);
			slowTick("echo", e0);
		}

		boolean s = false, f = false;
		pressed(scanKey); // drain clicks; R scans on release
		if (releasedTap) s = true;
		if (pressed(frontKey)) f = true;
		if (pressed(summaryKey)) { pendingSummary = true; s = true; }
		boolean held = cfg.holdToRepeat && !cfg.autoScan && (scanKey.isDown() || frontKey.isDown()) && sinceScan >= cfg.repeatTicks; // in auto mode a hold never pings: holding R is how you switch auto off
		if ((s || f) && scan != null && scan.auto) scan = null; // a key press during auto cancels the auto scan in progress and gives you your own
		if (scan == null && (s || f || held || autoScanNow)) {
			boolean front = f || (!s && frontKey.isDown());
			scan = new Scan(mc, cfg, front);
			scan.withEcho = !(autoScanNow && !s && !f && !held); // auto sonar leaves the echo to its own slot
			scan.auto = autoScanNow && !s && !f && !held;
			sinceScan = 0;
			if (scan.withEcho && cfg.echoCue && cfg.echoCount != 0) {
				if (CaveEcho.inCave(mc, mc.player.getEyePosition())) CaveEcho.prepare(mc, mc.player.getEyePosition(), cfg.caveRange); else CaveEcho.prepareHoles(mc, mc.player.getEyePosition());
			}
		}
		if (scan != null) {
			long s0 = System.nanoTime();
			scan.step();
			slowTick("sonar scan step", s0);
			if (scan.done) {
				long m0 = System.nanoTime();
				Cues.emit(mc, scan, cfg);
				Tutorial.event(Tutorial.Ev.PING, scan.auto ? 1 : 0);
				slowTick("sonar sounds (emit)", m0);
				last = scan;
				scan = null;
				if (pendingSummary) {
					pendingSummary = false;
					Speech.say(mc, Component.literal(Cues.summary(mc, last)));
				}
			}
		}
		autoLedge(mc, cfg);
		brakeTick(mc.player);
		// on stairs or a slope: standing on a stair/slab, or your height changed by about a block over the last half second (stepping down is spread over several ticks, so check over a window)
		var under = mc.player.getBlockStateOn();
		if (under.is(net.minecraft.tags.BlockTags.STAIRS) || under.is(net.minecraft.tags.BlockTags.SLABS)) stepTicks = 40;
		if (tick % 10 == 0) {
			if (Math.abs(mc.player.getY() - lastY) >= 0.8) stepTicks = 40;
			lastY = mc.player.getY();
		}
		if (stepTicks > 0) stepTicks--;
		stuck(mc, cfg);
		guard("water and lava", () -> FluidLoop.tick(mc, cfg));
		// the player jingle LAST: its route search is the one job that can pause and carry on next tick, so it gets the time the rest of this tick left (Budget)
		long c0 = System.nanoTime();
		guard("waypoint guide", () -> WaypointGuide.tick(mc, cfg));
		guard("player jingle", () -> PlayerChime.tick(mc, cfg));
		slowTick("player jingle", c0);
	}

	/** While walking on the ground, keep warning about edges ahead. Keeps going for 4 s after you stop, then pauses; sneaking it is quieter (LEDGE_SNEAK), and sneaking still it is silent. */
	static void autoLedge(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		var v = p.getDeltaMovement();
		if (!p.onGround() && Ledges.ledgeActive && cfg.autoLedge) return; // jumping near an edge: hold the tone until you land
		boolean sneakMoving = p.isShiftKeyDown() && Math.hypot(v.x, v.z) > 0.005;
		LedgeTone.sneak = p.isShiftKeyDown() ? Tuning.LEDGE_SNEAK : 1f;
		if (!cfg.autoLedge || !cfg.ledgeCue || !p.onGround() || (stillTicks >= 80 && !Ledges.ledgeActive)) { // standing still pauses it after 4 s, unless you are right at an edge
			LedgeTone.fade();
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
		edgeBrake(mc, cfg, p, v);
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

	private static net.minecraft.core.BlockPos lookDoor;
	private static int lookTicks;
	private static boolean lookSaid;

	private static int facingDir = -1, facingCandidate = -1, facingSince;

	/**
	 * Says, quietly, which way you face when it changes: "north", "east", "south" or "west". Only once you have settled on the new direction (well inside it, within 35
	 * degrees of due north, east..., for half a second), so turning past or wobbling on a boundary says nothing. No other words.
	 */
	/** A teleport (the tutorial moving you): the way you now face is not announced. */
	static void forgetFacing() { facingDir = -1; facingCandidate = -1; }

	static void sayFacing(Minecraft mc) {
		if (!SonarConfig.cur.sayFacing || mc.player == null || mc.gui.screen() != null) return;
		float yaw = net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot());
		int dir = Math.floorMod(Math.round(yaw / 90f), 4); // the game's 0 degrees is south, 90 west, 180 north, 270 east
		if (Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - dir * 90f)) > 35f) return; // near a boundary: not settled
		if (dir != facingCandidate) { facingCandidate = dir; facingSince = tick; return; }
		if (dir == facingDir || tick - facingSince < Tuning.FACING_SETTLE) return;
		boolean first = facingDir < 0;
		facingDir = dir;
		if (first) return; // not on joining the world: only when you turn
		Speech.say(mc, Component.literal(new String[]{"south", "west", "north", "east"}[dir]), SonarConfig.cur.facingVolume); // the "North/east/south/west" slider
		Tutorial.event(Tutorial.Ev.FACING, dir);
	}

	/** Looking at a door or trapdoor right in front of you (the next block) for 2 seconds says "door ahead" / "trapdoor ahead", once, until you look at something else. */
	private static void doorAhead(Minecraft mc) {
		if (mc.player == null || mc.level == null) return;
		net.minecraft.core.BlockPos at = null;
		boolean trap = false;
		if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
			&& net.minecraft.world.phys.Vec3.atCenterOf(bh.getBlockPos()).distanceTo(mc.player.position().add(0, 0.9, 0)) <= 1.8) { // right in front of you: the next block over
			var st = mc.level.getBlockState(bh.getBlockPos());
			if (st.is(net.minecraft.tags.BlockTags.DOORS)) at = bh.getBlockPos();
			else if (st.is(net.minecraft.tags.BlockTags.TRAPDOORS)) { at = bh.getBlockPos(); trap = true; }
		}
		// the two halves of one door count as the same door
		if (at == null || lookDoor == null || Math.abs(at.getX() - lookDoor.getX()) + Math.abs(at.getZ() - lookDoor.getZ()) + Math.abs(at.getY() - lookDoor.getY()) > 1) { lookDoor = at; lookTicks = 0; lookSaid = false; return; }
		if (++lookTicks >= 40 && !lookSaid) { lookSaid = true; Speech.say(mc, net.minecraft.network.chat.Component.literal(trap ? "trapdoor ahead" : "door ahead")); }
	}

	static void toggleAuto(Minecraft mc, SonarConfig cfg) {
		cfg.autoScan = !cfg.autoScan;
		autoTick = 0;
		try { SonarConfig.save(); } catch (Exception ignored) {}
		if (!Tutorial.toggleSpoken()) Speech.say(mc, Component.literal(cfg.autoScan ? "Auto scan on" : "Auto scan off"));
		Tutorial.event(Tutorial.Ev.AUTO_TOGGLED, cfg.autoScan ? 1 : 0);
	}

	public static int brakeTicks;
	static int brakeCooldown, stepTicks, brakeDbg;
	static double lastY;
	static double brakeX, brakeZ; // the edge being braked for
	static boolean brakeSprint, brakeFwd; // were you sprinting, and has forward been held the whole time?

	/** Optional: walking or sprinting at an edge with a real drop within about 3 blocks slows you by ~90% for half a second, then a short cooldown. */
	static void edgeBrake(Minecraft mc, SonarConfig cfg, net.minecraft.client.player.LocalPlayer p, net.minecraft.world.phys.Vec3 v) {
		if (brakeTicks > 0 || brakeCooldown > 0) return;
		double speed = Math.hypot(v.x, v.z);
		String why = null;
		if (!cfg.edgeBrake) why = "brake is off";
		else if (!p.onGround()) why = "not on ground";
		else if (p.isShiftKeyDown()) why = "sneaking";
		else if (speed < 0.05) why = "not moving";
		// the nearest thing ahead worth braking for: a real edge (drop 4+) or a step down of 2+ blocks
		double lip = Float.MAX_VALUE;
		double bx = 0.0;
		double bz = 0.0;
		int bdrop = 0;
		if (Ledges.edgeAheadD != Float.MAX_VALUE && Ledges.edgeAheadDrop >= 4) {
			lip = Ledges.edgeAheadD;
			bx = Ledges.edgeAheadX;
			bz = Ledges.edgeAheadZ;
			bdrop = Ledges.edgeAheadDrop;
		}

		if (Ledges.stepAheadD != Float.MAX_VALUE && Ledges.stepAheadD < lip) {
			lip = Ledges.stepAheadD;
			bx = Ledges.stepAheadX;
			bz = Ledges.stepAheadZ;
			bdrop = Ledges.stepAheadDrop;
		}

		if (why == null && lip == Float.MAX_VALUE) why = "no drop of 2+ blocks ahead of you (within 45 deg)";
		if (why == null && lip > 1.5) why = String.format("drop is %.1f blocks away (needs 1.5)", lip);
		if (why != null) {
			if (cfg.debug && lip != Float.MAX_VALUE && ++brakeDbg % 10 == 0) Dbg.send(p, Component.literal("brake waiting: " + why));
			return;
		}
		Tutorial.event(Tutorial.Ev.BRAKE, bdrop);
		if (cfg.debug) Dbg.send(p, Component.literal("brake: FIRED, drop " + Math.round(lip * 10) / 10.0 + " blocks ahead, " + bdrop + " blocks deep"));
		brakeTicks = 10; // half a second
		brakeSprint = p.isSprinting();
		brakeFwd = mc.options.keyUp.isDown();
		brakeX = bx;
		brakeZ = bz;
	}

	/** Runs every tick while a brake is active: keeps horizontal speed at 10%. Sneaking, stopping, or moving sideways or away from the edge cancels it at once. */
	/** If you were sprinting and never let go of forward, keep sprinting once the slowdown ends. */
	static void resumeSprint(net.minecraft.client.player.LocalPlayer p, net.minecraft.client.Options o) {
		if (brakeSprint && brakeFwd && o.keyUp.isDown() && !p.isShiftKeyDown()) {
			p.setSprinting(true);
		}
	}

	static void brakeTick(net.minecraft.client.player.LocalPlayer p) {
		if (brakeTicks > 0) {
			var o = Minecraft.getInstance().options;
			double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0), sr = (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0);
			double yaw = Math.toRadians(p.getYRot());
			double dx = f * -Math.sin(yaw) + sr * -Math.cos(yaw), dz = f * Math.cos(yaw) + sr * -Math.sin(yaw);
			double ex = brakeX - p.getX(), ez = brakeZ - p.getZ();
			double align = (dx * ex + dz * ez) / (Math.max(1e-6, Math.hypot(dx, dz)) * Math.max(1e-6, Math.hypot(ex, ez)));
			if (!o.keyUp.isDown()) brakeFwd = false; // letting go of forward even for a moment means no automatic sprint afterwards
			if (p.isShiftKeyDown() || (f == 0 && sr == 0) || align < 0.5) { // sneaking, stopping, or turning to the side or away ends it at once
				brakeTicks = 0;
				resumeSprint(p, o);
				return;
			}
			p.setSprinting(false);
			if (--brakeTicks == 0) {
				brakeCooldown = 30; // 1.5 s before it can brake again, so it can never trap you
				resumeSprint(p, o);
			}
		} else if (brakeCooldown > 0) {
			brakeCooldown--;
		}
	}

	static int thudAgain, thudRun, lastContact = -1000;
	static double contactX, contactZ;

	/** Pushing into a block (walking straight in or sliding along it): a thud every second. Two thuds when the wall is taller than you can jump (more than about one block:
	 *  a block with a slab on top counts); one thud when any part of what you touch is a single block you could jump onto (a corner where a 1-tall and a 2-tall block meet).
	 *  Tapping W into a wall instead of holding it: the second tap against the same wall thuds straight away. Two thuds in a row (still pushing): you are moved to the middle
	 *  of your block and turned to face straight north, east, south or west, whichever is nearest. */
	void stuck(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		if (thudAgain > 0 && --thudAgain == 0) thud(mc, cfg, p);
		var o = mc.options;
		double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0), sr = (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0);
		if (!cfg.stuckCue || (f == 0 && sr == 0)) { stuckTicks = 0; stuckLatched = false; if (tick - lastContact > 40) thudRun = 0; return; }
		if (p.onClimbable()) { stuckTicks = 0; thudRun = 0; return; } // on a ladder or vine you are pressed against the wall on purpose: no thuds
		if (p.horizontalCollision) {
			stuckTicks++;
			// a new tap into the wall you touched a moment ago (within 2 s, without walking away): thud now instead of waiting for a long push
			boolean tap = stuckTicks == 1 && tick - lastContact <= 40 && Math.hypot(p.getX() - contactX, p.getZ() - contactZ) < 0.6;
			lastContact = tick; contactX = p.getX(); contactZ = p.getZ();
			if (tap || (stuckTicks >= 8 && (stuckTicks - 8) % 20 == 0)) {
				double yaw = Math.toRadians(p.getYRot());
				double dx = f * -Math.sin(yaw) + sr * -Math.cos(yaw), dz = f * Math.cos(yaw) + sr * -Math.sin(yaw);
				double len = Math.max(1e-6, Math.hypot(dx, dz));
				dx /= len; dz /= len;
				var lv = Sense.hear(mc); // the wall as Sonar hears it: a guide block (barrier) is not there (Sense)
				int feet = (int) Math.floor(p.getY() + 0.01);
				boolean climbable = false, tall = false, heard = false, guide = false;
				var gp = new net.minecraft.core.BlockPos.MutableBlockPos();
				for (double off : new double[]{-0.29, 0.0, 0.29}) { // across your width (you are 0.6 wide), just past your front: every block you could be touching
					double sx = p.getX() + dx * 0.55 - dz * off, sz = p.getZ() + dz * 0.55 + dx * off;
					int bx = (int) Math.floor(sx), bz = (int) Math.floor(sz);
					if (Sense.guideAt(lv, gp.set(bx, feet, bz)) || Sense.guideAt(lv, gp.set(bx, feet + 1, bz))) guide = true;
					double top = wallTop(lv, bx, feet, bz) - p.getY(); // how high this part of the wall reaches above your feet
					if (top <= 0.6) continue; // a slab or carpet you step onto
					heard = true;
					int ty = (int) Math.floor(p.getY() + top - 0.01); // the block that forms the top
					if (top <= 1.1 && !blocks(lv, bx, ty + 1, bz, -1e9) && !blocks(lv, bx, ty + 2, bz, -1e9)) climbable = true; // one block with room above it: jump up
					if (top > 1.1) tall = true; // more than a block: you can not jump it
				}
				if (guide && !heard) { Tutorial.event(Tutorial.Ev.GUIDE_PUSH, 0); return; } // only a guide block in the way: silent, no thud and no squaring up (Sense)
				thud(mc, cfg, p);
				Tutorial.event(Tutorial.Ev.BUMP, tall && !climbable ? 2 : 1);
				if (tall && !climbable) thudAgain = 4; // too tall everywhere you touch: second thud
				if (++thudRun >= 2 && cfg.centerOnBump && tick - lastCentered >= Tuning.CENTER_COOLDOWN) { centerAndSquare(p); thudRun = 0; lastCentered = tick; Tutorial.event(Tutorial.Ev.SQUARED, 0); } // at most once every 1.5 s
			}
		} else { stuckTicks = 0; if (tick - lastContact > 40) thudRun = 0; }
	}

	/** Top of the wall in column x,z: the highest collision top of the blocks stacked from your feet level up (3 blocks), stopping at the first gap. */
	static double wallTop(net.minecraft.world.level.BlockGetter lv, int x, int feet, int z) {
		double top = feet;
		for (int y = feet; y <= feet + 2; y++) {
			var bp = new net.minecraft.core.BlockPos(x, y, z);
			var shape = lv.getBlockState(bp).getCollisionShape(lv, bp);
			if (shape.isEmpty() || y + shape.min(net.minecraft.core.Direction.Axis.Y) > top + 0.01) break;
			top = y + shape.max(net.minecraft.core.Direction.Axis.Y);
		}
		return top;
	}

	/** A block at x,y,z that stops you: its collision reaches above the given height (so a slab or carpet you simply step onto does not count). */
	static boolean blocks(net.minecraft.world.level.BlockGetter lv, int x, int y, int z, double above) {
		var bp = new net.minecraft.core.BlockPos(x, y, z);
		var shape = lv.getBlockState(bp).getCollisionShape(lv, bp);
		return !shape.isEmpty() && y + shape.max(net.minecraft.core.Direction.Axis.Y) > above;
	}

	/** Moves you to the middle of the block you stand on (only if you fit there) and turns you to the nearest of north, east, south, west. Up/down look is kept. */
	static void centerAndSquare(net.minecraft.client.player.LocalPlayer p) {
		double cx = Math.floor(p.getX()) + 0.5, cz = Math.floor(p.getZ()) + 0.5;
		if (p.onGround() && p.level().noCollision(p, p.getBoundingBox().move(cx - p.getX(), 0, cz - p.getZ()))) p.setPos(cx, p.getY(), cz);
		float yaw = Math.round(p.getYRot() / 90f) * 90f;
		p.setYRot(yaw);
		p.yRotO = yaw;
		p.setYHeadRot(yaw);
		if (SonarConfig.cur.debug) Dbg.send(p, Component.literal("bump: centered and turned to " + Math.round(net.minecraft.util.Mth.wrapDegrees(yaw)) + " deg"));
	}

	static void thud(Minecraft mc, SonarConfig cfg, net.minecraft.world.entity.player.Player p) {
		Cues.play(mc, SoundEvents.NOTE_BLOCK_BASEDRUM.value(), Math.min(1f, cfg.volume + 0.3f), 0.6f, p.getX(), p.getEyeY(), p.getZ());
	}

}
