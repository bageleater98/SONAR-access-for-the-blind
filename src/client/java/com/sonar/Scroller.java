package com.sonar;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * SCROLLING FOR EVERY SCREEN WHOSE BUTTONS DO NOT FIT. When a screen's widgets run past the bottom of the window (a small window, a big GUI scale, the title
 * screen with Sonar's two extra rows), the whole stack scrolls: with the mouse wheel, and by itself to keep the keyboard-focused widget in view (Tab through a
 * menu and it follows). A thin bar on the right shows where you are. Applies to the title screen and every Sonar screen; nothing changes when everything fits.
 */
final class Scroller {
	private Scroller() {}

	private static final int MARGIN = 6;

	private static final class State {
		int token, scroll, max;
		boolean fresh = true;
		Object lastFocus;
		final List<AbstractWidget> widgets = new ArrayList<>();
		final List<Integer> base = new ArrayList<>();
	}

	private static final Map<Screen, State> STATES = new WeakHashMap<>();

	static boolean applies(Screen s) { return s instanceof TitleScreen || s.getClass().getName().startsWith("com.sonar."); }

	static void init() {
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (!applies(screen)) return;
			State old = STATES.get(screen);
			State st = new State();
			st.token = old == null ? 1 : old.token + 1;
			st.scroll = old == null ? 0 : old.scroll; // (a rebuilt screen keeps its place)
			STATES.put(screen, st);
			final int token = st.token;
			ScreenEvents.beforeExtract(screen).register((sc, g, mx, my, dt) -> { if (st.token == token) update(sc, st); });
			ScreenEvents.afterExtract(screen).register((sc, g, mx, my, dt) -> {
				if (st.token != token || st.max <= 0) return;
				int hgt = sc.height, track = hgt - 2 * MARGIN;
				int thumb = Math.max(16, track * track / (track + st.max));
				int y = MARGIN + (int) ((long) (track - thumb) * st.scroll / st.max);
				g.fill(sc.width - 5, MARGIN, sc.width - 2, MARGIN + track, 0x55000000);
				g.fill(sc.width - 5, y, sc.width - 2, y + thumb, 0xCCFFFFFF);
			});
			ScreenMouseEvents.allowMouseScroll(screen).register((sc, mx, my, sx, sy) -> {
				if (st.token != token || st.max <= 0 || sy == 0) return true;
				st.scroll = clamp(st.scroll - (int) Math.round(sy * 20), st.max);
				place(st);
				return false;
			});
		});
	}

	private static int clamp(int v, int max) { return Math.max(0, Math.min(max, v)); }

	private static void update(Screen sc, State st) {
		if (st.fresh) { // the first frame after the screen was built: its widgets are where it put them
			st.fresh = false;
			int bottom = 0;
			for (var ch : sc.children())
				if (ch instanceof AbstractWidget aw && aw.visible) { st.widgets.add(aw); st.base.add(aw.getY()); bottom = Math.max(bottom, aw.getBottom()); }
			st.max = Math.max(0, bottom + MARGIN - sc.height);
			st.scroll = clamp(st.scroll, st.max);
		}
		if (st.max <= 0) return;
		Object f = sc.getFocused();
		if (f != st.lastFocus) { // keyboard focus moved: bring it into view
			st.lastFocus = f;
			if (f instanceof AbstractWidget aw && st.widgets.contains(aw)) {
				int i = st.widgets.indexOf(aw), top = st.base.get(i), bot = top + aw.getHeight();
				if (top - st.scroll < MARGIN) st.scroll = top - MARGIN;
				else if (bot - st.scroll > sc.height - MARGIN) st.scroll = bot - sc.height + MARGIN;
				st.scroll = clamp(st.scroll, st.max);
			}
		}
		place(st);
	}

	private static void place(State st) {
		for (int i = 0; i < st.widgets.size(); i++) st.widgets.get(i).setY(st.base.get(i) - st.scroll);
	}
}
