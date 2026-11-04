<div align="center">

# 🔊 SONAR - Access

### Hear the space around you.

**A Fabric mod that turns Minecraft into 3D sound, so blind and low-vision players can explore by ear.**

<a href="https://github.com/bageleater98/SONAR-access-for-the-blind/releases/latest"><img src="assets/download-button-2.svg" alt="Download SONAR, latest release" width="480"></a>

By **BagelEater**

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62B47A?style=for-the-badge)
![Loader](https://img.shields.io/badge/Fabric-Client--only-DBD0B4?style=for-the-badge)
![Java](https://img.shields.io/badge/Java-25%2B-F89820?style=for-the-badge)
![License](https://img.shields.io/badge/License-GPL--3.0-blue?style=for-the-badge)

[What is SONAR](#what-is-sonar) | [Features](#-features) | [Controls](#-controls) | [How it works](#-how-it-works) | [Install](#-installation) | [FAQ](#-faq)

</div>

---

## What is SONAR?

SONAR lets you explore Minecraft by ear.

Press a key and the mod sends out a sonar ping. Walls, drops, water, paths and open rooms each answer with a sound of their own, placed in 3D around your head exactly where they are in the world. A wall to your left is heard on your left. The closer something is, the louder it plays, and anything behind you is quieter and more muffled, so you can tell front from back.

With a pair of headphones, that is enough to find your way around: you can hear where the walls are, where a drop begins, where the water and lava are, which way a path runs, and how big a room is.

SONAR runs only on your own computer, so nothing needs to be installed on the server. Put on headphones and press **R**.

<div align="center">

**[⬇️ Download the latest release](../../releases/latest)**

</div>

---

## ✨ Features

| | |
|---|---|
| 🎧 **True 3D sound** | Every sound comes from the direction of the thing that made it. |
| 📡 **Sonar ping** | One key press scans everything within your range, up to 60 blocks. |
| 🔄 **Auto-ping** | SONAR keeps pinging on its own at a steady rhythm, so your hands stay free. |
| ⚠️ **Ledge warning** | A tone that speeds up as you get closer to a drop, a steep slope or lava. The warning matches the danger: a 3-block drop is a soft, low tone, a 4-block drop a little louder and higher, and 5 blocks or more (or lava) is full strength. Ordinary stairs and fenced edges are ignored, edges on ground well above you (the top of a wall beside you) stay silent, and it stays quiet while you sneak. On a path with the guide bell on, it switches to a path mode that tells you which side of the path you are drifting towards. |
| 🛑 **Edge brake** | A safety net, on by default: if you run at a real drop, SONAR slows you down for half a second. Sneaking, stopping or turning away cancels it at once, so it can never trap you. You can turn it off under **More** in Sonar Settings. |
| 🌊 **Echo guide** | The echo shows you the way out of a building, like a sound coming out of each door. Inside a room it plays one tick from each door or opening to the outside that you can see, and one from each staircase up or down. Plain walls and glass never echo, and the main way out always plays last. Outside, it still marks cave mouths and big covered openings. A roof alone (a covered deck or porch) does not count as a room. |
| 🛤️ **Path guide** | A bell rings from the path 8 to 11 blocks ahead so you can follow roads and corridors. It follows dirt path blocks and also sparse trails of separate blocks, up to 6 blocks apart in a roughly straight line, such as stepping stones climbing a hill. It stays quiet inside buildings, on wide plazas, and on paths shorter than 8 blocks. |
| 〰️ **Path ripple** | On a path, a short ripple of sound starts 3 blocks ahead on the path and runs out to 12 blocks, one quick tick per block, in the sound of each block it passes. It follows stairs and slopes, works in the air, and on sparse trails it ripples along the actual blocks. Where a path begins ahead of you, you hear it too. |
| 💧 **Water and lava** | Within a few blocks of either, SONAR repeats its sound every couple of seconds so you always know it is there. |
| 💥 **Wall thud** | Walk into a wall and you hear a thud. Two thuds in a row mean the wall is two or more blocks tall, so you cannot jump it. |
| 🗣️ **Spoken help** | Hear the name and distance of whatever you are looking at, or a short summary of your surroundings. |
| 🧍 **Player chime** | Other players give off a steady jingle, so you can find each other. |
| 🎛️ **Fully adjustable** | Volumes, range, key bindings and every cue can be changed in game. |

---

## 🎮 Controls

| Key | Action | What it does |
|:---:|---|---|
| **R** | Ping | Tap for a single ping. Hold for 2 seconds to turn auto-ping on or off. |
| **V** | Front ping | Ping only what is in front of you. |
| **B** | Spoken summary | Ping, then speak a short summary of your surroundings. |
| **N** | Identify | Speak the name and distance of whatever your crosshair is pointing at, block or creature. |
| **H** | Echo | Turn the echo guide on or off. |
| **J** | Ledge warning | Turn the ledge warning on or off. |

Every key can be changed: open the pause menu, choose **Sonar Settings**, then **Keys**, click an action and press the key you want.

---

## 🧠 How it works

Every ping happens in three steps.

### 1. 👀 Look

SONAR scans the blocks in a sphere around you. It divides the sphere into 16 directions and 3 heights, and sorts what it finds into four kinds: dangers, solid surfaces such as walls and floors, things you can touch, and water.

Each direction keeps only its most important find of each kind, so you hear a clear picture instead of a wall of noise. A block only counts if you have a clear line of sight to it, so nothing hidden behind a wall gives itself away. The scan is spread over many game ticks, so the game keeps running smoothly.

### 2. 🎧 Sound

Each find becomes a sound played from its direction. If there are too many to play at once, dangers come first, then walls, then things you can touch.

Minecraft's sound system fades distant sounds out quickly, so SONAR squeezes distances into a range where everything stays audible while near things still sound louder than far ones. The sounds start a fraction of a second apart, front first, so they arrive as a sequence you can follow instead of one jumble.

### 3. 🌊 Echo

Inside a building, SONAR adds an echo that works as a guide, not a reflection. It follows the floor you can actually walk on, up to 16 blocks around you, and finds the doors, the ways out to open sky, and the staircases up and down. Only the ones you have a clear line of sight to sound. Each one answers from its own direction, the least important first and your main way out last. Walls and glass never echo. Outside, the echo marks cave mouths and big covered openings.

The ping also keeps some noise down: grass and dirt behind you are skipped, animal noises fade quickly with distance (full volume only within 2 blocks), and the sonar keeps playing when you are outside, even under a partial roof.

---

## 🎛️ Make it yours

Open the pause menu and choose **Sonar Settings**. Everything is there, on separate screens for **Volumes**, **Keys** and **More**.

| Setting | What it does |
|---|---|
| Sonar range | How far SONAR listens, from 15 to 60 blocks. |
| Direction spread | Exaggerates left and right so directions are easier to tell apart. |
| Smallest drop warned | The smallest fall that triggers a warning, from 2 to 8 blocks. |
| Switches | Turn individual cues on or off, such as hazards, close-range touch, gaps and doors, wall thud, water and lava, path guide, path ripple and edge brake. |
| Reset | Put every setting back to its default. |

Settings are saved in `config/sonar.json` and reload instantly, so you can edit the file while the game is running. If the file contains a mistake, SONAR keeps your last working settings.

The first time you play, SONAR also turns on Minecraft's *Directional Audio* setting, which is what gives you proper 3D sound. You can switch it back afterwards.

---

## 📋 Requirements

| | |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5 or newer |
| Fabric API | Required |
| Java | 25 or newer |
| Environment | Client only |

Stereo headphones are strongly recommended, because the 3D sound is what makes SONAR work. Spoken help uses a Windows voice and falls back to Minecraft's built-in narrator on other systems.

## 📥 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and the latest SONAR `.jar` from the [Releases page](https://github.com/bageleater98/SONAR-access-for-the-blind/releases).
3. Put both files in your `mods` folder.
4. Start the game and press **R**.

---

## ❓ FAQ

**Do I need to install anything on the server?**
No. SONAR runs only on your own computer, so it works in singleplayer and on any server that allows client mods.

**Do I need headphones?**
They are strongly recommended. Stereo headphones are what let you tell which direction a sound is coming from.

**Will it slow my game down?**
It is designed not to. The scan is split into small pieces spread over many game ticks, so the game does not freeze or stutter.

**It is too loud, too busy or too quiet. What can I do?**
Open **Sonar Settings**. You can change the volumes and range, and switch off any cue you do not want.

**How do I report a problem or suggest an idea?**
Open an issue on this repository. SONAR is built for blind players, so feedback from the people using it matters most.

---

## 🛠️ For developers

### Building from source

```bash
git clone https://github.com/bageleater98/SONAR-access-for-the-blind.git
cd SONAR-access-for-the-blind
./gradlew build
```

The finished jar appears in `build/libs/`. You need JDK 25 or newer.

### Project layout

```
src/client/java/com/sonar/    The mod: Scan, Cues, helpers, and the settings screens
src/main/resources/           fabric.mod.json and assets
  assets/sonar/sounds/m/      Muffled copies of vanilla block sounds
DESIGN.md                     Technical design notes
```

For the full technical details, see [DESIGN.md](DESIGN.md).

### Versions and releases

Versions use three numbers, `major.minor.patch`. The last number goes up for bug fixes and small tweaks. The middle number goes up when features are added, removed or changed a lot. The first number goes up if the whole mod is redone. The `release.ps1` script raises the version, builds, pushes to GitHub, and publishes a new release with the jar attached:

```powershell
.\release.ps1 "what changed" -Level patch   # or minor, or major
```

It needs the GitHub CLI (`gh`) signed in with write access to the repository.

---

## 👏 Credits

Created by **BagelEater**. Built with [Fabric](https://fabricmc.net/).

## 📜 License

Released under the [GNU GPL v3.0](LICENSE). Use it, learn from it, and build on it. If you share a modified version, it must stay open source under the same license and credit the original author (BagelEater).
