# Sonar Access: changes

## 1.5.1

### Tutorial
- New order: ledges, round the corner, the echo indoors, finding entrances, then jumps, stairs, ladders and doors (its door now clicks, and auto scan is on there).
- The path bell has a new sound (clean glass tones with the glass of a breaking bottle), plays faster, and glides instead of jumping. On the path level, Wren says it will lead you backwards if you turn round, and tells you if you do.
- Every line that names a key has a second recording that says "your ping key" for players who changed that key, so the narrator's voice is used everywhere.
- Opening a door centres you in front of it. Invisible walls on the ridge, gazebo and pond levels; a wider cave mouth; rock all round the cave level.
- The "move on if you're stuck" offer is gone; hints come a little sooner; the sweep is one notch slower.

### Mod
- Master switch off now silences the direction words and the head-bump tone. The head-bump tone plays only under a two-block ceiling.
- The edge tone is louder when you crouch or stand still.
- Tab loops through every Sonar menu.
### Routes (all of the mod)
- Route searches for the player jingle and the waypoint guide now run on a helper thread, so a long search can no longer stutter the game.
- The waypoint guide no longer keeps leading you into a wall: when a search finds no way on, it forgets the dead ends it was avoiding, looks again, and drops a route that leads nowhere.
- The player jingle is no longer muffled while you stand or swim in water.


## 1.5.0

### The tutorial
- A voiced tutorial with a narrator called Wren: nineteen levels, from a headphone check to a graduation course. It is on the title screen, above Singleplayer.
- Every level is a small room of real Minecraft blocks in its own private save. Wren reacts to what you do, gives hints when you are stuck, keeps it short on levels you have done, and ends a level as soon as you reach its goal.
- A Levels menu to jump to any level, and a menu to repeat the last instruction or restart the level. **K** marks anything that confused you.
- Explains what SONAR is and what a ping does, in plain words, before anything else.

### Sound and echoes
- The echo for a way you cannot see (round a corner) is muffled and comes from the corner you would turn at, not through the wall.
- Entrances are found up to 45 blocks away. A roof on posts (a gazebo) is no longer taken for a building, so it no longer clicks.
- The ceiling tone now plays only when you jump and hit your head.
- Fences and ladders ping; the ledge tone while sneaking is a little louder.
- Ways out round a corner, and in the next room through a door, click too (muffled).
- Waypoints guide you round walls with a hum and three rising notes.

## Earlier changes (also in this version)

### Finding your friends
- The player jingle now leads you along the actual walking route, from a spot up to 7 blocks ahead that you can always see: it slides round corners instead of jumping, and never sounds through a wall.
- Louder and quicker the closer you are, fading smoothly to silence at the edge of hearing. A winding route sounds further away than a direct one.
- Look toward the jingle for 2 seconds and it says who it is and how far: "Alex, 40 blocks".
- The route keeps to the middle of stairs, corridors and paths, away from open edges, walls and farms, and climbs ladders that start a block up.
- Long routes no longer cause lag.
- A tiny copy of the player floats ahead of you along the route (for sighted friends; Advanced settings, "Show guide icons").

### Paths
- The path bell follows the middle of the path, comes in closer on curves and moves further ahead when you run, so you never catch up with it.
- A path that changes material (a dirt path onto a bridge) is still one path.
- A small bell icon floats ahead on the path for sighted friends.

### Echoes
- Echoes wait until the sonar has finished, so they are never covered.
- No more echoes from tree groves. A house with trees around it still echoes.
- One echo per way out: a double door, or a door that is also an opening, is one click.
- Doorways round a corner sound quieter than ones you can see.
- A cliff next to a house roof no longer clicks like a cave.

### Sound
- Every sound fades the same way over its own range, so "this loud means this close" means the same for all of them.
- In front is loudest, to the side about half, behind about a quarter, for every sound.
- Guide sounds (the jingle, the path bell) never play on top of a sonar ping or an echo click.
- "Wall ahead" now means exactly "a block you can't step onto". Steps and stairs never count.
- New in Settings: "Hear what each sound means" names and plays every Sonar sound in turn.

### Reliability
- If one feature ever hits a problem, it rests for a few seconds instead of stopping the rest of the mod or crashing the game.
- Changing world or dimension starts every guide fresh.
- Older settings files are upgraded automatically.
