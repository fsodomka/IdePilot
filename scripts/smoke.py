"""Smoke test of IDE Pilot's tools against a running IDE, e.g. the runIde sandbox.

    python scripts/smoke.py [--url http://127.0.0.1:63400/mcp] [--token T]

The token comes from --token or the IDEPILOT_TOKEN environment variable. Opens a small throwaway project in the IDE,
drives every tool once, and fails if the IDE caught any exception. Stdlib only.
"""
import argparse
import base64
import http.client
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request

_next_id = 0


class Client:
    def __init__(self, url, token):
        self.url = url
        self.token = token

    def rpc(self, method, params=None):
        global _next_id
        _next_id += 1
        body = json.dumps({"jsonrpc": "2.0", "id": _next_id, "method": method, "params": params or {}}).encode("utf-8")
        request = urllib.request.Request(self.url, body, {"Content-Type": "application/json",
                                                          "Authorization": "Bearer " + self.token})
        with urllib.request.urlopen(request, timeout=180) as response:
            reply = json.load(response)
        if "error" in reply:
            raise AssertionError(f"{method} failed: {reply['error']}")
        return reply["result"]

    def call(self, name, **arguments):
        """The tool's text and content; fails on an isError result."""
        result = self.rpc("tools/call", {"name": name, "arguments": arguments})
        text = result["content"][-1]["text"]
        if result["isError"]:
            raise AssertionError(f"{name} {arguments} failed: {text}")
        return text, result["content"]


def ref_of(snapshot, pattern):
    """The ref of the first snapshot line matching the regex, or None."""
    for line in snapshot.splitlines():
        if re.search(pattern, line):
            match = re.search(r"\[ref=(e[\d.]+)]", line)
            if match:
                return match.group(1)
    return None


def full_snapshot(client):
    text, _ = client.call("pilot_snapshot", limit=100000)
    return text


def full_snapshot_bounds(client):
    text, _ = client.call("pilot_snapshot", limit=100000, bounds=True)
    return text


