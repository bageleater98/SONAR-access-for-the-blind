<div align="center">

# 🔊 SONAR

### Hear the space around you.

**A client-side Fabric accessibility mod that turns Minecraft's surroundings into sound, built for blind and low-vision players.**

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62B47A?style=flat-square)
![Loader](https://img.shields.io/badge/Fabric-Client--only-DBD0B4?style=flat-square)
![Java](https://img.shields.io/badge/Java-25%2B-F89820?style=flat-square)
![License](https://img.shields.io/badge/License-CC0--1.0-blue?style=flat-square)

</div>

---

## What is SONAR?

SONAR lets you press a key and *hear* the world: walls, floors, hazards, water, paths, and open rooms are each given their own sound, placed in 3D along the true direction of the thing that made it. No visuals required.

It runs entirely on the client. Nothing needs installing on the server, so it works in singleplayer and on servers that allow client mods.

## Features

| | Feature | What it does |
|---|---|---|
| 📡 | **Sphere scan** | A time-sliced scan of everything around you, a few layers per tick so the game never stutters. |
| 🎧 | **Directional cues** | Each thing becomes a sound at its real bearing. Sounds behind you are quieter, lower in pitch, and muffled. |
| 🌊 | **Echoes** | In enclosed rooms and cave mouths you hear echoes whose pitch and tail reflect the size of the space. |
| ⚠️ | **Ledge tone** | Blips that speed up near drops, steep slopes, and lava. Plain stairs and fenced edges are ignored. |
| 🛤️ | **Path guide** | A chime from built paths 8 to 11 blocks ahead, so you can follow roads and corridors. |
| 🟫 | **Floor edge** | Tells you where an unusual, built floor begins or ends. |
| 🧍 | **Player chime** | A constant jingle from other players nearby. |
| 💥 | **Stuck thud** | A thud when you walk into a wall, doubled for walls two or more blocks tall. |
| 🗣️ | **Speech** | Spoken feedback through a Windows voice, falling back to the game narrator. |

## How it works

SONAR is a three-stage pipeline.

**1. Scan.** Every exposed solid block competes for one slot per *sector* (16 yaw by 3 height bands) and per *category* (hazard, solid, tactile, water). A block only counts if you could actually see it (a line-of-sight ray) and if its face toward you is at least half open.

**2. Cues.** The winners are turned into sound. Priority runs **hazard, then wall, then tactile**, and per-material budgets for terrain and trees keep the result readable. Sounds are placed along the true direction at a compressed distance of 1 to 13 blocks, because Minecraft clamps gain at 1 block and fades out by 16. Sounds start 0 to 2 ticks apart, front first. An optional `directionSpread` exaggerates sideways angles.

**3. Echo.** Only in enclosed rooms and cave mouths. Far echoes play first and the nearest last, and room size changes pitch and tail.

For the full details, see [DESIGN.md](DESIGN.md).

## Requirements

| | |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5 or newer |
| Fabric API | Required |
| Java | 25 or newer |
| Environment | Client only |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and the SONAR `.jar`.
3. Drop both files into your `mods` folder.
4. Launch the game.

## Configuration

Settings live in `config/sonar.json` and are **hot-reloaded**, so changes apply without a restart. If the file is invalid, SONAR keeps your previous settings instead of breaking.

You can also change everything in game from the pause menu, which opens the Settings screen with sub-screens for **Volumes**, **Keys**, and **More**.

## Building from source

```bash
git clone https://github.com/bageleater98/SONAR-access-for-the-blind.git
cd SONAR-access-for-the-blind
./gradlew build
```

The built jar appears in `build/libs/`. You need JDK 25 or newer.

## Project layout

```
src/main/java/com/sonar/      Mod source (Scan, Cues, and the always-on helpers)
src/main/resources/           fabric.mod.json, mixins, and assets
  assets/sonar/sounds/m/      Generated low-passed copies of vanilla block sounds
DESIGN.md                     Technical design notes
```

## Feedback

SONAR is built for blind players, so your feedback shapes it more than anything else. If a sound is confusing, too loud, or missing, please [open an issue](../../issues).

## License

Released under [CC0-1.0](LICENSE), public domain. Use it, learn from it, and build on it.
