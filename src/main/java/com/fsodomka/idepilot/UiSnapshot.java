package com.fsodomka.idepilot;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.Toggleable;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.util.text.StringUtil;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleComponent;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.accessibility.AccessibleStateSet;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTree;
import javax.swing.ListCellRenderer;
import javax.swing.tree.TreePath;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The visible Swing UI as an indented tree like Playwright's snapshot. Unnamed containers are flattened into their
 * children. Every listed component gets a ref that stays its own while the component lives, so a stale ref never
 * points at another component; a list or tree row's ref is its owner's ref and the row index, e.g. e14.3, and
 * clicking it fails once that row shows another item.
 */
final class UiSnapshot {
	private static final int MAX_TEXT = 80;
	private static final int MAX_ROWS = 200;
	private static final String COMPOSE_PANEL = "androidx.compose.ui.awt.ComposePanel";
	/** Each component's ref, kept while the component lives. */
	private static final Map<Component, String> ids = new WeakHashMap<>();
	private static final Map<String, Target> refs = new HashMap<>();
	private static int lastId;

	/**
	 * What a ref points at: a component, or with {@code row} >= 0 a row of a list or tree, whose {@code item} (the list
	 * value or tree node) tells whether the row still shows it.
	 */
	record Target(WeakReference<Component> component, int row, WeakReference<Object> item) {
		Target(Component component) {
			this(new WeakReference<>(component), -1, null);
		}

		/** Whether a row target's row still shows its item (a Compose node: is still showing); true for a component. Call on the EDT. */
		boolean rowCurrent() {
			if (row < 0) {
				return true;
			}
			Object expected = item.get();
			Component owner = component.get();
			if (owner != null && isCompose(owner) && expected instanceof Accessible node) {
				AccessibleContext context = node.getAccessibleContext();
				return context != null && context.getAccessibleStateSet() != null
						&& context.getAccessibleStateSet().contains(AccessibleState.SHOWING);
			}
			Object actual = owner instanceof JList<?> list ? row < list.getModel().getSize() ? list.getModel().getElementAt(row) : null
					: owner instanceof JTree tree && tree.getPathForRow(row) != null ? tree.getPathForRow(row).getLastPathComponent() : null;
			return expected != null && expected.equals(actual);
		}
	}

	private UiSnapshot() {
	}

	/**
	 * Lines {@code offset + 1} to {@code offset + limit} of the snapshot. Stops walking at the limit and ends with the
	 * offset and first ref of the next page instead of counting the rest. Given that ref as {@code next}, fails when
	 * the UI changed so that the page would not continue the previous one.
	 */
	static String snapshot(int offset, int limit, String next, boolean bounds, String find, boolean toon, boolean allRows) {
		return Edt.compute(() -> {
			if (offset == 0) {
				refs.values().removeIf(target -> target.component().get() == null);
			}
			Page page = new Page(offset, Math.max(limit, 1), bounds, find, toon);
			page.allRows = allRows;
			for (Window window : Window.getWindows()) {
				if (page.full) {
					break;
				}
				if (window.isShowing()) {
					page.add(0, "window " + quote(title(window)), new Target(window), window.isActive() ? " (active)" : "");
					children(window, 1, page);
				}
			}
			return page.finish(next);
		});
	}

	private static String ref(Target target) {
		Component component = target.component().get();
		String id = ids.computeIfAbsent(component, c -> "e" + ++lastId);
		return target.row() < 0 ? id : id + "." + target.row();
	}

	/** One page of snapshot lines; {@code full} once a line past the page was reached, whose ref is {@code nextRef}. */
	static final class Page {
		final int offset;
		final int limit;
		final StringBuilder out = new StringBuilder();
		int count;
		boolean full;
		String first;
		String nextRef;

		final boolean bounds;
		/** With find, only the lines containing it (case-insensitive) and their ancestors are listed. */
		final String find;
		/** The ancestors of the current line and the line itself, each listed once when a line below them matches. */
		private final List<Line> path = new ArrayList<>();

