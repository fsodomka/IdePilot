package com.fsodomka.idepilot;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleComponent;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleState;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTree;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** The refs snapshots hand out: each component's or row's ref, and its lookup. Owned by the EDT. */
final class UiRefs {
	/** Each component's ref, kept while the component lives. */
	private static final Map<Component, String> ids = new WeakHashMap<>();
	private static final Map<String, Target> refs = new HashMap<>();
	private static int lastId;

	private UiRefs() {
	}

	/** Makes {@code ref} resolve to {@code target}. Call on the EDT. */
	static void register(String ref, Target target) {
		refs.put(ref, target);
	}

	/** Forgets the refs whose component is gone. Call on the EDT. */
	static void prune() {
		refs.values().removeIf(target -> target.component().get() == null);
	}

	static String ref(Target target) {
		Component component = target.component().get();
		String id = ids.computeIfAbsent(component, c -> "e" + ++lastId);
		return target.row() < 0 ? id : id + "." + target.row();
	}

	/** " @x,y wxh" in screen coordinates, of the row for a row. */
	static String screenBounds(Target target) {
		Rectangle r = screenRect(target);
		return r == null ? "" : " @" + r.x + "," + r.y + " " + r.width + "x" + r.height;
	}

	/** The bounds in screen coordinates, of the row for a row; null when not shown. */
	static Rectangle screenRect(Target target) {
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
		if (UiSnapshot.isCompose(owner) && target.item().get() instanceof Accessible node) {
			AccessibleContext context = node.getAccessibleContext();
			AccessibleComponent shown = context == null ? null : context.getAccessibleComponent();
			return shown == null ? null : shown.getBounds();
		}
		return owner instanceof JList<?> list ? list.getCellBounds(target.row(), target.row())
				: owner instanceof JTree tree ? tree.getRowBounds(target.row())
				: owner instanceof JTable table && target.row() < table.getRowCount() && table.getColumnCount() > 0
				? table.getCellRect(target.row(), 0, true).union(table.getCellRect(target.row(), table.getColumnCount() - 1, true)) : null;
	}

	/** The component of {@code ref}, for a row its list or tree. Resolved on the EDT, which owns {@link #refs}. */
	static Component component(String ref) {
		return Edt.compute(() -> resolve(ref));
	}

	/** {@link #component}'s lookup; call on the EDT. */
	private static Component resolve(String ref) {
		if (ref == null) {
			throw new IllegalArgumentException("ref is required");
		}
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

	/**
	 * What a ref points at: a component, or with {@code row} >= 0 a row of a list, tree or table, whose {@code item} (the
	 * list value or tree node) or, for a table, {@code text} (the row's rendered text, held strongly, as table values are
	 * often made on each call) tells whether the row still shows it.
	 */
	record Target(WeakReference<Component> component, int row, WeakReference<Object> item, String text) {
		Target(Component component) {
			this(new WeakReference<>(component), -1, null, null);
		}

		/** Whether a row target's row still shows its item (a Compose node: is still showing); true for a component. Call on the EDT. */
		boolean rowCurrent() {
			if (row < 0) {
				return true;
			}
			Component owner = component.get();
			if (owner instanceof JTable table) {
				return row < table.getRowCount() && UiSnapshot.tableRowText(table, row).equals(text);
			}
			Object expected = item.get();
			if (owner != null && UiSnapshot.isCompose(owner) && expected instanceof Accessible node) {
				AccessibleContext context = node.getAccessibleContext();
				return context != null && context.getAccessibleStateSet() != null
					    && context.getAccessibleStateSet().contains(AccessibleState.SHOWING);
			}
			Object actual = owner instanceof JList<?> list ? row < list.getModel().getSize() ? list.getModel().getElementAt(row) : null
					: owner instanceof JTree tree && tree.getPathForRow(row) != null ? tree.getPathForRow(row).getLastPathComponent() : null;
			return expected != null && expected.equals(actual);
		}
	}
}
