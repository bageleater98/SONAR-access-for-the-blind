package com.sonar;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Rebind the Sonar keys: click a button, then press the new key (Esc unbinds). */
public class KeysScreen extends Screen {
	private final Screen parent;
	private KeyMapping selected;
	private final java.util.Map<KeyMapping, Button> buttons = new java.util.LinkedHashMap<>();

	public KeysScreen(Screen parent) {
		super(Component.literal("Sonar Key Bindings"));
		this.parent = parent;
	}

	private Component label(KeyMapping k, String name) {
		return Component.literal(name + ": ").append(k == selected ? Component.literal("> press a key <") : k.getTranslatedKeyMessage());
	}

	@Override
	protected void init() {
		buttons.clear();
		int tw = this.font.width(this.title);
		this.addRenderableWidget(new StringWidget(this.width / 2 - tw / 2, 20, tw, 9, this.title, this.font));
		Object[][] rows = {
			{SonarClient.scanKey, "Scan (hold = auto)"}, {SonarClient.frontKey, "Front scan"}, {SonarClient.summaryKey, "Spoken summary"},
			{SonarClient.identifyKey, "Identify"}, {SonarClient.echoKey, "Echo on/off"}, {SonarClient.ledgeKey, "Ledge warn on/off"}};
		for (int i = 0; i < rows.length; i++) {
			KeyMapping k = (KeyMapping) rows[i][0];
			String name = (String) rows[i][1];
			Button b = Button.builder(label(k, name), btn -> { selected = k; refresh(); })
				.bounds(this.width / 2 - 154 + (i % 2) * 158, 40 + (i / 2) * 24, 150, 20).build();
			buttons.put(k, b);
			b.setMessage(label(k, name));
			this.addRenderableWidget(b);
			names.put(k, name);
		}
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
			.bounds(this.width / 2 - 102, 40 + 3 * 24 + 8, 204, 20).build());
	}

	private final java.util.Map<KeyMapping, String> names = new java.util.HashMap<>();

	private void refresh() {
		KeyMapping.resetMapping();
		buttons.forEach((k, b) -> b.setMessage(label(k, names.get(k))));
	}

	private void bind(InputConstants.Key key) {
		selected.setKey(key);
		selected = null;
		this.minecraft.options.save();
		refresh();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (selected != null) {
			bind(event.isEscape() ? InputConstants.UNKNOWN : InputConstants.getKey(event));
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (selected != null) {
			bind(InputConstants.Type.MOUSE.getOrCreate(event.button()));
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}
}
