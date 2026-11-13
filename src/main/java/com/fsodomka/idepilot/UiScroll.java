package com.fsodomka.idepilot;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Scrolling the scroll pane of a component, by pages or to a list, tree or table row with some text. */
final class UiScroll {
	private UiScroll() {
	}

	/**
	 * Scrolls the scroll pane that holds {@code ref} (or that {@code ref} is) {@code pages} pages {@code direction}
	 * (up, down, left, right), or to its top or bottom; or with {@code to}, its list, tree or table until the first row
	 * whose text contains {@code to} (case-insensitive) shows.
	 */
	static String scroll(String ref, String direction, double pages, String to) {
		if ((direction == null) == (to == null)) {
			throw new IllegalArgumentException("Give direction or to");
		}
		Component component = UiRefs.component(ref);
		return Edt.compute(() -> to != null ? scrollTo(component, to) : scrollBy(component, direction.toLowerCase(Locale.ROOT), pages));
	}

	private static String scrollBy(Component component, String direction, double pages) {
		JViewport viewport = viewport(component);
		Rectangle visible = viewport.getViewRect();
		Dimension size = viewport.getViewSize();
		Point at = visible.getLocation();
		int dy = (int) Math.round(visible.height * pages), dx = (int) Math.round(visible.width * pages);
		switch (direction) {
			case "up" -> at.y -= dy;
			case "down" -> at.y += dy;
			case "left" -> at.x -= dx;
			case "right" -> at.x += dx;
			case "top" -> at.y = 0;
			case "bottom" -> at.y = size.height;
			default -> throw new IllegalArgumentException("Bad direction " + direction + "; use up, down, left, right, top or bottom");
		}
		at.x = Math.clamp(at.x, 0, Math.max(0, size.width - visible.width));
		at.y = Math.clamp(at.y, 0, Math.max(0, size.height - visible.height));
		viewport.setViewPosition(at);
		Rectangle now = viewport.getViewRect();
		return "Scrolled " + direction + ": showing " + range(now.y, now.height, size.height) + " vertically"
				+ (size.width > now.width ? ", " + range(now.x, now.width, size.width) + " horizontally" : "")
				+ "; pilot_snapshot to see it";
	}

	private static String range(int from, int extent, int total) {
		String edge = from <= 0 ? " (top)" : from + extent >= total ? " (bottom)" : "";
		return from + "-" + (from + extent) + " of " + total + " px" + edge;
	}

	private static JViewport viewport(Component component) {
		JScrollPane pane = component instanceof JScrollPane p ? p
				: (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, component);
		if (pane == null) {
			throw new IllegalArgumentException("The component is in no scroll pane; give the ref of a scroll pane or a component in one");
		}
		return pane.getViewport();
	}

	private static String scrollTo(Component component, String to) {
		Component rows = rowComponent(component);
		if (rows == null) {
			throw new IllegalArgumentException("No list, tree or table at the ref; to scrolls their rows (for an editor, use pilot_editor caret)");
		}
		String needle = to.toLowerCase(Locale.ROOT);
		int count = rows instanceof JList<?> list ? list.getModel().getSize() : rows instanceof JTree tree ? tree.getRowCount()
				: ((JTable) rows).getRowCount();
		for (int i = 0; i < count; i++) {
			String text = text(rows, i);
			if (text.toLowerCase(Locale.ROOT).contains(needle)) {
				Rectangle cell = rows instanceof JList<?> list ? list.getCellBounds(i, i) : rows instanceof JTree tree
						? tree.getRowBounds(i) : ((JTable) rows).getCellRect(i, 0, true);
				((JComponent) rows).scrollRectToVisible(cell);
				return "Scrolled to row " + (i + 1) + " of " + count + ": \"" + text + "\"; pilot_snapshot to get its ref";
			}
		}
		throw new IllegalArgumentException("No row of " + count + " contains \"" + to + "\" (trees: only expanded rows)");
	}

	/** The list, tree or table at the component: itself, or the first one inside it (e.g. inside a scroll pane). */
	private static Component rowComponent(Component component) {
		if (component instanceof JList<?> || component instanceof JTree || component instanceof JTable) {
			return component;
		}
		if (component instanceof Container container) {
			for (Component child : container.getComponents()) {
				Component found = rowComponent(child);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static String text(Component rows, int i) {
		if (rows instanceof JList<?> list) {
			return UiSnapshot.listRowText(list, i);
		}
		if (rows instanceof JTree tree) {
			return UiSnapshot.treeRowText(tree, i);
		}
		JTable table = (JTable) rows;
		return IntStream.range(0, table.getColumnCount()).mapToObj(c -> String.valueOf(table.getValueAt(i, c)))
				.collect(Collectors.joining(" "));
	}
}
