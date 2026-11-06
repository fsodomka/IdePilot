package com.fsodomka.idepilot;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.util.SystemInfo;

import javax.swing.JList;
import javax.swing.JTree;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.Locale;
import java.util.Map;

/** Clicks on snapshot refs, as mouse events dispatched to the component (or the row's cell). */
final class UiClick {
	private UiClick() {
	}

	/** AWT button for left, middle or right (default left). */
	static int button(String button) {
		return switch (button == null ? "left" : button.toLowerCase(Locale.ROOT)) {
			case "left" -> MouseEvent.BUTTON1;
			case "middle" -> MouseEvent.BUTTON2;
			case "right" -> MouseEvent.BUTTON3;
			default -> throw new IllegalArgumentException("Unknown button " + button + "; use left, middle or right");
		};
	}

	/** Modifier masks for words like "ctrl shift" or "ctrl+alt". */
	static int modifiers(String modifiers) {
		int mask = 0;
		if (modifiers == null || modifiers.isBlank()) {
			return mask;
		}
		for (String word : modifiers.trim().toLowerCase(Locale.ROOT).split("[\\s+]+")) {
			mask |= switch (word) {
				case "ctrl", "control" -> InputEvent.CTRL_DOWN_MASK;
				case "shift" -> InputEvent.SHIFT_DOWN_MASK;
				case "alt" -> InputEvent.ALT_DOWN_MASK;
				case "meta", "cmd" -> InputEvent.META_DOWN_MASK;
				default -> throw new IllegalArgumentException("Unknown modifier " + word + "; use ctrl, shift, alt or meta");
			};
		}
		return mask;
	}

	@SuppressWarnings("MagicConstant") // mask combines InputEvent *_DOWN_MASK flags, from modifiers() and getMaskForButton
	static String click(String ref, String button, int clickCount, String modifiers) {
		int awtButton = button(button);
		int mask = modifiers(modifiers);
		if (clickCount < 1 || clickCount > 3) {
			throw new IllegalArgumentException("clickCount must be 1 to 3");
		}
		// the platform's popup trigger: the press on macOS and Linux, the release on Windows
		boolean popupOnPress = awtButton == MouseEvent.BUTTON3 && !SystemInfo.isWindows;
		boolean popupOnRelease = awtButton == MouseEvent.BUTTON3 && SystemInfo.isWindows;
		Map.Entry<Component, UiSnapshot.Target> resolved = UiSnapshot.resolveRow(ref);
		Component component = resolved.getKey();
		UiSnapshot.Target target = resolved.getValue();
		// later, not and-wait: the click may open a modal dialog, which would block this request
		ApplicationManager.getApplication().invokeLater(() -> {
			if (!component.isShowing()) {
				return;
			}
			Rectangle cell = target.row() < 0 ? null
					: component instanceof JList<?> list ? list.getCellBounds(target.row(), target.row())
					: component instanceof JTree tree ? tree.getRowBounds(target.row()) : null;
			if (target.row() >= 0 && cell == null) {
				return;
			}
			Point p = cell == null ? new Point(component.getWidth() / 2, component.getHeight() / 2)
					: new Point((int) cell.getCenterX(), (int) cell.getCenterY());
			long now = System.currentTimeMillis();
			component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_ENTERED, now, mask, p.x, p.y, 0, false, MouseEvent.NOBUTTON));
			for (int n = 1; n <= clickCount; n++) {
				component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_PRESSED, now, mask | InputEvent.getMaskForButton(awtButton),
						p.x, p.y, n, popupOnPress, awtButton));
				component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_RELEASED, now, mask, p.x, p.y, n, popupOnRelease, awtButton));
				component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_CLICKED, now, mask, p.x, p.y, n, false, awtButton));
			}
		}, ModalityState.stateForComponent(component)); // its dialog's modality: write-safe, yet runs while that dialog is open
		return "Clicked " + ref;
	}
}