		private static final class Line {
			final int depth;
			final String text;
			final Target target;
			final String states;
			boolean listed;

			Line(int depth, String text, Target target, String states) {
				this.depth = depth;
				this.text = text;
				this.target = target;
				this.states = states;
			}
		}

		Page(int offset, int limit, boolean bounds) {
			this(offset, limit, bounds, null);
		}

		Page(int offset, int limit, boolean bounds, String find) {
			this(offset, limit, bounds, find, false);
		}

		Page(int offset, int limit, boolean bounds, String find, boolean toon) {
			this.offset = offset;
			this.limit = limit;
			this.bounds = bounds;
			this.find = find == null || find.isEmpty() ? null : find.toLowerCase(Locale.ROOT);
			this.toon = toon;
		}

		/** With toon, the page is a TOON table of lines instead of an indented tree. */
		final boolean toon;
		/** List every row of lists and trees (at most {@link #MAX_ROWS}), not only those that show. */
		boolean allRows;
		private final List<String> rows = new ArrayList<>();

		void add(int depth, String text, Target target, String states) {
			if (find == null) {
				list(depth, text, target, states);
				return;
			}
			while (!path.isEmpty() && path.getLast().depth >= depth) {
				path.removeLast();
			}
			Line line = new Line(depth, text, target, states);
			path.add(line);
			if ((text + states).toLowerCase(Locale.ROOT).contains(find)) {
				for (Line l : path) {
					if (!l.listed) {
						l.listed = true;
						list(l.depth, l.text, l.target, l.states);
					}
				}
			}
		}

		private void list(int depth, String text, Target target, String states) {
			if (full) {
				return;
			}
			if (++count > offset + limit) {
				full = true;
				nextRef = ref(target);
				return;
			}
			if (count > offset) {
				String ref = ref(target);
				refs.put(ref, target);
				if (first == null) {
					first = ref;
				}
				if (toon) {
					rows.add(toonRow(depth, ref, text, states, bounds ? screenRect(target) : null));
				} else {
					out.repeat("  ", depth).append("- ").append(text).append(" [ref=").append(ref).append(']').append(states).append(bounds ? screenBounds(target) : "").append('\n');
				}
			}
		}

		/** depth,ref,role,name,states[,x,y,w,h]; text is role or role "name", as {@link #quote} writes it. */
		private String toonRow(int depth, String ref, String text, String states, Rectangle r) {
			int at = text.indexOf(" \"");
			String role = at < 0 || !text.endsWith("\"") ? text : text.substring(0, at);
			String name = role.equals(text) ? "" : text.substring(at + 2, text.length() - 1).replace("\\\"", "\"");
			// " (focused) (selected)" -> "focused selected"
			String stateList = states.replace(") (", " ").replace(" (", "").replace(")", "").strip();
			StringBuilder row = new StringBuilder().append(depth).append(',').append(Toon.value(ref)).append(',')
					.append(Toon.value(role)).append(',').append(Toon.value(name)).append(',').append(Toon.value(stateList));
			if (bounds) {
				row.append(',').append(r == null ? "null,null,null,null" : r.x + "," + r.y + "," + r.width + "," + r.height);
			}
			return row.toString();
		}

		/**
		 * The page's text, ending with the next page's offset and ref when full. Given the previous page's
		 * {@code next} ref, fails when this page does not start with it: the UI changed in between.
		 */
		String finish(String next) {
			if (next != null && offset > 0 && !next.equals(first)) {
				throw new IllegalStateException("The UI changed since the previous page (line " + (offset + 1) + " is "
						+ (first == null ? "gone" : first) + ", not " + next + "); take a new pilot_snapshot from offset 0");
			}
			if (toon) {
				StringBuilder table = new StringBuilder("lines[").append(rows.size()).append("]{depth,ref,role,name,states")
						.append(bounds ? ",x,y,w,h" : "").append("}:\n");
				for (String row : rows) {
					table.append("  ").append(row).append('\n');
				}
				if (full) {
					table.append("nextOffset: ").append(offset + limit).append('\n').append("next: ").append(nextRef).append('\n');
				}
				return table.toString();
			}
			if (full) {
				out.append("- ... more: pilot_snapshot offset=").append(offset + limit).append(" next=").append(nextRef)
						.append(find == null ? "" : " with the same find").append('\n');
			}
			if (out.isEmpty()) {
				return offset > 0 ? "(no more lines)" : find != null ? "(no line contains \"" + find + "\")" : "(no visible windows)";
			}
			return out.toString();
		}
	}

