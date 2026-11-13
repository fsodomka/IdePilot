package com.fsodomka.idepilot;


import javax.imageio.ImageIO;
import javax.swing.RootPaneContainer;
import java.awt.AWTException;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * A PNG of a component or window. "paint" mode paints it (works while the IDE is covered; logical size), and for a
 * window also the popups and dialogs it owns at their places; "screen" mode copies the screen pixels (exactly what is
 * shown, at full resolution, but only when nothing covers it).
 */
final class Screenshot {
	record Shot(byte[] png, String description) {
	}

	private Screenshot() {
	}

	static Shot take(String ref, String path, boolean popups, boolean screen) {
		Component target = ref == null ? null : UiRefs.component(ref);
		Object result = Edt.compute(() -> {
			Component component = target != null ? target : topWindow(activeWindow());
			Component painted = component instanceof RootPaneContainer container ? container.getRootPane() : component;
			if (painted.getWidth() <= 0 || painted.getHeight() <= 0) {
				throw new IllegalStateException("Nothing to capture: the component has no size");
			}
			return screen ? new Rectangle(painted.getLocationOnScreen(), painted.getSize())
					: paint(painted, popups && component instanceof Window window ? owned(window) : List.of());
		});
		BufferedImage image = result instanceof Rectangle bounds ? capture(bounds) : (BufferedImage) result;
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		try {
			ImageIO.write(image, "png", png);
			if (path != null) {
				Path file = Path.of(path);
				if (file.getParent() != null) {
					Files.createDirectories(file.getParent());
				}
				Files.write(file, png.toByteArray());
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return new Shot(png.toByteArray(), image.getWidth() + "x" + image.getHeight() + (ref == null ? " active window" : " " + ref)
				+ (screen ? " from the screen" : "") + (path == null ? "" : ", saved to " + path));
	}

	/** {@code base} painted, then each of {@code overlays} (root panes of windows) at its place relative to it. */
	private static BufferedImage paint(Component base, List<Component> overlays) {
		// a plain image, not UIUtil.createImage's HiDPI one: the PNG holds exactly these pixels
		//noinspection UndesirableClassUsage
		BufferedImage image = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			base.paint(g);
			Point origin = base.getLocationOnScreen();
			for (Component overlay : overlays) {
				Point at = overlay.getLocationOnScreen();
				Graphics2D og = (Graphics2D) g.create(at.x - origin.x, at.y - origin.y, overlay.getWidth(), overlay.getHeight());
				try {
					overlay.paint(og);
				} finally {
					og.dispose();
				}
			}
		} finally {
			g.dispose();
		}
		return image;
	}

	/** The root panes of the visible windows owned, directly or not, by {@code window}: popups, dialogs, balloons. */
	private static List<Component> owned(Window window) {
		return Arrays.stream(Window.getWindows())
				.filter(w -> w != window && w.isShowing() && w.getWidth() > 0 && w.getHeight() > 0 && ownedBy(w, window))
				.map(w -> w instanceof RootPaneContainer container ? (Component) container.getRootPane() : w)
				.toList();
	}

	private static boolean ownedBy(Window window, Window owner) {
		for (Window w = window.getOwner(); w != null; w = w.getOwner()) {
			if (w == owner) {
				return true;
			}
		}
		return false;
	}

	/** The screen pixels in {@code bounds}, at the screen's full resolution. */
	private static BufferedImage capture(Rectangle bounds) {
		try {
			MultiResolutionImage shot = new Robot().createMultiResolutionScreenCapture(bounds);
			List<Image> variants = shot.getResolutionVariants();
			Image largest = variants.getLast();
			if (largest instanceof BufferedImage buffered) {
				return buffered;
			}
			// the capture is already in device pixels; UIUtil.createImage would scale it again
			//noinspection UndesirableClassUsage
			BufferedImage image = new BufferedImage(largest.getWidth(null), largest.getHeight(null), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = image.createGraphics();
			g.drawImage(largest, 0, 0, null);
			g.dispose();
			return image;
		} catch (AWTException e) {
			throw new IllegalStateException("Cannot capture the screen: " + e.getMessage(), e);
		}
	}

	/** The frame or dialog a popup belongs to, so its screenshot shows the popup over its window. */
	private static Window topWindow(Window window) {
		Window w = window;
		while (!(w instanceof Frame || w instanceof Dialog) && w.getOwner() != null) {
			w = w.getOwner();
		}
		return w;
	}

	private static Window activeWindow() {
		Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		if (active != null) {
			return active;
		}
		return Arrays.stream(Window.getWindows()).filter(Window::isShowing).reduce((a, b) -> b)
				.orElseThrow(() -> new IllegalStateException("No visible window"));
	}
}
