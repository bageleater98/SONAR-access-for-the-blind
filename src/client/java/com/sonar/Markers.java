package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Guide icons you can SEE, for sighted friends and testing (setting "Show guide icons", on by default): a bell for the path bell and a tiny copy of the player for the
 * player jingle. Each guide hands its icon the spot it leads to (on the path or route, ahead of you) EVERY tick; the icon glides there, floating level at your own eye
 * height plus a small lift, so it never sits on the ground or a step and never waits for the sound to play.
 * Icons exist only on your screen (client-side entities, never picked up, never sent to the server) and vanish when their guide stops or the setting is off.
 */
final class Markers {
	/** How far ahead the guides place their icons (see the guides), and how far above the middle of your view (degrees): the bell, and the mini player a little lower. */
	static final double AHEAD = Tuning.ICON_AHEAD;
	static final double BELL_LIFT = Tuning.ICON_BELL_LIFT, PLAYER_LIFT = Tuning.ICON_PLAYER_LIFT;

	private static final Map<String, Entity> live = new HashMap<>();
	private static final Map<String, Integer> seen = new HashMap<>();
	private static final Map<String, Vec3> target = new HashMap<>(), at = new HashMap<>();
	private static final Map<String, Double> lift = new HashMap<>();
	private static int t, nextId = -770000;
	/** Icons that stay put over a spot in the world (a waypoint), not at a fixed height on your screen. */
	private static final java.util.Set<String> fixed = new java.util.HashSet<>();

	/** An item icon that floats right over a spot in the world, with a name above it (the waypoint), this tick. */
	static void showFixed(Minecraft mc, String key, Item item, String name, double x, double y, double z) {
		fixed.add(key);
		show(mc, key, x, y, z, 0, () -> {
			ItemEntity e = new ItemEntity(mc.level, 0, 0, 0, new ItemStack(item));
			e.setNoGravity(true);
			e.setNeverPickUp();
			e.setUnlimitedLifetime();
			e.noPhysics = true;
			e.setCustomName(net.minecraft.network.chat.Component.literal(name));
			e.setCustomNameVisible(true);
			return e;
		});
		Entity e = live.get(key);
		if (e != null && (e.getCustomName() == null || !e.getCustomName().getString().equals(name))) e.setCustomName(net.minecraft.network.chat.Component.literal(name));
	}

	/** An item icon (the path bell) at spot x,z this tick. */
	static void showAt(Minecraft mc, String key, Item item, double x, double y, double z) {
		show(mc, key, x, y, z, BELL_LIFT, () -> {
			ItemEntity e = new ItemEntity(mc.level, 0, 0, 0, new ItemStack(item));
			e.setNoGravity(true);
			e.setNeverPickUp();
			e.setUnlimitedLifetime();
			e.noPhysics = true;
			return e;
		});
	}

	/** The player jingle's icon at spot x,z this tick: a tiny copy of that player (their own skin when they are online, the default skin for a test dummy). */
	static void showPlayerAt(Minecraft mc, String key, java.util.UUID id, double x, double y, double z) {
		show(mc, key, x, y, z, PLAYER_LIFT, () -> {
			var info = mc.getConnection() != null ? mc.getConnection().getPlayerInfo(id) : null;
			var profile = info != null ? net.minecraft.world.item.component.ResolvableProfile.createResolved(info.getProfile()) : null;
			return new MiniPlayer(mc.level, mc.playerSkinRenderCache(), profile);
		});
	}

	private static void show(Minecraft mc, String key, double x, double y, double z, double up, java.util.function.Supplier<Entity> make) {
		if (!SonarConfig.cur.guideIcons || mc.level == null || mc.player == null) return;
		Entity e = live.get(key);
		target.put(key, new Vec3(x, y, z));
		lift.put(key, up);
		if (e == null || e.isRemoved() || e.level() != mc.level) {
			Vec3 p = spot(mc, key);
			e = make.get();
			e.setPos(p.x, p.y, p.z);
			e.setId(nextId--);
			mc.level.addEntity(e);
			live.put(key, e);
			at.put(key, p);
		}
		seen.put(key, t);
	}

	/**
	 * Where the icon floats: over its spot, at the height that puts it the same small angle above the middle of your view, wherever you look up or down, so it
	 * stays at one fixed height on your screen (its lift is that angle, in degrees).
	 */
	private static Vec3 spot(Minecraft mc, String key) {
		Vec3 g = target.get(key);
		if (fixed.contains(key)) return g;
		double h = Math.hypot(g.x - mc.player.getX(), g.z - mc.player.getZ());
		double up = Math.max(-70, Math.min(70, -mc.player.getXRot() + lift.getOrDefault(key, 8.0))); // (the game's pitch is positive looking down)
		return new Vec3(g.x, mc.player.getEyeY() + h * Math.tan(Math.toRadians(up)), g.z);
	}

	/** A new world or dimension: the old icons went with the old world; just forget them. */
	static void reset() {
		live.clear(); seen.clear(); target.clear(); at.clear(); lift.clear(); fixed.clear();
	}

	static void tick(Minecraft mc) {
		t++;
		boolean on = SonarConfig.cur.guideIcons && mc.level != null && mc.player != null;
		live.entrySet().removeIf(en -> {
			String key = en.getKey();
			Entity e = en.getValue();
			Integer s = seen.get(key);
			if (on && s != null && t - s <= 10 && e.level() == mc.level) {
				// glide: a third of the way to its spot each tick (smooth, and it keeps up even at a sprint, since its spot moves on with you)
				Vec3 want = spot(mc, key), cur = at.getOrDefault(key, want);
				Vec3 next = Smooth.toward(cur, want, Tuning.ICON_GLIDE);
				next = new Vec3(next.x, want.y, next.z); // the height follows your view at once (only the way along the path glides), so it never drifts on your screen
				at.put(key, next);
				if (e instanceof ItemEntity) e.setPos(next.x, next.y - 0.25, next.z); // an item floats with its middle a quarter block above its position
				else { // the mini player: stands with its feet just below the spot, and turns to face you
					e.setPos(next.x, next.y - 0.15, next.z);
					Vec3 eye = mc.player.getEyePosition();
					float yaw = (float) Math.toDegrees(Math.atan2(-(eye.x - next.x), eye.z - next.z));
					e.setYRot(yaw); e.setYHeadRot(yaw); e.setYBodyRot(yaw);
				}
				e.setDeltaMovement(Vec3.ZERO);
				return false;
			}
			if (mc.level != null && e.level() == mc.level) mc.level.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
			seen.remove(key); target.remove(key); at.remove(key); lift.remove(key); fixed.remove(key);
			return true;
		});
	}
}