	/** All visible text: the names of the windows, components and rows a snapshot would list. Call on the EDT. */
	static String text() {
		// it registers the refs it lists, and pilot_wait_for calls it every poll
		refs.values().removeIf(target -> target.component().get() == null);
		Page page = new Page(0, Integer.MAX_VALUE - 1, false);
		for (Window window : Window.getWindows()) {
			if (window.isShowing()) {
				page.add(0, "window " + quote(title(window)), new Target(window), "");
				children(window, 1, page);
			}
		}
		return page.out.toString();
	}

	/** " @x,y wxh" in screen coordinates, of the row for a row. */
	private static String screenBounds(Target target) {
		Rectangle r = screenRect(target);
		return r == null ? "" : " @" + r.x + "," + r.y + " " + r.width + "x" + r.height;
	}

	/** The bounds in screen coordinates, of the row for a row; null when not shown. */
	private static Rectangle screenRect(Target target) {
		Component component = target.component().get();
		if (component == null || !component.isShowing()) {
			return null;
		}
		Rectangle r = target.row() < 0 ? new Rectangle(0, 0, component.getWidth(), component.getHeight()) : rowBounds(component, target);
		if (r == null) {
			return null;
		}
		Point origin = component.getLocationOnScreen();
		return new Rectangle(origin.x + r.x, origin.y + r.y, r.width, r.height);
	}

	/**
	 * A row target's bounds in its owner's coordinates: a list or tree row's cell, or a Compose node's bounds (which
	 * Compose reports relative to its panel); null for a component or a row no longer shown. Call on the EDT.
	 */
	static Rectangle rowBounds(Component owner, Target target) {
		if (target.row() < 0) {
			return null;
		}
		if (isCompose(owner) && target.item().get() instanceof Accessible node) {
			AccessibleContext context = node.getAccessibleContext();
			AccessibleComponent shown = context == null ? null : context.getAccessibleComponent();
			return shown == null ? null : shown.getBounds();
		}
		return owner instanceof JList<?> list ? list.getCellBounds(target.row(), target.row())
				: owner instanceof JTree tree ? tree.getRowBounds(target.row()) : null;
	}

	/** Whether the component is a Compose for Desktop panel, whose content shows only through its accessible tree. */
	static boolean isCompose(Component component) {
		return component.getClass().getName().equals(COMPOSE_PANEL);
	}

	/** The component of {@code ref}, for a row its list or tree. Resolved on the EDT, which owns {@link #refs}. */
	static Component component(String ref) {
		return Edt.compute(() -> resolve(ref));
	}

	/** {@link #component}'s lookup; call on the EDT. */
	private static Component resolve(String ref) {
		Target target = refs.get(ref);
		Component component = target == null ? null : target.component().get();
		if (component == null) {
			throw new IllegalArgumentException("No component " + ref + "; take a new pilot_snapshot");
		}
		if (!component.isShowing()) {
			throw new IllegalArgumentException(ref + " is no longer shown; take a new pilot_snapshot");
		}
		return component;
	}

	/** The component of {@code ref} with its target, for a row checked to still show its item. */
	static Map.Entry<Component, Target> resolveRow(String ref) {
		// one lookup on the EDT, so the target and its component agree; resolve fails first when the ref is stale
		return Edt.compute(() -> {
			Component owner = resolve(ref);
			Target found = refs.get(ref);
			if (!found.rowCurrent()) {
				throw new IllegalArgumentException(ref + " no longer shows the same row; take a new pilot_snapshot");
			}
			return Map.entry(owner, found);
		});
	}

