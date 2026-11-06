package com.fsodomka.idepilot;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.Toggleable;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.util.text.StringUtil;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
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

		/** Whether a row target's row still shows its item; true for a component. Call on the EDT. */
		boolean rowCurrent() {
			if (row < 0) {
				return true;
			}
			Object expected = item.get();
			Component owner = component.get();
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
	static String snapshot(int offset, int limit, String next, boolean bounds, String find, boolean toon) {
		return Edt.compute(() -> {
			if (offset == 0) {
				refs.values().removeIf(target -> target.component().get() == null);
			}
			Page page = new Page(offset, Math.max(limit, 1), bounds, find, toon);
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
		private final List<String> rows = new ArrayList<>();

		void add(int depth, String text, Target target, String states) {
			if (find == null) {
				list(depth, text, target, states);
				return;
			}
			while (!path.isEmpty() && path.get(path.size() - 1).depth >= depth) {
				path.remove(path.size() - 1);
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
					out.append("  ".repeat(depth)).append("- ").append(text).append(" [ref=").append(ref).append(']').append(states).append(bounds ? screenBounds(target) : "").append('\n');
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
			if (out.length() == 0) {
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
		Rectangle r = target.row() < 0 ? new Rectangle(0, 0, component.getWidth(), component.getHeight())
				: component instanceof JList<?> list ? list.getCellBounds(target.row(), target.row())
				: component instanceof JTree tree ? tree.getRowBounds(target.row()) : null;
		if (r == null) {
			return null;
		}
		Point origin = component.getLocationOnScreen();
		return new Rectangle(origin.x + r.x, origin.y + r.y, r.width, r.height);
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
			if (child instanceof Container c) {
				children(c, childDepth, page);
			}
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void listRows(JList list, int depth, Page page) {
		int first = list.getFirstVisibleIndex();
		int last = Math.min(list.getLastVisibleIndex(), first + MAX_ROWS - 1);
		ListCellRenderer renderer = list.getCellRenderer();
		for (int i = Math.max(first, 0); first >= 0 && i <= last && !page.full; i++) {
			Object value = list.getModel().getElementAt(i);
			String text = renderer == null ? String.valueOf(value)
					: rendererText(renderer.getListCellRendererComponent(list, value, i, list.isSelectedIndex(i), false));
			row(list, i, value, "listitem", text, list.isSelectedIndex(i) ? " (selected)" : "", depth, page);
		}
	}

	private static void treeRows(JTree tree, int depth, Page page) {
		Rectangle visible = tree.getVisibleRect();
		int first = tree.getClosestRowForLocation(visible.x, visible.y);
		int last = Math.min(tree.getClosestRowForLocation(visible.x, visible.y + visible.height - 1), first + MAX_ROWS - 1);
		int rootDepth = tree.isRootVisible() ? 1 : 2;
		for (int i = Math.max(first, 0); first >= 0 && i <= last && !page.full; i++) {
			TreePath path = tree.getPathForRow(i);
			if (path == null) {
				continue;
			}
			Object node = path.getLastPathComponent();
			boolean leaf = tree.getModel().isLeaf(node);
			String text = tree.getCellRenderer() == null ? tree.convertValueToText(node, tree.isRowSelected(i), tree.isExpanded(i), leaf, i, false)
					: rendererText(tree.getCellRenderer().getTreeCellRendererComponent(tree, node, tree.isRowSelected(i), tree.isExpanded(i), leaf, i, false));
			String states = (leaf ? "" : tree.isExpanded(i) ? " (expanded)" : " (collapsed)") + (tree.isRowSelected(i) ? " (selected)" : "");
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
				text.append(text.length() == 0 ? "" : " ").append(part);
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
			ActionUtil.performDumbAwareUpdate(button.getAction(), AnActionEvent.createFromDataContext("IdePilot", presentation,
					DataManager.getInstance().getDataContext(button)), false);
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
