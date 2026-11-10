# Sonar Tutorial: handover (updated Nov 9 2026)

Read this, then your Sonar memory files (sonar-mod-project, sonar-tutorial-project, feedback-*). The user's original spec is restated in `C:\Users\ammon\New league\Sonar-tutorial-phase0.md`.

## The user's standing instruction
"Finish all the rest of the phases and everything without reporting back. Once you feel you are done, test it as a player would and think like they would as you test. Look for inconsistencies, bugs, potential polishes etc., and apply them with root-cause solutions and solid applications. Keep testing and refining until it's fully done." Later: "keep running until finished with all of it like I said, and test and test and test." So work autonomously to the end. Do not stop to ask; report once it is truly done.

## Rules
- Root causes, not patches. A detector bug found by a room gets fixed in the shared code, with a test.
- No commits or releases until the user says. Never add Claude / Co-Authored-By lines.
- Build: `./gradlew build -q --offline` (it runs the self-test, currently 561 checks, all green). Install: copy `build/libs/sonar-1.4.1.jar` to `%APPDATA%\ModrinthApp\profiles\New instance (1)\mods\`.
- Never `taskkill java.exe`. Stop only the dev client: `Get-CimInstance Win32_Process` where the CommandLine contains `devlaunchinjector` and `SonarMod`.
- Shell heredocs break long Python scripts: write them with the Write tool to the scratchpad and run them with `python -I`.
- Tell the user the dev test window plays by itself and to leave it alone. Last time they pressed Escape and "Leave the tutorial" in it, which ended the run.

## State: Phases 0-4 done, Phase 5 in progress
Everything is installed in the test profile and uncommitted. All of it is under `src/client/java/com/sonar/`:
- **Voice:** `Voice` plays the narrator; `Narrator` is the queue. The script is `assets/sonar/voice/script.json` (265 lines, including the "phrase" lines). Render with `tools/voice/.venv/Scripts/python -I -W ignore tools/voice/render.py`. Now 325 files, 19.7 min, 6.31 MB of 12 MB.
- **Fixed phrases:** `Speech.say` plays a pre-rendered phrase ("north", "Auto scan on", the legend's names...) through `Voice.phrase`, on its own channel. Names and numbers still use the system voice.
- **Pause:** the `SoundPauseMixin` keeps the VOICE channel playing while the game is paused, so menus are narrated.
- **Director:** an instance on a `Host` (`GameHost` in the game, a fake in the tests). Lessons are Steps and Reactions in `Lessons.all()` (18 of them).
  - Steps wait for menus to close (unless marked `inMenu()`). The next lesson waits for menus too, with a nudge (`sys.closemenu`).
  - Reactions only play when they can be heard. A once-line counts as said only when it is said.
  - Resets resume the current step with the short instruction.
  - Stuck: a hint at 12 s, a reset at 40 s. Pushing into a guide barrier says `sys.guide`. The narrator keeps quiet while the legend plays.
  - A lesson's last reaction is chained before its success line.
- **Other parts:**
  - `Pacing` (ahead or struggling) and `LessonLog` (`config/sonar-tutorial/log-DATE.json`, with the confusing marks).
  - `LessonMenu`, and `TutorialMenu` (two columns: lessons, skip intros, waypoints...). The first-launch offer is on the title screen.
  - `Tutorial.Ev` hooks across the mod. `TutorialWorld.command` runs server commands in the tutorial world only. `CREDITS.md` ships in the jar.
- **Tests** (src/selftest): TutorialTests, RoomTests, DirectorTests.

## Phase 5 / testing still to do
1. **Publish the checklist artifact.** The edited page is at `C:\Users\ammon\AppData\Local\Temp\claude\52aa80c2f50465e3\72008faa-b472-4276-b0d4-cf92094f7b68\scratchpad\artifact-files\c394b076-1c65-4eef-8b40-8d8f1cc6f2d0\index.html`.
   - It has a new "Tutorial" section (22 items) at the top of SECTIONS.
   - If that file is gone, re-read the artifact with `path: index.html` and redo the section.
   - Publish to https://claude.ai/artifact/R9mUo9VapCq46RPEKCYDYK: read it first with Artifact read (no path), then publish with `url`. It keeps the `db` capability.
   - Update the items to match the final behaviour, e.g. stuck is now 40 s and starting a waypoint guide closes the menus.
2. **The in-game autoplay playtest.** `AutoPlay.java` is a test-only scripted player, enabled by the environment variable `SONAR_TUTORIAL_AUTOPLAY`. It waits for each instruction, then presses the real keys, walks the route with the movement keys (jump, swim, ladder, doors), and screenshots each lesson.
   - Run: delete `run/config/sonar-tutorial/progress.json` and `run/logs/latest.log`, then `./gradlew build -q --offline -x selfTest`, then in the background `SONAR_TUTORIAL_AUTOSTART=1 SONAR_TUTORIAL_AUTOPLAY=1 ./gradlew runClient --offline`.
   - Watch `run/logs/latest.log` for "AUTOPLAY", "SONAR tutorial ... passed/reset/hint", STUCK, NO ROUTE, "tick failed" and exceptions. Ignore the oshi, 401 and Realms noise.
   - Screenshots land in `run/screenshots/`. View them with Read: check the forced brightness and that the rooms look right. The window may be minimized; then the screenshots fail.
   - The last run only reached L0 because the user clicked Leave in the window. It has never completed: expect AutoPlay bugs (steering, the ladder, doors, swimming, the L0 timing) as well as tutorial bugs. Fix both, rerun, and repeat until every lesson passes without teleport fallbacks and without unexpected resets, hints or exceptions.
3. **Keep testing as a player.** Lines vs geometry, timing, overlapping speech, menus by keyboard (every button speaks on focus), leave/resume, complete → title. Then sandbox restore, the first-launch offer, and the multiplayer button text.
4. **Finish.** Final `./gradlew build -q --offline` (all green), install, update memory (sonar-tutorial-project), then one report to the user: what was done, what was verified, what wasn't.
