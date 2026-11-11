package com.fsodomka.idepilot;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ToolWindowType;
import com.intellij.ui.ScreenUtil;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.wm.IdeFrame;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Activating, resizing and closing IDE windows: project frames, dialogs, detached tool windows. */
final class UiWindow {
	private static final Pattern SIZE = Pattern.compile("(\\d+)\\s*[xX]\\s*(\\d+)");
	private static final Pattern POSITION = Pattern.compile("(-?\\d+)\\s*,\\s*(-?\\d+)");

	private UiWindow() {
	}

	/**
	 * A pilot_window call, parsed: with {@code close}, close the window of {@code ref} (or the active window) as its
	 * close button would. Otherwise {@code maximize} maximizes (true) or restores (false) it, {@code size} resizes it,
	 * {@code position} moves its top-left corner there on screen, and {@code anchor} and {@code type} move or detach
	 * the tool window that holds {@code ref} (or the focused component); without any of these, bring the window to the
	 * front.
	 */
	record WindowRequest(String ref, Dimension size, Point position, Boolean maximize, ToolWindowAnchor anchor,
			ToolWindowType type, boolean close) {
		/** Checks the combination and parses size ("1280x800"), position ("100,50"), anchor and type. */
		static WindowRequest parse(String ref, String size, String position, Boolean maximize, String anchor, String type,
				boolean close) {
			if (close && (size != null || position != null || maximize != null || anchor != null || type != null)) {
				throw new IllegalArgumentException("close takes only ref");
			}
			if ((size != null || position != null) && Boolean.TRUE.equals(maximize)) {
				throw new IllegalArgumentException("Give size or position, or maximize, not both");
			}
			// qualified: inside the record, size() and the others are its accessors
			return new WindowRequest(ref, size == null ? null : UiWindow.size(size),
					position == null ? null : UiWindow.position(position), maximize,
					anchor == null ? null : UiWindow.anchor(anchor), type == null ? null : UiWindow.type(type), close);
		}
	}

	/** Carries out the request; returns what was done, then the window's (and tool window's) state. */
	static String window(WindowRequest request) {
		return Edt.compute(() -> act(request));
	}

	private static String act(WindowRequest request) {
		Window window = window(request.ref());
		String name = "window \"" + title(window) + "\"";
		if (request.close()) {
			return close(window, name);
		}
		ToolWindow toolWindow = toolWindow(request.ref(), window);
		if ((request.anchor() != null || request.type() != null) && toolWindow == null) {
			throw new IllegalArgumentException((request.ref() == null ? "The focused component" : request.ref())
					+ " is in no tool window; give the ref of a component in one, e.g. its title or content");
		}
		List<String> done = new ArrayList<>();
		arrange(window, name, request, done);
		moveToolWindow(toolWindow, request, done);
		if (done.isEmpty()) {
			done.add(activate(window, name));
		}
		Window shown = window;
		if (request.type() != null && SwingUtilities.getWindowAncestor(toolWindow.getComponent()) instanceof Window own) {
			shown = own; // a tool window made floating or windowed has a window of its own, a docked one its frame's
		}
		String state = "State: window \"" + title(shown) + "\"" + windowStates(shown);
		return String.join("\n", done) + "\n" + state + (toolWindow == null ? "" : toolWindowStates(toolWindow));
	}

