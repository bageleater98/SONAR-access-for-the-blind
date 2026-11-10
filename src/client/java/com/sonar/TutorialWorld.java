package com.sonar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The tutorial's own throwaway singleplayer world: save folder "sonar-tutorial" ("Sonar Tutorial" in the world list). Void generation, no structures,
 * Peaceful, Adventure, cheats on; fixed midnight, no weather, no mob spawning, no random ticks or fire spread (rooms never change by themselves), no
 * fall (except in the ledges lesson)/fire/drowning/freeze damage, keep inventory, no advancement or command
 * messages. It is created and loaded straight away (no create-world screen) through the game's own WorldOpenFlows.
 *
 * MARKER: sonar_tutorial.json in the save folder (magic, world version, the rooms' fingerprint, built yes/no). Every tutorial action is gated on it, so
 * nothing ever touches any other save. When the mod's rooms changed since the save was built, the save is deleted and built again (the player's lesson
 * progress is kept in config, so it survives).
 *
 * Everything that changes the world runs on the integrated server's thread (game rules, blocks, teleports).
 */
final class TutorialWorld {
	private TutorialWorld() {}

	static final String FOLDER = "sonar-tutorial";
	static final String NAME = "Sonar Tutorial";
	static final String MAGIC = "sonar-tutorial";
	/** Bump when the world's setup (not the rooms: they are fingerprinted) changes, to rebuild old saves. */
	static final int VERSION = 1;
	static final String MARKER = "sonar_tutorial.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** The marker's contents. */
	static final class Marker {
		String magic = MAGIC;
		int version = VERSION;
		String rooms = "";
		boolean built;
		/** True when this save can be used as it is: our magic, this version, these rooms. */
		boolean current(String roomsHash) { return MAGIC.equals(magic) && version == VERSION && roomsHash.equals(rooms); }
	}

	static Marker readMarker(Path levelDir) {
		try {
			Path f = levelDir.resolve(MARKER);
			if (!Files.exists(f)) return null;
			Marker m = GSON.fromJson(Files.readString(f), Marker.class);
			return m != null && MAGIC.equals(m.magic) ? m : null;
		} catch (Exception e) { return null; }
	}

	static void writeMarker(Path levelDir, Marker m) {
		try { Files.writeString(levelDir.resolve(MARKER), GSON.toJson(m)); } catch (Exception e) { SonarClient.LOG.error("Sonar tutorial: could not write the world marker", e); }
	}

	// ---------------------------------------------------------------- client: open or create

	/** A save we are creating right now: its marker is written when its server starts. */
	private static volatile boolean creating;

	/** Opens the tutorial world, creating (or rebuilding) it first when needed. Call with no world loaded. */
	static void open(Minecraft mc) {
		var src = mc.getLevelSource();
		Path dir = src.getBaseDir().resolve(FOLDER);
		Marker m = src.levelExists(FOLDER) ? readMarker(dir) : null;
		if (m != null && (m.current(TutorialRooms.hash()) || System.getenv("SONAR_TUTORIAL_EXPORT") != null)) { // (an export opens an older save as it is)
			SonarClient.LOG.info("SONAR tutorial: opening the tutorial world");
			mc.createWorldOpenFlows().openWorld(FOLDER, () -> mc.gui.setScreen(new TitleScreen()));
			return;
		}
		if (src.levelExists(FOLDER)) {
			if (m == null && !Files.exists(dir.resolve(MARKER))) { // a folder of that name that is NOT ours: never touch it
				SonarClient.LOG.error("Sonar tutorial: a save named " + FOLDER + " exists and is not the tutorial's; not touching it");
				Speech.say(mc, net.minecraft.network.chat.Component.literal("A save called sonar tutorial already exists and is not the tutorial's. Rename it to start the tutorial."));
				return;
			}
			SonarClient.LOG.info("SONAR tutorial: rooms changed (or an older tutorial world), rebuilding the world from scratch");
			try (var access = src.createAccess(FOLDER)) { access.deleteLevel(); } catch (Exception e) { SonarClient.LOG.error("Sonar tutorial: could not delete the old tutorial world", e); return; }
		}
		create(mc);
	}

