package com.sonar;

/**
 * The feel of the mod in one place: every number tuned by ear in testing, with what it does. Change a number here and every feature that uses it follows.
 * (Settings the player changes live in SonarConfig; these are the fixed design values behind them.)
 */
final class Tuning {
	private Tuning() {}

	// ---- echo timing (ticks; 20 ticks = 1 second) ----
	/** Longest wait after a sonar ping before its echo starts, so the echo never lands on the ping's own sounds (still ringing for about half a second). */
	static final int ECHO_DELAY_MAX = 11;
	/** Ticks between two echoes in one sequence: closer together they blur into a crowd. */
	static final int ECHO_GAP = 3;
	/** An echo whose way you can NOT see (round a corner, out of sight behind a house): this loud (was 0.65), and heavily muffled (sounds/h: 450 Hz), so it really
	 *  sounds like it is round the corner (round 16). One rule for every echo that is out of sight (Echo.queueDir). */
	static final float HIDDEN_ECHO = 0.72F;
	/** How far (blocks, each way) the ways into buildings are looked for: as far as the echo itself listens (SonarConfig.range). */
	static final int ENTRANCE_RANGE = 45;
	/** An echo out of sight fades with distance this much slower than one in sight (it is already muffled and quieter; at 20 blocks it was lost). */
	static final double HIDDEN_FADE = 0.5;
	/** Cave passages round a corner (CaveEcho.Walk): out of view this many steps and still going = a passage (a pillar's shadow or a nook is less); the echo sounds
	 *  from CORNER_PEEK steps round the corner; the walk is at most CORNER_MAX_CELLS floor spots, CORNER_TICK_NS a tick, and the echo finishes it within
	 *  CORNER_FINISH_NS; its finds are used while you are within CORNER_MOVED blocks of where it started; at most CORNERS_MAX of them echo. */
	static final int CORNER_DEPTH = 10, CORNER_PEEK = 4, CORNER_MAX_CELLS = 40000, CORNERS_MAX = 2;
	static final long CORNER_TICK_NS = 1_500_000L, CORNER_FINISH_NS = 3_000_000L;
	static final double CORNER_MOVED = 4.0;
	/** A hole down into a cave echoes from this far (blocks along the ground; was 10: a pit 10 blocks ahead went silent as you stepped back, round 16). */
	/** Hole edges: where the floor walk can not step down (more than 3), DROP_DEEP+ below the lip, with a cave at the bottom (asked at every edge, in the walk). */
	static final int DROP_DEEP = 4;
	static final int CAVE_DROPS_MAX = 3; // separate holes that echo at once, nearest first (round 16: one 'best' hole silenced the one in front of you)
	static final int CAVE_DROP_RANGE = 32; // (24 for a moment, then 32: as far as the ways round a corner reach in buildings)
	/** Ticks the last echo of a sequence takes to ring out; the next auto ping waits for it. */
	static final int ECHO_RING_OUT = 14;

	// ---- holding steady (so a detection that flickers at its threshold does not flip a sound on and off) ----
	/** Ticks the path still counts as "you are on it" after it was last found (the edge warning uses this): 3 s. */
	static final int GRACE_PATH_KEEP = 60;
	/** Ticks the bell keeps its last bearing through a gap in finding the path (a stair, a jump). */
	static final int GRACE_BELL_GAP = 8;
	/** Path checks off the path before stepping onto one counts as "newly on a path" (about a second). */
	static final int GRACE_OFF_PATH_CHECKS = 12;
	/** How long (ns) you count as still inside the room you were in, while you are within 4 blocks of where it was found: 3 s. */
	static final long GRACE_ROOM_NS = 3_000_000_000L;
	/** Wall rays (of 16) under the roof needed to be a room: once already inside, with the whole roof found, and with part of it. */
	static final int ROOM_WALLS_INSIDE = 8, ROOM_WALLS_FULL_ROOF = 10, ROOM_WALLS_PART_ROOF = 11;

