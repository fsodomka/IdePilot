package com.fsodomka.idepilot;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One page of snapshot lines; {@code full} once a line past the page was reached, whose ref is {@code nextRef}. */
final class SnapshotPage {
	private static final String PUSH_BUTTON = "push button";

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
		final UiRefs.Target target;
		final String states;
		boolean listed;

		Line(int depth, String text, UiRefs.Target target, String states) {
			this.depth = depth;
			this.text = text;
			this.target = target;
			this.states = states;
		}
	}

	SnapshotPage(int offset, int limit, boolean bounds) {
		this(offset, limit, bounds, null);
	}

	SnapshotPage(int offset, int limit, boolean bounds, String find) {
		this(offset, limit, bounds, find, false);
	}

	SnapshotPage(int offset, int limit, boolean bounds, String find, boolean toon) {
		this.offset = offset;
		this.limit = limit;
		this.bounds = bounds;
		this.find = find == null || find.isEmpty() ? null : find.toLowerCase(Locale.ROOT);
		this.toon = toon;
	}

	/** With toon, the page is a TOON table of lines instead of an indented tree. */
	final boolean toon;
	/** List every row of lists and trees (at most {@link UiSnapshot#MAX_ROWS}), not only those that show. */
	boolean allRows;
	/** Add each line's class, and list the unnamed containers of plugin classes that are otherwise flattened. */
	boolean classes;
	/** Whether the window being walked is a modal dialog: with find, its buttons are listed once a line in it matched. */
	boolean modal;
	private final List<String> rows = new ArrayList<>();

	void add(int depth, String text, UiRefs.Target target, String states) {
		if (find == null) {
			list(depth, text, target, states);
			return;
		}
		while (!path.isEmpty() && path.getLast().depth >= depth) {
			path.removeLast();
		}
		Line line = new Line(depth, text, target, states);
		path.add(line);
		// in a modal dialog with a match, its buttons too: they are what one acts on (those after the match)
		boolean button = modal && path.getFirst().listed && text.startsWith(PUSH_BUTTON);
		if (button || (text + states).toLowerCase(Locale.ROOT).contains(find)) {
			for (Line l : path) {
				if (!l.listed) {
					l.listed = true;
					list(l.depth, l.text, l.target, l.states);
				}
			}
		}
	}

	private void list(int depth, String text, UiRefs.Target target, String states) {
		if (full) {
			return;
		}
		if (++count > offset + limit) {
			full = true;
			nextRef = UiRefs.ref(target);
			return;
		}
		if (count > offset) {
			String ref = UiRefs.ref(target);
			UiRefs.register(ref, target);
			if (first == null) {
				first = ref;
			}
			if (toon) {
				rows.add(toonRow(depth, ref, text, states, bounds ? UiRefs.screenRect(target) : null));
			} else {
				out.repeat("  ", depth).append("- ").append(text).append(" [ref=").append(ref).append(']').append(states).append(bounds ? UiRefs.screenBounds(target) : "").append('\n');
			}
		}
	}

	/** depth,ref,role,name,states[,x,y,w,h]; text is role or role "name", as {@link UiSnapshot#quote} writes it. */
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
