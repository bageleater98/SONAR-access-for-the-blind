package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * WHAT SONAR SAYS BY ITSELF, things a sighted player reads off the screen:
 *  - the way you face ("north"...), quietly, once you settle on a new direction;
 *  - "door ahead" when you stand looking at one;
 *  - the item in your hand when you switch to it ("Stone pickaxe", "Torch, 12"), and when a tool is nearly worn out;
 *  - your hearts, hunger and air when they run low, and when you catch fire.
 * Short fixed words, each said once per occasion. Never in a menu.
 */
final class Announce {
	private Announce() {}

	/** Hearts are 2 health each: "Health low" at 3 hearts, "Health very low" at one and a half; said again only after you have recovered past the next line up. */
	static final float HEALTH_LOW = 6f, HEALTH_CRITICAL = 3f, HEALTH_REARM = 10f;
	static final int FOOD_LOW = 6, FOOD_REARM = 10;
	/** Air left (of 300 ticks) at which "running out of air" is said. */
	static final int AIR_LOW = 100;
	/** A tool with this share of its life left (or less) is "nearly broken". */
	static final double WORN = 0.1;

	static void reset() { facingDir = -1; facingCandidate = -1; healthStage = 0; hungry = starving = airSaid = burning = false; slot = -1; held = ItemStack.EMPTY; }

	static void tick(Minecraft mc, SonarConfig cfg) {
		var p = mc.player;
		if (p == null || !SonarClient.present(p)) { slot = -1; return; }
		if (cfg.sayFacing) facing(mc);
		doorAhead(mc);
		if (cfg.announce) { hand(mc); vitals(mc); }
	}

	private static void say(Minecraft mc, String text) { Speech.say(mc, Component.literal(text)); }

	// ---------------------------------------------------------------- facing

	private static int facingDir = -1, facingCandidate = -1, facingSince;

	/** A teleport (the tutorial moving you): the way you now face is not announced. */
	static void forgetFacing() { facingDir = -1; facingCandidate = -1; }

	/** The word for a yaw: the game's 0 degrees is south, 90 west, 180 north, 270 east. */
	static String compass(float yaw) {
		return new String[]{"south", "west", "north", "east"}[Math.floorMod(Math.round(net.minecraft.util.Mth.wrapDegrees(yaw) / 90f), 4)];
	}

