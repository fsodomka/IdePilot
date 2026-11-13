package com.fsodomka.idepilot;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.wm.IdeFocusManager;

import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.components.JBList;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.KeyStroke;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Keyboard input posted to the event queue, so the IDE's keymap sees it like real typing. */
final class UiInput {
	private UiInput() {
	}

	/** The modifier keys pressed by hold and not yet released, as InputEvent *_DOWN_MASK flags. */
	private static volatile int held;

	/** The modifiers held down: later keys and clicks carry them. */
	static int held() {
		return held;
	}

	private static final int[][] MODIFIER_KEYS = {{InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_CONTROL},
			{InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SHIFT}, {InputEvent.ALT_DOWN_MASK, KeyEvent.VK_ALT},
			{InputEvent.META_DOWN_MASK, KeyEvent.VK_META}};

	/**
	 * Presses {@code hold}'s modifier keys and keeps them down, then types {@code text} and presses {@code key}, then
	 * releases {@code release}'s modifiers; keys and clicks in between carry the held ones. Synchronized, so that
	 * concurrent calls do not lose each other's hold or release.
	 */
	static synchronized String type(String ref, String text, String key, boolean clear, String hold, String release) {
		if (text == null && key == null && !clear && hold == null && release == null) {
			throw new IllegalArgumentException("Give text, key, clear, hold, release or a combination");
		}
		// typed alone, a control character does nothing in a text field; pressed, Enter and Tab act (submit, move the focus)
		if (text != null && text.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("text cannot hold newlines, tabs or other control characters: "
					+ "type each line in its own call and press Enter or Tab with key");
		}
		int before = held;
		int down = UiClick.modifiers(hold) & ~before;
		int up = release == null ? 0 : UiClick.modifiers(release);
		if ((up & ~(before | down)) != 0) {
			throw new IllegalArgumentException("Not held: " + release + (before == 0 ? "; nothing is held" : ""));
		}
		Map.Entry<Component, UiRefs.Target> resolved = ref == null ? null : UiRefs.resolveRow(ref);
		Component target = resolved == null ? null : resolved.getKey();
		// a Compose node: its panel takes the focus, then the node within it, through its accessible component
		Object item = resolved == null || resolved.getValue().item() == null ? null : resolved.getValue().item().get();
		KeyStroke stroke = key == null ? null : keyStroke(key);
		if (target instanceof JComboBox<?> combo && text != null && !Edt.compute(combo::isEditable)) {
			if (key != null || clear || hold != null || release != null) {
				throw new IllegalArgumentException("A combo box ref takes only text, the item to select");
			}
			return Edt.compute(() -> select(combo, text)) + " in " + ref;
		}
		// the modality of the target's dialog (or the focused one's): write-safe, yet runs while that dialog is open
		Component context = target != null ? target : KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		// select all and delete in a tree or list would select its nodes, and Backspace may be bound to Delete there
		if (clear && !Edt.compute(() -> textInput(context))) {
			throw new IllegalArgumentException("clear works only in a text field, an editor or Compose UI; "
					+ (context == null ? "nothing has the focus" : "the focus is in " + context.getClass().getSimpleName()));
		}
		// now, so that a click right after this call carries the held modifiers
		held = (before | down) & ~up;
		ModalityState modality = Edt.modality(context);
		CompletableFuture<Void> dispatched = new CompletableFuture<>();
		ApplicationManager.getApplication().invokeLater(() -> {
			if (target != null) {
				IdeFocusManager.getGlobalInstance().requestFocus(target, true);
			}
			if (item instanceof Accessible node && node.getAccessibleContext() != null) {
				AccessibleComponent shown = node.getAccessibleContext().getAccessibleComponent();
				if (shown != null) {
					shown.requestFocus();
				}
			}
			// after the focus request has been processed, so the events go to the new focus owner
			ApplicationManager.getApplication().invokeLater(() -> {
				post(text, stroke, clear, before, down, up);
				// queued behind the posted events: runs once they are dispatched
				EventQueue.invokeLater(() -> dispatched.complete(null));
			}, modality);
		}, modality);
		// return only once the keys are dispatched, so that a click or key sent next comes after them; not forever,
		// as a key may open a dialog whose modality holds back the second step
		try {
			dispatched.get(5, TimeUnit.SECONDS);
		} catch (TimeoutException ignored) {
			// the keys go in when the dialog allows
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			throw new IllegalStateException(e.getCause());
		}
		List<String> done = new ArrayList<>();
		if (down != 0) {
			done.add("held " + hold);
		}
		if (clear || text != null) {
			done.add((clear ? "cleared, typed" : "typed") + (text == null ? "" : " " + text.length() + " characters"));
		}
		if (stroke != null) {
			done.add("pressed " + key);
		}
		if (up != 0) {
			done.add("released " + release);
		}
		String result = String.join(", ", done);
		return (result.isEmpty() ? "Nothing to do" : Character.toUpperCase(result.charAt(0)) + result.substring(1))
				+ (ref == null ? "" : " in " + ref) + heldNote();
	}

	/** "; holding ctrl until released" while hold keeps modifiers down, else empty: results of keys and clicks carry it. */
	static String heldNote() {
		int mask = held;
		return mask == 0 ? "" : "; holding " + names(mask) + " until released";
	}

