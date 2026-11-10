package com.sonar;

/**
 * Everything the tutorial's Director needs from the game, in one place: where you are, which keys are down, moving you, a few sounds, the settings. The game
 * plays it through GameHost; the self-test through a scripted fake player (DirectorTests), so lesson order, skip, resume, hints and the wait for a room to load
 * are all tested without the game.
 */
interface Host {
	enum Key { FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK }

	/** Now, in nanoseconds. */
	long now();
	/** The game is paused (a paused singleplayer world loads nothing and moves nothing). */
	boolean paused();

	/** Puts you at room coordinates rx, ry, rz (feet) of room r, facing yaw. */
	void teleport(TutorialRooms.Room r, double rx, double ry, double rz, float yaw);
	/** What still keeps room r from being ready with you at rx, ry, rz (null = ready): built, you in place, chunks loaded and drawn, on the ground. */
	String notLoaded(TutorialRooms.Room r, double rx, double ry, double rz);

	double x();
	double y();
	double z();
	float yaw();
	boolean onGround();
	boolean inWater();
	boolean down(Key k);
	/** The block under your feet (its id path, "dirt_path"), or "" when none. */
	String floorBlock();

	/** A chime on your left (side -1) or right (1), for the headphone check. */
	void chime(int side);
	/** The pass chime (three rising notes). */
	void passChime();
	/** A quiet click (the "confusing" mark). */
	void click();

	/** The settings in use (the tutorial's own copy), and saving them. */
	SonarConfig cfg();
	void saveCfg();

	/** Leave the tutorial (save and quit to the title screen). */
	void leave();
	/** No sonar sound is about to play (the narrator waits for a quiet moment). */
	boolean timelineFree();
	/** A command on the tutorial world's server (summon the sheep...). Only ever the tutorial's own world. */
	void serverCommand(String cmd);
	/** The tutorial world's waypoints are cleared (the waypoints lesson starts from none). */
	void clearWaypoints();
	/** A menu or screen is open. */
	boolean screenOpen();
	/** The Waypoints menu is open. */
	boolean waypointsOpen();
	/** A waypoint guide is running. */
	boolean guiding();
	/** Empties your hands and pockets; with `axe`, then gives you an iron axe that can chop logs (the tree lesson). */
	void inventory(boolean axe);
	/** The block at room coordinates of room r (its id path, "oak_log"; "air" when empty). */
	String blockAt(TutorialRooms.Room r, int rx, int ry, int rz);
	/** Falls hurt (the ledges lesson lets a fall be felt) or not (everywhere else). */
	void fallDamage(boolean on);
	/** The graduation's fanfare. */
	void fanfare();
}
