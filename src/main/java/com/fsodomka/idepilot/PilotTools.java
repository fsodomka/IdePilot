package com.fsodomka.idepilot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Base64;
import java.util.List;
import java.util.Set;

/** The MCP tools: their schemas for tools/list and their calls for tools/call. */
final class PilotTools {
	private static final Gson GSON = new Gson();

	private PilotTools() {
	}

	/** The tools/list result. */
	static JsonObject list() {
		JsonObject result = new JsonObject();
		result.add("tools", GSON.toJsonTree(List.of(
				tool("pilot_snapshot", "Snapshot of the IDE's visible UI: windows and their components with role, name, "
						+ "state and a ref to act on. Long snapshots are paged: the last line gives the offset of the next page. "
						+ "Refs stay with their component, so a stale ref fails rather than hitting another component.", List.of(),
						"offset", "integer", "Lines to skip, as given by the previous page; default 0",
						"limit", "integer", "Lines per page, default 150",
						"next", "string", "The next= ref from the previous page; fails if the UI changed so the page would not continue it",
						"bounds", "boolean", "Add each line's screen bounds as @x,y wxh; default false",
						"find", "string", "Only lines whose role, name or state contains this text (case-insensitive), "
								+ "each under its ancestors (window, panels, tree) for context; pass it again for the next page",
						"allRows", "boolean", "List every row of lists and trees (popups such as Search Everywhere and "
								+ "completion included), not only those that show: at most 200 each, around those that show (the "
								+ "first and last listed say how many more are not); those that do not show are marked "
								+ "(offscreen), and clicking one scrolls it into view first; default false",
						"format", "string", "tree (default): indented lines; toon: a TOON table lines[N]{depth,ref,role,"
								+ "name,states} (plus x,y,w,h with bounds), followed by nextOffset and next when there is more"),
				tool("pilot_click", "Click the component with the given ref from the last pilot_snapshot; or hover "
								+ "over it, or drag it onto another ref. A click returns once its handlers ran; popups and dialogs "
								+ "that load in the background may show later: pilot_wait_for them before pilot_snapshot.", List.of("ref"),
						"ref", "string", REF,
						"button", "string", "left (default), middle or right; right opens context menus",
						"clickCount", "integer", "1 (default), 2 for a double-click, or 3",
						"modifiers", "string", "Keys held during the click, e.g. ctrl, shift, ctrl shift, alt, meta",
						"hover", "boolean", "Move the mouse onto ref instead of clicking, to show tooltips and hover "
								+ "popups; follow with pilot_wait_for, as they show after a delay",
						"to", "string", "Drag ref onto this ref instead of clicking (IDE drags such as tool window "
								+ "buttons and editor tabs; not native drag and drop)"),
				tool("pilot_scroll", "Scroll the scroll pane that holds ref (or that ref is) by pages or to its top or "
								+ "bottom; or scroll a list, tree or table at ref until the first row containing some text "
								+ "shows. Snapshots list only the rows that show, so scroll to reach the others.",
						List.of("ref"),
						"ref", "string", "Ref from pilot_snapshot of a scroll pane, a list, tree or table, or any component in a scroll pane",
						"direction", "string", "up, down, left, right, top or bottom",
						"pages", "number", "How many visible heights (or widths) to scroll with up, down, left or right; default 1",
						"to", "string", "Instead of direction, scroll to the first row whose text contains this (case-insensitive); "
								+ "trees: only expanded rows"),
				tool("pilot_window", "Bring a window (project frame, dialog, detached tool window) to the front, "
								+ "resize it, or close it as its close button would. Without ref, the active window.",
						List.of(),
						"ref", "string", "Ref of the window, or of any component in it",
						"size", "string", "Resize to WIDTHxHEIGHT, e.g. 1280x800; un-maximizes a maximized frame",
						"close", "boolean", "Close the window; returns at once, so follow with pilot_wait_for gone"),
				tool("pilot_type", "Type text and/or press a key. With ref, focuses that component first; otherwise "
								+ "goes to the focused component. Key events go through the IDE, so shortcuts run their actions. "
								+ "With a non-editable combo box ref, text selects the item shown with that text.",
						List.of(), "ref", "string", REF,
						"text", "string", "Text to type, character by character, without newlines or tabs (press them with key); or the combo box item to select",
						"clear", "boolean", "Select all and delete before typing, so text replaces the content "
								+ "(a whole document in an editor); default false",
						"key", "string", "Key to press after the text, e.g. Enter, Escape, Tab, Back_Space, Down, "
								+ "ctrl shift A, alt F7",
						"hold", "string", "Modifier keys to press first and keep down, e.g. ctrl or ctrl shift: later "
								+ "keys and clicks carry them until release, e.g. for Switcher (hold ctrl, key Tab, release ctrl)",
						"release", "string", "Modifier keys held by hold to release last, e.g. ctrl"),
				tool("pilot_editor", "Read an editor: file, caret, selection and numbered text; its problems (errors, "
								+ "warnings); the Alt+Enter actions (quick fixes, intentions) at the caret, or apply one. "
								+ "Without ref, the selected editor of the focused project. Without options, the whole text. "
								+ "The IDE sees files changed outside it only when its window is activated: after writing files "
								+ "outside the IDE, run pilot_action Synchronize first.",
						List.of(),
						"ref", "string", "Ref of an editor (text \"Editor for ...\" in the snapshot)",
						"lines", "string", "Line range to show, N or N-M (1-based); at most 300 lines at once",
						"caret", "string", "Move the caret to LINE or LINE:COLUMN (1-based) first, e.g. to a problem",
						"problems", "boolean", "List the highlighted problems as LINE:COLUMN SEVERITY: message; "
								+ "pilot_wait_for idle first, as analysis runs after edits",
						"actions", "boolean", "List the Alt+Enter actions at the caret",
						"apply", "string", "Apply the Alt+Enter action at the caret with this text (as actions lists it); "
								+ "returns at once, as it may open a dialog"),
				tool("pilot_log", "The last lines of the IDE's idea.log, or with errors the exceptions the IDE caught "
								+ "(the red balloon in the status bar), newest last.", List.of(),
						"errors", "boolean", "Return the IDE's caught exceptions instead of the log; default false",
						"freezes", "boolean", "Return the newest UI freezes (threadDumps-freeze-* folders: date, time, "
								+ "culprit, duration, and the UI thread's stack) instead of the log; lines is then the "
								+ "number of freezes; default false",
						"notifications", "boolean", "Return the notifications (balloons and the Notifications tool "
								+ "window) of the open projects and the IDE, oldest first, instead of the log; lines is then "
								+ "the number of notifications; default false",
						"output", "boolean", "Return what the IDE process wrote to stdout and stderr since the plugin "
								+ "started (stderr lines start with [err]) instead of the log; default false",
						"consoles", "boolean", "Return the last lines of each Run/Debug console (and test runner output) and Terminal tab (titled "
								+ "Terminal: TAB) of the open projects, each under its tab's name and state as sessions gives it, "
								+ "instead of the log; default false",
						"sessions", "boolean", "Return one line per Run/Debug tab instead of the log: name, mode (Run, "
								+ "Debug, ...), running, paused, stopping or terminated with exit code, and the process ID and "
								+ "start time; Terminal tabs as Terminal: TAB (Terminal tab, running, exited or, for the reworked "
								+ "terminal, state unknown, which running leaves out); default false",
						"tests", "boolean", "Return the results of each test run tab instead of the log: a summary (passed, "
								+ "failed, ignored, running, duration), then the failed tests with message and stack, or with "
								+ "contains the tests of any state whose name (Suite.test) contains it; lines is then the most "
								+ "tests per tab; default false",
						"session", "string", "With consoles, sessions or tests, only the tabs whose name contains this (case-insensitive)",
						"running", "boolean", "With consoles, sessions or tests, only those whose process still runs; default false",
						"lines", "integer", "Number of lines, default 50",
						"contains", "string", "Only lines containing this text (case-insensitive)"),
				tool("pilot_action", "Run an IDE action by ID, e.g. ShowSettings, GotoFile, SearchEverywhere, "
								+ "ActivateProjectToolWindow, OpenFile, CloseProject. With ref, in that component's context "
								+ "(e.g. a tool window's selection); otherwise the focused component's. An unknown ID lists similar ones. "
								+ "Then watches the UI for wait seconds and returns what showed meanwhile (hints, popups, dialogs, as "
								+ "snapshot lines with refs, those gone again marked so) and the notifications fired. "
								+ "With find instead of id, lists matching actions without running any.",
						List.of(),
						"id", "string", "Action ID to run",
						"ref", "string", REF,
						"wait", "number", "Seconds to watch for what the action shows, default 1; more for slow results "
								+ "(e.g. 10 for Hotswap, which builds first), 0 to return at once; at most " + MAX,
						"find", "string", "List the actions whose ID or menu text contains this (case-insensitive), "
								+ "as ID – text – shortcut lines; groups are marked",
						"limit", "integer", "Most actions find lists, default 50"),
				tool("pilot_settings", "Effective values of IDE and project settings, defaults included (files hold only "
								+ "changed values): registry keys, Advanced Settings (by ID or title), and the persisted state of "
								+ "the IDE's and open projects' services, as SCOPE FILE Component.property = value, marked "
								+ "(default) or (changed) where known. Searches the services already created, and creates those "
								+ "whose class or state name contains find (of 4 or more characters). Settings page labels are not searched: find by "
								+ "property name words, e.g. hotswap.",
						List.of("find"),
						"find", "string", "Text the setting's line contains (case-insensitive): key, component, property or value",
						"limit", "integer", "Most lines, default 50"),
				tool("pilot_open", "Open a directory as a project, or a file in the editor (of the open project that "
								+ "contains it), optionally at a line. Returns at once; follow with pilot_wait_for.",
						List.of("path"),
						"path", "string", "Absolute path of a project directory or a file",
						"line", "integer", "1-based line to go to in a file"),
				tool("pilot_wait_for", "Wait until all the given conditions hold: text shows in the UI, text is gone, "
								+ "indexing or all background tasks have finished, text shows in a Run console or Terminal tab, and/or time "
								+ "has passed. With text, returns the snapshot lines that contain it, with their refs.",
						List.of(),
						"text", "string", "Wait until this text shows in a window, component or row",
						"gone", "string", "Wait until this text no longer shows",
						"smart", "boolean", "Wait until no open project is opening or indexing",
						"idle", "boolean", "Wait until no background task runs (progress in the status bar: "
								+ "indexing, Gradle sync, builds, ...)",
						"console", "string", "Wait until this text shows in a Run/Debug console or Terminal tab, in output written since the "
								+ "last pilot call that acted (pilot_action, pilot_click, pilot_type, pilot_evaluate, pilot_open, "
								+ "pilot_editor apply, pilot_window), so a previous run's output does not count; a run "
								+ "started since the call before that counts in full",
						"session", "string", "With console, only the Run/Debug tabs whose name contains this (case-insensitive); "
								+ "Terminal for the Terminal tabs",
						"time", "number", "Wait at least this many seconds; alone, a plain pause; at most " + MAX,
						"timeout", "number", "Seconds before failing, default 10, or time + 10 with time; capped at " + MAX
								+ " (MCP clients give up on a call after 60 s), so wait longer in several calls"),
				tool("pilot_evaluate", "Run a Groovy script inside the IDE on the UI thread and return its result as text. "
								+ "Bindings: project (ref's project, else the focused or first open project), component (ref's component or null). "
								+ "Wrap changes in WriteCommandAction; reads may need ReadAction.",
						List.of("script"),
						"script", "string", "Groovy source; the value of the last expression is returned",
						"ref", "string", REF + "; also runs the script in that component's dialog",
						"timeout", "number", "Seconds to wait for the result, default 30, at most " + MAX),
				tool("pilot_screenshot", "PNG of a component, or without ref of the active frame or dialog with its open popups "
								+ "(Search Everywhere, completion, menus) and child dialogs. Painted by default, so it works while "
								+ "the IDE is covered; popups are clipped to the window.", List.of(),
						"ref", "string", REF,
						"path", "string", "Also save the PNG to this file",
						"popups", "boolean", "Paint the window's popups and child dialogs over it; default true",
						"mode", "string", "paint (default) or screen: copy the screen pixels at full resolution, exactly as "
								+ "shown, including popups beyond the window; only correct when nothing covers the IDE"))));
return result;
	}

