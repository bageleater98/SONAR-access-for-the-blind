# Sonar - design notes

Client-only Fabric mod (Minecraft 26.3, Mojang names) that turns the surroundings into sound for blind players.

## Pipeline
1. **Scan** (`Scan`): time-sliced sphere scan, a few Y-layers per tick (twice as many while you move, so the picture is not stale
   when it plays). Each exposed solid block competes for one slot per *sector* (16 yaw x 3 height) and *category* (hazard, solid,
   tactile, water). A block only counts if you could see it (line-of-sight ray) and if its face toward you is at least half open.
   Walkable slabs and stairs count as floor, not walls (`ignoreSteps`).
2. **Cues** (`Cues.emit`): turns the winners into sounds. Priority hazard > wall > tactile. Per-material budgets (terrain up to 16,
   trees) keep it readable; grass/dirt behind you is dropped. Sounds are placed along the true direction at a compressed 1-13 block
   distance around where you are NOW (`Cues.lag` shifts them by how far you moved since the scan began). Behind you: quieter, lower
   pitch, and a bundled low-passed copy of the sound. Sounds start 0-2 ticks apart, front first. Optional `directionSpread`
   exaggerates sideways angles. Animal noises are full volume within 2 blocks and halve every 4 blocks after that.
3. **Echo** (`Cues.echo`, `ExitGuide`): a guide, not a reflection.
   - Inside a building (`Cues.enclosed`: a roof over you AND walls in 6 of 8 directions within 10 blocks, so a covered deck is outside)
     `ExitGuide` flood-fills the floor you can walk on (up 1, down 3, 2 blocks of headroom, doors count as passable) up to 16 blocks.
     Doors and cells with open sky are "ways out", and floor 3+ blocks above/below you marks staircases. Only targets with a clear
     line of sight count. Ways out within 50 degrees of a nearer one merge into one echo. Walls and glass never echo.
   - Outside (or in a cave) the ray-based `mouths` detector marks covered openings.
   - Echoes play in reverse importance order (main way out last), 6 ticks apart. No echo means no start click either.
   - `echoReplacesSonar` only mutes the block sounds inside an enclosed room, not outside next to an opening.

## Always-on helpers
- `LedgeTone` / `Scan.ledgePass`: tones that speed up near drops, steep slopes and lava (plain stairs and fenced edges are ignored,
  as are edges on ground more than 2 blocks above you). Volume and pitch follow the drop (`Cues.dropFactor` / `dropPitch`): 3 blocks
  soft and low, 4 a little louder, 5+ and lava full. With the path guide on and you standing on a real path (2+ touching path
  neighbours), `PathGuide.edges` measures the path sides and the warning switches to path mode.
- `PathGuide`: a bell from the built path 8-11 blocks ahead (not in buildings or on plazas, the connected path must reach 8+ blocks).
  A cell needs 2 free blocks above it, so the side of a house is never a path. Dirt path blocks count as built. A cell with few
  neighbours also bridges to built blocks up to 6 away along the trail's heading (within 40 degrees, up/down 2), so sparse trails
  of separate blocks are followed (`PathGuide.sparseTrail`).
- `FloorEdge` (the "Path ripple" switch): a ripple of the path blocks' own sounds (dirt path uses its louder break sound), starting
  3 blocks ahead, one tick per block out to 12, fading 10% per block, over the two forward diagonals (and a softer straight ahead).
  It follows stairs and slopes (the floor is tracked per step), works while jumping, and delayed hits are shifted by your speed.
  On a sparse trail (or when the three lines find nothing) it ripples along the guide's actual trail blocks instead.
- `PlayerChime`: constant jingle from other players. `stuck()`: thud (double for 2+ tall walls).
- `Speech`: Windows voice via a persistent PowerShell process, falling back to the game narrator.

## Config
`config/sonar.json`, hot-reloaded. A bad file keeps the previous settings. Screens: `SettingsScreen` -> `VolumesScreen`,
`KeysScreen`, `MoreScreen` (opened from the pause menu).

## Debug
Set `"debug": true` in `sonar.json` to print what the cues decide into chat and `logs/latest.log` (wall pings, ledge tone choices,
the guide's reasons, the echo's findings and the ripple's blocks).

## Releases
`release.ps1 "message" -Level patch|minor|major` bumps `gradle.properties`, builds, commits, pushes, tags, and publishes a GitHub
release with the jar.

## Assets
`assets/sonar/sounds/m/*` are generated low-passed copies of vanilla block sounds (`muffled.txt` lists them),
`step*.ogg` / `chime.ogg` are the filtered player-jingle sounds.
