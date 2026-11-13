package com.fsodomka.idepilot;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.util.SystemInfo;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Clicks on snapshot refs, as mouse events dispatched to the component (or the row's cell). */
final class UiClick {
	private static final long CLICK_WAIT_MILLIS = 1000;

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
		int mask = modifiers(modifiers) | UiInput.held();
		if (clickCount < 1 || clickCount > 3) {
			throw new IllegalArgumentException("clickCount must be 1 to 3");
		}
		// the platform's popup trigger: the press on macOS and Linux, the release on Windows
		boolean popupOnPress = awtButton == MouseEvent.BUTTON3 && !SystemInfo.isWindows;
		boolean popupOnRelease = awtButton == MouseEvent.BUTTON3 && SystemInfo.isWindows;
		Map.Entry<Component, UiRefs.Target> resolved = UiRefs.resolveRow(ref);
		Component component = resolved.getKey();
		UiRefs.Target target = resolved.getValue();
		CompletableFuture<Void> handled = new CompletableFuture<>();
		// later, not and-wait: the click may open a modal dialog, which would block this request
		ApplicationManager.getApplication().invokeLater(() -> {
			try {
				Map.Entry<Component, Point> hit = hit(component, target);
				if (hit == null) {
					return;
				}
				Component c = hit.getKey();
				Point p = hit.getValue();
				long now = System.currentTimeMillis();
				moveOnto(c, p, mask, now);
				for (int n = 1; n <= clickCount; n++) {
					c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, now, mask | InputEvent.getMaskForButton(awtButton),
							p.x, p.y, n, popupOnPress, awtButton));
					c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, now, mask, p.x, p.y, n, popupOnRelease, awtButton));
					c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, now, mask, p.x, p.y, n, false, awtButton));
				}
			} finally {
				handled.complete(null);
			}
		}, ModalityState.stateForComponent(component)); // its dialog's modality: write-safe, yet runs while that dialog is open
		// wait for the click's handlers, so a snapshot right after sees what they opened; a modal dialog keeps
		// them running until it closes, so stop waiting after a second
		try {
			handled.get(CLICK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			String dialogs = UiSnapshot.modalDialogs();
			return "Clicked " + ref + "; still handling it after " + CLICK_WAIT_MILLIS + " ms"
					+ (dialogs.isEmpty() ? " (a modal dialog may be open)" : "; a modal dialog is open:\n" + dialogs) + UiInput.heldNote();
		} catch (ExecutionException ignored) {
			// the handlers' exception is the IDE's to report
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return "Clicked " + ref + UiInput.heldNote();
	}

	/** Moves the mouse onto a ref (entered, then moved), so tooltips, hover popups and Quick Documentation show. */
	@SuppressWarnings("MagicConstant") // mask combines InputEvent *_DOWN_MASK flags from modifiers()
	static String hover(String ref, String modifiers) {
		int mask = modifiers(modifiers) | UiInput.held();
		Map.Entry<Component, UiRefs.Target> resolved = UiRefs.resolveRow(ref);
		Component component = resolved.getKey();
		ApplicationManager.getApplication().invokeLater(() -> {
			Map.Entry<Component, Point> hit = hit(component, resolved.getValue());
			if (hit == null) {
				return;
			}
			Component c = hit.getKey();
			Point p = hit.getValue();
			moveOnto(c, p, mask, System.currentTimeMillis());
		}, ModalityState.stateForComponent(component));
		return "Hovered " + ref + UiInput.heldNote();
	}

	/**
	 * Drags from one ref to another: press on the source, drag events in steps, release over the target. As with a
	 * real mouse grab, every event goes to the source, in its coordinates. Covers the IDE's own mouse-listener drags
	 * (tool window buttons, editor tabs, splitters); drags that use AWT drag and drop run a native loop these events
	 * cannot reach.
	 */
	@SuppressWarnings("MagicConstant") // mask combines InputEvent *_DOWN_MASK flags, from modifiers() and getMaskForButton
	static String drag(String ref, String to, String button, String modifiers) {
		int awtButton = button(button);
		int mask = modifiers(modifiers) | UiInput.held();
		Map.Entry<Component, UiRefs.Target> from = UiRefs.resolveRow(ref);
		Map.Entry<Component, UiRefs.Target> onto = UiRefs.resolveRow(to);
		Component source = from.getKey();
		ApplicationManager.getApplication().invokeLater(() -> {
			Map.Entry<Component, Point> hit = hit(source, from.getValue());
			Point end = center(onto.getKey(), onto.getValue());
			if (hit == null || end == null) {
				return;
			}
			Component grab = hit.getKey();
			Point start = hit.getValue();
			end = SwingUtilities.convertPoint(onto.getKey(), end, grab);
			int held = mask | InputEvent.getMaskForButton(awtButton);
			long now = System.currentTimeMillis();
			grab.dispatchEvent(new MouseEvent(grab, MouseEvent.MOUSE_ENTERED, now, mask, start.x, start.y, 0, false, MouseEvent.NOBUTTON));
			grab.dispatchEvent(new MouseEvent(grab, MouseEvent.MOUSE_PRESSED, now, held, start.x, start.y, 1, false, awtButton));
			for (int step = 1; step <= DRAG_STEPS; step++) {
				int x = start.x + (end.x - start.x) * step / DRAG_STEPS;
				int y = start.y + (end.y - start.y) * step / DRAG_STEPS;
				grab.dispatchEvent(new MouseEvent(grab, MouseEvent.MOUSE_DRAGGED, now + step, held, x, y, 0, false, MouseEvent.NOBUTTON));
			}
			grab.dispatchEvent(new MouseEvent(grab, MouseEvent.MOUSE_RELEASED, now + DRAG_STEPS, mask, end.x, end.y, 1, false, awtButton));
		}, ModalityState.stateForComponent(source));
		return "Dragged " + ref + " to " + to + UiInput.heldNote();
	}

	private static final int DRAG_STEPS = 10;

	/**
	 * Enters {@code c} and moves to {@code p} in two steps: popup lists (ListPopupImpl) take their first move as where the
	 * mouse rests and select the row under it only on a later one, and act on a click only on the selected row.
	 */
	@SuppressWarnings("MagicConstant") // mask combines InputEvent *_DOWN_MASK flags
	private static void moveOnto(Component c, Point p, int mask, long now) {
		int fromX = p.x > 0 ? p.x - 1 : p.x + 1;
		c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_ENTERED, now, mask, fromX, p.y, 0, false, MouseEvent.NOBUTTON));
		c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_MOVED, now, mask, fromX, p.y, 0, false, MouseEvent.NOBUTTON));
		c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_MOVED, now, mask, p.x, p.y, 0, false, MouseEvent.NOBUTTON));
	}

	/** The component and point mouse events at the ref's center go to; null if it is no longer showing. Call on the EDT. */
	private static Map.Entry<Component, Point> hit(Component component, UiRefs.Target target) {
		Point at = center(component, target);
		return at == null ? null : under(component, at);
	}

	/** The center of the ref's row cell, or of the component; null if it is no longer showing. Call on the EDT. */
	private static Point center(Component component, UiRefs.Target target) {
		if (!component.isShowing()) {
			return null;
		}
		Rectangle cell = UiRefs.rowBounds(component, target);
		if (cell != null && component instanceof JComponent owner && !owner.getVisibleRect().contains(cell)) {
			owner.scrollRectToVisible(cell); // an offscreen row from a snapshot with allRows; its bounds stay put
		}
		if (target.row() >= 0 && cell == null) {
			return null;
		}
		return cell == null ? new Point(component.getWidth() / 2, component.getHeight() / 2)
				: new Point((int) cell.getCenterX(), (int) cell.getCenterY());
	}

	/**
	 * Where mouse events at {@code p} (in {@code owner}'s coordinates) go: for a Compose panel the canvas child under
	 * the point, which has the mouse listeners, with the point in its coordinates; otherwise the owner, or, as AWT
	 * retargets a real click, its nearest ancestor that listens to the mouse (e.g. the tabbed pane of a tab's label).
	 */
	private static Map.Entry<Component, Point> under(Component owner, Point p) {
		Component deepest = UiSnapshot.isCompose(owner) ? SwingUtilities.getDeepestComponentAt(owner, p.x, p.y) : null;
		if (deepest != null && deepest != owner) {
			return Map.entry(deepest, SwingUtilities.convertPoint(owner, p, deepest));
		}
		for (Component c = owner; c != null && !(c instanceof Window); c = c.getParent()) {
			if (handlesMouse(c)) {
				return Map.entry(c, SwingUtilities.convertPoint(owner, p, c));
			}
		}
		return Map.entry(owner, p);
	}

	/**
	 * Whether {@code c} takes mouse clicks itself: it has mouse listeners, or a class of its own (not the JDK's)
	 * overrides processMouseEvent, as ActionButton does after enableEvents, with no listener when it has no tooltip.
	 */
	private static boolean handlesMouse(Component c) {
		if (c.getMouseListeners().length > 0) {
			return true;
		}
		for (Class<?> type = c.getClass(); type != null && !type.getName().startsWith("java.") && !type.getName().startsWith("javax.");
				type = type.getSuperclass()) {
			try {
				type.getDeclaredMethod("processMouseEvent", MouseEvent.class);
				return true;
			} catch (NoSuchMethodException e) {
				// not here; try the superclass
			}
		}
		return false;
	}
}