	static JsonObject call(String name, JsonObject arguments) {
		String text;
		boolean error = false;
		JsonArray contents = new JsonArray();
		if (acting(name, arguments)) {
			RunConsoles.markConsoles(); // pilot_wait_for console then sees only the output that came after
		}
		try {
			text = switch (name) {
				case "pilot_screenshot" -> {
					Screenshot.Shot shot = Screenshot.take(string(arguments, "ref"), string(arguments, "path"),
							!arguments.has("popups") || bool(arguments, "popups"), "screen".equals(string(arguments, "mode")));
					JsonObject image = new JsonObject();
					image.addProperty("type", "image");
					image.addProperty("data", Base64.getEncoder().encodeToString(shot.png()));
					image.addProperty("mimeType", "image/png");
					contents.add(image);
					yield shot.description();
				}
				case "pilot_snapshot" -> UiSnapshot.snapshot(integer(arguments, "offset", 0), integer(arguments, "limit", 150), string(arguments, "next"), bool(arguments, "bounds"), string(arguments, "find"),
						format(string(arguments, "format")), bool(arguments, "allRows"));
				case "pilot_action" -> {
					String id = string(arguments, "id");
					String find = string(arguments, "find");
					if ((id == null) == (find == null)) {
						throw new IllegalArgumentException("Give id to run an action, or find to search for one");
					}
					if (id != null) {
						yield IdeActions.run(id, string(arguments, "ref"), Math.min(number(arguments, "wait", 1), IdeActions.MAX_SECONDS));
					}
					yield IdeActions.find(find, limit(arguments));
				}
				case "pilot_settings" -> {
					int limit = limit(arguments); // checked before find, as before
					yield IdeSettings.find(arguments.get("find").getAsString(), limit);
				}
				case "pilot_evaluate" -> Evaluator.eval(arguments.get("script").getAsString(), string(arguments, "ref"),
						Math.min(number(arguments, "timeout", 30), IdeActions.MAX_SECONDS));
				case "pilot_open" -> IdeActions.open(arguments.get("path").getAsString(),
						arguments.has("line") ? arguments.get("line").getAsInt() : null);
				case "pilot_wait_for" -> {
					double time = number(arguments, "time", 0);
					yield IdeActions.waitFor(new IdeActions.Wait(string(arguments, "text"), string(arguments, "gone"),
									bool(arguments, "smart"), bool(arguments, "idle"), string(arguments, "console"),
									string(arguments, "session"), time),
							number(arguments, "timeout", Math.max(10, time + 10)));
				}
				case "pilot_scroll" -> UiScroll.scroll(string(arguments, "ref"), string(arguments, "direction"),
						number(arguments, "pages", 1), string(arguments, "to"));
				case "pilot_window" -> UiWindow.window(string(arguments, "ref"), string(arguments, "size"), bool(arguments, "close"));
				case "pilot_click" -> {
					String ref = arguments.get("ref").getAsString();
					String to = string(arguments, "to");
					if (bool(arguments, "hover")) {
						if (to != null || arguments.has("clickCount") || arguments.has("button")) {
							throw new IllegalArgumentException("hover takes only ref and modifiers");
						}
						yield UiClick.hover(ref, string(arguments, "modifiers"));
					}
					if (to != null) {
						if (arguments.has("clickCount")) {
							throw new IllegalArgumentException("to (drag) does not take clickCount");
						}
						yield UiClick.drag(ref, to, string(arguments, "button"), string(arguments, "modifiers"));
					}
					yield UiClick.click(ref, string(arguments, "button"), integer(arguments, "clickCount", 1),
							string(arguments, "modifiers"));
				}
				case "pilot_editor" -> UiEditor.editor(new UiEditor.Request(string(arguments, "ref"), string(arguments, "lines"),
						string(arguments, "caret"), bool(arguments, "problems"), bool(arguments, "actions"), string(arguments, "apply")));
				case "pilot_type" -> UiInput.type(string(arguments, "ref"), string(arguments, "text"), string(arguments, "key"),
						bool(arguments, "clear"), string(arguments, "hold"), string(arguments, "release"));
				case "pilot_log" -> {
					String contains = string(arguments, "contains");
					String mode = bool(arguments, "notifications") ? "notifications" : bool(arguments, "output") ? "output"
							: bool(arguments, "consoles") ? "consoles" : bool(arguments, "sessions") ? "sessions" : bool(arguments, "tests") ? "tests" : bool(arguments, "freezes") ? "freezes"
							: bool(arguments, "errors") ? "errors" : "tail";
					yield switch (mode) {
						case "notifications" -> IdeLog.notifications(integer(arguments, "lines", 20), contains);
						case "output" -> IdeLog.output(integer(arguments, "lines", 50), contains);
						case "consoles" -> RunConsoles.consoles(integer(arguments, "lines", 50), contains, string(arguments, "session"),
								bool(arguments, "running"));
						case "sessions" -> RunConsoles.sessions(string(arguments, "session"), bool(arguments, "running"));
						case "tests" -> TestResults.tests(integer(arguments, "lines", 50), contains, string(arguments, "session"),
								bool(arguments, "running"));
							case "freezes" -> IdeLog.freezes(integer(arguments, "lines", 5), contains);
						case "errors" -> IdeLog.errors(integer(arguments, "lines", 50), contains);
						default -> IdeLog.tail(integer(arguments, "lines", 50), contains);
					};
				}
				default -> throw new IllegalArgumentException("Unknown tool " + name);
			};
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			text = "Interrupted";
			error = true;
		} catch (RuntimeException e) {
			text = e.getMessage() != null ? e.getMessage() : e.toString();
			error = true;
		}
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", text);
		contents.add(content);
		JsonObject result = new JsonObject();
		result.add("content", contents);
		result.addProperty("isError", error);
		return result;
	}

