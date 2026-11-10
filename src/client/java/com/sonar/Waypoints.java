package com.sonar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Saved places, per world (single player: the save's folder name; multiplayer: the server address) and per dimension. Kept in config/sonar-waypoints/<world>.json,
 * so they survive restarts. A waypoint is the block one above where you stood (its icon floats one block higher).
 */
final class Waypoints {
	private Waypoints() {}

	/** One saved place: its name, its dimension ("minecraft:overworld"...), and the block one above where you stood. */
	static final class Wp {
		String name, dim;
		int x, y, z;
		Wp() {}
		Wp(String name, String dim, int x, int y, int z) { this.name = name; this.dim = dim; this.x = x; this.y = y; this.z = z; }
		@Override public boolean equals(Object o) { return o instanceof Wp w && w.name.equals(name) && w.dim.equals(dim) && w.x == x && w.y == y && w.z == z; }
		@Override public int hashCode() { return java.util.Objects.hash(name, dim, x, y, z); }
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static String loadedFor;
	static final List<Wp> all = new ArrayList<>();

	/** The list as saved text, and back (the file format; also used by the self-test). */
	static String write(List<Wp> list) { return GSON.toJson(list); }
	static List<Wp> read(String text) {
		List<Wp> l = GSON.fromJson(text, new TypeToken<List<Wp>>() {}.getType());
		return l == null ? new ArrayList<>() : new ArrayList<>(l);
	}

	/** This world's file name: the single player save folder, or the server address, made safe for a file name. */
	static String worldKey(Minecraft mc) {
		String k;
		if (mc.getSingleplayerServer() != null) k = "sp-" + mc.getSingleplayerServer().getWorldData().getLevelName();
		else if (mc.getCurrentServer() != null) k = "mp-" + mc.getCurrentServer().ip;
		else k = "unknown";
		return k.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	static String dim(Minecraft mc) { return mc.level.dimension().identifier().toString(); }

	private static Path file(String key) { return FabricLoader.getInstance().getConfigDir().resolve("sonar-waypoints").resolve(key + ".json"); }

	/** Loads this world's waypoints (once per world). */
	static List<Wp> list(Minecraft mc) {
		String key = worldKey(mc);
		if (!key.equals(loadedFor)) {
			all.clear();
			try { Path f = file(key); if (Files.exists(f)) all.addAll(read(Files.readString(f))); } catch (Exception e) { SonarClient.LOG.warn("Sonar: could not read waypoints: " + e); }
			loadedFor = key;
		}
		return all;
	}

	static void save(Minecraft mc) {
		try { Path f = file(worldKey(mc)); Files.createDirectories(f.getParent()); Files.writeString(f, write(all)); }
		catch (Exception e) { SonarClient.LOG.warn("Sonar: could not save waypoints: " + e); }
	}

	/** A new waypoint where you stand: the block one above your feet. */
	static Wp add(Minecraft mc, String name) {
		var p = mc.player;
		Wp w = new Wp(name, dim(mc), p.getBlockX(), p.getBlockY() + 1, p.getBlockZ());
		list(mc).add(w);
		save(mc);
		return w;
	}

	/** Blocks from you to it (straight line), or -1 in another dimension. */
	static int distance(Minecraft mc, Wp w) {
		if (!w.dim.equals(dim(mc))) return -1;
		return (int) Math.round(Math.sqrt(mc.player.distanceToSqr(w.x + 0.5, w.y - 1, w.z + 0.5)));
	}
}