	private static void children(Container container, int depth, Page page) {
		for (Component child : container.getComponents()) {
			if (page.full) {
				return;
			}
			// getVisibleRect is empty when the component is scrolled out of its viewport or clipped by a parent
			if (!child.isShowing() || child.getWidth() == 0 || child.getHeight() == 0
					|| child instanceof JComponent jc && jc.getVisibleRect().isEmpty()) {
				continue;
			}
			String role = role(child);
			String name = name(child);
			boolean listed = !name.isEmpty() || !(child instanceof Container c && c.getComponentCount() > 0 && isGeneric(role));
			int childDepth = depth;
			if (listed) {
				page.add(depth, name.isEmpty() ? role : role + " " + quote(name), new Target(child), states(child));
				childDepth++;
				if (child instanceof JList<?> list) {
					listRows(list, childDepth, page);
				} else if (child instanceof JTree tree) {
					treeRows(tree, childDepth, page);
				}
			}
			if (isCompose(child)) {
				// its Swing children are only the canvas it paints on; its content is in its accessible tree
				composeNodes(child, child.getAccessibleContext(), childDepth, page, new int[1]);
			} else if (child instanceof Container c) {
				children(c, childDepth, page);
			}
		}
	}

	/**
	 * The showing nodes of a Compose panel's accessible tree, as rows of the panel numbered in walk order; unnamed
	 * generic nodes are flattened into their children, as unnamed Swing containers are.
	 */
	private static void composeNodes(Component panel, AccessibleContext context, int depth, Page page, int[] index) {
		if (context == null) {
			return;
		}
		for (int i = 0; i < context.getAccessibleChildrenCount() && !page.full; i++) {
			Accessible node = context.getAccessibleChild(i);
			AccessibleContext child = node == null ? null : node.getAccessibleContext();
			AccessibleStateSet set = child == null ? null : child.getAccessibleStateSet();
			Rectangle bounds = child == null || child.getAccessibleComponent() == null ? null : child.getAccessibleComponent().getBounds();
			if (child == null) {
				continue;
			}
			// some containers report no states or no size, yet hold showing nodes: walk them unlisted
			boolean showing = set != null && bounds != null && !bounds.isEmpty() && set.contains(AccessibleState.SHOWING);
			AccessibleRole accessibleRole = child.getAccessibleRole();
			String role = accessibleRole == null ? "unknown" : accessibleRole.toDisplayString(Locale.ENGLISH);
			String name = clean(child.getAccessibleName());
			int childDepth = depth;
			if (showing && (!name.isEmpty() || child.getAccessibleChildrenCount() == 0 || !isGeneric(role))) {
				page.add(depth, name.isEmpty() ? role : role + " " + quote(name),
						new Target(new WeakReference<>(panel), index[0]++, new WeakReference<>(node)), states(set));
				childDepth++;
			}
			composeNodes(panel, child, childDepth, page, index);
		}
	}

	private static String states(AccessibleStateSet set) {
		StringBuilder states = new StringBuilder();
		if (!set.contains(AccessibleState.ENABLED)) {
			states.append(" (disabled)");
		}
		if (set.contains(AccessibleState.CHECKED)) {
			states.append(" (checked)");
		}
		if (set.contains(AccessibleState.SELECTED)) {
			states.append(" (selected)");
		}
		if (set.contains(AccessibleState.FOCUSED)) {
			states.append(" (focused)");
		}
		return states.toString();
	}

	private static void listRows(JList<?> list, int depth, Page page) {
		int shownFirst = list.getFirstVisibleIndex(), shownLast = list.getLastVisibleIndex();
		int count = list.getModel().getSize();
		int[] range = rowRange(count, shownFirst, shownLast, page.allRows);
		for (int i = range[0]; i <= range[1] && !page.full; i++) {
			row(list, i, list.getModel().getElementAt(i), "listitem", listRowText(list, i), (list.isSelectedIndex(i) ? " (selected)" : "")
					+ offscreen(i, shownFirst, shownLast, page.allRows) + unlisted(i, range, count, page.allRows), depth, page);
		}
	}

