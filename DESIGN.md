# Sonar - design notes

Client-only Fabric mod (Minecraft 26.3, Mojang names) that turns the surroundings into sound for blind players.

## Pipeline
1. **Scan** (`Scan`): time-sliced sphere scan, a few Y-layers per tick. Each exposed solid block competes for one slot per
   *sector* (16 yaw x 3 height) and *category* (hazard, solid, tactile, water). A block only counts if you could see it
   (line-of-sight ray) and if its face toward you is at least half open.
2. **Cues** (`Cues.emit`): turns the winners into sounds. Priority hazard > wall > tactile. Per-material budgets (terrain,
   trees) keep it readable. Sounds are placed along the true direction at a compressed 1-13 block distance (Minecraft
   clamps gain at 1 and fades at 16 blocks). Behind you: quieter, lower pitch, and a bundled low-passed copy of the sound.
   Sounds start 0-2 ticks apart, front first. Optional `directionSpread` exaggerates sideways angles.
3. **Echo** (`Cues.echo`): enclosed rooms and cave mouths only. Far echoes first, nearest last; room size changes pitch/tail.

## Always-on helpers
- `LedgeTone` / `Scan.ledgeScan`: blips that speed up near drops, steep slopes and lava (plain stairs and fenced edges are ignored).
- `PathGuide`: a chime from the built path 8-11 blocks ahead (narrow paths only, not in buildings or on plazas), angle exaggerated.
- `FloorEdge`: where an unusual (built) floor begins or ends, on each ping.
- `PlayerChime`: constant jingle from other players. `stuck()`: thud (double for 2+ tall walls).
- `Speech`: Windows voice via a persistent PowerShell process, falling back to the game narrator.

## Config
`config/sonar.json`, hot-reloaded. A bad file keeps the previous settings. Screens: `SettingsScreen` -> `VolumesScreen`,
`KeysScreen`, `MoreScreen` (opened from the pause menu).

## Assets
`assets/sonar/sounds/m/*` are generated low-passed copies of vanilla block sounds (`muffled.txt` lists them),
`step*.ogg` / `chime.ogg` are the filtered player-jingle sounds.