	// ---- ledge warning (how it SOUNDS; where edges are is worked out in Ledges) ----
	/** A drop this deep or deeper (or lava) is a real fall: the early, steady warning. */
	static final int LEDGE_BIG_DROP = 5;
	/** A real fall's tone starts at this share of full loudness at the far end of the warning range, rising steadily to full. */
	static final float LEDGE_BIG_FLOOR = 0.4F;
	/** A smaller drop starts almost silent (this share) and rises on a curve (this power), so it only gets clear close to the edge. */
	static final float LEDGE_SMALL_FLOOR = 0.08F, LEDGE_SMALL_CURVE = 1.6F;
	/** Loudness of a 4-block drop, and of smaller ones, compared with a real fall. */
	static final float LEDGE_LOUD_4 = 0.45F, LEDGE_LOUD_SMALL = 0.08F;
	/** Pitch of a 4-block drop, and of smaller ones (a real fall is 1). */
	static final float LEDGE_PITCH_4 = 0.68F, LEDGE_PITCH_SMALL = 0.5F;
	/** The ledge tone never sounds from closer than this (blocks): any closer and its direction can not be heard. */
	static final double LEDGE_CLOSEST = 2.0;
	/** Sonar sounds are never placed closer to your head than this (as LEDGE_CLOSEST): closer, the game can not tell where they come from. */
	static final double CUE_CLOSEST = 2.0;
	/** The diagonal dip (SONAR_DIAGONAL) is off within DIAGONAL_NEAR blocks and full from DIAGONAL_FULL. */
	static final double DIAGONAL_NEAR = 1.5, DIAGONAL_FULL = 3.0;
	/** A slope warns as a ledge only when it is steeper than you can walk down step by step: this many blocks lost within 3 steps, or one step of LEDGE_FALL_STEP+. */
	static final int LEDGE_SLOPE_DROP = 6, LEDGE_FALL_STEP = 3;
	/** The ledge tone's overall level. */
	static final float LEDGE_LEVEL = 0.72F;
	/** The edge tone while you sneak and move (sneaking you can not fall, so it is quieter, never gone). */
	static final float LEDGE_SNEAK = 0.55F;
	/**
	 * Bridges up to LEDGE_BRIDGE_W wide (drop to drop, measured from your body to each actual drop line). Centred: silent. Drifting toward one side: that
	 * side's tone fades in once its drop is within LEDGE_BRIDGE_START x width/3, full at LEDGE_BRIDGE_FULL x width/3 (a 3-wide bridge: 1.25 and 0.5, as it
	 * always was). A 1-wide bridge (up to LEDGE_ONE_WIDE) also keeps a faint tone on both sides while centred (LEDGE_ONE_WIDE_FAINT of full). The drop at a
	 * bridge's far end only sounds within LEDGE_NARROW_AHEAD. Wider paths keep the corridor rule.
	 */
	static final double LEDGE_BRIDGE_W = 3.5, LEDGE_BRIDGE_START = 1.25, LEDGE_BRIDGE_FULL = 0.5, LEDGE_ONE_WIDE = 1.5, LEDGE_NARROW_AHEAD = 2.5;
	static final float LEDGE_ONE_WIDE_FAINT = 0.12F;

	// ---- echo ----
	/** Echoes closer together than this in direction (and at about the same distance) are one way. */
	static final double ECHO_SAME_WAY_DEG = 15;
	/** How long (ns) an echo's findings are reused while you stay on the same block: 3 s (doors opened, blocks placed are picked up after that). */
	static final long ECHO_REUSE_NS = 3_000_000_000L;

	// ---- speech ----
	/** Ticks you must face a new direction before it is said (0.5 s). (How loud: the "North/east/south/west" volume slider.) */
	static final int FACING_SETTLE = 10;

