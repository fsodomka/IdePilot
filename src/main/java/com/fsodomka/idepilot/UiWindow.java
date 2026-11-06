package com.fsodomka.idepilot;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Activating, resizing and closing IDE windows: project frames, dialogs, detached tool windows. */
final class UiWindow {
	private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*[xX]\\s*(\\d+)");

	private UiWindow() {
	}

	/**
	 * Brings the window of {@code ref} (or the active window) to the front, or with {@code size} ("1280x800") resizes
	 * it, or with {@code close} closes it as its close button would.
	 */
	static String window(String ref, String size, boolean close) {
		if (size != null && close) {
			throw new IllegalArgumentException("Give size or close, not both");
		}
		Dimension dimension = size == null ? null : size(size);
		return Edt.compute(() -> act(ref, dimension, close));
	}

	private static String act(String ref, Dimension size, boolean close) {
		Window window = window(ref);
		String name = "window \"" + title(window) + "\"";
		if (close) {
			// later: closing may ask for confirmation in a modal dialog, which would block this request
			ApplicationManager.getApplication().invokeLater(
					() -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)), ModalityState.any());
			return "Closing " + name + "; follow with pilot_wait_for gone";
		}
		if (size != null) {
			if (window instanceof Frame frame && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) {
				frame.setExtendedState(Frame.NORMAL); // a maximized frame ignores setSize
			}
			window.setSize(size);
			window.validate();
			return "Resized " + name + " to " + window.getWidth() + "x" + window.getHeight();
		}
		if (window instanceof Frame frame && (frame.getExtendedState() & Frame.ICONIFIED) != 0) {
			frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
		}
		window.toFront();
		window.requestFocus();
		return "Activated " + name;
	}

	/** The window of the ref's component, or the active window without a ref. */
	private static Window window(String ref) {
		if (ref == null) {
			Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
			if (active == null) {
				throw new IllegalArgumentException("No active window; give the ref of a window from pilot_snapshot");
			}
			return active;
		}
		Component component = UiSnapshot.component(ref);
		Window window = component instanceof Window w ? w : SwingUtilities.getWindowAncestor(component);
		if (window == null) {
			throw new IllegalArgumentException(ref + " is in no window");
		}
		return window;
	}

	/** "1280x800" as a size of at least 100x100. */
	static Dimension size(String size) {
		Matcher m = SIZE.matcher(size.strip());
		if (!m.matches()) {
			throw new IllegalArgumentException("Bad size " + size + "; use WIDTHxHEIGHT, e.g. 1280x800");
		}
		Dimension d = new Dimension(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
		if (d.width < 100 || d.height < 100) {
			throw new IllegalArgumentException("Size " + size + " is below 100x100");
		}
		return d;
	}

	private static String title(Window window) {
		return window instanceof Frame f ? f.getTitle() : window instanceof Dialog d ? d.getTitle() : window.getClass().getSimpleName();
	}
}