	private static void create(Minecraft mc) {
		SonarClient.LOG.info("SONAR tutorial: creating the tutorial world");
		creating = true;
		var settings = new LevelSettings(NAME, GameType.ADVENTURE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, true), true, WorldDataConfiguration.DEFAULT);
		var options = new WorldOptions(WorldOptions.randomSeed(), false, false);
		mc.createWorldOpenFlows().createFreshLevel(FOLDER, settings, options, provider -> {
			var biome = provider.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID);
			var flat = new FlatLevelGeneratorSettings(Optional.empty(), biome, List.of())
				.withBiomeAndLayers(List.of(new FlatLayerInfo(1, Blocks.AIR)), Optional.empty(), biome);
			return WorldPresets.createNormalWorldDimensions(provider).replaceOverworldGenerator(provider, new FlatLevelSource(flat));
		}, new TitleScreen());
	}

	// ---------------------------------------------------------------- server

	/** The server running the tutorial world right now, or null. */
	static volatile MinecraftServer server;
	/** Rooms whose blocks are all placed (by index), for the client to wait on. */
	static volatile boolean[] built = new boolean[0];
	/** Blocks still to place: {room index, x, y, z} and the block. */
	private static final ArrayDeque<Object[]> todo = new ArrayDeque<>();

	/** Is this server's save the tutorial world? (Our marker in its folder.) */
	static boolean isTutorial(MinecraftServer s) {
		return s != null && readMarker(s.getWorldPath(LevelResource.ROOT)) != null;
	}

	/** Server started: if it is the tutorial world (or the one being created), set it up and start building. */
	static void serverStarted(MinecraftServer s) {
		Path dir = s.getWorldPath(LevelResource.ROOT);
		if (creating && dir.normalize().getFileName().toString().equals(FOLDER)) {
			creating = false;
			Marker m = new Marker();
			m.rooms = TutorialRooms.hash();
			writeMarker(dir, m);
		}
		Marker m = readMarker(dir);
		if (m == null) { server = null; return; }
		server = s;
		setup(s);
		ServerLevel lv = s.overworld();
		if (System.getenv("SONAR_TUTORIAL_EXPORT") != null) { RoomEdits.export(lv, s.getWorldPath(LevelResource.ROOT)); java.util.Arrays.fill(built = new boolean[TutorialRooms.SLOTS], true); return; } // (dev only: never rebuild what is being exported)
		built = new boolean[TutorialRooms.SLOTS];
		todo.clear();
		var first = TutorialRooms.byId(Progress.load().lesson);
		if (first == null) first = TutorialRooms.ALL.get(0);
		// the room you start in is built at once (before you arrive), the rest a batch per tick
		buildNow(lv, first);
		lv.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, BlockPos.containing(first.spawnX(), first.spawnY(), first.spawnZ()), first.yaw(), 0));
		if (m.built) { java.util.Arrays.fill(built, true); return; }
		for (var r : TutorialRooms.ALL) if (r != first) queue(r);
		SonarClient.LOG.info("SONAR tutorial: building " + todo.size() + " blocks");
	}

	static void serverStopped(MinecraftServer s) {
		if (s == server) { server = null; todo.clear(); }
	}

	/** The world's fixed rules, set every time it loads (26.3 no longer takes game rules at creation). */
	private static void setup(MinecraftServer s) {
		GameRules g = s.getGameRules();
		g.set(GameRules.ADVANCE_TIME, false, s);
		g.set(GameRules.ADVANCE_WEATHER, false, s);
		g.set(GameRules.SPAWN_MOBS, false, s);
		g.set(GameRules.SPAWN_MONSTERS, false, s);
		g.set(GameRules.SPAWN_PATROLS, false, s);
		g.set(GameRules.SPAWN_PHANTOMS, false, s);
		g.set(GameRules.SPAWN_WANDERING_TRADERS, false, s);
		g.set(GameRules.FALL_DAMAGE, false, s);
		g.set(GameRules.FIRE_DAMAGE, false, s);
		g.set(GameRules.DROWNING_DAMAGE, false, s);
		g.set(GameRules.FREEZE_DAMAGE, false, s);
		g.set(GameRules.KEEP_INVENTORY, true, s);
		g.set(GameRules.SHOW_ADVANCEMENT_MESSAGES, false, s);
		g.set(GameRules.SEND_COMMAND_FEEDBACK, false, s);
		g.set(GameRules.RESPAWN_RADIUS, 0, s);
		g.set(GameRules.RANDOM_TICK_SPEED, 0, s);            // the rooms never change by themselves: no grass dying to dirt, no fire, no leaves falling
		g.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, s);
		ServerLevel ov = s.overworld();
		var clock = s.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
		ov.clockManager().moveToTimeMarker(clock, ClockTimeMarkers.MIDNIGHT);
		ov.clockManager().setPaused(clock, true);
		var weather = ov.getWeatherData();
		weather.setRaining(false);
		weather.setThundering(false);
		weather.setRainTime(0);
		weather.setThunderTime(0);
		weather.setClearWeatherTime(Integer.MAX_VALUE / 2);
	}

	private static void buildNow(ServerLevel lv, TutorialRooms.Room r) {
		place(lv, r, false);
		built[r.index()] = true;
	}

	private static void queue(TutorialRooms.Room r) { place(null, r, true); }

	/** Solid blocks first and liquids last (water placed before its walls would run off into the void). */
	private static void place(ServerLevel lv, TutorialRooms.Room r, boolean later) {
		java.util.List<Object[]> fluids = new java.util.ArrayList<>();
		r.build((x, y, z, st) -> {
			Object[] e = {r.index(), x, y, z, st};
			if (!st.getFluidState().isEmpty()) fluids.add(e);
			else if (later) todo.add(e);
			else set(lv, e);
		});
		for (Object[] e : fluids) { if (later) todo.add(e); else set(lv, e); }
		if (later) todo.add(new Object[]{r.index()}); // end of this room
	}

	private static void set(ServerLevel lv, Object[] e) {
		lv.setBlock(new BlockPos((int) e[1], (int) e[2], (int) e[3]), (BlockState) e[4], Block.UPDATE_ALL);
	}

	/** Every server tick: place queued blocks for up to 3 ms; when all are placed, the marker says built. */
	static void serverTick(MinecraftServer s) {
		if (s != server || todo.isEmpty()) return;
		ServerLevel lv = s.overworld();
		long end = System.nanoTime() + 3_000_000L;
		while (!todo.isEmpty() && System.nanoTime() < end) {
			Object[] e = todo.poll();
			if (e.length == 1) { built[(int) e[0]] = true; continue; }
			set(lv, e);
		}
		if (todo.isEmpty()) {
			Path dir = s.getWorldPath(LevelResource.ROOT);
			Marker m = readMarker(dir);
			if (m != null) { m.built = true; writeMarker(dir, m); }
			SonarClient.LOG.info("SONAR tutorial: all rooms built");
		}
	}

	/** True when this room's blocks are all in the world. */
	static boolean roomBuilt(TutorialRooms.Room r) { return r.index() < built.length && built[r.index()]; }

	/** Puts the player at room coordinates rx, ry, rz of room r (on the server thread), standing still, facing yaw. */
	static void teleport(Minecraft mc, TutorialRooms.Room r, double rx, double ry, double rz, float yaw) {
		MinecraftServer s = server;
		if (s == null || mc.player == null) return;
		var id = mc.player.getUUID();
		s.execute(() -> {
			var sp = s.getPlayerList().getPlayer(id);
			if (sp == null) return;
			sp.teleportTo(s.overworld(), r.ox() + rx, r.oy() + ry, r.oz() + rz, Set.of(), yaw, 0f, true);
			sp.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
			sp.resetFallDistance();
			sp.setGameMode(GameType.ADVENTURE);
			sp.setHealth(sp.getMaxHealth()); // (a fall in the ledges lesson is felt, then healed)
		});
	}

	/** Empties the player's inventory; with `axe`, gives an iron axe that may break logs (Adventure mode breaks only what an item says it can). */
	static void inventory(Minecraft mc, boolean axe) {
		MinecraftServer s = server;
		if (s == null || mc.player == null || !isTutorial(s)) return;
		var id = mc.player.getUUID();
		s.execute(() -> {
			var sp = s.getPlayerList().getPlayer(id);
			if (sp == null) return;
			sp.getInventory().clearContent();
			if (axe) {
				var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_AXE);
				var logs = net.minecraft.advancements.predicates.BlockPredicate.Builder.block()
					.of(s.registryAccess().lookupOrThrow(Registries.BLOCK), net.minecraft.tags.BlockTags.LOGS).build();
				stack.set(net.minecraft.core.component.DataComponents.CAN_BREAK, new net.minecraft.world.item.AdventureModePredicate(List.of(logs)));
				sp.getInventory().setItem(0, stack);
				sp.getInventory().setSelectedSlot(0);
			}
			sp.containerMenu.broadcastChanges();
		});
	}

	/** Falls hurt or not (the ledges lesson only). */
	static void fallDamage(boolean on) {
		MinecraftServer s = server;
		if (s == null || !isTutorial(s)) return;
		s.execute(() -> s.getGameRules().set(GameRules.FALL_DAMAGE, on, s));
	}

	/** A command on the tutorial world's server (never any other world), on its thread, with its output hidden. */
	static void command(String cmd) {
		MinecraftServer s = server;
		if (s == null || !isTutorial(s)) return;
		s.execute(() -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), cmd));
	}
}