	// ---- every sound ----
	/** How loud a sound straight behind you is, compared with in front (to the side it is halfway between). */
	static final double BEHIND_LEVEL = 0.4;
	/** Sonar pings behind you (the sweep's last beat): this loud (0.35), muffled and lower. */
	static final float BEHIND_SONAR = 0.35F;
	/** Sonar pings at a DIAGONAL (45 degrees: front-left, front-right, back-left, back-right) are this loud; straight ahead, to each side and behind stay full, with a
	 *  smooth curve between (Cues.diagonal). Stereo can not tell front-left from back-left, so a wall on your left with blocks front-left and back-left sounded like
	 *  three equal pings all on the left (round 16); now the wall is the loud one and the diagonals soft. User's pick: 40%. */
	static final double SONAR_DIAGONAL = 0.4;
	/** Sonar groups: at most this many blocks followed from one heard block (it stops sooner, once it has found every heard block of its sound). */
	static final int GROUP_FILL_MAX = 6000;
	/** Sonar group spread: a block this much nearer (radians of direction per block) is preferred over a further one in much the same direction (12 blocks: about 14 degrees). */
	static final double SPREAD_NEAR = 0.02;
	/** The guides (jingle, waypoint, path bell): how far down their sound dips straight to the side (0.6: to 0.28 of full; behind stays BEHIND_LEVEL). */
	static final double GUIDE_SIDE_DIP = 0.6;
	/** Ticks between two "centre and square up" moves after bumping a wall (1.5 s). */
	static final int CENTER_COOLDOWN = 30;
	/** Even loudness: a level per sound (by its name, e.g. "block.note_block.hat"), to even out sounds the game recorded louder or softer than the rest. Not listed = 1.
	 *  Starts empty: levels are set by ear in testing. */
	static final java.util.Map<String, Float> SOUND_LEVELS = java.util.Map.of();

	/** How every sound fades over its own range: (1 - fraction of the range)^this. 1.5: about a third left halfway out, silent at the edge. */
	static final double FADE_CURVE = 1.5;

	// ---- player route: what the walking route avoids (costs per step; a plain step on the level costs 1) ----
	/** A step on built floor (a path, a house floor) costs this much: the route prefers made ways. */
	static final double ROUTE_BUILT_FLOOR = 0.75;
	/** Extra cost of a step right at an edge (clearance 1: a wall, rail, drop, or the open side of a staircase beside you), and one block in from it (clearance 2).
	 *  Further in costs nothing extra. So the route runs down the middle of whatever it is in, and keeps a margin from walls and drops in the open (PlayerChime.clearance). */
	static final double ROUTE_EDGE_1 = 1.5, ROUTE_EDGE_2 = 0.4;
	/** Extra cost of a step on farmland: practically never across crops. */
	static final double ROUTE_FARM = 3.0;
	/** Extra cost for a 45-degree turn, and for a right angle or more: a few long straight stretches instead of a zigzag. */
	static final double ROUTE_TURN_45 = 0.15, ROUTE_TURN_90 = 0.3;
	/** Cost per ladder or vine rung (climbing blind is hard: only used when there is no walking way at all). */
	static final double ROUTE_RUNG = 1.5;
	/** Extra cost per step walked under rock (a cave): over a hill in the open beats a dark, twisting cave under it, unless the cave is much shorter. Each block
	 *  under rock counts as 2.5 blocks in the open (0.8 was too mild: a tunnel and the way over a low ridge came out nearly equal, and the search took the tunnel). */
	static final double ROUTE_UNDERGROUND = 1.5;
	/** Waypoint routes only: a block swum costs this many walked ones. Walking wins unless swimming is the easier way: across a lake rather than far round it, or
	 *  rather than a narrow ledge (whose edge costs add up). Swimming is not hard (hold forward and jump), so it is a fair choice, not a last resort. */
	static final double ROUTE_SWIM = 2.0;
	/** Waypoint routes only: a block of made path (or other built floor) out in the open costs this much, so a path nearby that goes your way is taken. */
	static final double ROUTE_WP_PATH = 0.5;
	/** The waypoint scout searches this far either side of the straight line (the way round a lake can lie well off it). */
	static final int SCOUT_MARGIN = 90;
	/** Spots a route search may look at per tick, and in total. */
	static final int ROUTE_SLICE = 1500, ROUTE_MAX = 60000;
	/** Most time a route search may take in one tick (2 ms: an eighth of a frame at 60 fps). */
	static final long ROUTE_TICK_NANOS = 2_000_000L;
	/** The route searches' own share of every tick (all of them together), whatever the rest of the tick used: 1.5 ms. */
	static final long SEARCH_SHARE_NS = 1_500_000L;
	/** Most time one step of the AUTO sonar scan may take (a key press scans at full speed); it may then take up to twice its usual ticks. */
	static final long SCAN_TICK_NS = 2_500_000L;
	/** The whole mod's time budget per tick (4 ms: a quarter of a frame at 60 fps), and the least a route search always gets (0.3 ms). */
	static final long TICK_BUDGET_NS = 4_000_000L, SEARCH_FLOOR_NS = 300_000L;

