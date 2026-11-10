package com.sonar;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

/** Shared layout and widgets for the Sonar settings screens. */
public abstract class SonarScreen extends Screen {
	protected final Screen parent;

	protected SonarScreen(String title, Screen parent) {
		super(Component.literal(title));
		this.parent = parent;
	}

	protected void heading() {
		int tw = this.font.width(this.title);
		this.addRenderableWidget(new StringWidget(this.width / 2 - tw / 2, 16, tw, 9, this.title, this.font));
	}

	/** Left (col 0) or right (col 1) half of the 308-wide content area. */
	protected int colX(int col) {
		return this.width / 2 - 154 + col * 158;
	}

	protected static void save() {
		try { SonarConfig.save(); } catch (Exception ignored) {}
	}

	protected void toggle(int col, int y, String name, BooleanSupplier get, Runnable flip) {
		Supplier<Component> label = () -> Component.literal(name + "  ")
			.append(Component.literal(get.getAsBoolean() ? "ON" : "OFF").withStyle(get.getAsBoolean() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		this.addRenderableWidget(Button.builder(label.get(), b -> { flip.run(); save(); b.setMessage(label.get()); Tutorial.event(Tutorial.Ev.SETTING, get.getAsBoolean() ? 1 : 0, name); })
			.bounds(colX(col), y, 150, 20).build());
	}

	protected void slider(int y, int w, String name, double min, double max, double step, double cur, DoubleFunction<String> fmt, DoubleConsumer set) {
		this.addRenderableWidget(new Slider(this.width / 2 - w / 2, y, w, name, min, max, step, cur, fmt, set));
	}

	/** A slider in one of two columns (col 0 left, 1 right), each 150 wide. */
	protected void sliderCol(int col, int y, String name, double min, double max, double step, double cur, DoubleFunction<String> fmt, DoubleConsumer set) {
		this.addRenderableWidget(new Slider(this.width / 2 + (col == 0 ? -154 : 4), y, 150, name, min, max, step, cur, fmt, set));
	}

	private static class Slider extends AbstractSliderButton {
		private final String name;
		private final double min, max, step;
		private final DoubleFunction<String> fmt;
		private final DoubleConsumer set;

		Slider(int x, int y, int w, String name, double min, double max, double step, double cur, DoubleFunction<String> fmt, DoubleConsumer set) {
			super(x, y, w, 20, Component.empty(), Math.max(0, Math.min(1, (cur - min) / (max - min))));
			this.name = name; this.min = min; this.max = max; this.step = step; this.fmt = fmt; this.set = set;
			updateMessage();
		}

		private double real() {
			return Math.round((min + value * (max - min)) / step) * step;
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(name + ":  ").append(Component.literal(fmt.apply(real())).withStyle(ChatFormatting.AQUA)));
		}

		@Override
		protected void applyValue() {
			set.accept(real());
			save();
			Tutorial.event(Tutorial.Ev.SETTING, real(), name);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