	/**
	 * Says, quietly, which way you face when it changes. Only once you have settled on the new direction (well inside it, within 35 degrees of due north,
	 * east..., for half a second), so turning past or wobbling on a boundary says nothing.
	 */
	private static void facing(Minecraft mc) {
		if (mc.gui.screen() != null) return;
		float yaw = net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot());
		int dir = Math.floorMod(Math.round(yaw / 90f), 4);
		if (Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - dir * 90f)) > 35f) return; // near a boundary: not settled
		if (dir != facingCandidate) { facingCandidate = dir; facingSince = SonarClient.tick; return; }
		if (dir == facingDir || SonarClient.tick - facingSince < Tuning.FACING_SETTLE) return;
		boolean first = facingDir < 0;
		facingDir = dir;
		if (first) return; // not on joining the world: only when you turn
		Speech.say(mc, Component.literal(compass(yaw)), SonarConfig.cur.facingVolume);
		Tutorial.event(Tutorial.Ev.FACING, dir);
	}

	// ---------------------------------------------------------------- door ahead

	private static BlockPos lookDoor;
	private static int lookTicks;
	private static boolean lookSaid;

	/** Looking at a door or trapdoor right in front of you (the next block) for 2 seconds says "door ahead" / "trapdoor ahead", once, until you look at something else. */
	private static void doorAhead(Minecraft mc) {
		if (mc.gui.screen() != null) { lookTicks = 0; return; } // (not with a menu open)
		BlockPos at = null;
		boolean trap = false;
		if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult bh && mc.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
			&& net.minecraft.world.phys.Vec3.atCenterOf(bh.getBlockPos()).distanceTo(mc.player.position().add(0, 0.9, 0)) <= 1.8) { // right in front of you: the next block over
			var st = mc.level.getBlockState(bh.getBlockPos());
			if (st.is(net.minecraft.tags.BlockTags.DOORS)) at = bh.getBlockPos();
			else if (st.is(net.minecraft.tags.BlockTags.TRAPDOORS)) { at = bh.getBlockPos(); trap = true; }
		}
		// the two halves of one door count as the same door
		if (at == null || lookDoor == null || at.distManhattan(lookDoor) > 1) { lookDoor = at; lookTicks = 0; lookSaid = false; return; }
		if (++lookTicks >= 40 && !lookSaid) { lookSaid = true; say(mc, trap ? "trapdoor ahead" : "door ahead"); }
	}

	// ---------------------------------------------------------------- the item in your hand

	private static int slot = -1;
	private static ItemStack held = ItemStack.EMPTY;
	private static boolean wornSaid;

	/** What to say for a stack you just switched to. */
	static String itemLine(String name, int count, boolean empty) {
		return empty ? "Empty hand" : count > 1 ? name + ", " + count : name;
	}

	/** Share of a tool's life left (1 = new, or not a tool). */
	static double lifeLeft(ItemStack s) {
		return s.isDamageableItem() && s.getMaxDamage() > 0 ? 1 - (double) s.getDamageValue() / s.getMaxDamage() : 1;
	}

	private static void hand(Minecraft mc) {
		var inv = mc.player.getInventory();
		ItemStack now = inv.getSelectedItem();
		int sl = inv.getSelectedSlot();
		boolean first = slot < 0;
		// said when you move to another slot, or when the thing in your hand becomes a different thing (picked up into an empty hand, the last one used up)
		boolean changed = sl != slot || now.getItem() != held.getItem();
		if (changed) {
			slot = sl;
			wornSaid = lifeLeft(now) <= WORN; // (a tool already worn when you pick it: said with its name, not again)
			if (!first && mc.gui.screen() == null) say(mc, itemLine(now.getHoverName().getString(), now.getCount(), now.isEmpty()) + (wornSaid ? ", nearly broken" : ""));
		} else if (!wornSaid && lifeLeft(now) <= WORN) {
			wornSaid = true;
			say(mc, now.getHoverName().getString() + " is nearly broken");
		}
		held = now.copy();
	}

	// ---------------------------------------------------------------- hearts, hunger, air, fire

	private static int healthStage; // 0 fine, 1 "low" said, 2 "very low" said
	private static boolean hungry, starving, airSaid, burning;

	/** Which health line is due: 2 = very low, 1 = low, 0 = none, given the line already said. A line is due only on the way down. */
	static int healthLine(float health, int said) {
		if (health <= 0) return 0;
		if (health <= HEALTH_CRITICAL && said < 2) return 2;
		if (health <= HEALTH_LOW && said < 1) return 1;
		return 0;
	}

	private static void vitals(Minecraft mc) {
		var p = mc.player;
		if (p.getAbilities().invulnerable) { healthStage = 0; hungry = starving = airSaid = burning = false; return; } // creative: nothing to warn about
		float h = p.getHealth();
		int line = healthLine(h, healthStage);
		if (line > 0) { healthStage = line; say(mc, line == 2 ? "Health very low" : "Health low"); }
		else if (h >= HEALTH_REARM) healthStage = 0;
		else if (h > HEALTH_LOW && healthStage == 2) healthStage = 1;
		int food = p.getFoodData().getFoodLevel();
		if (food <= 0 && !starving) { starving = hungry = true; say(mc, "Starving"); }
		else if (food <= FOOD_LOW && !hungry) { hungry = true; say(mc, "Hungry"); }
		else if (food >= FOOD_REARM) hungry = starving = false;
		else if (food > 0) starving = false;
		int air = p.getAirSupply();
		if (air <= AIR_LOW && air < p.getMaxAirSupply() && !airSaid) { airSaid = true; say(mc, "Running out of air"); }
		else if (air >= p.getMaxAirSupply()) airSaid = false;
		boolean fire = p.isOnFire();
		if (fire && !burning) say(mc, "On fire");
		burning = fire;
	}
}
