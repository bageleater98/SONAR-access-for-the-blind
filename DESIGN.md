# Sonar - design notes

Client-only Fabric mod (Minecraft 26.3, Mojang names) that turns the surroundings into sound for blind players.

The rule behind every decision: **it just works**. Two keys, one small settings screen, and everything else happens by itself.

## The tick (`SonarClient`)
`SonarClient` owns the two keys and the order things run in; each feature lives in its own class and runs inside `guard(...)`, so a bug in one
feature is logged and rests for 5 seconds while the rest carry on.

- **R** pings on release (`Scan` then `Cues.emit`); held for 2 seconds it switches auto scan. A tap shorter than one tick still counts.
- **V** (`Identify`): a tap says what you are facing; held it scans silently and says where you are.
- Dead, spectating, the master switch off, or a tutorial room still loading: everything that loops is faded and no cue plays.

## Pipeline
1. **Scan** (`Scan`): time-sliced sphere scan. Each exposed solid block competes for one slot per *sector* (32 yaw x 5 bands) and *category*
   (hazard, solid, water). A block only counts if you could see it (line-of-sight ray) and if a face toward you is open. Walkable slabs and
   stairs are floor, not walls. Floors never sound.
2. **Cues** (`Cues.emit`): turns the winners into sounds. Blocks are grouped by material (`Cues.groupKey`), about one sound per three heard,
   spread over direction, and played as four even beats: front, right, left, behind. Behind you: quieter, lower, and a low-passed copy
   (`sounds/m`). Inside a room or cave the AUTO sonar leaves the block sounds out (the echo speaks there); a key press always plays them.
3. **Echo** (`Echo`, `ExitGuide`, `CaveEcho`, `Rooms`): a guide, not a reflection. Clicks from the ways out of the room or cave you are in,
   from entrances you can reach outside, and from holes into caves. The nearest entrance always clicks last. Ways out of sight are muffled
   (`sounds/h`). The slow work is spread over ticks (`Echo.Job`, `Budget`).

## Always-on helpers
- `Ledges` / `LedgeTone` / `EdgeBrake`: the edge tone, and the brake that slows you when you head straight for a drop. Off while riding,
  gliding or flying.
- `Bump`: thuds when you push into a wall (two for a wall too tall to jump), centring after two thuds, squaring up to a door you open.
- `PathGuide` (the bell on built paths) and `FloorEdge` (the path ripple).
- `PlayerChime`: the jingle from other players, led along the walkable route (`PlayerChime.Search`, an A* that can pause between ticks).
- `Waypoints` / `WaypointGuide` / `WaypointRoute` / `WaypointPlanner` / `GuideSpeech`: saved places and the guide to them. "Last death" and
  "Bed" are kept automatically (`Waypoints.tick`).
- `FluidLoop`: water and lava nearby.
- `Announce`: what a sighted player reads off the screen, said aloud: the way you face, a door ahead, the item you switch to, low health,
  hunger and air, catching fire.
- `Threat`: a monster that can see you is named with its side, then the nearest pulses from where it stands; and the loot tick after a ping.
- `Speech`: Windows voice via a persistent PowerShell process, falling back to the game narrator. Fixed phrases are pre-rendered in the
  tutorial narrator's voice (`Voice.phrase`).
- `Loud`, `Tuning`: the shared loudness rules and every tuned number.

## Menus
`Menus` adds two rows to the pause menu and makes Sonar's screens speak: every widget says its label on focus and on change (unless the
game's Narrator is on). `SettingsScreen` is the whole everyday screen; `AdvancedScreen` holds the per-feature switches and Reset;
`WaypointsScreen` the places. `Scroller` scrolls any Sonar screen that does not fit and makes Tab wrap round.

## Config
`config/sonar.json` (`SonarConfig`), hot-reloaded; a bad file keeps the previous settings. Only the fields in the first block have a button.
`upgrade()` brings an older file up to date one version at a time.

## The tutorial
`Tutorial` (entry points, events), `TutorialWorld` + `TutorialRooms` + `RoomBuilder` + `RoomEdits` (the void world and its rooms),
`Director` + `Lessons` (the levels as steps and reactions), `Narrator` + `Voice` (the queue and the recorded lines), `Sandbox` (the player's
own settings are untouched), `Sense` (guide barriers are air to every sound). The script is `assets/sonar/voice/script.json`; render it with
`tools/voice/render.py`.

## Testing
- `./gradlew build --offline` runs the self-test first (`src/selftest`: fake worlds with real blocks and tags).
- `AutoPlay` (env `SONAR_TUTORIAL_AUTOSTART=1 SONAR_TUTORIAL_AUTOPLAY=1`): a scripted player plays the whole tutorial in the real game.
- `FieldTest` (env `SONAR_FIELD_TEST=<seed>`): a bot in a fresh survival world follows the waypoint guide's sound home from several
  directions and reports each trip, then checks everything Sonar says.
- `SONAR_HIDDEN_WINDOW=1` hides the window. With OpenAL's wave-file driver (`ALSOFT_DRIVERS=wave`) the game's audio is written to a file
  and can be measured (levels, clipping, left/right balance).
- Debug log in the Advanced screen: "SONAR ..." lines in `logs/latest.log`, including everything said ("SONAR said: ...").

## Releases
`release.ps1 "message" -Level patch|minor|major` bumps `gradle.properties`, builds, commits, pushes, tags, and publishes a GitHub release
with the jar.
