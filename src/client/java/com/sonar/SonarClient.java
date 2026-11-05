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
	static int sinceScan = 1000, tick, stuckTicks, loopTimer;
	static boolean narratorChecked, stuckLatched, pendingSummary;
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
		});
	}

	static int ledgeTimer, ledgeInterval = 10, stillTicks, rHeld, autoTick;
	static double px0, pz0;
	static int rippleTick;

	@Override
	public void onInitializeClient() {
		hookPauseMenu();
		var cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sonar", "sonar"));
		scanKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.scan", InputConstants.KEY_R, cat));
		frontKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.front", InputConstants.KEY_V, cat));
		summaryKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.summary", InputConstants.KEY_B, cat));
		identifyKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.identify", InputConstants.KEY_N, cat));
		echoKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.echo", InputConstants.KEY_H, cat));
		ledgeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.sonar.ledge", InputConstants.KEY_J, cat));
		Speech.warm();
		SonarConfig.reloadIfChanged();
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("sonar");
	private static long lastFail = -1000;

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
		if (mc.player == null || mc.level == null) { scan = null; return; }
		if (++tick % 20 == 0) SonarConfig.reloadIfChanged();
		SonarConfig cfg = SonarConfig.cur;
		if (!cfg.enabled) { // master switch off: silence everything that loops and skip all cues
			LedgeTone.fade(); Cues.loopSound = null; Cues.ledgeActive = false; brakeTicks = 0; rHeld = 0;
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
		}
		if (!cfg.directionalAudioDone) { // once: turn on Directional Audio (binaural 3D), still changeable afterwards
			cfg.directionalAudioDone = true;
			mc.options.directionalAudio().set(true);
			mc.options.save();
			try { SonarConfig.save(); } catch (Exception ignored) {}
		}
		Cues.tickPending(mc);
		LedgeTone.duckTarget = stillTicks >= 60 ? 0.2f : 1f; // not moved for 3 s: the edge warning mostly quiets until you move again
		LedgeTone.tick(mc);
		PlayerChime.tick(mc, cfg);
		PathGuide.tick(mc, cfg);
		// path ripple: while you are on a guided path it repeats by itself at the auto-scan interval (with auto scan on, each auto ping already carries it)
		if (cfg.floorEdge && !cfg.autoScan && PathGuide.active() && rHeld == 0) {
			if (++rippleTick >= Math.max(6, Math.round(cfg.autoPing * 20))) { rippleTick = 0; FloorEdge.ping(mc, cfg); }
		} else rippleTick = 0;
		if (pressed(ledgeKey)) {
			cfg.autoLedge = !cfg.autoLedge;
			try { SonarConfig.save(); } catch (Exception ignored) {}
			Speech.say(mc, Component.literal(cfg.autoLedge ? "Ledge warning on" : "Ledge warning off"));
		}
		if (pressed(identifyKey)) Identify.run(mc);
		// auto cycle: sonar on the first second, echo one second later, then quiet until the next cycle (paused while R is held)
		boolean autoScanNow = false, autoEchoNow = false;
		if (cfg.autoScan && rHeld == 0) {
			int period = Math.max(6, Math.round(cfg.autoPing * 20));
			int echoAt = (1 + Math.min(6, Math.max(2, period / 2))) % period; // echo a fraction of a second after the ping
			autoTick++;
			if (autoTick % period == 1) autoScanNow = true;
			else if (cfg.echoCue && autoTick % period == echoAt) autoEchoNow = true;
		}
		if (autoEchoNow) Cues.echo(mc, new Scan(mc, cfg, false), cfg);

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
		}
		if (scan != null) {
			scan.step();
			if (scan.done) {
				Cues.emit(mc, scan, cfg);
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
		loop(mc, cfg);
	}

	/** While walking on the ground (not sneaking), keep warning about edges ahead. Keeps going for 4 s after you stop, then pauses; sneaking is silent. */
	static void autoLedge(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		var v = p.getDeltaMovement();
		if (!p.onGround() && Cues.ledgeActive && cfg.autoLedge) return; // jumping near an edge: hold the tone until you land
		if (!cfg.autoLedge || !cfg.ledgeCue || !p.onGround() || p.isShiftKeyDown() || (stillTicks >= 80 && !Cues.ledgeActive)) { // standing still pauses it after 4 s, unless you are right at an edge
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
		ledgeInterval = Cues.watchLedges(mc, ls, cfg, vx, vz);
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

	static void toggleAuto(Minecraft mc, SonarConfig cfg) {
		cfg.autoScan = !cfg.autoScan;
		autoTick = 0;
		try { SonarConfig.save(); } catch (Exception ignored) {}
		Speech.say(mc, Component.literal(cfg.autoScan ? "Auto scan on" : "Auto scan off"));
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
		if (Cues.edgeAheadD != Float.MAX_VALUE && Cues.edgeAheadDrop >= 4) {
			lip = Cues.edgeAheadD;
			bx = Cues.edgeAheadX;
			bz = Cues.edgeAheadZ;
			bdrop = Cues.edgeAheadDrop;
		}

		if (Cues.stepAheadD != Float.MAX_VALUE && Cues.stepAheadD < lip) {
			lip = Cues.stepAheadD;
			bx = Cues.stepAheadX;
			bz = Cues.stepAheadZ;
			bdrop = Cues.stepAheadDrop;
		}

		if (why == null && lip == Float.MAX_VALUE) why = "no drop of 2+ blocks ahead of you (within 45 deg)";
		if (why == null && lip > 1.5) why = String.format("drop is %.1f blocks away (needs 1.5)", lip);
		if (why != null) {
			if (cfg.debug && lip != Float.MAX_VALUE && ++brakeDbg % 10 == 0) p.sendSystemMessage(Component.literal("brake waiting: " + why));
			return;
		}
		if (cfg.debug) p.sendSystemMessage(Component.literal("brake: FIRED, drop " + Math.round(lip * 10) / 10.0 + " blocks ahead, " + bdrop + " blocks deep"));
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

	static int thudAgain, thudRun;

	/** Pushing into a block (walking straight in or sliding along it): a thud every second. Two thuds only when EVERY block you are touching is 2+ tall; if any of them is a single
	 *  block you could jump onto (a corner where a 1-tall and a 2-tall block meet), it is one thud, so you know you can get up there. Two thuds in a row (still pushing):
	 *  you are moved to the middle of your block and turned to face straight north, east, south or west, whichever is nearest. */
	void stuck(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		if (thudAgain > 0 && --thudAgain == 0) thud(mc, cfg, p);
		var o = mc.options;
		double f = (o.keyUp.isDown() ? 1 : 0) - (o.keyDown.isDown() ? 1 : 0), sr = (o.keyRight.isDown() ? 1 : 0) - (o.keyLeft.isDown() ? 1 : 0);
		if (!cfg.stuckCue || (f == 0 && sr == 0)) { stuckTicks = 0; thudRun = 0; stuckLatched = false; return; }
		if (p.horizontalCollision && ++stuckTicks >= 8 && (stuckTicks - 8) % 20 == 0) {
			thud(mc, cfg, p);
			double yaw = Math.toRadians(p.getYRot());
			double dx = f * -Math.sin(yaw) + sr * -Math.cos(yaw), dz = f * Math.cos(yaw) + sr * -Math.sin(yaw);
			double len = Math.max(1e-6, Math.hypot(dx, dz));
			dx /= len; dz /= len;
			var lv = p.level();
			int feet = (int) Math.floor(p.getY() + 0.01);
			boolean climbable = false, tall = false;
			for (double off : new double[]{-0.29, 0.0, 0.29}) { // across your width (you are 0.6 wide), just past your front: every block you could be touching
				double sx = p.getX() + dx * 0.55 - dz * off, sz = p.getZ() + dz * 0.55 + dx * off;
				int bx = (int) Math.floor(sx), bz = (int) Math.floor(sz);
				boolean low = blocks(lv, bx, feet, bz, p.getY() + 0.6), high = blocks(lv, bx, feet + 1, bz, -1e9), head = blocks(lv, bx, feet + 2, bz, -1e9);
				if (low && !high && !head) climbable = true; // one block tall with room above it: jump up
				if (low && high) tall = true;
			}
			if (tall && !climbable) thudAgain = 4; // 2+ blocks tall everywhere you touch: second thud
			if (++thudRun >= 2 && cfg.centerOnBump) { centerAndSquare(p); thudRun = 0; }
		} else if (!p.horizontalCollision) { stuckTicks = 0; thudRun = 0; }
	}

	/** A block at x,y,z that stops you: its collision reaches above the given height (so a slab or carpet you simply step onto does not count). */
	static boolean blocks(net.minecraft.world.level.Level lv, int x, int y, int z, double above) {
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
		if (SonarConfig.cur.debug) p.sendSystemMessage(Component.literal("bump: centered and turned to " + Math.round(net.minecraft.util.Mth.wrapDegrees(yaw)) + " deg"));
	}

	static void thud(Minecraft mc, SonarConfig cfg, net.minecraft.world.entity.player.Player p) {
		Cues.play(mc, SoundEvents.NOTE_BLOCK_BASEDRUM.value(), Math.min(1f, cfg.volume + 0.3f), 0.6f, p.getX(), p.getEyeY(), p.getZ());
	}

	/** Water/lava re-played every 2 s while within ~4 blocks of the nearest one from the last scan. */
	void loop(Minecraft mc, SonarConfig cfg) {
		if (!cfg.waterLavaLoop || Cues.loopSound == null) return;
		if (mc.player.distanceToSqr(Cues.loopX, Cues.loopY, Cues.loopZ) > 16 || ++loopTimer < 40) return; // water re-plays every 2 s
		loopTimer = 0;
		Cues.play(mc, Cues.loopSound, Cues.loopVol * cfg.volume, Cues.loopPitch, Cues.loopX, Cues.loopY, Cues.loopZ);
	}
}
