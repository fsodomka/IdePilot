# IDE Pilot

Lets AI agents see and drive a JetBrains IDE over MCP, the way the Playwright MCP drives a browser. Made for plugin
development and UI testing in the `runIde` sandbox.

## Tools

| Tool | What it does |
|---|---|
| `pilot_action` | Runs an IDE action by ID (`ShowSettings`, `GotoFile`), in a ref's context; an unknown ID lists similar ones. |
| `pilot_click` | Clicks the component or row with a ref; `button: right` opens context menus, `clickCount: 2` double-clicks, `modifiers` (`ctrl shift`) holds keys. |
| `pilot_evaluate` | Runs a Groovy script inside the IDE with `project` and `component` bound, and returns its result. |
| `pilot_log` | The tail of `idea.log` (filtered with `contains`), the IDE's caught exceptions (`errors`), the latest UI freezes with the UI thread's stack (`freezes`), notifications (`notifications`), the IDE's stdout/stderr since the plugin started (`output`), or the Run/Debug consoles' text (`consoles`). |
| `pilot_open` | Opens a directory as a project, or a file in the editor at a line. |
| `pilot_screenshot` | PNG of a component, or of the active window with its popups; `mode: screen` copies the screen pixels. |
| `pilot_snapshot` | The visible UI as a text tree: windows, components, list and tree rows, with role, name, state (focused, selected, checked, toolbar toggles on/off) and a ref to act on. Paged (`offset`, `limit`, `next`); `bounds` adds screen positions; `find` lists only matching lines under their ancestors; `format: toon` returns a [TOON](https://github.com/toon-format/toon) table instead. |
| `pilot_type` | Types text and/or presses a key (`Enter`, `ctrl shift A`) into a ref or the focused component; shortcuts run their actions. |
| `pilot_window` | Brings a window (project frame, dialog, detached tool window) to the front, resizes it (`size: 1280x800`), or closes it (`close`) as its close button would. |
| `pilot_wait_for` | Waits until text shows (returning the matching lines with their refs), text is gone, projects have opened and finished indexing (`smart`), no background task runs (`idle`), a Run console shows text (`console`), and/or `time` seconds have passed. |

Refs stay with their component: a stale ref fails rather than clicking something else. Clicks, keys and actions
return at once; follow them with `pilot_wait_for`.

## Setup

1. Install the plugin into the IDE (or sandbox) to drive.
2. *Settings | Tools | IDE Pilot*: click **Generate** and copy the shown command, e.g.
   `claude mcp add --scope user --transport http idepilot http://127.0.0.1:63400/mcp --header "Authorization: Bearer <token>"`.
   Without a token the server is off; Generate replaces the token, so agents with the old one get 401.
3. For sandboxes, set `idePilotToken=<token>` in `~/.gradle/gradle.properties`: `runIde` passes it as
   `-Didepilot.token`, which overrides the settings, so new sandboxes need no Generate. `-Didepilot.port` overrides the
   port (default 63400).

The server listens on localhost only, and every request needs the token, since `pilot_evaluate` runs code in the IDE.

## Build

`./gradlew buildPlugin` builds `build/distributions/IdePilot-<version>.zip`; `./gradlew runIde` runs a sandbox with it.