	// ---- player jingle ----
	/** Walking distance (blocks) within which the jingle is at its close level and quickest beat. */
	static final double JINGLE_NEAR = 10;
	/** Walking distance at which it is faintest and slowest. */
	static final double JINGLE_FAR = 256; // (walking routes wind: someone 128 blocks away in a straight line can be 250 blocks of walking)
	/** Volume close by (it falls to silence at JINGLE_FAR). */
	static final double JINGLE_NEAR_VOL = 0.55;
	/** Ticks between beats: close, and far off (one a second). */
	static final int JINGLE_BEAT_NEAR = 5, JINGLE_BEAT_FAR = 20;

	/** Saying the player's name and distance when you face the jingle: OFF for now (it spoke far too often in testing; to be reworked). */
	static final boolean SAY_NAME = false;
	/** Ticks of facing the jingle before it says the player's name and distance (2 s). */
	static final int NAME_AFTER_TICKS = 40;

	// ---- path bell ----
	/** Running: blocks added ahead per block-per-second over walking pace (4.5), at most BELL_LEAD_MAX. */
	/** How far ahead along the path's centre line the bell leads at walking pace (less on curves, where it comes in to keep over the middle). */
	static final double BELL_LEAD = 8;
	/** The bell's level (and how far a continuous tone would dip while a ping or echo plays: unused since the bell blips). */
	/** Waypoints: the quietest the guide plays (at the start, or further off than that), arrival distance (blocks), how far ahead the route search reaches
	 *  toward a far waypoint (blocks), the hum's and the chime call's share of the loudness, and the call's bar (ticks: the three rising notes, then a rest). */
	static final double WP_MIN_VOL = 0.15, WP_ARRIVE = 2.0, WP_RENEW = 24;
	static final int WP_REFRESH = 200; // (10 s)
	/** Waypoint plan: within WP_NEAR blocks across, its whole route (as the jingle); further, straight at it while the next WP_LOOK blocks walk clear, else a detour toward the spot WP_DETOUR blocks on. */
	static final double WP_NEAR = 48, WP_LOOK = 16, WP_DETOUR = 32, WP_MIN_SOUND = 4, WP_DETOUR_DONE = 2.5;
	static final float WP_HUM_LEVEL = 0.55f, WP_CHIME_LEVEL = 0.6f;
	static final int WP_CALL_BAR = 24;


	/** A way into a building outside must open onto at least this many walkable floor cells under the roof (a small hut has 9; a 1-deep nook has 1 or 2). */
	static final int ENTRANCE_MIN_SPACE = 5;

	static final float BELL_LEVEL = 0.064f /* chime_soft.ogg is normalised to ~0.85 peak; 0.064 makes 100% about 2.8x the old bell, 200% about 5.6x */, BELL_DUCK = 0.35f;
	/** Ticks between two bell chimes (5: four a second). */
	static final int BELL_BLIP_TICKS = 4;
	/** The bell leads at least this far along the path, even at a turn right in front of you. */
	static final double BELL_MIN_LEAD = 3.5;
	static final double BELL_LEAD_PER_SPEED = 3.0, BELL_LEAD_MAX = 5;

	// ---- guide icons ----
	/** How far ahead of you the icons float (blocks), and how far above the middle of your view (degrees; the mini player a little lower than the bell). */
	static final double ICON_AHEAD = 7.0, ICON_BELL_LIFT = 9, ICON_PLAYER_LIFT = 6; // (degrees above the middle of your view: the icons stay put on your screen as you look up and down)
	/** Share of the way an icon glides to its spot each tick. */
	static final double ICON_GLIDE = 0.33;
	/** Share of the way a guide SOUND's position glides to its new spot each tick. */
	static final double SOUND_GLIDE = 0.25;

	// ---- the tutorial narrator
	/** Sonar's own sounds (not the ledge warning) play at this share while the narrator speaks, so the voice is clear and the sounds still tell where things are. */
	static final float VOICE_DUCK = 0.5f;
}