	/** "ctrl shift" for a modifier mask. */
	private static String names(int mask) {
		List<String> names = new ArrayList<>();
		String[] words = {"ctrl", "shift", "alt", "meta"};
		for (int i = 0; i < MODIFIER_KEYS.length; i++) {
			if ((mask & MODIFIER_KEYS[i][0]) != 0) {
				names.add(words[i]);
			}
		}
		return String.join(" ", names);
	}

	/** Whether text typed into the component edits text: a text component (editors are) or Compose UI, whose fields are not Swing. */
	private static boolean textInput(Component component) {
		for (Component c = component; c != null; c = c.getParent()) {
			if (UiSnapshot.isCompose(c)) {
				return true;
			}
		}
		return component instanceof JTextComponent;
	}

	/** Selects the item whose shown text (renderer label, else toString) equals text, ignoring case if no exact match. */
	private static String select(JComboBox<?> combo, String text) {
		List<String> items = new ArrayList<>();
		for (int i = 0; i < combo.getItemCount(); i++) {
			items.add(itemText(combo, i));
		}
		int index = items.indexOf(text);
		for (int i = 0; index < 0 && i < items.size(); i++) {
			if (items.get(i).trim().equalsIgnoreCase(text.trim())) {
				index = i;
			}
		}
		if (index < 0) {
			throw new IllegalArgumentException("No item " + text + "; items: "
					+ String.join(", ", items.subList(0, Math.min(items.size(), 50))) + (items.size() > 50 ? ", ..." : ""));
		}
		combo.setSelectedIndex(index); // fires the combo's listeners, as picking from its popup does
		return "Selected " + items.get(index);
	}

	@SuppressWarnings({"unchecked", "rawtypes"}) // the renderer's item type is the combo's
	private static String itemText(JComboBox combo, int index) {
		Object item = combo.getItemAt(index);
		// the combo's own popup list, as the renderer expects; a bare list only if the UI has none
		JList list = combo.getUI().getAccessibleChild(combo, 0) instanceof ComboPopup popup ? popup.getList() : new JBList<>();
		String text = shownText(combo.getRenderer().getListCellRendererComponent(list, item, index, false, false));
		return text != null ? text : String.valueOf(item);
	}

	/** The first label or colored text in the renderer component (UI DSL renderers nest it in panels), or null. */
	private static String shownText(Component shown) {
		String text = shown instanceof JLabel label ? label.getText()
				: shown instanceof SimpleColoredComponent colored ? colored.getCharSequence(false).toString() : null;
		if (text != null && !text.isBlank()) {
			return text;
		}
		if (shown instanceof Container container) {
			for (Component child : container.getComponents()) {
				String found = shownText(child);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/**
	 * Posts to the focus owner: the key presses of the {@code down} modifiers, then clear, text and stroke with the
	 * {@code before} and {@code down} modifiers held, then the key releases of the {@code up} modifiers.
	 */
	@SuppressWarnings("MagicConstant") // masks are InputEvent *_DOWN_MASK flags
	private static void post(String text, KeyStroke stroke, boolean clear, int before, int down, int up) {
		Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		if (focus == null) {
			return;
		}
		var queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
		long now = System.currentTimeMillis();
		int mask = before;
		for (int[] modifier : MODIFIER_KEYS) {
			if ((down & modifier[0]) != 0) {
				mask |= modifier[0];
				queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_PRESSED, now, mask, modifier[1], KeyEvent.CHAR_UNDEFINED));
			}
		}
		if (clear) { // select all (ctrl A, or cmd A on macOS) and delete, through the keymap like real keys
			press(queue, focus, now, KeyStroke.getKeyStroke(KeyEvent.VK_A, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), 0);
			press(queue, focus, now, KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), 0);
		}
		if (text != null) {
			// pressed, typed and released, as real typing: the reworked terminal takes characters from key presses
			for (char c : text.toCharArray()) {
				int code = KeyEvent.getExtendedKeyCodeForChar(c); // no control characters: type rejects them
				queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_PRESSED, now, 0, code, c));
				queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, c));
				queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_RELEASED, now, 0, code, c));
			}
		}
		if (stroke != null) {
			press(queue, focus, now, stroke, mask);
		}
		for (int[] modifier : MODIFIER_KEYS) {
			if ((up & modifier[0]) != 0) {
				mask &= ~modifier[0];
				queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_RELEASED, now, mask, modifier[1], KeyEvent.CHAR_UNDEFINED));
			}
		}
	}

	/** Pressed, typed (for a key with a character and no modifiers) and released events of a key stroke, with {@code held} modifiers added. */
	@SuppressWarnings("MagicConstant") // KeyStroke modifiers are InputEvent masks
	private static void press(EventQueue queue, Component focus, long now, KeyStroke stroke, int held) {
		int code = stroke.getKeyCode();
		char c = code == KeyEvent.VK_ENTER ? '\n' : code == KeyEvent.VK_TAB ? '\t' : code == KeyEvent.VK_ESCAPE ? 27
				: code == KeyEvent.VK_BACK_SPACE ? '\b' : KeyEvent.CHAR_UNDEFINED;
		int modifiers = stroke.getModifiers() | held;
		queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_PRESSED, now, modifiers, code, c));
		if (c != KeyEvent.CHAR_UNDEFINED && modifiers == 0) {
			queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, c));
		}
		queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_RELEASED, now, modifiers, code, c));
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
