package com.sonar;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Debug: with Debug on, pressing the screenshot key (F2) also saves the blocks around you (33 x 34 x 33) to config/sonar-spots/, named with the same time as the
 * screenshot. That exact spot can then be loaded into the self-test as a world, so a bug found in testing is reproduced and checked from then on.
 *
 * File: line 1 "sonar spot 1"; line 2 "player x y z yaw"; line 3 "box ox oy oz sx sy sz"; line 4 the number of block kinds, then one block per line
 * (as in commands, e.g. minecraft:oak_stairs[facing=north,...]); last line the block kind of every cell, x fastest, then z, then y, as comma-separated numbers.
 */
final class Spot {
	private Spot() {}

	static final int RX = 16, DOWN = 24, UP = 9; // 24 down (was 6): deep enough to see the bottom of a pit or cave below you (round 16)

	/** Saves the spot around you, named like the screenshot taken at the same moment. */
	static void save(Minecraft mc) {
		if (mc.player == null || mc.level == null) return;
		String name = new java.text.SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new java.util.Date()) + ".txt";
		Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("sonar-spots");
		int ox = mc.player.getBlockX() - RX, oy = mc.player.getBlockY() - DOWN, oz = mc.player.getBlockZ() - RX;
		String text = write(mc.level, ox, oy, oz, 2 * RX + 1, DOWN + UP + 1, 2 * RX + 1, mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot());
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
			Dbg.send(mc.player, net.minecraft.network.chat.Component.literal("spot saved: sonar-spots/" + name));
			saveRoute(mc, dir, name);
		} catch (IOException e) {
			org.slf4j.LoggerFactory.getLogger("sonar").warn("Could not save the spot: {}", e.toString());
		}
	}

	/** With a waypoint being guided: the plan (route, what the last search did) as <name>.route.txt, and the whole area it searched (capped) as <name>.search.txt, a spot file the self-test can load. */
	static void saveRoute(Minecraft mc, Path dir, String name) throws IOException {
		String rep = WaypointGuide.report();
		if (rep == null) return;
		String base = name.substring(0, name.length() - 4);
		Files.writeString(dir.resolve(base + ".route.txt"), rep, StandardCharsets.UTF_8);
		int[] b = WaypointGuide.searchedBox();
		if (b == null) return;
		int px = mc.player.getBlockX(), pz = mc.player.getBlockZ(), py = mc.player.getBlockY();
		int x0 = Math.max(b[0] - 4, px - 80), x1 = Math.min(b[1] + 4, px + 80), z0 = Math.max(b[4] - 4, pz - 80), z1 = Math.min(b[5] + 4, pz + 80);
		int y0 = Math.max(b[2] - 4, py - 50), y1 = Math.min(b[3] + 12, py + 40);
		if (x1 < x0 || z1 < z0 || y1 < y0) return;
		Files.writeString(dir.resolve(base + ".search.txt"), write(mc.level, x0, y0, z0, x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1, mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getYRot()), StandardCharsets.UTF_8);
		Dbg.send(mc.player, net.minecraft.network.chat.Component.literal("route saved: sonar-spots/" + base + ".route.txt and .search.txt"));
	}

	/** The spot file text for a box of any world (the game, or a self-test world). */
	static String write(BlockGetter w, int ox, int oy, int oz, int sx, int sy, int sz, double px, double py, double pz, float yaw) {
		Map<BlockState, Integer> kinds = new HashMap<>();
		List<BlockState> palette = new ArrayList<>();
		StringBuilder cells = new StringBuilder();
		var pos = new BlockPos.MutableBlockPos();
		for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
			BlockState s = w.getBlockState(pos.set(ox + x, oy + y, oz + z));
			Integer k = kinds.get(s);
			if (k == null) { k = palette.size(); kinds.put(s, k); palette.add(s); }
			if (cells.length() > 0) cells.append(',');
			cells.append(k);
		}
		StringBuilder out = new StringBuilder("sonar spot 1\n");
		out.append("player ").append(px).append(' ').append(py).append(' ').append(pz).append(' ').append(yaw).append('\n');
		out.append("box ").append(ox).append(' ').append(oy).append(' ').append(oz).append(' ').append(sx).append(' ').append(sy).append(' ').append(sz).append('\n');
		out.append(palette.size()).append('\n');
		for (BlockState s : palette) out.append(BlockStateParser.serialize(s)).append('\n');
		out.append(cells).append('\n');
		return out.toString();
	}

	/** Reads a spot file: calls `put(x, y, z, block)` for every cell that is not air, and returns {player x, y, z, yaw}. */
	static double[] read(String text, PutBlock put) throws Exception {
		String[] lines = text.split("\\R");
		if (!lines[0].startsWith("sonar spot")) throw new IllegalArgumentException("not a spot file");
		String[] pl = lines[1].split(" "), bx = lines[2].split(" ");
		int ox = Integer.parseInt(bx[1]), oy = Integer.parseInt(bx[2]), oz = Integer.parseInt(bx[3]), sx = Integer.parseInt(bx[4]), sz = Integer.parseInt(bx[6]);
		int n = Integer.parseInt(lines[3].trim());
		BlockState[] palette = new BlockState[n];
		for (int i = 0; i < n; i++) palette[i] = BlockStateParser.parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK, lines[4 + i].trim(), false).blockState();
		String[] cells = lines[4 + n].split(",");
		for (int i = 0; i < cells.length; i++) {
			BlockState s = palette[Integer.parseInt(cells[i].trim())];
			if (s.isAir()) continue;
			int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
			put.put(ox + x, oy + y, oz + z, s);
		}
		return new double[]{Double.parseDouble(pl[1]), Double.parseDouble(pl[2]), Double.parseDouble(pl[3]), Double.parseDouble(pl[4])};
	}

	interface PutBlock { void put(int x, int y, int z, BlockState s); }
}
