package com.sonar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * HAND EDITS TO THE ROOMS. The rooms are code (TutorialRooms), so a tutorial save edited by hand (creative mode) is lost the next time the rooms change and
 * the world is rebuilt. This keeps such edits:
 *   EXPORT (dev only, environment variable SONAR_TUTORIAL_EXPORT): opening a tutorial save compares every room's box with what the code builds and writes
 *   each changed block (room coordinates) to config/sonar-tutorial/edits-export.json, plus a readable summary. Nothing in the save is touched.
 *   OVERLAY: assets/sonar/tutorial/edits.json (the export, reviewed, moved into the mod) is applied on top of each room's scene, before its barrier shell,
 *   so the edits are part of the room: in the world, in the self-test's rooms, and in the rooms' fingerprint.
 * A block is written as "namespace:id" or "namespace:id[prop=value,...]".
 */
final class RoomEdits {
	private RoomEdits() {}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** One changed block in room coordinates. */
	record Edit(int x, int y, int z, BlockState state) {}

	private static Map<String, List<Edit>> overlay;

	/** The overlay's edits for room `id` (empty when none). */
	static synchronized List<Edit> of(String id) {
		if (overlay == null) overlay = load();
		return overlay.getOrDefault(id, List.of());
	}

	private static Map<String, List<Edit>> load() {
		Map<String, List<Edit>> m = new HashMap<>();
		var in = RoomEdits.class.getResourceAsStream("/assets/sonar/tutorial/edits.json");
		if (in == null) return m;
		try (var r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
			for (var e : root.entrySet()) {
				if (e.getKey().startsWith("_")) continue;
				List<Edit> list = new ArrayList<>();
				for (var el : e.getValue().getAsJsonArray()) {
					JsonArray a = el.getAsJsonArray();
					list.add(new Edit(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), parse(a.get(3).getAsString())));
				}
				m.put(e.getKey(), list);
			}
		} catch (Exception ex) { throw new IllegalStateException("bad assets/sonar/tutorial/edits.json", ex); }
		return m;
	}

	/** "minecraft:oak_stairs[facing=north,half=bottom]" -> the block state. Unknown blocks or values are an error (caught by the self-test). */
	static BlockState parse(String s) {
		int b = s.indexOf('[');
		String id = b < 0 ? s : s.substring(0, b);
		Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
		if (block == Blocks.AIR && !id.endsWith(":air")) throw new IllegalArgumentException("unknown block " + id);
		BlockState st = block.defaultBlockState();
		if (b >= 0) {
			for (String kv : s.substring(b + 1, s.length() - 1).split(",")) {
				if (kv.isBlank()) continue;
				String[] p = kv.split("=", 2);
				Property<?> prop = block.getStateDefinition().getProperty(p[0]);
				if (prop == null) throw new IllegalArgumentException(id + " has no property " + p[0]);
				st = with(st, prop, p[1]);
			}
		}
		return st;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState st, Property<T> prop, String v) {
		var val = prop.getValue(v);
		if (val.isEmpty()) throw new IllegalArgumentException(prop.getName() + " cannot be " + v);
		return st.setValue(prop, val.get());
	}

	/** The block state as text, the way parse reads it. */
	static String text(BlockState st) {
		String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
		var props = new TreeMap<String, String>();
		st.getValues().forEach(pv -> props.put(pv.property().getName(), pv.valueName()));
		if (props.isEmpty()) return id;
		var sb = new StringBuilder(id).append('[');
		boolean first = true;
		for (var e : props.entrySet()) { if (!first) sb.append(','); first = false; sb.append(e.getKey()).append('=').append(e.getValue()); }
		return sb.append(']').toString();
	}

	/** Dev export: every block in every room's box (barrier shell included) that differs from the code's room, to config/sonar-tutorial. */
	static void export(ServerLevel lv, Path saveDir) {
		Map<String, Object> out = new LinkedHashMap<>();
		StringBuilder sum = new StringBuilder("Tutorial save " + saveDir + " compared with the room code\n");
		int total = 0;
		for (var r : TutorialRooms.ALL) {
			Map<BlockPos, BlockState> want = new HashMap<>();
			r.build((x, y, z, s) -> want.put(new BlockPos(x, y, z), s));
			List<Object[]> diffs = new ArrayList<>();
			Map<String, Integer> kinds = new TreeMap<>();
			int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
			for (int y = -1; y <= r.h(); y++)
				for (int z = -1; z <= r.d(); z++)
					for (int x = -1; x <= r.w(); x++) {
						BlockPos p = new BlockPos(r.ox() + x, r.oy() + y, r.oz() + z);
						BlockState want1 = want.getOrDefault(p, Blocks.AIR.defaultBlockState());
						BlockState have = lv.getBlockState(p);
						if (have.equals(want1)) continue;
						diffs.add(new Object[]{x, y, z, text(have)});
						kinds.merge(text(want1).replaceAll("\\[.*", "") + " -> " + text(have).replaceAll("\\[.*", ""), 1, Integer::sum);
						x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); z0 = Math.min(z0, z); z1 = Math.max(z1, z);
					}
			var ents = lv.getEntities((net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(r.ox() - 1, r.oy() - 1, r.oz() - 1, r.ox() + r.w() + 1, r.oy() + r.h() + 1, r.oz() + r.d() + 1), e -> !(e instanceof net.minecraft.world.entity.player.Player));
			if (diffs.isEmpty() && ents.isEmpty()) continue;
			total += diffs.size();
			out.put(r.id(), diffs);
			sum.append("\n").append(r.id()).append(": ").append(diffs.size()).append(" blocks changed");
			if (!diffs.isEmpty()) sum.append(String.format(" within x %d..%d, y %d..%d, z %d..%d", x0, x1, y0, y1, z0, z1));
			sum.append('\n');
			kinds.forEach((k, n) -> sum.append("   ").append(n).append("  ").append(k).append('\n'));
			for (var e : ents) sum.append(String.format("   entity %s at %.1f %.1f %.1f%n", e.getType().toShortString(), r.rx(e.getX()), r.ry(e.getY()), r.rz(e.getZ())));
		}
		try {
			Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("sonar-tutorial");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("edits-export.json"), GSON.toJson(out));
			Files.writeString(dir.resolve("edits-export.txt"), sum.toString());
			SonarClient.LOG.info("SONAR tutorial: exported " + total + " edited blocks to " + dir.resolve("edits-export.json"));
		} catch (Exception e) { SonarClient.LOG.error("Sonar tutorial: export failed", e); }
	}
}