	private static final String REF = "Ref from pilot_snapshot, e.g. e12";
	private static final String MAX = (int) IdeActions.MAX_SECONDS + " s";
	/** The tools that may start a run; each marks where the consoles' output ends for pilot_wait_for console. */
	private static final Set<String> ACTING = Set.of("pilot_action", "pilot_click", "pilot_type", "pilot_evaluate",
			"pilot_open", "pilot_editor", "pilot_window");

	/** Whether the call may start a run: an acting tool, except pilot_action find and pilot_editor without apply, which only read. */
	private static boolean acting(String name, JsonObject arguments) {
		return switch (name) {
			case "pilot_action" -> string(arguments, "find") == null;
			case "pilot_editor" -> string(arguments, "apply") != null;
			default -> ACTING.contains(name);
		};
	}

	private static boolean bool(JsonObject arguments, String name) {
		return arguments.has(name) && !arguments.get(name).isJsonNull() && arguments.get(name).getAsBoolean();
	}

	private static int integer(JsonObject arguments, String name, int fallback) {
		return arguments.has(name) && !arguments.get(name).isJsonNull() ? arguments.get(name).getAsInt() : fallback;
	}

	/** The limit argument of find-like calls, default 50; fails below 1. */
	private static int limit(JsonObject arguments) {
		int limit = integer(arguments, "limit", 50);
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be at least 1");
		}
		return limit;
	}

	/** Whether pilot_snapshot's format is toon rather than the default tree. */
	private static boolean format(String format) {
		if (format == null || format.equals("tree")) {
			return false;
		}
		if (format.equals("toon")) {
			return true;
		}
		throw new IllegalArgumentException("Unknown format " + format + "; use tree or toon");
	}

	private static double number(JsonObject arguments, String name, double fallback) {
		return arguments.has(name) && !arguments.get(name).isJsonNull() ? arguments.get(name).getAsDouble() : fallback;
	}

	private static String string(JsonObject arguments, String name) {
		return arguments.has(name) && !arguments.get(name).isJsonNull() ? arguments.get(name).getAsString() : null;
	}

	/** A tool with its input properties given as name, type, description triples. */
	private static JsonObject tool(String name, String description, List<String> required, String... properties) {
		JsonObject props = new JsonObject();
		for (int i = 0; i < properties.length; i += 3) {
			JsonObject property = new JsonObject();
			property.addProperty("type", properties[i + 1]);
			property.addProperty("description", properties[i + 2]);
			props.add(properties[i], property);
		}
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", props);
		if (!required.isEmpty()) {
			schema.add("required", GSON.toJsonTree(required));
		}
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		tool.addProperty("description", description);
		tool.add("inputSchema", schema);
		return tool;
	}
}