	/**
	 * The first and last row to list, 0-based (first > last for none): the shown rows, at most {@link #MAX_ROWS}; with
	 * {@code all}, every row, or when there are more than {@link #MAX_ROWS}, that many around the shown ones.
	 */
	static int[] rowRange(int count, int shownFirst, int shownLast, boolean all) {
		if (!all || count <= MAX_ROWS) {
			return !all ? shownFirst < 0 ? new int[]{0, -1} : new int[]{shownFirst, Math.min(shownLast, shownFirst + MAX_ROWS - 1)}
					: new int[]{0, count - 1};
		}
		int shown = shownFirst < 0 ? 0 : Math.min(shownLast - shownFirst + 1, MAX_ROWS);
		int start = shownFirst < 0 ? 0 : shownFirst - (MAX_ROWS - shown) / 2;
		int first = Math.clamp(start, 0, count - MAX_ROWS);
		return new int[]{first, first + MAX_ROWS - 1};
	}

	/** " (offscreen)" for a row outside the shown ones, which only allRows lists. */
	private static String offscreen(int row, int shownFirst, int shownLast, boolean all) {
		return all && (shownFirst < 0 || row < shownFirst || row > shownLast) ? " (offscreen)" : "";
	}

	/** With allRows, on the first and last row listed, how many rows before or after them are not listed. */
	private static String unlisted(int row, int[] range, int count, boolean all) {
		if (!all) {
			return "";
		}
		return (row == range[0] && range[0] > 0 ? " (" + range[0] + " rows above not listed)" : "")
				+ (row == range[1] && range[1] < count - 1 ? " (" + (count - 1 - range[1]) + " rows below not listed)" : "");
	}

	/** The text a list shows in row {@code i}. Call on the EDT. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	static String listRowText(JList list, int i) {
		Object value = list.getModel().getElementAt(i);
		ListCellRenderer renderer = list.getCellRenderer();
		return renderer == null ? String.valueOf(value)
				: rendererText(renderer.getListCellRendererComponent(list, value, i, list.isSelectedIndex(i), false));
	}

	/** The text a tree shows in row {@code i}. Call on the EDT. */
	static String treeRowText(JTree tree, int i) {
		Object node = tree.getPathForRow(i).getLastPathComponent();
		boolean leaf = tree.getModel().isLeaf(node);
		return tree.getCellRenderer() == null ? tree.convertValueToText(node, tree.isRowSelected(i), tree.isExpanded(i), leaf, i, false)
				: rendererText(tree.getCellRenderer().getTreeCellRendererComponent(tree, node, tree.isRowSelected(i), tree.isExpanded(i), leaf, i, false));
	}

	private static void treeRows(JTree tree, int depth, Page page) {
		Rectangle visible = tree.getVisibleRect();
		int shownFirst = tree.getClosestRowForLocation(visible.x, visible.y);
		int shownLast = tree.getClosestRowForLocation(visible.x, visible.y + visible.height - 1);
		int count = tree.getRowCount();
		int[] range = rowRange(count, shownFirst, shownLast, page.allRows);
		int rootDepth = tree.isRootVisible() ? 1 : 2;
		for (int i = range[0]; i <= range[1] && !page.full; i++) {
			TreePath path = tree.getPathForRow(i);
			if (path == null) {
				continue;
			}
			Object node = path.getLastPathComponent();
			boolean leaf = tree.getModel().isLeaf(node);
			String text = treeRowText(tree, i);
			String states = (leaf ? "" : tree.isExpanded(i) ? " (expanded)" : " (collapsed)") + (tree.isRowSelected(i) ? " (selected)" : "")
					+ offscreen(i, shownFirst, shownLast, page.allRows) + unlisted(i, range, count, page.allRows);
			row(tree, i, node, "treeitem", text, states, depth + Math.max(path.getPathCount() - rootDepth, 0), page);
		}
	}

	private static void row(Component owner, int row, Object item, String role, String text, String states, int depth, Page page) {
		page.add(depth, role + " " + quote(text), new Target(new WeakReference<>(owner), row, new WeakReference<>(item)), states);
	}

