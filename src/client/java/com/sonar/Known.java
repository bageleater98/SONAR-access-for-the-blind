package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GROUND YOU HAVE WALKED: only the land around you is loaded, so a waypoint route can never see further than that, and a way back along a road you walked an hour
 * ago looks like open air once you are far from it. This remembers every floor cell you stood on (per world and dimension, kept in config/sonar-waypoints/) and
 * lets the waypoint searches step onto one where the world shows nothing (not loaded). Real ground wins: the memory is only asked when the loaded world has no
 * floor there, so a dug-out or built-over cell is never trusted over what is really there.
 */
final class Known {
	private Known() {}

	/** Packed positions (BlockPos.asLong) of floor cells you stood on. */
	static final it.unimi.dsi.fastutil.longs.LongOpenHashSet cells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private static String key;
	private static boolean dirty;
	private static long lastSave;
	private static int ticks;
	static final int MAX = 400_000;

	/** The remembered floor within one block of `ref` at column (x,z), or BlockRules.NONE. */
	static int at(int x, int z, int ref) {
		if (cells.isEmpty()) return BlockRules.NONE;
		for (int d : new int[]{0, 1, -1}) if (cells.contains(BlockPos.asLong(x, ref + d, z))) return ref + d;
		return BlockRules.NONE;
	}

	static void remember(int x, int y, int z) {
		if (cells.size() < MAX && cells.add(BlockPos.asLong(x, y, z))) dirty = true;
	}

	/** Every few ticks: the floor you stand on is remembered. */
	static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null || !mc.player.onGround() || ++ticks % 5 != 0) return;
		int f = BlockRules.floorUnder(mc.level, mc.player.getX(), mc.player.getY(), mc.player.getZ(), new BlockPos.MutableBlockPos());
		if (f != BlockRules.NONE) remember(mc.player.getBlockX(), f, mc.player.getBlockZ());
		if (dirty && System.currentTimeMillis() - lastSave > 60_000) save();
	}

	private static Path file(String k) { return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("sonar-waypoints").resolve(k + ".walked"); }

	/** A new world or dimension: the old memory is kept, the new one read. */
	static void switchTo(Minecraft mc) {
		save();
		cells.clear();
		key = null;
		if (mc.level == null) return;
		try {
			key = Waypoints.worldKey(mc) + "." + Waypoints.dim(mc).replace(':', '_');
			Path f = file(key);
			if (Files.exists(f)) try (var in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(f)))) {
				int n = in.readInt();
				for (int i = 0; i < n && i < MAX; i++) cells.add(in.readLong());
			}
		} catch (Exception e) { SonarClient.LOG.warn("Sonar: could not read the walked ground: {}", e.toString()); }
		dirty = false;
	}

	static void save() {
		if (key == null || !dirty) return;
		lastSave = System.currentTimeMillis();
		try {
			Path f = file(key);
			Files.createDirectories(f.getParent());
			try (var out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(f)))) {
				out.writeInt(cells.size());
				for (long c : cells) out.writeLong(c);
			}
			dirty = false;
		} catch (Exception e) { SonarClient.LOG.warn("Sonar: could not save the walked ground: {}", e.toString()); }
	}
}
