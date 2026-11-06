package com.fsodomka.idepilot;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.wm.IdeFocusManager;

import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.Locale;

/** Keyboard input posted to the event queue, so the IDE's keymap sees it like real typing. */
final class UiInput {
	private UiInput() {
	}

	static String type(String ref, String text, String key) {
		if (text == null && key == null) {
			throw new IllegalArgumentException("Give text, key or both");
		}
		Component target = ref == null ? null : UiSnapshot.component(ref);
		KeyStroke stroke = key == null ? null : keyStroke(key);
		// the modality of the target's dialog (or the focused one's): write-safe, yet runs while that dialog is open
		Component context = target != null ? target : KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		ModalityState modality = context == null ? ModalityState.nonModal() : ModalityState.stateForComponent(context);
		ApplicationManager.getApplication().invokeLater(() -> {
			if (target != null) {
				IdeFocusManager.getGlobalInstance().requestFocus(target, true);
			}
			// after the focus request has been processed, so the events go to the new focus owner
			ApplicationManager.getApplication().invokeLater(() -> post(text, stroke), modality);
		}, modality);
		return "Typed" + (text == null ? "" : " " + text.length() + " characters") + (stroke == null ? "" : " and pressed " + key)
				+ (ref == null ? "" : " into " + ref);
	}

	private static void post(String text, KeyStroke stroke) {
		Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		if (focus == null) {
			return;
		}
		var queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
		long now = System.currentTimeMillis();
		if (text != null) {
			for (char c : text.toCharArray()) {
				queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, c));
			}
		}
		if (stroke != null) {
			int code = stroke.getKeyCode();
			char c = code == KeyEvent.VK_ENTER ? '\n' : code == KeyEvent.VK_TAB ? '\t' : code == KeyEvent.VK_ESCAPE ? 27
					: code == KeyEvent.VK_BACK_SPACE ? '\b' : KeyEvent.CHAR_UNDEFINED;
			int modifiers = stroke.getModifiers();
			queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_PRESSED, now, modifiers, code, c));
			if (c != KeyEvent.CHAR_UNDEFINED && modifiers == 0) {
				queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, c));
			}
			queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_RELEASED, now, modifiers, code, c));
		}
	}

	/** "ctrl shift A", "ctrl+shift+a", "Enter" or "Back_Space" as a key stroke. */
	static KeyStroke keyStroke(String key) {
		String[] parts = key.trim().split("[\\s+]+");
		StringBuilder spec = new StringBuilder();
		for (int i = 0; i < parts.length - 1; i++) {
			String modifier = parts[i].toLowerCase(Locale.ROOT);
			spec.append(modifier.equals("control") ? "ctrl" : modifier.equals("cmd") ? "meta" : modifier).append(' ');
		}
		spec.append(parts[parts.length - 1].toUpperCase(Locale.ROOT));
		KeyStroke stroke = KeyStroke.getKeyStroke(spec.toString());
		if (stroke == null) {
			throw new IllegalArgumentException("Unknown key " + key + "; use e.g. Enter, Escape, Down, ctrl shift A");
		}
		return stroke;
	}
}
