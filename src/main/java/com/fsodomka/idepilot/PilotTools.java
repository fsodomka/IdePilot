package com.fsodomka.idepilot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Base64;
import java.util.List;

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
						"format", "string", "tree (default): indented lines; toon: a TOON table lines[N]{depth,ref,role,"
								+ "name,states} (plus x,y,w,h with bounds), followed by nextOffset and next when there is more"),
				tool("pilot_click", "Click the component with the given ref from the last pilot_snapshot.", List.of("ref"),
						"ref", "string", REF,
						"button", "string", "left (default), middle or right; right opens context menus",
						"clickCount", "integer", "1 (default), 2 for a double-click, or 3",
						"modifiers", "string", "Keys held during the click, e.g. ctrl, shift, ctrl shift, alt, meta"),
				tool("pilot_window", "Bring a window (project frame, dialog, detached tool window) to the front, "
								+ "resize it, or close it as its close button would. Without ref, the active window.",
						List.of(),
						"ref", "string", "Ref of the window, or of any component in it",
						"size", "string", "Resize to WIDTHxHEIGHT, e.g. 1280x800; un-maximizes a maximized frame",
						"close", "boolean", "Close the window; returns at once, so follow with pilot_wait_for gone"),
				tool("pilot_type", "Type text and/or press a key. With ref, focuses that component first; otherwise "
								+ "goes to the focused component. Key events go through the IDE, so shortcuts run their actions.",
						List.of(), "ref", "string", REF,
						"text", "string", "Text to type, character by character",
						"key", "string", "Key to press after the text, e.g. Enter, Escape, Tab, Back_Space, Down, "
								+ "ctrl shift A, alt F7"),
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
						"consoles", "boolean", "Return the last lines of each Run/Debug console (and test runner "
								+ "output) of the open projects instead of the log; default false",
						"lines", "integer", "Number of lines, default 50",
						"contains", "string", "Only lines containing this text (case-insensitive)"),
				tool("pilot_action", "Run an IDE action by ID, e.g. ShowSettings, GotoFile, SearchEverywhere, "
								+ "ActivateProjectToolWindow, OpenFile, CloseProject. With ref, in that component's context "
								+ "(e.g. a tool window's selection); otherwise the focused component's. An unknown ID lists similar ones.",
						List.of("id"),
						"id", "string", "Action ID",
						"ref", "string", REF),
				tool("pilot_open", "Open a directory as a project, or a file in the editor (of the open project that "
								+ "contains it), optionally at a line. Returns at once; follow with pilot_wait_for.",
						List.of("path"),
						"path", "string", "Absolute path of a project directory or a file",
						"line", "integer", "1-based line to go to in a file"),
				tool("pilot_wait_for", "Wait until all the given conditions hold: text shows in the UI, text is gone, "
								+ "indexing or all background tasks have finished, text shows in a Run console, and/or time "
								+ "has passed. With text, returns the snapshot lines that contain it, with their refs.",
						List.of(),
						"text", "string", "Wait until this text shows in a window, component or row",
						"gone", "string", "Wait until this text no longer shows",
						"smart", "boolean", "Wait until no open project is opening or indexing",
						"idle", "boolean", "Wait until no background task runs (progress in the status bar: "
								+ "indexing, Gradle sync, builds, ...)",
						"console", "string", "Wait until this text shows in a Run/Debug console",
						"time", "number", "Wait at least this many seconds; alone, a plain pause",
						"timeout", "number", "Seconds before failing, default 10, or time + 10 with time"),
				tool("pilot_evaluate", "Run a Groovy script inside the IDE on the UI thread and return its result as text. "
								+ "Bindings: project (ref's project, else the focused or first open project), component (ref's component or null). "
								+ "Wrap changes in WriteCommandAction; reads may need ReadAction.",
						List.of("script"),
						"script", "string", "Groovy source; the value of the last expression is returned",
						"ref", "string", REF + "; also runs the script in that component's dialog",
						"timeout", "number", "Seconds to wait for the result, default 30"),
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
						format(string(arguments, "format")));
				case "pilot_action" -> IdeActions.run(arguments.get("id").getAsString(), string(arguments, "ref"));
				case "pilot_evaluate" -> Evaluator.eval(arguments.get("script").getAsString(), string(arguments, "ref"),
						arguments.has("timeout") ? arguments.get("timeout").getAsDouble() : 30);
				case "pilot_open" -> IdeActions.open(arguments.get("path").getAsString(),
						arguments.has("line") ? arguments.get("line").getAsInt() : null);
				case "pilot_wait_for" -> {
					try {
						double time = number(arguments, "time", 0);
						yield IdeActions.waitFor(new IdeActions.Wait(string(arguments, "text"), string(arguments, "gone"),
										bool(arguments, "smart"), bool(arguments, "idle"), string(arguments, "console"), time),
								number(arguments, "timeout", Math.max(10, time + 10)));
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException("Interrupted");
					}
				}
				case "pilot_window" -> UiWindow.window(string(arguments, "ref"), string(arguments, "size"), bool(arguments, "close"));
				case "pilot_click" -> UiClick.click(arguments.get("ref").getAsString(), string(arguments, "button"),
						integer(arguments, "clickCount", 1), string(arguments, "modifiers"));
				case "pilot_type" -> UiInput.type(string(arguments, "ref"), string(arguments, "text"), string(arguments, "key"));
				case "pilot_log" -> {
					String contains = string(arguments, "contains");
					String mode = bool(arguments, "notifications") ? "notifications" : bool(arguments, "output") ? "output"
							: bool(arguments, "consoles") ? "consoles" : bool(arguments, "freezes") ? "freezes"
							: bool(arguments, "errors") ? "errors" : "tail";
					yield switch (mode) {
						case "notifications" -> IdeLog.notifications(integer(arguments, "lines", 20), contains);
						case "output" -> IdeLog.output(integer(arguments, "lines", 50), contains);
						case "consoles" -> IdeLog.consoles(integer(arguments, "lines", 50), contains);
						case "freezes" -> IdeLog.freezes(integer(arguments, "lines", 5), contains);
						case "errors" -> IdeLog.errors(integer(arguments, "lines", 50), contains);
						default -> IdeLog.tail(integer(arguments, "lines", 50), contains);
					};
				}
				default -> throw new IllegalArgumentException("Unknown tool " + name);
			};
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

	private static boolean bool(JsonObject arguments, String name) {
		return arguments.has(name) && !arguments.get(name).isJsonNull() && arguments.get(name).getAsBoolean();
	}

	private static int integer(JsonObject arguments, String name, int fallback) {
		return arguments.has(name) && !arguments.get(name).isJsonNull() ? arguments.get(name).getAsInt() : fallback;
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