	/** The text a cell renderer shows: its own name, else its children's joined, e.g. a panel of labels. */
	private static String rendererText(Component component) {
		String name = name(component);
		if (!name.isEmpty() || !(component instanceof Container container)) {
			return name;
		}
		StringBuilder text = new StringBuilder();
		for (Component child : container.getComponents()) {
			String part = rendererText(child);
			if (!part.isEmpty()) {
				text.append(text.isEmpty() ? "" : " ").append(part);
			}
		}
		return StringUtil.shortenTextWithEllipsis(text.toString(), MAX_TEXT, 0);
	}

	private static boolean isGeneric(String role) {
		return role.equals("panel") || role.equals("filler") || role.equals("layered pane") || role.equals("root pane")
				|| role.equals("viewport") || role.equals("unknown") || role.equals(simpleRole(null));
	}

	private static String role(Component component) {
		AccessibleContext context = component.getAccessibleContext();
		AccessibleRole role = context == null ? null : context.getAccessibleRole();
		return role == null ? simpleRole(component) : role.toDisplayString(Locale.ENGLISH);
	}

	private static String simpleRole(Component component) {
		return component == null ? "component" : component.getClass().getSimpleName();
	}

	private static String name(Component component) {
		String name = null;
		try {
			AccessibleContext context = component.getAccessibleContext();
			name = context == null ? null : context.getAccessibleName();
		} catch (RuntimeException ignored) {
			// some components fail to compute their accessible name outside of their usual state
		}
		if (StringUtil.isEmptyOrSpaces(name)) {
			if (component instanceof AbstractButton button) {
				name = StringUtil.defaultIfEmpty(button.getText(), button.getToolTipText());
			} else if (component instanceof JLabel label) {
				name = label.getText();
			} else if (component instanceof JTextComponent text) {
				name = text.getText();
			}
		}
		return clean(name);
	}

	/** A name without HTML, on one line, shortened. */
	private static String clean(String name) {
		return StringUtil.shortenTextWithEllipsis(StringUtil.removeHtmlTags(StringUtil.notNullize(name), true).strip().replaceAll("\\s+", " "), MAX_TEXT, 0);
	}

	private static String states(Component component) {
		StringBuilder states = new StringBuilder();
		if (!component.isEnabled()) {
			states.append(" (disabled)");
		}
		AccessibleContext context = component.getAccessibleContext();
		if (context != null && context.getAccessibleStateSet() != null) {
			if (context.getAccessibleStateSet().contains(AccessibleState.CHECKED)) {
				states.append(" (checked)");
			}
			if (context.getAccessibleStateSet().contains(AccessibleState.SELECTED)) {
				states.append(" (selected)");
			}
		}
		// toolbar toggles (ToggleAction) report their state only through their presentation, which may be stale (tool
		// window stripe buttons), so update a copy of it
		if (component instanceof ActionButton button && button.getAction() instanceof Toggleable) {
			states.append(toggled(button) ? " (on)" : " (off)");
		}
		if (component.isFocusOwner()) {
			states.append(" (focused)");
		}
		return states.toString();
	}

	private static boolean toggled(ActionButton button) {
		Presentation presentation = button.getPresentation().clone();
		try {
			ActionUtil.updateAction(button.getAction(), AnActionEvent.createEvent(
					DataManager.getInstance().getDataContext(button), presentation, "IdePilot", ActionUiKind.NONE, null));
		} catch (RuntimeException e) {
			return Toggleable.isSelected(button.getPresentation());
		}
		return Toggleable.isSelected(presentation);
	}

	private static String title(Window window) {
		if (window instanceof Frame frame) {
			return frame.getTitle();
		}
		if (window instanceof Dialog dialog) {
			return dialog.getTitle();
		}
		return window.getClass().getSimpleName();
	}

	private static String quote(String text) {
		return '"' + StringUtil.notNullize(text).replace("\"", "\\\"") + '"';
	}
}
