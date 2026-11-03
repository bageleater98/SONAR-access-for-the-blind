<div align="center">

# 🔊 SONAR - Access

### Hear the space around you.

**A Fabric mod that turns Minecraft into 3D sound, so blind and low-vision players can explore by ear.**

By **BagelEater**

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62B47A?style=for-the-badge)
![Loader](https://img.shields.io/badge/Fabric-Client--only-DBD0B4?style=for-the-badge)
![Java](https://img.shields.io/badge/Java-25%2B-F89820?style=for-the-badge)
![License](https://img.shields.io/badge/License-CC0--1.0-blue?style=for-the-badge)

[Features](#-features) | [Controls](#-controls) | [How it works](#-how-it-works) | [Install](#-installation) | [FAQ](#-faq)

</div>

---

## 🔊 What is SONAR?

SONAR lets you explore Minecraft by ear.

Press a key and the mod sends out a sonar ping. Walls, drops, water, lava, paths and open rooms each answer with a sound of their own, placed in 3D around your head exactly where they are in the world. A wall to your left is heard on your left. Something behind you is quieter and more muffled. The closer something is, the louder it plays.

With headphones on, you can hear:

- 🧱 the walls around you, and how far away they are
- ⚠️ the edge of a drop, before you reach it
- 🌊 water and lava nearby
- 🛤️ the path ahead, so you can follow roads and corridors
- 🏛️ the size of the room you are in, from its echo
- 🧍 other players nearby, by their chime

SONAR runs only on your own computer, so there is nothing to install on the server. All you need is a pair of headphones. Install it, put them on, and press **R**.

---

## ✨ Features

| | |
|---|---|
| 🎧 **True 3D sound** | Every sound comes from the direction of the thing that made it. Sounds behind you are quieter, lower and muffled. |
| 📡 **Sonar ping** | One key press scans everything within your range, up to 60 blocks. |
| 🔄 **Auto-ping** | Hold **R** for two seconds and SONAR keeps pinging on its own, hands free. |
| ⚠️ **Ledge warning** | A blip that speeds up as you get closer to a drop, a steep slope or lava. |
| 🛑 **Edge brake** | An optional safety net that briefly slows you down when you run at a real drop. |
| 🌊 **Room echoes** | Caves and enclosed rooms echo back, and the pitch and length of the echo tell you how big the space is. |
| 🛤️ **Path guide** | A bell sounds from the path ahead so you can follow it. |
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
| **H** | Echo | Turn room echoes on or off. |
| **J** | Ledge warning | Turn the ledge warning on or off. |

Every key can be changed. Open the pause menu, choose **Sonar Settings**, then **Keys**, click an action and press the key you want.

---

## 🧠 How it works

Every ping happens in three steps.

### 1. 👀 Look

SONAR scans the blocks in a sphere around you. It divides that sphere into 16 directions and 3 heights, and sorts what it finds into four kinds: dangers, solid surfaces such as walls and floors, things you can touch, and water.

Each direction keeps only its most important find in each kind, so you hear a clear picture instead of a wall of noise. A block only counts if you have a clear line of sight to it, so nothing hidden behind a wall gives itself away. The scan is spread over many game ticks in small slices, so your game keeps running smoothly.

### 2. 🎧 Sound

Each find becomes a sound, played from the direction it is in. When there are too many to play at once, dangers come first, then walls, then things you can touch.

Minecraft's own sound system makes distant sounds fade out quickly, so SONAR compresses distances into a range where everything stays audible, while keeping near things louder than far ones. The sounds start a fraction of a second apart, front first, so they come across as a sequence you can follow instead of one jumble. Anything behind you is quieter, lower in pitch and muffled, which is how you tell front from back.

### 3. 🌊 Echo

Inside a cave or a closed room, SONAR adds an echo, much like the way a bathroom sounds different from a cathedral. The far walls echo first and the nearest last. A small room gives a short, high echo, and a large hall gives a long, low one.

---

## 🛰️ Always-on helpers

These work in the background as you move, without any key presses.

**⚠️ Ledge warning.** When you walk toward a drop, a steep slope or lava, you hear a blip that gets faster the closer you get. Ordinary stairs and fenced edges are ignored, so it only speaks up when it matters. It quiets down when you stand still and stays silent while you sneak.

**🛑 Edge brake (optional).** If you run at a real drop, SONAR can slow you down for half a second to give you time to react. It can never trap you: sneaking, stopping or turning away cancels it immediately.

**🛤️ Path guide.** On a built path, a bell rings from 8 to 11 blocks ahead so you can follow roads and corridors. It stays quiet inside buildings and on wide plazas.

**🟫 Floor edges.** Tells you where a built floor begins or ends, such as stepping from grass onto a stone road.

**💧 Water and lava loop.** Within a few blocks of water or lava, SONAR keeps sounding it every couple of seconds so you always know it is there.

**💥 Stuck thud.** If you walk into a wall, you hear a thud about once a second. Two thuds in a row mean the wall is two or more blocks tall, so you cannot jump over it.

**🧍 Player chime.** Other players give off a constant jingle, so you can find your friends.

**🗣️ Speech.** SONAR speaks its messages using a Windows voice, and falls back to Minecraft's built-in narrator if that is not available.

**🔄 Auto-ping.** Hold **R** and SONAR pings at a steady rhythm on its own, with an echo a moment after each ping.

---

## 🎛️ Make it yours

Open the pause menu and choose **Sonar Settings**. Everything is there, with separate screens for **Volumes**, **Keys** and **More**.

| Setting | What it does |
|---|---|
| Sonar range | How far SONAR listens, from 15 to 60 blocks. |
| Direction spread | Exaggerates left and right so directions are easier to tell apart. |
| Smallest drop warned | The smallest fall that triggers a warning, from 2 to 8 blocks. |
| Switches | Turn individual cues on or off: hazards, close-range touch, gaps and doors, stuck thud, water and lava, path guide, floor edges, edge brake, and sonar off indoors. |
| Reset | Put every setting back to its default. |

Settings are saved in `config/sonar.json` and reload instantly, so you can edit the file while the game is running. If the file contains a mistake, SONAR keeps your last working settings instead of breaking.

The first time you play, SONAR also turns on Minecraft's *Directional Audio* setting so you get proper 3D sound. You can change it back afterwards.

---

## 📋 Requirements

| | |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5 or newer |
| Fabric API | Required |
| Java | 25 or newer |
| Environment | Client only |

**Tip:** use stereo headphones. The 3D sound is what makes SONAR work.

## 📥 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3.
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and the SONAR `.jar`.
3. Put both files in your `mods` folder.
4. Start the game and press **R**.

## 🛠️ Building from source

```bash
git clone https://github.com/bageleater98/SONAR-access-for-the-blind.git
cd SONAR-access-for-the-blind
./gradlew build
```

The finished jar appears in `build/libs/`. You need JDK 25 or newer.

## 🗂️ Project layout

```
src/client/java/com/sonar/    The mod: Scan, Cues, helpers, and the settings screens
src/main/resources/           fabric.mod.json and assets
  assets/sonar/sounds/m/      Muffled copies of vanilla block sounds
DESIGN.md                     Technical design notes
```

For the full technical details, see [DESIGN.md](DESIGN.md).

## 🙇 Getting help and feedback

SONAR is built for blind players, so your feedback matters most. If a sound is confusing, too loud or missing, or if you have an idea, please [open an issue](../../issues).

## ❓ FAQ

**Do I need to install anything on the server?**
No. SONAR runs only on your own computer, so it works in singleplayer and on any server that allows client mods.

**Do I need headphones?**
They are strongly recommended. The 3D sound is what tells you which direction something is in, and that works best with stereo headphones.

**Will it slow my game down?**
It is designed not to. The scan is spread over many game ticks in small pieces, so the game does not freeze or stutter.

**Can I change the keys?**
Yes. Open the pause menu, choose **Sonar Settings**, then **Keys**.

**It is too loud, too busy or too quiet. What can I do?**
Open **Sonar Settings**. You can change the volumes and range, and switch off any cue you do not want.

**How do I report a problem or suggest an idea?**
Open an issue on this repository. See *Getting help and feedback* above.

---

## 👏 Credits

Created by **BagelEater**. Built with [Fabric](https://fabricmc.net/).

## 📜 License

Released under [CC0-1.0](LICENSE), public domain. Use it, learn from it, and build on it.
