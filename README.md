# IDE Pilot

**Playwright MCP for JetBrains IDEs.** IDE Pilot is a Model Context Protocol (MCP) server inside IntelliJ IDEA,
PyCharm, WebStorm, GoLand and other JetBrains IDEs that lets AI agents (Claude Code, Codex, Gemini CLI, Cursor) see
and use the IDE's UI the way a user does. It reads windows, dialogs, tool windows, trees, tables and editors as a text
tree with refs. It clicks, types, presses shortcuts and runs actions. It reads logs, consoles, test results, freezes
and settings.

Use it to:
- test a plugin's UI end to end in the `runIde` sandbox, and let the agent reload its own changes
- reproduce a bug report step by step and capture what the IDE showed
- check that dialogs, settings pages, inspections and quick fixes behave as intended
- run tests or a debug session and read the failures and console output

IDE Pilot drives the **UI**. Code-level MCP servers, such as JetBrains' built-in one, navigate and edit code; the two
work well side by side.

## Tools

| Tool | What it does |
|---|---|
| `pilot_action` | Runs an IDE action by ID (`ShowSettings`, `GotoFile`), in a ref's context; an unknown ID lists similar ones. Then watches the UI for `wait` seconds (default 1) and returns what showed meanwhile (hints, popups, dialogs, with refs) and the notifications fired, e.g. HotSwap's result with `id: Hotswap, wait: 10`. `find` lists actions by ID or menu text, with shortcuts. |
| `pilot_click` | Clicks the component or row with a ref; `button: right` opens context menus, `clickCount: 2` double-clicks, `modifiers` (`ctrl shift`) holds keys; `hover: true` shows tooltips and hover popups, `to: <ref>` drags onto another ref. `wait` (seconds, default 0) then watches the UI and returns what showed meanwhile, as `pilot_action` does, e.g. the context menu of a right-click or the Debug tool window of a Debug button. |
| `pilot_editor` | Reads an editor: file, caret, selection and numbered text (`lines`); `problems` lists its errors and warnings; `actions` lists the Alt+Enter actions at the caret (`caret: 12:5` moves it and focuses the editor, so later actions run there), and `apply` runs one by name, at its problem when that is elsewhere on the line. |
| `pilot_evaluate` | Runs a Groovy script inside the IDE with `project` and `component` bound, and returns its result. |
| `pilot_log` | What the IDE and its runs report: the tail of `idea.log` (filtered with `contains`), the IDE's caught exceptions (`errors`), the latest UI freezes with the UI thread's stack (`freezes`), notifications (`notifications`), the IDE's stdout/stderr since the plugin started (`output`), the Run/Debug sessions (`sessions`: mode, running, paused or terminated with exit code, process ID and start time), or their consoles' text (`consoles`, Terminal tabs included as `Terminal: <tab>`), or test run results (`tests`: counts, then failed tests with message and stack, or tests matching `contains`); pick tabs by name with `session`, or only `running` ones. |
| `pilot_open` | Opens a directory as a project, or a file in the editor at a line. |
| `pilot_screenshot` | PNG of a component, or of the active window with its popups; `mode: screen` copies the screen pixels. |
| `pilot_scroll` | Scrolls the scroll pane holding a ref `direction` `up`/`down`/`left`/`right` by `pages` (default 1), or to its `top`/`bottom`; or with `to`, a list, tree or table until the first row containing that text shows. Snapshots list only the rows that show, so this reaches the others. |
| `pilot_settings` | Effective values of settings, defaults included: registry keys by a part of their key or value, an Advanced Setting by its ID, and the persisted state of a service of the IDE or the open projects by its class name, e.g. `com.intellij.ide.GeneralSettings`, the platform's only (per-client ones such as the editor's settings included). |
| `pilot_snapshot` | The visible UI as a text tree: windows, components, list, tree and table rows (a table row as its cells joined by ` \| `), Compose (Jewel) UI from its accessibility tree, with role, name, state (focused, selected, checked, toolbar toggles on/off; windows active, maximized, modal or indexing; tool windows with ID, anchor and type) and a ref to act on. Paged (`offset`, `limit`, `next`); `bounds` adds screen positions; `find` lists only matching lines under their ancestors; `allRows` lists every row of lists, trees and tables (popups such as Search Everywhere and completion included, at most 200 each), marking those that don't show `(offscreen)`, which a click scrolls into view; `windowsOnly` lists just the windows, then the screens (primary, bounds, scale); `classes` adds each component's Java class and lists plugins' own unnamed panels; `format: toon` returns a [TOON](https://github.com/toon-format/toon) table instead. |
| `pilot_type` | Types text and/or presses a key (`Enter`, `ctrl shift A`) into a ref or the focused component; shortcuts run their actions. `clear: true` replaces the content; with a combo box ref, `text` selects that item. `hold: ctrl` keeps modifier keys down across calls, carried by later keys and clicks, until `release: ctrl`, e.g. for Switcher (`hold: ctrl, key: Tab`, then `release: ctrl`). |
| `pilot_window` | Brings a window (project frame, dialog, detached tool window) to the front, maximizes or restores it (`maximize`), resizes it (`size: 1280x800`), moves it (`position: 100,50`, on some screen), moves or detaches the tool window holding the ref (`anchor: right`, `type: floating`), or closes it (`close`) as its close button would; returns the resulting window and tool window states. |
| `pilot_wait_for` | Waits until text shows (returning the matching lines with their refs), text is gone, projects have opened and finished indexing (`smart`), no background task runs (`idle`), a Gradle/Maven sync has ended, failing with its error if it failed (`sync`; start it with intellij-index's `ide_reload_project` or an action), a Run console or Terminal tab shows text (`console`, in output written since the last call that acted, so a previous run's output does not count, returning the matching lines under their tab's name; `session` picks tabs by name), and/or `time` seconds have passed. |

Refs stay with their component: a stale ref fails rather than clicking something else. Clicks and keys
return once handled, and actions after their `wait`; for what shows later, follow them with `pilot_wait_for`.

## Setup

1. Install the plugin into the IDE (or sandbox) to drive.
2. *Settings | Tools | IDE Pilot*: click **Generate** and copy the shown command, e.g.
   `claude mcp add --scope user --transport http idepilot http://127.0.0.1:63400/mcp --header "Authorization: Bearer <token>"`.
   Without a token the server is off; Generate replaces the token, so agents with the old one get 401.
3. For sandboxes, set `idePilotToken=<token>` in `~/.gradle/gradle.properties`: `runIde` passes it as
   `-Didepilot.token`, which overrides the settings, so new sandboxes need no Generate. `-Didepilot.port` overrides the
   port (default 63400); `runIde` sets it to 63401, so the sandbox does not clash with the IDE it is developed in
   (`-PidePilotPort=...` for another).

The server listens on localhost only, and every request needs the token, since `pilot_evaluate` runs code in the IDE.

### Driving another plugin's sandbox

`scripts/idepilot.init.gradle` adds a `runIdePilot` task to every IntelliJ Platform plugin build, without changing that
build: it runs the plugin's sandbox like `runIde`, with IDE Pilot installed.

1. Copy it to `~/.gradle/init.d/`.
2. In `~/.gradle/gradle.properties`, set `idePilotZip` to IDE Pilot's zip, or to the directory holding it (the newest
   `IdePilot-*.zip` there is used), e.g. `idePilotZip=<IdePilot>/build/distributions`, next to `idePilotToken`.
3. In the plugin's project, `./gradlew runIdePilot`; its MCP server listens on 63402 (`-PidePilotPort=...` for another).
   The sandbox needs IntelliJ 2025.2 or newer, e.g. `-PplatformVersion=2025.2.6.4` when the plugin targets an older one.
4. To let an agent reload the plugin's changes, run `restart_sandbox.py` from the plugin's checkout:
   `python <IdePilot>/scripts/restart_sandbox.py --url http://127.0.0.1:63402/mcp --project . --task runIdePilot -- -PplatformVersion=2025.2.6.4`.

## Build

`./gradlew buildPlugin` builds `build/distributions/IdePilot-<version>.zip`; `./gradlew runIde` runs a sandbox with it.
`python scripts/restart_sandbox.py` exits a running sandbox through IDE Pilot and starts a new one with the current
build, so an agent can load its own changes; `./gradlew smokeTest` then drives every tool against it.