def step(name):
    print(f"- {name}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="http://127.0.0.1:63400/mcp")
    parser.add_argument("--token", default=os.environ.get("IDEPILOT_TOKEN"))
    args = parser.parse_args()
    if not args.token:
        sys.exit("Give --token or set IDEPILOT_TOKEN")
    client = Client(args.url, args.token)

    step("unauthorized request is refused")
    try:
        Client(args.url, "wrong").rpc("ping")
        raise AssertionError("a wrong token was accepted")
    except urllib.error.HTTPError as e:
        assert e.code == 401, e.code

    step("initialize, tools/list")
    client.rpc("initialize", {"protocolVersion": "2025-06-18"})
    names = {tool["name"] for tool in client.rpc("tools/list")["tools"]}
    assert {"pilot_snapshot", "pilot_click", "pilot_type", "pilot_log", "pilot_action", "pilot_open",
            "pilot_wait_for", "pilot_evaluate", "pilot_screenshot", "pilot_window"} <= names, names

    step("wait for smart mode")
    client.call("pilot_wait_for", smart=True, timeout=300)

    step("close projects left open by an interrupted run")
    # their project views show hello.txt too, so waiting for it to be gone would time out
    stale, closing = re.compile(r'^- window "(idepilot-smoke-[^"]*)".*\[ref=(e[\d.]+)]', re.M), set()
    for _ in range(150):  # CloseProject returns at once; wait_for gone won't do, recent projects keep the name shown
        windows = stale.findall(full_snapshot(client))
        if not windows:
            break
        for title, ref in windows:
            if title not in closing:
                closing.add(title)
                client.call("pilot_action", id="CloseProject", ref=ref)
        time.sleep(0.2)
    else:
        raise AssertionError("cannot close the projects of an earlier run")

    step("open a throwaway project")
    project = tempfile.mkdtemp(prefix="idepilot-smoke-")
    with open(os.path.join(project, "hello.txt"), "w", encoding="utf-8") as f:
        f.write("hello from the smoke test\n")
    with open(os.path.join(project, "other.txt"), "w", encoding="utf-8") as f:
        f.write("another file\n")
    # with a project open the IDE would ask where to open the new one, in a modal dialog that blocks the tools;
    # open in a new window without asking, and restore the setting at the end
    settings = "com.intellij.ide.GeneralSettings.getInstance()"
    previous, _ = client.call("pilot_evaluate", script=f"def s = {settings}; def p = s.confirmOpenNewProject; "
                              "s.confirmOpenNewProject = com.intellij.ide.GeneralSettings.OPEN_PROJECT_NEW_WINDOW; p")
    try:
        run_in_project(client, project)
    except AssertionError:
        print(project_window(client, project) or "(no project window)", file=sys.stderr)
        raise
    finally:
        window = project_window_ref(client, project)
        if window:
            client.call("pilot_action", id="CloseProject", ref=window)
        client.call("pilot_evaluate", script=f"{settings}.confirmOpenNewProject = {int(previous.strip())}")
    print("OK")


def wait_for_console(client, title, line):
    """Waits until the Run console titled title shows the line."""
    text = ""
    for _ in range(50):  # the process runs and its output reaches the console after pilot_evaluate returns
        text, _ = client.call("pilot_log", consoles=True, lines=20)
        section = next((s for s in text.split("\n\n") if s.startswith("== " + title)), "")
        if line in section.splitlines()[1:]:
            return
        time.sleep(0.2)
    raise AssertionError(f"no '{line}' line in the '{title}' console:\n{text}")


def project_window(client, project):
    """The snapshot lines of the project's window."""
    lines, inside = [], False
    for line in full_snapshot(client).splitlines():
        if line.startswith("- window"):
            inside = line.startswith('- window "' + os.path.basename(project))
        if inside:
            lines.append(line)
    return "\n".join(lines)


def project_window_ref(client, project):
    return ref_of(full_snapshot(client), r'^- window "' + re.escape(os.path.basename(project)))


def run_in_project(client, project):
    client.call("pilot_open", path=project)
    client.call("pilot_wait_for", text=os.path.basename(project), timeout=120)
    trust = ref_of(full_snapshot(client), r'button "Trust Project"')
    if trust:
        client.call("pilot_click", ref=trust)
        client.call("pilot_wait_for", gone="Trust Project", timeout=30)
    client.call("pilot_wait_for", smart=True, timeout=300)

    step("wait_for: idle, time, text with refs")
    client.call("pilot_wait_for", idle=True, timeout=300)
    start = time.monotonic()
    client.call("pilot_wait_for", time=1)
    assert 1 <= time.monotonic() - start < 3, time.monotonic() - start
    text, _ = client.call("pilot_wait_for", text="hello.txt", timeout=30)
    assert re.search(r'hello\.txt.*\[ref=e[\d.]+]', text), text
    try:
        client.call("pilot_wait_for", text="no such text anywhere", timeout=0.5)
        raise AssertionError("waiting for missing text did not time out")
    except AssertionError as e:
        assert "Timed out" in str(e), e

    step("evaluate")
    text, _ = client.call("pilot_evaluate", script="project.basePath")
    assert os.path.normcase(os.path.normpath(text.strip())) == os.path.normcase(os.path.normpath(project)), text

    step("the project view shows, and its stripe button is on")
    # a new project opens with the project view showing; ActivateProjectToolWindow would toggle it off
    client.call("pilot_wait_for", text="hello.txt", timeout=30)
    assert re.search(r'push button "Project" \[ref=[^]]+] \(on\)', project_window(client, project)), "stripe button not on"

    step("action: collapse and expand the project tree")
    tree = ref_of(project_window(client, project), r'tree "Project structure tree"')
    assert tree, "no project tree"
    client.call("pilot_action", id="CollapseAll", ref=tree)
    client.call("pilot_wait_for", gone="hello.txt", timeout=10)
    client.call("pilot_action", id="ExpandAll", ref=tree)
    client.call("pilot_wait_for", text="hello.txt", timeout=10)

    step("snapshot paging")
    page, _ = client.call("pilot_snapshot", limit=5)
    more = re.search(r"offset=(\d+) next=(\S+)", page)
    assert more, page
    client.call("pilot_snapshot", offset=int(more.group(1)), limit=5, next=more.group(2))

    step("snapshot find: matches under their ancestors")
    found, _ = client.call("pilot_snapshot", find="HELLO.TXT")
    lines = found.splitlines()
    assert lines[0].startswith("- window "), found
    assert any(re.match(r'\s+- tree "Project structure tree"', line) for line in lines), found
    assert any(re.match(r'\s+- treeitem "hello\.txt" \[ref=', line) for line in lines), found
    assert not any("other.txt" in line for line in lines), found

    step("snapshot format toon")
    table, _ = client.call("pilot_snapshot", find="hello.txt", format="toon", bounds=True)
    lines = table.splitlines()
    assert re.fullmatch(r"lines\[(\d+)]\{depth,ref,role,name,states,x,y,w,h}:", lines[0]), table
    assert int(re.search(r"\[(\d+)]", lines[0]).group(1)) == len(lines) - 1, table
    assert any(re.fullmatch(r"  \d+,e[\d.]+,treeitem,hello\.txt,[^,]*,-?\d+,-?\d+,\d+,\d+", line) for line in lines), table

    step("click a project view row")
    row = ref_of(full_snapshot(client), r'treeitem "hello\.txt')
    assert row, "no hello.txt row in the snapshot"
    client.call("pilot_click", ref=row)
    client.call("pilot_wait_for", text="hello.txt", timeout=10)
    assert re.search(r'treeitem "hello\.txt[^\n]*\(selected\)', full_snapshot(client)), "hello.txt row not selected"

    step("ctrl-click adds a row to the selection")
    other = ref_of(full_snapshot(client), r'treeitem "other\.txt')
    assert other, "no other.txt row in the snapshot"
    client.call("pilot_click", ref=other, modifiers="ctrl")
    snapshot = full_snapshot(client)
    for name in ("hello", "other"):
        assert re.search(rf'treeitem "{name}\.txt[^\n]*\(selected\)', snapshot), f"{name}.txt row not selected"

    step("right-click opens the context menu")
    client.call("pilot_click", ref=row, button="right")
    client.call("pilot_wait_for", text="Copy Path", timeout=10)
    client.call("pilot_type", key="Escape")
    client.call("pilot_wait_for", gone="Copy Path", timeout=10)

    step("double-click opens a file")
    client.call("pilot_click", ref=other, clickCount=2)
    client.call("pilot_wait_for", text='page tab "other.txt"', timeout=30)
    # the editor takes the focus a moment after its tab shows; wait, or it takes it from the tree after the click below
    for _ in range(50):
        if re.search(r'text "Editor for other\.txt" \[ref=[^]]+] \(focused\)', full_snapshot(client)):
            break
        time.sleep(0.2)
    else:
        raise AssertionError("the other.txt editor did not get the focus")
    client.call("pilot_click", ref=row)  # select hello.txt again for the Enter below

    step("type: open the file and type into the editor")
    client.call("pilot_type", ref=row, key="Enter")
    # snapshots list names, not editor content: read the editor's document
    path = os.path.join(project, "hello.txt").replace("\\", "/")
    editor_text = ("com.intellij.openapi.fileEditor.FileDocumentManager.instance.getDocument("
                   f"com.intellij.openapi.vfs.LocalFileSystem.instance.findFileByPath('{path}'))?.text")
    client.call("pilot_wait_for", text='page tab "hello.txt"', timeout=30)
    text, _ = client.call("pilot_evaluate", script=editor_text)
    assert text.startswith("hello from the smoke test"), text
    client.call("pilot_type", key="ctrl End")
    client.call("pilot_type", text="typed by smoke")
    for _ in range(25):  # the key events are posted, so typing finishes after pilot_type returns
        text, _ = client.call("pilot_evaluate", script=editor_text)
        if text.endswith("typed by smoke"):
            break
        time.sleep(0.2)
    assert text.endswith("typed by smoke"), text
    client.call("pilot_type", key="Escape")

    step("window: resize, activate, close a dialog")
    window = project_window_ref(client, project)
    text, _ = client.call("pilot_window", ref=window, size="1000x700")
    assert text.endswith("to 1000x700"), text
    snapshot = full_snapshot_bounds(client)
    assert re.search(r"\[ref=" + re.escape(window) + r"][^\n]* 1000x700", snapshot), "size not applied:\n" + snapshot
    client.call("pilot_window", ref=window)
    client.call("pilot_action", id="ShowSettings", ref=window)
    client.call("pilot_wait_for", text='window "Settings"', timeout=30)
    dialog = ref_of(full_snapshot(client), r'^- window "Settings"')
    client.call("pilot_window", ref=dialog, close=True)
    client.call("pilot_wait_for", gone='window "Settings"', timeout=10)

    step("screenshot")
    _, content = client.call("pilot_screenshot")
    image = next(c for c in content if c["type"] == "image")
    assert base64.b64decode(image["data"])[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"

    step("log")
    text, _ = client.call("pilot_log", lines=5)
    assert text.strip(), "empty log"
    client.call("pilot_log", freezes=True, lines=1)

    step("log: notifications, stdout/stderr, consoles")
    client.call("pilot_evaluate", script="com.intellij.notification.Notifications.Bus.notify(new com.intellij.notification"
                ".Notification('IDE Pilot', 'smoke title', 'smoke <b>content</b>', "
                "com.intellij.notification.NotificationType.INFORMATION), project); null")
    for _ in range(25):  # the notification reaches the model after notify returns
        text, _ = client.call("pilot_log", notifications=True, contains="smoke title")
        if "smoke title" in text:
            break
        time.sleep(0.2)
    assert "INFORMATION [IDE Pilot] smoke title: smoke content" in text, text
    client.call("pilot_evaluate", script="System.out.println('smoke stdout'); System.err.println('smoke stderr'); null")
    text, _ = client.call("pilot_log", output=True, contains="smoke std")
    assert text.splitlines()[-2:] == ["smoke stdout", "[err] smoke stderr"], text

    step("log: Run consoles of real runs")
    # a plain Run console (ConsoleViewImpl), as Application and Gradle runs use
    client.call("pilot_evaluate", script="""
        def command = com.intellij.openapi.util.SystemInfo.isWindows ? ['cmd.exe', '/c', 'echo smoke-plain'] : ['sh', '-c', 'echo smoke-plain']
        new com.intellij.execution.RunContentExecutor(project, new com.intellij.execution.process.OSProcessHandler(
            new com.intellij.execution.configurations.GeneralCommandLine(command))).withTitle('smoke plain').run()
        null""")
    client.call("pilot_wait_for", console="smoke-plain", timeout=30)
    wait_for_console(client, "smoke plain", "smoke-plain")
    # a Shell Script run configuration, whose console is a terminal (TerminalExecutionConsole); not saved in the project
    client.call("pilot_evaluate", script="""
        def type = com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
            .find { it.id == 'ShConfigurationType' }
        def settings = com.intellij.execution.RunManager.getInstance(project)
            .createConfiguration('smoke shell', type.configurationFactories[0])
        def c = settings.configuration
        c.executeScriptFile = false
        c.executeInTerminal = false
        c.scriptText = 'echo smoke-shell'  // one word: PowerShell's echo prints each argument on its own line
        com.intellij.execution.runners.ExecutionUtil.runConfiguration(settings,
            com.intellij.execution.executors.DefaultRunExecutor.runExecutorInstance)
        null""")
    client.call("pilot_wait_for", console="smoke-shell", timeout=30)
    wait_for_console(client, "smoke shell", "smoke-shell")

    step("no IDE errors")
    text, _ = client.call("pilot_log", errors=True, lines=20)
    assert text == "(no IDE errors)", "the IDE caught exceptions:\n" + text


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, http.client.HTTPException) as e:
        sys.exit(f"FAILED: {e}")