	private static String close(Window window, String name) {
		// later: closing may ask for confirmation in a modal dialog, which would block this request
		ApplicationManager.getApplication().invokeLater(
				() -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)), ModalityState.any());
		return "Closing " + name + "; follow with pilot_wait_for gone";
	}

	/** Maximizes or restores, then resizes, then moves the window, as requested, adding what it did to {@code done}. */
	private static void arrange(Window window, String name, WindowRequest request, List<String> done) {
		Dimension size = request.size();
		Point position = request.position();
		if (request.maximize() != null) {
			if (!(window instanceof Frame frame)) {
				throw new IllegalArgumentException(name + " is a dialog, which cannot be maximized");
			}
			boolean maximize = request.maximize();
			int state = frame.getExtendedState();
			frame.setExtendedState(maximize ? state | Frame.MAXIMIZED_BOTH : state & ~Frame.MAXIMIZED_BOTH);
			done.add((maximize ? "Maximized " : "Restored ") + name);
		}
		if ((size != null || position != null) && window instanceof Frame frame
				&& (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) {
			frame.setExtendedState(Frame.NORMAL); // a maximized frame ignores setSize and setLocation
		}
		if (size != null) {
			window.setSize(size);
			window.validate();
			done.add("Resized " + name + " to " + window.getWidth() + "x" + window.getHeight());
		}
		if (position != null) {
			if (!ScreenUtil.isVisible(new Rectangle(position, window.getSize()))) {
				throw new IllegalArgumentException("At " + position.x + "," + position.y + " " + name
						+ " would be off every screen; see the screens pilot_snapshot windowsOnly lists");
			}
			window.setLocation(position);
			done.add("Moved " + name + " to " + window.getX() + "," + window.getY());
		}
	}

	/** Moves the tool window to the requested anchor, then gives it the requested type, adding what it did to {@code done}. */
	private static void moveToolWindow(ToolWindow toolWindow, WindowRequest request, List<String> done) {
		if (request.anchor() != null) {
			toolWindow.setAnchor(request.anchor(), null);
			done.add("Moved tool window \"" + toolWindow.getId() + "\" to " + request.anchor());
		}
		if (request.type() != null) {
			toolWindow.setType(request.type(), null);
			done.add("Made tool window \"" + toolWindow.getId() + "\" " + request.type().name().toLowerCase(Locale.ROOT));
		}
	}

	private static String activate(Window window, String name) {
		if (window instanceof Frame frame && (frame.getExtendedState() & Frame.ICONIFIED) != 0) {
			frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
		}
		window.toFront();
		window.requestFocus();
		return "Activated " + name;
	}

	/**
	 * The tool window that holds {@code ref}'s component (or without ref the focused component), or whose own window
	 * {@code window} is, when it is floating or windowed; null if none.
	 */
	private static ToolWindow toolWindow(String ref, Window window) {
		Component component = ref == null ? KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() : UiSnapshot.component(ref);
		if (component != null && !(component instanceof Window)) {
			ToolWindow found = toolWindowOf(component);
			if (found != null) {
				return found;
			}
		}
		for (Project project : ProjectManager.getInstance().getOpenProjects()) {
			ToolWindowManager manager = ToolWindowManager.getInstance(project);
			for (String id : manager.getToolWindowIds()) {
				ToolWindow candidate = manager.getToolWindow(id);
				if (candidate != null && candidate.isVisible()
						&& (candidate.getType() == ToolWindowType.FLOATING || candidate.getType() == ToolWindowType.WINDOWED)
						&& SwingUtilities.getWindowAncestor(candidate.getComponent()) == window) {
					return candidate;
				}
			}
		}
		return null;
	}

	/** The tool window whose content or decorator holds {@code component}, as the IDE's data context gives it; null if none. */
	static ToolWindow toolWindowOf(Component component) {
		return PlatformDataKeys.TOOL_WINDOW.getData(DataManager.getInstance().getDataContext(component));
	}

	/** "100,50" as a screen point; negative coordinates reach screens left of or above the primary one. */
	static Point position(String position) {
		Matcher m = POSITION.matcher(position.strip());
		if (!m.matches()) {
			throw new IllegalArgumentException("Bad position " + position + "; use X,Y, e.g. 100,50");
		}
		return new Point(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
	}

	static ToolWindowAnchor anchor(String anchor) {
		return switch (anchor.strip().toLowerCase(Locale.ROOT)) {
			case "left" -> ToolWindowAnchor.LEFT;
			case "right" -> ToolWindowAnchor.RIGHT;
			case "top" -> ToolWindowAnchor.TOP;
			case "bottom" -> ToolWindowAnchor.BOTTOM;
			default -> throw new IllegalArgumentException("Bad anchor " + anchor + "; use left, right, top or bottom");
		};
	}

	static ToolWindowType type(String type) {
		return switch (type.strip().toLowerCase(Locale.ROOT)) {
			case "docked" -> ToolWindowType.DOCKED;
			case "floating" -> ToolWindowType.FLOATING;
			case "windowed" -> ToolWindowType.WINDOWED;
			case "sliding" -> ToolWindowType.SLIDING;
			default -> throw new IllegalArgumentException("Bad type " + type + "; use docked, floating, windowed or sliding");
		};
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

	static String title(Window window) {
		return window instanceof Frame f ? f.getTitle() : window instanceof Dialog d ? d.getTitle() : window.getClass().getSimpleName();
	}

	/**
	 * The screens, after the windows: ID, (primary), bounds in screen coordinates as window bounds give them, and the
	 * scale (HiDPI) where it is not 1; with toon, a table screens[N]{id,primary,x,y,w,h,scale}.
	 */
	static String screens(boolean toon) {
		GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
		if (environment.isHeadlessInstance()) {
			return "";
		}
		GraphicsDevice primary = environment.getDefaultScreenDevice();
		GraphicsDevice[] devices = environment.getScreenDevices();
		StringBuilder out = new StringBuilder(toon ? "screens[" + devices.length + "]{id,primary,x,y,w,h,scale}:\n" : "");
		for (GraphicsDevice device : devices) {
			GraphicsConfiguration configuration = device.getDefaultConfiguration();
			Rectangle r = configuration.getBounds();
			double scale = configuration.getDefaultTransform().getScaleX();
			if (toon) {
				out.append("  ").append(Toon.value(device.getIDstring())).append(',').append(device == primary).append(',')
						.append(r.x).append(',').append(r.y).append(',').append(r.width).append(',').append(r.height).append(',')
						.append(scale).append('\n');
			} else {
				out.append("- screen ").append(UiSnapshot.quote(device.getIDstring())).append(device == primary ? " (primary)" : "")
						.append(scale == 1 ? "" : " (scale " + scale + ")").append(" @").append(r.x).append(',').append(r.y)
						.append(' ').append(r.width).append('x').append(r.height).append('\n');
			}
		}
		return out.toString();
	}

	/** A tool window's ID, anchor and type, e.g. (tool window "Project", left, docked), plus split, auto-hide or hidden. */
	static String toolWindowStates(ToolWindow toolWindow) {
		return " (tool window " + UiSnapshot.quote(toolWindow.getId()) + ", " + toolWindow.getAnchor() + ", "
				+ toolWindow.getType().name().toLowerCase(Locale.ROOT) + (toolWindow.isSplitMode() ? ", split" : "")
				+ (toolWindow.isAutoHide() ? ", auto-hide" : "") + (toolWindow.isVisible() ? "" : ", hidden") + ")";
	}

	/**
	 * (active), (maximized) for a maximized frame, (modal) for a modal dialog, and (indexing) for a project frame whose
	 * project is in dumb mode.
	 */
	static String windowStates(Window window) {
		String states = window.isActive() ? " (active)" : "";
		if (window instanceof Frame frame && (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
			states += " (maximized)";
		}
		if (window instanceof Dialog dialog && dialog.isModal()) {
			states += " (modal)";
		}
		if (window instanceof IdeFrame frame && frame.getProject() != null
				&& DumbService.isDumb(frame.getProject())) {
			states += " (indexing)";
		}
		return states;
	}
}
