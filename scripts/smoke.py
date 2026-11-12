"""Smoke test of IDE Pilot's tools against a running IDE, e.g. the runIde sandbox.

    python scripts/smoke.py [--url http://127.0.0.1:63400/mcp] [--token T]

The token comes from --token or the IDEPILOT_TOKEN environment variable. Opens a small throwaway project in the IDE,
drives every tool once, and fails if the IDE caught any exception. Stdlib only.
"""
import argparse
import base64
import http.client
import itertools
import json
import os
import re
import shutil
import sys
import tempfile
import time
import urllib.error
import urllib.request


class Client:
    def __init__(self, url, token):
        self.url = url
        self.token = token
        self.ids = itertools.count(1)

    def rpc(self, method, params=None):
        body = json.dumps({"jsonrpc": "2.0", "id": next(self.ids), "method": method,
                           "params": params or {}}).encode("utf-8")
        request = urllib.request.Request(self.url, body, {"Content-Type": "application/json",
                                                          "Authorization": "Bearer " + self.token})
        with urllib.request.urlopen(request, timeout=180) as response:
            reply = json.load(response)
        if "error" in reply:
            raise AssertionError(f"{method} failed: {reply['error']}")
        return reply["result"]

    def tool(self, name, arguments):
        result = self.rpc("tools/call", {"name": name, "arguments": arguments})
        return result["content"][-1]["text"], result["content"], result["isError"]

    def call(self, name, **arguments):
        """The tool's text and content; fails on an isError result."""
        text, content, error = self.tool(name, arguments)
        if error:
            raise AssertionError(f"{name} {arguments} failed: {text}")
        return text, content

    def text(self, name, **arguments):
        """The tool's text; fails on an isError result."""
        return self.call(name, **arguments)[0]

    def fail(self, name, **arguments):
        """The text of the tool's isError result; fails if the call succeeds."""
        text, _, error = self.tool(name, arguments)
        if not error:
            raise AssertionError(f"{name} {arguments} did not fail: {text}")
        return text


def poll(fn, until, tries=25):
    """Calls fn every 0.2 s until until(its result) holds or tries calls ran; returns the last result."""
    for _ in range(tries):
        result = fn()
        if until(result):
            break
        time.sleep(0.2)
    return result


def ref_of(snapshot, pattern):
    """The ref of the first snapshot line matching the regex, or None."""
    for line in snapshot.splitlines():
        if re.search(pattern, line):
            match = re.search(r"\[ref=(e[\d.]+)]", line)
            if match:
                return match.group(1)
    return None


def full_snapshot(client, **arguments):
    return client.text("pilot_snapshot", limit=100000, **arguments)


def step(name):
    print(f"- {name}", flush=True)


def show_dialog(client, title, panel, imports=""):
    """Shows a modeless dialog titled title, whose createCenterPanel body is the Groovy panel."""
    client.call("pilot_evaluate", script=f"""{imports}
        def dialog = new com.intellij.openapi.ui.DialogWrapper(project, false,
                com.intellij.openapi.ui.DialogWrapper.IdeModalityType.MODELESS) {{
            javax.swing.JComponent createCenterPanel() {{
                {panel}
            }}
        }}
        dialog.title = '{title}'
        dialog.init()
        dialog.show()
        null""")
    client.call("pilot_wait_for", text=f'window "{title}"', timeout=10)


def run_echo(client, title, word):
    """Runs echo word in a plain Run console (ConsoleViewImpl) titled title."""
    client.call("pilot_evaluate", script=f"""
        def command = com.intellij.openapi.util.SystemInfo.isWindows ? ['cmd.exe', '/c', 'echo {word}'] : ['sh', '-c', 'echo {word}']
        new com.intellij.execution.RunContentExecutor(project, new com.intellij.execution.process.OSProcessHandler(
            new com.intellij.execution.configurations.GeneralCommandLine(command))).withTitle('{title}').run()
        null""")


def wait_for_console(client, title, line):
    """Waits until the Run console titled title shows the line."""
    def shows(text):
        section = next((s for s in text.split("\n\n") if s.startswith("== " + title)), "")
        return line in section.splitlines()[1:]
    # the process runs and its output reaches the console after pilot_evaluate returns
    text = poll(lambda: client.text("pilot_log", consoles=True, lines=20), shows, tries=50)
    assert shows(text), f"no '{line}' line in the '{title}' console:\n{text}"


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
    except urllib.error.HTTPError as e:
        assert e.code == 401, e.code
    else:
        raise AssertionError("a wrong token was accepted")

    step("initialize, tools/list")
    client.rpc("initialize", {"protocolVersion": "2025-06-18"})
    names = {tool["name"] for tool in client.rpc("tools/list")["tools"]}
    assert {"pilot_snapshot", "pilot_click", "pilot_type", "pilot_log", "pilot_action", "pilot_open",
            "pilot_wait_for", "pilot_evaluate", "pilot_screenshot", "pilot_window", "pilot_editor", "pilot_settings", "pilot_scroll"} <= names, names

    step("wait for smart mode")
    client.call("pilot_wait_for", smart=True, timeout=300)

    step("close projects left open by an interrupted run")
    # their project views show hello.txt too, so waiting for it to be gone would time out
    stale, closing = re.compile(r'^- window "(idepilot-smoke-[^"]*)".*\[ref=(e[\d.]+)]', re.M), set()

    def close_stale():
        windows = stale.findall(full_snapshot(client))
        for title, ref in windows:
            if title not in closing:
                closing.add(title)
                client.call("pilot_action", id="CloseProject", ref=ref)
        return windows
    # CloseProject returns at once; wait_for gone won't do, recent projects keep the name shown
    if poll(close_stale, lambda windows: not windows, tries=150):
        raise AssertionError("cannot close the projects of an earlier run")

    step("open a throwaway project")
    project = tempfile.mkdtemp(prefix="idepilot-smoke-")
    with open(os.path.join(project, "hello.txt"), "w", encoding="utf-8") as f:
        f.write("hello from the smoke test\n")
    with open(os.path.join(project, "other.txt"), "w", encoding="utf-8") as f:
        f.write("another file\n")
    # before opening, so the IDE sets the folder up as a Java module and checks the file
    with open(os.path.join(project, "A.java"), "w", encoding="utf-8") as f:  # an error to list and fix
        f.write("class A {\n    void f() {\n        String s = 1;\n\t}\n}\n")  # a tab: columns count characters
    # with a project open the IDE would ask where to open the new one, in a modal dialog that blocks the tools;
    # open in a new window without asking, and restore the setting at the end
    settings = "com.intellij.ide.GeneralSettings.getInstance()"
    previous = client.text("pilot_evaluate", script=f"def s = {settings}; def p = s.confirmOpenNewProject; "
                           "s.confirmOpenNewProject = com.intellij.ide.GeneralSettings.OPEN_PROJECT_NEW_WINDOW; p")
    try:
        run_in_project(client, project)
    except AssertionError:
        print(project_window(client, project) or "(no project window)", file=sys.stderr)
        print(f"Kept the project for a look: {project}", file=sys.stderr)
        raise
    finally:
        window = project_window_ref(client, project)
        if window:
            client.call("pilot_action", id="CloseProject", ref=window)
        client.call("pilot_evaluate", script=f"{settings}.confirmOpenNewProject = {int(previous.strip())}")
    # CloseProject returns at once, and on Windows the IDE holds the project's files open until it has closed
    poll(lambda: project_window_ref(client, project), lambda ref: not ref, tries=150)
    poll(lambda: shutil.rmtree(project, ignore_errors=True) or os.path.exists(project), lambda left: not left)
    if os.path.exists(project):
        print(f"Could not delete the project: {project}", file=sys.stderr)
    print("OK")


def run_in_project(client, project):
    client.call("pilot_open", path=project)
    client.call("pilot_wait_for", text=os.path.basename(project), timeout=120)
    trust = ref_of(full_snapshot(client), r'button "Trust Project"')
    if trust:
        client.call("pilot_click", ref=trust)
        client.call("pilot_wait_for", gone="Trust Project", timeout=30)
    client.call("pilot_wait_for", smart=True, timeout=300)
    # in order: later tests rely on the state earlier ones leave, such as the open editor tabs
    tests = (test_wait_for, test_evaluate, test_project_view, test_action, test_snapshot, test_tree_clicks,
             test_hover, test_drag_tabs, test_editor, test_synchronize, test_compose, test_scroll, test_hold_release,
             test_type_newline, test_window, test_snapshot_classes, test_tool_window_and_settings, test_screenshot,
             test_log, test_consoles, test_log_tests, test_settings, test_table, test_click_process_mouse_event, test_popup_checked, test_settled, test_tasks,
             test_terminal, test_no_ide_errors)
    # stops at the first failure, since the tests after it would fail on the state it left
    for i, test in enumerate(tests):
        start = time.monotonic()
        try:
            test(client, project)
        except Exception as e:
            print(f"FAIL {test.__name__} ({time.monotonic() - start:.1f} s): {e}", flush=True)
            for skipped in tests[i + 1:]:
                print(f"SKIP {skipped.__name__}")
            print(f"{i} passed, 1 failed, {len(tests) - i - 1} skipped", flush=True)
            raise
        print(f"PASS {test.__name__} ({time.monotonic() - start:.1f} s)", flush=True)
    print(f"{len(tests)} passed, 0 failed, 0 skipped")


def test_wait_for(client, project):
    step("wait_for: idle, time, text with refs")
    client.call("pilot_wait_for", idle=True, timeout=300)
    start = time.monotonic()
    client.call("pilot_wait_for", time=1)
    assert 1 <= time.monotonic() - start < 3, time.monotonic() - start
    text = client.text("pilot_wait_for", text="hello.txt", timeout=30)
    assert re.search(r'hello\.txt.*\[ref=e[\d.]+]', text), text
    text = client.fail("pilot_wait_for", text="no such text anywhere", timeout=0.5)
    assert "Timed out" in text, text
    # the smoke project has no build tool, so no sync runs; a first call may report syncs of other open projects
    client.tool("pilot_wait_for", {"sync": True, "timeout": 2})
    text = client.fail("pilot_wait_for", sync=True, timeout=0.5)
    assert "no build sync has ended" in text, text


def test_evaluate(client, project):
    step("evaluate")
    # with the window's ref: without one, the focused project, which after a sandbox restart may still be another one
    text = client.text("pilot_evaluate", script="project.basePath", ref=project_window_ref(client, project))
    assert os.path.normcase(os.path.normpath(text.strip())) == os.path.normcase(os.path.normpath(project)), text


def test_project_view(client, project):
    step("the project view shows, and its stripe button is on")
    # a new project opens with the project view showing; ActivateProjectToolWindow would toggle it off
    client.call("pilot_wait_for", text="hello.txt", timeout=30)
    assert re.search(r'push button "Project" \[ref=[^]]+] \(on\)', project_window(client, project)), "stripe button not on"


def test_action(client, project):
    step("action find: by menu text, with shortcut, and limited")
    text = client.text("pilot_action", find="reformat code")
    assert re.search(r"^ReformatCode – Reformat Code – \S", text, re.M), text
    text = client.text("pilot_action", find="e", limit=3)
    assert len(text.splitlines()) == 4 and re.fullmatch(r"\.\.\. \d+ more; narrow find", text.splitlines()[-1]), text

    step("action: collapse and expand the project tree")
    tree = ref_of(project_window(client, project), r'tree "Project structure tree"')
    assert tree, "no project tree"
    client.call("pilot_action", id="CollapseAll", ref=tree)
    client.call("pilot_wait_for", gone="hello.txt", timeout=10)
    client.call("pilot_action", id="ExpandAll", ref=tree)
    client.call("pilot_wait_for", text="hello.txt", timeout=10)


def test_snapshot(client, project):
    step("snapshot paging")
    page = client.text("pilot_snapshot", limit=5)
    more = re.search(r"offset=(\d+) next=(\S+)", page)
    assert more, page
    client.call("pilot_snapshot", offset=int(more.group(1)), limit=5, next=more.group(2))

    step("snapshot find: matches under their ancestors")
    found = client.text("pilot_snapshot", find="HELLO.TXT")
    lines = found.splitlines()
    assert lines[0].startswith("- window "), found
    assert any(re.match(r'\s+- tree "Project structure tree"', line) for line in lines), found
    assert any(re.match(r'\s+- treeitem "hello\.txt" \[ref=', line) for line in lines), found
    assert not any("other.txt" in line for line in lines), found

    step("snapshot format toon")
    table = client.text("pilot_snapshot", find="hello.txt", format="toon", bounds=True)
    lines = table.splitlines()
    assert re.fullmatch(r"lines\[(\d+)]\{depth,ref,role,name,states,x,y,w,h}:", lines[0]), table
    assert int(re.search(r"\[(\d+)]", lines[0]).group(1)) == len(lines) - 1, table
    assert any(re.fullmatch(r"  \d+,e[\d.]+,treeitem,hello\.txt,[^,]*,-?\d+,-?\d+,\d+,\d+", line) for line in lines), table


def test_tree_clicks(client, project):
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

    step("click with wait: returns what the click showed")
    text = client.text("pilot_click", ref=row, button="right", wait=2)
    assert "Showed within 2.0 s:" in text and "Copy Path" in text, text
    client.call("pilot_type", key="Escape")
    client.call("pilot_wait_for", gone="Copy Path", timeout=10)

    step("double-click opens a file")
    client.call("pilot_click", ref=other, clickCount=2)
    client.call("pilot_wait_for", text='page tab "other.txt"', timeout=30)
    # the editor takes the focus a moment after its tab shows; wait, or it takes it from the tree after the click below
    focused = r'text "Editor for other\.txt" \[ref=[^]]+] \(focused\)'
    if not re.search(focused, poll(lambda: full_snapshot(client), lambda s: re.search(focused, s), tries=50)):
        raise AssertionError("the other.txt editor did not get the focus")
    client.call("pilot_click", ref=row)  # select hello.txt again for the Enter below

    step("type: open the file and type into the editor")
    client.call("pilot_type", ref=row, key="Enter")
    # snapshots list names, not editor content: read the editor's document
    path = os.path.join(project, "hello.txt").replace("\\", "/")
    editor_text = lambda: client.text("pilot_evaluate", script=(
        "com.intellij.openapi.fileEditor.FileDocumentManager.instance.getDocument("
        f"com.intellij.openapi.vfs.LocalFileSystem.instance.findFileByPath('{path}'))?.text"))
    client.call("pilot_wait_for", text='page tab "hello.txt"', timeout=30)
    text = editor_text()
    assert text.startswith("hello from the smoke test"), text
    client.call("pilot_type", key="ctrl End")
    client.call("pilot_type", text="typed by smoke")
    # the key events are posted, so typing finishes after pilot_type returns
    text = poll(editor_text, lambda t: t.endswith("typed by smoke"))
    assert text.endswith("typed by smoke"), text
    client.call("pilot_type", text="replaced by smoke", clear=True)
    text = poll(editor_text, lambda t: t == "replaced by smoke")
    assert text == "replaced by smoke", text
    client.call("pilot_type", key="Escape")


def test_hover(client, project):
    step("hover shows a tooltip over the Project stripe button")
    window = project_window_ref(client, project)
    # the stripe button is on; the Project tool window header has a "Project" button too, without a tooltip
    stripe = ref_of(project_window(client, project), r'push button "Project" \[ref=[^]]+][^\n]*\(on\)')
    assert stripe, "no Project stripe button"
    # the tooltip (HelpTooltip) shows in a heavyweight window owned by the frame, not as an IdeTooltip
    client.call("pilot_click", ref=stripe, hover=True)
    # tooltips show after the initial delay
    shown = poll(lambda: client.text("pilot_evaluate", script="component.ownedWindows.any { it.showing }", ref=window),
                 lambda t: t.strip() == "true")
    if shown.strip() != "true":
        state = client.text("pilot_evaluate", ref=window, script="[owned: component.ownedWindows.collect { "
                            "it.class.simpleName + (it.showing ? '' : ' hidden') }, mouse: java.awt.MouseInfo.pointerInfo?.location, "
                            "frame: component.bounds, active: component.active]")
        raise AssertionError("no tooltip after hovering the Project stripe button: " + state)
    client.call("pilot_type", key="Escape")


def test_drag_tabs(client, project):
    step("drag an editor tab onto another reorders them")
    tabs = lambda: re.findall(r'page tab "(\w+\.txt)"', project_window(client, project))
    before = tabs()
    assert sorted(before) == ["hello.txt", "other.txt"], before
    snapshot = project_window(client, project)
    client.call("pilot_click", ref=ref_of(snapshot, rf'page tab "{re.escape(before[1])}"'),
                to=ref_of(snapshot, rf'page tab "{re.escape(before[0])}"'))
    after = poll(tabs, lambda t: t == before[::-1])
    assert after == before[::-1], f"tabs not reordered: {after}"


def test_editor(client, project):
    step("editor: text, problems, actions at the caret, apply a fix")
    client.call("pilot_open", path=os.path.join(project, "A.java"), line=3)
    client.call("pilot_wait_for", text='page tab "A.java"', idle=True, timeout=60)
    text = client.text("pilot_editor", lines="3")
    assert re.match(r"A\.java – 6 lines, caret 3:\d+\n3\| {9}String s = 1;$", text), text
    # analysis runs after the file opens
    text = poll(lambda: client.text("pilot_editor", problems=True),
                lambda t: "Incompatible types" in t and "not finished" not in t, tries=50)
    assert re.search(r"^  3:20 ERROR: Incompatible types", text, re.M), text
    text = client.text("pilot_editor", caret="3:20", actions=True)
    assert "\n  Change variable 's' type to 'int' (error fix)" in text, text
    text = client.fail("pilot_editor", apply="no such fix")
    assert "Change variable 's' type to 'int'" in text, text
    client.call("pilot_editor", apply="Change variable 's' type to 'int'")
    text = poll(lambda: client.text("pilot_editor", lines="3"), lambda t: t.endswith("int s = 1;"))
    assert text.endswith("3|         int s = 1;"), text
    # select a range, forwards and backwards: the caret goes to TO; a column past the line's end is its end
    text = client.text("pilot_editor", select="2:5-4:6", lines="1")
    assert text.startswith("A.java – 6 lines, caret 4:3, selection 2:5-4:3\n"), text
    text = client.text("pilot_editor", select="4:3-2:5", lines="1")
    assert text.startswith("A.java – 6 lines, caret 2:5, selection 2:5-4:3\n"), text
    # line 4 is a tab and }: column 2 is the }, not a column inside the tab's width
    text = client.text("pilot_editor", select="4:2-4:3", lines="1")
    assert text.startswith("A.java – 6 lines, caret 4:3, selection 4:2-4:3\n"), text
    text = client.text("pilot_editor", select="3-3:99", lines="1")
    assert text.startswith("A.java – 6 lines, caret 3:19, selection 3:1-3:19\n"), text
    # TO may be the line after the last: the end of the text
    text = client.text("pilot_editor", select="1-7", lines="1")
    assert re.match(r"A\.java – 6 lines, caret 6:\d+, selection 1:1-6:\d+\n", text), text
    assert "outside lines 1-7" in client.fail("pilot_editor", select="1-8")
    assert "not both" in client.fail("pilot_editor", select="2-3", caret="1")
    assert "select must be FROM-TO" in client.fail("pilot_editor", select="2:5")
    text = client.text("pilot_editor", caret="1", lines="1")
    assert "selection" not in text.splitlines()[0], text


def test_synchronize(client, project):
    step("files changed outside the IDE show after Synchronize")
    client.call("pilot_action", id="SaveAll")  # the fix above is in the document; save it so the overwrite is clean
    client.call("pilot_wait_for", idle=True, timeout=30)
    with open(os.path.join(project, "A.java"), "w", encoding="utf-8") as f:
        f.write("class A {\n    // changed outside\n}\n")
    client.call("pilot_action", id="Synchronize")
    text = poll(lambda: client.text("pilot_editor", lines="2"), lambda t: t.endswith("// changed outside"), tries=50)
    assert text.endswith("2|     // changed outside"), text


def test_compose(client, project):
    step("Compose UI: snapshot its accessible tree, click, hover, type")
    client.call("pilot_action", id="ComposeShowcaseAction")
    client.call("pilot_wait_for", text='check box "checkBox"', timeout=30)
    showcase = full_snapshot(client)
    showcase = showcase[showcase.index('- window "Compose Components Showcase"'):]
    assert re.search(r'radio button "Value 2" \[ref=e\d+\.\d+] \(checked\)', showcase), showcase
    assert re.search(r'page tab "Tab 1" \[ref=e\d+\.\d+] \(selected\)', showcase), showcase
    client.call("pilot_click", ref=ref_of(showcase, r'check box "checkBox"'))
    client.call("pilot_click", ref=ref_of(showcase, r'page tab "Tab 3"'))
    client.call("pilot_click", ref=ref_of(showcase, r'push button "Click me #0"'))
    client.call("pilot_click", ref=ref_of(showcase, r'label "Hovered: false"'), hover=True)
    field = re.search(r'label "Text field:".*\n\s+- scroll pane \[ref=(e[\d.]+)]', showcase)
    assert field, showcase
    client.call("pilot_type", ref=field.group(1), text="typed into compose")
    for expected in ('push button "Click me #1"', 'label "Hovered: true"', 'scroll pane "typed into compose"'):
        client.call("pilot_wait_for", text=expected, timeout=10)
    showcase = full_snapshot(client)
    assert re.search(r'check box "checkBox" \[ref=[^]]+] \(checked\)', showcase), showcase
    assert re.search(r'page tab "Tab 3" \[ref=[^]]+] \(selected\)', showcase), showcase
    client.call("pilot_type", ref=ref_of(showcase, r'^- window "Compose Components Showcase"'), key="Escape")
    client.call("pilot_wait_for", gone='window "Compose Components Showcase"', timeout=10)


def test_scroll(client, project):
    step("scroll: a list to a row by text, by pages, to top and bottom")
    show_dialog(client, "smoke scroll", """
                def pane = new com.intellij.ui.components.JBScrollPane(
                    new com.intellij.ui.components.JBList((1..300).collect { "scroll item $it".toString() }))
                pane.preferredSize = new java.awt.Dimension(250, 200)
                pane""")
    client.call("pilot_wait_for", text='"scroll item 1"', timeout=10)
    row = ref_of(full_snapshot(client), r'listitem "scroll item 1"')
    text = client.text("pilot_scroll", ref=row, to="SCROLL ITEM 250")
    assert text.startswith('Scrolled to row 250 of 300: "scroll item 250"'), text
    text = client.text("pilot_snapshot", find="scroll item 250")
    assert 'listitem "scroll item 250"' in text, text
    dialog = ref_of(full_snapshot(client), r'^- window "smoke scroll"')
    text = client.text("pilot_scroll", ref=dialog, to="scroll item 1")  # finds the list inside the window
    assert text.startswith('Scrolled to row 1 of 300'), text
    list_row = ref_of(full_snapshot(client), r'listitem "scroll item 1"')
    text = client.text("pilot_scroll", ref=list_row, direction="down", pages=2)
    assert text.startswith("Scrolled down: showing ") and "(top)" not in text, text
    text = client.text("pilot_snapshot", find='"scroll item 1"')
    assert 'listitem "scroll item 1"' not in text, text
    list_row = ref_of(full_snapshot(client), r'listitem "scroll item \d+"')
    text = client.text("pilot_scroll", ref=list_row, direction="bottom")
    assert "(bottom)" in text, text
    text = client.text("pilot_scroll", ref=list_row, direction="top")
    assert "(top)" in text, text
    text = client.fail("pilot_scroll", ref=list_row, to="no such item")
    assert "No row of 300" in text, text

    step("snapshot allRows: rows that do not show, clicked into view")
    text = client.text("pilot_snapshot", find='"scroll item 150"')
    assert text.startswith("(no line contains"), text
    text = client.text("pilot_snapshot", find='"scroll item 150"', allRows=True)
    assert re.search(r'listitem "scroll item 150" \[ref=[\w.]+] \(offscreen\)', text), text
    offscreen = ref_of(text, r'listitem "scroll item 150"')
    text = client.text("pilot_snapshot", find='"scroll item 1"', allRows=True)
    assert re.search(r'listitem "scroll item 1" \[ref=[\w.]+]$', text, re.M), text  # shows: not marked
    client.call("pilot_click", ref=offscreen)
    text = client.text("pilot_snapshot", find='"scroll item 150"')
    assert re.search(r'listitem "scroll item 150" \[ref=[\w.]+] \(selected\)$', text, re.M), text
    # past the first 200 rows: the 200 around those that show, the first listed saying how many are not
    client.call("pilot_scroll", ref=list_row, direction="bottom")
    text = client.text("pilot_snapshot", find='"scroll item 300"', allRows=True)
    assert re.search(r'listitem "scroll item 300" \[ref=[\w.]+]$', text, re.M), text
    text = client.text("pilot_snapshot", find="rows above not listed", allRows=True)
    assert re.search(r'listitem "scroll item 101" \[ref=[\w.]+] \(offscreen\) \(100 rows above not listed\)$', text, re.M), text
    client.call("pilot_window", ref=dialog, close=True)
    client.call("pilot_wait_for", gone="scroll item", timeout=10)


def test_hold_release(client, project):
    step("type hold and release: modifier keys down across calls, carried by keys and clicks")
    # a field that records its key and mouse events, in a client property, read back by evaluate
    show_dialog(client, "smoke keys", imports="import java.awt.event.*", panel="""
                def log = []
                def field = new com.intellij.ui.components.JBTextField('smoke keys')
                field.putClientProperty('smoke.log', log)
                field.addKeyListener(new KeyAdapter() {
                    void keyPressed(KeyEvent e) { log << 'down ' + KeyEvent.getKeyText(e.keyCode) + ' ' + e.modifiersEx }
                    void keyReleased(KeyEvent e) { log << 'up ' + KeyEvent.getKeyText(e.keyCode) + ' ' + e.modifiersEx }
                })
                field.addMouseListener(new MouseAdapter() {
                    void mousePressed(MouseEvent e) { log << 'click ' + (e.modifiersEx & InputEvent.CTRL_DOWN_MASK) }
                })
                field""")
    field = ref_of(full_snapshot(client), r'text "smoke keys"')
    text = client.text("pilot_type", ref=field, hold="ctrl")
    assert text == f"Held ctrl in {field}; holding ctrl until released", text
    client.call("pilot_type", key="F5")
    client.call("pilot_click", ref=field)
    text = client.text("pilot_type", release="ctrl")
    assert text == "Released ctrl", text
    text = client.fail("pilot_type", release="ctrl")
    assert "Not held: ctrl; nothing is held" in text, text
    client.call("pilot_click", ref=field)
    client.call("pilot_wait_for", time=0.5)
    text = client.text("pilot_evaluate", script="""
        def field = com.intellij.util.ui.UIUtil.findComponentOfType(
            java.awt.Window.windows.find { it.showing && it.title == 'smoke keys' }.rootPane, com.intellij.ui.components.JBTextField)
        field.getClientProperty('smoke.log').join(', ')""")
    ctrl = 128  # InputEvent.CTRL_DOWN_MASK
    assert text == f"down Ctrl {ctrl}, down F5 {ctrl}, up F5 {ctrl}, click {ctrl}, up Ctrl 0, click 0", text
    client.call("pilot_window", ref=field, close=True)
    client.call("pilot_wait_for", gone='window "smoke keys"', timeout=10)


def test_type_newline(client, project):
    step("type: newline and tab in text are refused, and pressed with key")
    # pressed, Enter would close the dialog through its default OK button; typed alone, a text area drops them
    show_dialog(client, "smoke area", "new javax.swing.JTextArea('smoke area', 3, 20)")
    area = ref_of(full_snapshot(client), r'text "smoke area"')
    for bad in ("a\nb", "a\tb"):
        text = client.fail("pilot_type", ref=area, text=bad)
        assert "cannot hold newlines, tabs" in text, text
    client.call("pilot_type", ref=area, text="a", clear=True)
    client.call("pilot_type", ref=area, key="Enter")  # a text area takes Enter before the dialog's default button
    client.call("pilot_type", ref=area, text="b")
    text = client.text("pilot_evaluate", script="""
        def window = java.awt.Window.windows.find { it.showing && it.title == 'smoke area' }
        window == null ? 'closed' : com.intellij.util.ui.UIUtil.findComponentOfType(window.rootPane, javax.swing.JTextArea).text""")
    assert text == "a\nb", repr(text)
    client.call("pilot_window", ref=area, close=True)
    client.call("pilot_wait_for", gone='window "smoke area"', timeout=10)


def test_window(client, project):
    step("window: resize, activate, close a dialog")
    window = project_window_ref(client, project)
    text = client.text("pilot_window", ref=window, size="1000x700")
    assert text.splitlines()[0].endswith("to 1000x700"), text
    snapshot = full_snapshot(client, bounds=True)
    assert re.search(r"\[ref=" + re.escape(window) + r"][^\n]* 1000x700", snapshot), "size not applied:\n" + snapshot

    step("window: maximize and restore a frame")
    text = client.text("pilot_window", ref=window, maximize=True)
    assert text.startswith("Maximized ") and "(maximized)" in text.splitlines()[-1], text
    windows = client.text("pilot_snapshot", windowsOnly=True)
    assert re.search(r"\[ref=" + re.escape(window) + r"][^\n]*\(maximized\)", windows), windows
    text = client.text("pilot_window", ref=window, maximize=False)
    assert "(maximized)" not in text and "tool window" not in text, text

    step("window: screens after the windows, move a frame on the primary screen and not off every screen")
    windows = client.text("pilot_snapshot", windowsOnly=True)
    primary = re.search(r'^- screen "[^"]*" \(primary\)(?: \(scale [\d.]+\))? @(-?\d+),(-?\d+) (\d+)x(\d+)$', windows, re.M)
    assert primary, windows
    x, y = int(primary.group(1)) + 40, int(primary.group(2)) + 30
    text = client.text("pilot_window", ref=window, position=f"{x},{y}")
    assert text.splitlines()[0].endswith(f"to {x},{y}"), text
    snapshot = full_snapshot(client, bounds=True)
    assert re.search(r"\[ref=" + re.escape(window) + rf"][^\n]* @{x},{y} 1000x700", snapshot), "position not applied:\n" + snapshot
    table = client.text("pilot_snapshot", windowsOnly=True, format="toon")
    assert re.search(r"^screens\[\d+]\{id,primary,x,y,w,h,scale}:$", table, re.M), table
    text = client.fail("pilot_window", ref=window, position="-100000,-100000")
    assert "off every screen" in text, text


def test_snapshot_classes(client, project):
    step("snapshot classes: a plugin's unnamed panel gets its own line")
    client.call("pilot_evaluate", script="def d = new javax.swing.JDialog((java.awt.Frame) null, 'smoke classes', false); "
                "def p = new javax.swing.JPanel() {}; p.add(new javax.swing.JButton('inside')); "
                "d.contentPane.add(p); d.setSize(300, 200); d.visible = true; null")
    client.call("pilot_wait_for", text='window "smoke classes"', timeout=10)
    assert not re.search(r'\(class ', full_snapshot(client)), "classes off, yet a class is shown"
    found_all = full_snapshot(client, classes=True)
    block = found_all[found_all.index('window "smoke classes"'):]
    block = block[:block.find("\n- ") + 1 or len(block)]
    assert re.search(r'^  - panel \[ref=e\d+] \(class \w+\$\d+\)$', block, re.M), block
    assert re.search(r'^    - push button "inside" \[ref=e\d+](?: \(focused\))? \(class JButton\)$', block, re.M), block
    client.call("pilot_window", ref=ref_of(found_all, r'^- window "smoke classes"'), close=True)
    client.call("pilot_wait_for", gone='window "smoke classes"', timeout=10)


def test_tool_window_and_settings(client, project):
    step("window: move and detach a tool window")
    window = project_window_ref(client, project)
    tree = ref_of(project_window(client, project), r'tree "Project structure tree"')
    found = client.text("pilot_snapshot", find='tool window "Project"')
    assert re.search(r'panel "Project Tool Window" \[ref=[^]]+] \(tool window "Project", left, docked\)', found), found
    text = client.text("pilot_window", ref=tree, anchor="right")
    assert '(tool window "Project", right, docked)' in text, text
    client.call("pilot_window", ref=tree, anchor="left")
    text = client.text("pilot_window", ref=tree, type="floating")
    assert '(tool window "Project", left, floating)' in text, text
    floating = re.search(r'^State: window "([^"]*)"', text, re.M).group(1)
    client.call("pilot_wait_for", text=f'window "{floating}"', timeout=10)
    floating_ref = ref_of(full_snapshot(client), r'^- window "' + re.escape(floating) + '"')
    assert floating_ref and floating_ref != window, text
    text = client.text("pilot_window", ref=floating_ref, type="docked")  # the tool window's own window as ref
    assert '(tool window "Project", left, docked)' in text, text
    client.call("pilot_window", ref=window)
    text = client.text("pilot_action", id="ShowSettings", ref=window, wait=5)
    assert 'window "Settings"' in text, "the action's result does not list the dialog it opened:\n" + text
    client.call("pilot_wait_for", text='window "Settings"', timeout=30)
    dialog = ref_of(full_snapshot(client), r'^- window "Settings"')

    step("evaluate without ref runs while a modal dialog is open")
    text = client.text("pilot_evaluate", script="1 + 1", timeout=5)
    assert text.strip() == "2", text

    step("type selects a combo box item by its shown text")
    client.call("pilot_wait_for", text='combo box "Theme:"', timeout=30)
    theme = ref_of(full_snapshot(client), r'combo box "Theme:"')
    text = client.fail("pilot_type", ref=theme, text="no such theme")
    assert "items: " in text and "Light" in text and "LafReference" not in text, text
    selected = lambda: client.text("pilot_evaluate", script="component.selectedItem.name", ref=theme).strip()
    # without ref, the modal Settings blocks it
    laf = lambda: client.text("pilot_evaluate", script="com.intellij.ide.ui.LafManager.instance.currentUIThemeLookAndFeel.name",
                              ref=dialog)
    before = laf()
    other = "Light" if selected() != "Light" else "Dark"
    client.call("pilot_type", ref=theme, text=other.lower())  # case-insensitive fallback
    assert selected() == other
    # the Theme combo applies its choice at once, so the theme changing shows its listeners ran as on a real pick
    text = laf()
    assert text.strip() == other, text
    client.call("pilot_type", ref=theme, text=before.strip())  # restore
    text = laf()
    assert text == before, f"theme not restored to {before}: {text}"

    client.call("pilot_window", ref=dialog, close=True)
    client.call("pilot_wait_for", gone='window "Settings"', timeout=10)


def test_screenshot(client, project):
    step("screenshot")
    _, content = client.call("pilot_screenshot")
    image = next(c for c in content if c["type"] == "image")
    assert base64.b64decode(image["data"])[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"


def test_log(client, project):
    step("log")
    text = client.text("pilot_log", lines=5)
    assert text.strip(), "empty log"
    client.call("pilot_log", freezes=True, lines=1)

    step("log: notifications, stdout/stderr, consoles")
    client.call("pilot_evaluate", script="com.intellij.notification.Notifications.Bus.notify(new com.intellij.notification"
                ".Notification('IDE Pilot', 'smoke title', 'smoke <b>content</b>', "
                "com.intellij.notification.NotificationType.INFORMATION), project); null")
    # the notification reaches the model after notify returns
    text = poll(lambda: client.text("pilot_log", notifications=True, contains="smoke title"), lambda t: "smoke title" in t)
    assert "INFORMATION [IDE Pilot] smoke title: smoke content" in text, text
    client.call("pilot_evaluate", script="System.out.println('smoke stdout'); System.err.println('smoke stderr'); null")
    text = client.text("pilot_log", output=True, contains="smoke std")
    assert text.splitlines()[-2:] == ["smoke stdout", "[err] smoke stderr"], text


def test_consoles(client, project):
    step("log: Run consoles of real runs")
    # a plain Run console (ConsoleViewImpl), as Application and Gradle runs use
    run_echo(client, "smoke plain", "smoke-plain")
    text = client.text("pilot_wait_for", console="smoke-plain", timeout=30)
    # the matched line, under its tab's name
    assert "\n- smoke plain: smoke-plain" in text, text
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

    step("wait_for console: only output since the last acting call; consoles by session")
    client.call("pilot_evaluate", script="null")  # acts: output before it no longer counts
    text = client.fail("pilot_wait_for", console="smoke-plain", timeout=1)
    assert "not in new output" in text, text
    # the shell run may have reused the finished plain run's tab, so pick the shell's
    text = client.text("pilot_log", consoles=True, session="SMOKE SHELL", lines=5)
    assert re.match(r"== smoke shell \(Run, terminated, exit code 0\b", text) and "== smoke plain" not in text, text
    text = client.text("pilot_log", consoles=True, session="smoke", running=True)
    assert text.startswith("(no running Run or Debug consoles"), text
    text = client.text("pilot_log", sessions=True, session="smoke shell")
    # no pid: a terminal console's process handler does not expose its process
    assert re.fullmatch(r"smoke shell \(Run, terminated, exit code 0(, pid \d+[^)]*)?\)", text.strip()), text

    # a run started by one acting call keeps its output for a wait after another acting call
    run_echo(client, "smoke late", "smoke-late")
    # pilot_log does not act, so it does not move the marks
    text = poll(lambda: client.text("pilot_log", consoles=True, session="smoke late", contains="smoke-late"),
                lambda t: "\nsmoke-late" in t, tries=50)
    assert "\nsmoke-late" in text, text
    client.call("pilot_evaluate", script="null")
    client.call("pilot_wait_for", console="smoke-late", session="smoke late", timeout=5)
    client.call("pilot_evaluate", script="null")  # the run was there at the previous call: now its output is old
    text = client.fail("pilot_wait_for", console="smoke-late", session="smoke late", timeout=1)
    assert "not in new output" in text, text


def test_log_tests(client, project):
    step("log tests: a test run's summary, failures and tests by name")
    # a test console fed TeamCity service messages, as the IDE's test runners do
    client.call("pilot_evaluate", script="""
        import com.intellij.execution.process.*
        import com.intellij.execution.testframework.sm.*
        import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
        import com.intellij.execution.ui.*
        def executor = com.intellij.execution.executors.DefaultRunExecutor.runExecutorInstance
        def type = com.intellij.execution.configurations.ConfigurationType.CONFIGURATION_TYPE_EP.extensionList
            .find { it.id == 'ShConfigurationType' }
        def config = com.intellij.execution.RunManager.getInstance(project)
            .createConfiguration('smoke tests', type.configurationFactories[0]).configuration
        def handler = new NopProcessHandler()
        def console = SMTestRunnerConnectionUtil.createAndAttachConsole('SmokeTests', handler,
            new SMTRunnerConsoleProperties(config, 'SmokeTests', executor))
        def descriptor = new RunContentDescriptor(console, handler, console.component, 'smoke tests')
        RunContentManager.getInstance(project).showRunContent(executor, descriptor)
        handler.startNotify()
        ["testSuiteStarted name='SmokeSuite'", "testStarted name='good'", "testFinished name='good' duration='3'",
         "testStarted name='bad'", "testFailed name='bad' message='expected 1 but was 2' details='at Smoke.bad(Smoke.java:7)'",
         "testFinished name='bad'", "testIgnored name='skipped'", "testSuiteFinished name='SmokeSuite'"].each {
            handler.notifyTextAvailable("##teamcity[" + it + "]\\n", ProcessOutputTypes.STDOUT)
        }
        handler.destroyProcess()
        null""")
    client.call("pilot_wait_for", time=1)
    text = client.text("pilot_log", tests=True, session="smoke tests")
    assert re.match(r"== smoke tests \(finished\)\n3 tests: 1 passed, 1 failed, 1 ignored", text), text
    assert "FAILED SmokeSuite.bad" in text and "expected 1 but was 2" in text and "Smoke.java:7" in text, text
    assert "SmokeSuite.good" not in text, text
    text = client.text("pilot_log", tests=True, session="smoke tests", contains="good")
    assert "PASSED SmokeSuite.good (3 ms)" in text and "SmokeSuite.bad" not in text, text
    text = client.text("pilot_log", tests=True, session="no such tab")
    assert text.startswith("(no test runs"), text


def test_settings(client, project):
    step("settings: registry keys by a part, an Advanced Setting by ID, a service's state by class name")
    text = client.text("pilot_settings", find="ide.experimental.ui")
    assert re.search(r"^registry ide\.experimental\.ui = (true|false)", text, re.M), text
    # every key, as the IDE's own (internal) list has them: the platform's and the plugins'
    count = client.text("pilot_evaluate", script="com.intellij.openapi.util.registry.Registry.getAll().size()").strip()
    text = client.text("pilot_settings", find="registry ", limit=1)
    assert text.splitlines()[-1] == f"... {int(count) - 1} more; narrow find or raise limit", (count, text)
    # set by this test at its start; its default is not written to ide.general.xml
    text = client.text("pilot_settings", find="com.intellij.ide.GeneralSettings.confirmOpenNewProject2")
    assert "app ide.general.xml GeneralSettings.confirmOpenNewProject2 = 0" in text, text
    text = client.text("pilot_settings", find="com.intellij.ide.GeneralSettings", limit=200)
    assert re.search(r"GeneralSettings\.\w+ = .* \(default\)$", text, re.M), text
    # an Advanced Setting of each type: boolean, number, text and enum
    for setting, value in (("terminal.show.application.title", "(true|false)"), ("ide.max.recent.projects", r"\d+"),
                           ("ssh.tool.custom.path", ".+"), ("editor.paste.line.copied.from.empty.selection", ".+")):
        text = client.text("pilot_settings", find=setting)
        assert re.search(rf"^advanced {re.escape(setting)} = {value} \((default|changed)\)$", text, re.M), text
    # a per-client service (client="all"), in the local session's container, not the application's
    text = client.text("pilot_settings", find="com.intellij.openapi.editor.ex.EditorSettingsExternalizable.IS_DND_ENABLED")
    assert re.search(r"^app editor\.xml EditorSettings\.IS_DND_ENABLED = (true|false) \((default|changed)\)$", text, re.M), text
    # a plugin's service is not found: its class loader cannot be reached through public API
    text = client.text("pilot_settings", find="git4idea.config.GitVcsApplicationSettings")
    assert text.startswith("(no setting found for"), text
    # neither a part of an Advanced Setting's ID nor a class that is no service finds anything, and logs no error
    for find in ("zz-no-such-setting", "ide.max.recent.project", "java.lang.String", "com.intellij.openapi.project.Project"):
        text = client.text("pilot_settings", find=find)
        assert text.startswith("(no setting found for") or text.startswith("registry "), text
    assert client.text("pilot_settings", find="zz-no-such-setting").startswith("(no setting found for")


def test_table(client, project):
    step("snapshot and click: table rows")
    client.call("pilot_evaluate", script="""
        def frame = new javax.swing.JFrame('smoke table')
        def table = new com.intellij.ui.table.JBTable(new javax.swing.table.DefaultTableModel(
            [['alpha', '1'], ['beta', '2'], ['gamma', '3']] as Object[][], ['name', 'n'] as Object[]))
        frame.contentPane.add(new javax.swing.JScrollPane(table))
        frame.setSize(300, 200)
        frame.visible = true
        null""")
    text = client.text("pilot_wait_for", text='row "beta | 2"')
    client.call("pilot_click", ref=re.search(r'row "beta \| 2" \[ref=(e[\d.]+)\]', text).group(1))
    text = client.text("pilot_snapshot", find='row "')
    assert re.search(r'row "beta \| 2" \[ref=e[\d.]+\] \(selected\)', text), text
    assert 'row "alpha | 1"' in text and 'row "gamma | 3"' in text and 'alpha | 1" [ref' in text, text
    client.call("pilot_evaluate", script="""
        java.awt.Window.windows.findAll { it instanceof javax.swing.JFrame && it.title == 'smoke table' }*.dispose()
        null""")
    client.call("pilot_wait_for", gone='row "beta | 2"')

    step("table row refs survive a GC when the table's values are made on each call, or null")
    client.call("pilot_evaluate", script="""
        def model = new javax.swing.table.AbstractTableModel() {
            int getRowCount() { 3 }
            int getColumnCount() { 2 }
            Object getValueAt(int row, int column) { column == 0 ? (row == 1 ? null : new String('made ' + row)) : new Integer(1000 + row) }
        }
        def frame = new javax.swing.JFrame('smoke made table')
        frame.contentPane.add(new javax.swing.JScrollPane(new com.intellij.ui.table.JBTable(model)))
        frame.setSize(300, 200)
        frame.visible = true
        null""")
    text = client.text("pilot_wait_for", text='row "made 2 | 1002"')
    made = re.search(r'row "made 2 \| 1002" \[ref=(e[\d.]+)\]', text).group(1)
    nulled = re.search(r'row "1001" \[ref=(e[\d.]+)\]', client.text("pilot_snapshot", find='row "1001"')).group(1)
    client.call("pilot_evaluate", script="System.gc(); null")
    client.call("pilot_click", ref=made)
    client.call("pilot_click", ref=nulled)
    text = client.text("pilot_snapshot", find='row "')
    assert re.search(r'row "1001" \[ref=e[\d.]+\] \(selected\)', text), text
    client.call("pilot_evaluate", script="""
        java.awt.Window.windows.findAll { it instanceof javax.swing.JFrame && it.title == 'smoke made table' }*.dispose()
        null""")
    client.call("pilot_wait_for", gone='row "1001"')


def test_click_process_mouse_event(client, project):
    step("click: a component that takes clicks in processMouseEvent, with no listener, gets them, not its listening parent")
    listeners = client.text("pilot_evaluate", script="""
        import com.intellij.openapi.actionSystem.*
        System.clearProperty('smoke.clicked')
        def action = new AnAction() {
            void actionPerformed(AnActionEvent e) { System.setProperty('smoke.clicked', 'button') }
            ActionUpdateThread getActionUpdateThread() { ActionUpdateThread.EDT }
        }
        def button = new com.intellij.openapi.actionSystem.impl.ActionButton(action, null, 'smoke', new java.awt.Dimension(40, 40))
        def panel = new javax.swing.JPanel()
        panel.addMouseListener(new java.awt.event.MouseAdapter() {
            void mousePressed(java.awt.event.MouseEvent e) { System.setProperty('smoke.clicked', 'panel') }
        })
        panel.add(button)
        def frame = new javax.swing.JFrame('smoke button')
        frame.contentPane.add(panel)
        frame.setSize(200, 120)
        frame.visible = true
        button.mouseListeners.length""").strip()
    assert listeners == "0", "the button has listeners, so the test proves nothing: " + listeners
    client.call("pilot_wait_for", text='window "smoke button"')
    snapshot = client.text("pilot_snapshot", classes=True, find="ActionButton", limit=500)
    ref = re.search(r'window "smoke button".*?\(class ActionButton\)', snapshot, re.S)
    assert ref, snapshot
    client.call("pilot_click", ref=re.findall(r"\[ref=(e[\d.]+)\]", ref.group(0))[-1])
    clicked = client.text("pilot_evaluate", script="System.getProperty('smoke.clicked')").strip()
    client.call("pilot_evaluate", script="""
        java.awt.Window.windows.findAll { it instanceof javax.swing.JFrame && it.title == 'smoke button' }*.dispose()
        null""")
    assert clicked == "button", clicked


def test_popup_checked(client, project):
    step("snapshot: checked rows of an action popup")
    client.call("pilot_evaluate", script="""
        import com.intellij.openapi.actionSystem.*
        def toggle = { String name, boolean on -> new ToggleAction(name) {
            boolean isSelected(AnActionEvent e) { on }
            void setSelected(AnActionEvent e, boolean state) {}
            ActionUpdateThread getActionUpdateThread() { ActionUpdateThread.EDT }
        } }
        def group = new DefaultActionGroup(toggle('smoke on', true), toggle('smoke off', false))
        def frame = com.intellij.openapi.wm.WindowManager.instance.getFrame(project)
        com.intellij.openapi.ui.popup.JBPopupFactory.instance.createActionGroupPopup('smoke toggles', group,
            com.intellij.ide.DataManager.instance.getDataContext(frame.rootPane),
            com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true).showInCenterOf(frame.rootPane)
        null""")
    client.call("pilot_wait_for", text='listitem "smoke off"')
    text = client.text("pilot_snapshot", find='listitem "smoke')
    assert re.search(r'listitem "smoke on" \[ref=e[\d.]+\] \(checked\)', text), text
    assert not re.search(r'listitem "smoke off" \[ref=e[\d.]+\] \(checked\)', text), text
    client.call("pilot_type", key="Escape")
    client.call("pilot_wait_for", gone='listitem "smoke off"')


def test_settled(client, project):
    step("wait_for settled: until the UI stops changing")
    # a label that changes 5 times, 200 ms apart: settled returns only after the last change
    client.call("pilot_evaluate", script="""
        def frame = new javax.swing.JFrame('smoke settled')
        def label = new javax.swing.JLabel('settling 0')
        frame.contentPane.add(label)
        frame.setSize(300, 100)
        frame.visible = true
        int n = 0
        def timer = new javax.swing.Timer(200, null)
        timer.addActionListener { if (++n == 5) { timer.stop(); label.text = 'settled done' } else { label.text = 'settling ' + n } }
        timer.start()
        null""")
    client.call("pilot_wait_for", settled=True)
    text = client.text("pilot_snapshot", find="settl")
    assert 'label "settled done"' in text, text
    client.call("pilot_evaluate", script="""
        java.awt.Window.windows.findAll { it instanceof javax.swing.JFrame && it.title == 'smoke settled' }*.dispose()
        null""")
    client.call("pilot_wait_for", gone='label "settled done"')


def test_tasks(client, project):
    step("log tasks and wait_for task: a named background task")
    # a task of about 3 s, in 30 steps, so it shows its progress
    client.call("pilot_evaluate", script="""
        new com.intellij.openapi.progress.Task.Backgroundable(project, 'Smoke task', true) {
            void run(com.intellij.openapi.progress.ProgressIndicator indicator) {
                indicator.indeterminate = false
                30.times { indicator.text = 'step ' + it; indicator.fraction = it / 30; Thread.sleep(100) }
            }
        }.queue()
        null""")
    # listed from the status bar once it shows there, with its project, and only once (not also as the task's own progress)
    text = poll(lambda: client.text("pilot_log", tasks=True, contains="step"), lambda t: "(cancellable)" in t)
    assert re.search(r'^"Smoke task": step \d+ \(\d+%\) \(cancellable\) \[[^]]+]$', text), text
    start = time.monotonic()
    client.call("pilot_wait_for", task="smoke TASK", timeout=20)
    assert time.monotonic() - start > 1, time.monotonic() - start  # it waited for the task, not only the settle time
    text = client.text("pilot_log", tasks=True, contains="smoke task")
    assert "No running background task contains" in text, text

    step("log tasks and wait_for task: a task with no title and no text")
    client.call("pilot_evaluate", script="""
        new com.intellij.openapi.progress.Task.Backgroundable(project, '', false) {
            void run(com.intellij.openapi.progress.ProgressIndicator indicator) { Thread.sleep(3000) }
        }.queue()
        null""")
    # listed under its indicator's class name; matching it against a name must not fail
    text = poll(lambda: client.text("pilot_log", tasks=True), lambda t: "[" in t)
    assert "No background task runs" not in text, text
    client.call("pilot_wait_for", task="no such task", timeout=5)
    client.call("pilot_wait_for", idle=True, timeout=20)


def test_terminal(client, project):
    step("log and wait_for: Terminal tabs")
    client.call("pilot_action", id="ActivateTerminalToolWindow", wait=0)
    client.call("pilot_wait_for", text='label "Terminal"', timeout=30)
    # the terminal's editor shows once its session has started, which took over 10 s on a loaded machine; other
    # projects may have one too
    editor = poll(lambda: re.search(r'label "Terminal".*?text "Editor" \[ref=(e\d+)\]', project_window(client, project), re.S),
                  bool, tries=150)
    assert editor, "no Terminal editor:\n" + project_window(client, project)
    name = os.path.basename(project)
    marker = "smoketerm-" + name[-8:]  # other projects' terminals are also titled Terminal: Local
    # keys typed while the shell starts can arrive reordered ("rmecho smokete-…"): wait for its prompt, which shows the folder
    text = poll(lambda: client.text("pilot_log", consoles=True, session="Terminal", lines=5), lambda t: name in t, tries=150)
    assert name in text, "no shell prompt in the Terminal:\n" + text
    client.call("pilot_type", ref=editor.group(1), text="echo " + marker, key="Enter")
    client.call("pilot_wait_for", console=marker, session="Terminal", timeout=60)
    # the command's output line, not only its echo
    text = poll(lambda: client.text("pilot_log", consoles=True, session="Terminal", lines=20),
                lambda t: marker in t.splitlines(), tries=50)
    assert marker in text.splitlines(), text
    text = client.text("pilot_log", sessions=True, session="terminal")
    assert re.match(r"Terminal: .+ \(Terminal tab, (running|exited|state unknown)\)$", text.splitlines()[0]), text


def test_no_ide_errors(client, project):
    step("no IDE errors")
    # but one IDE bug (2025.2): opening a new folder with a .java file in it, as this test does, its directory project
    # configurator refreshes files on the EDT, which SlowOperations logs as an error
    known = ("com.intellij.diagnostic.MessagePool.instance.getFatalErrors(true, true).count { "
             "!(it.throwableText.contains('Slow operations are prohibited on EDT') "
             "&& it.throwableText.contains('runDirectoryProjectConfigurators')) }")
    if client.text("pilot_evaluate", script=known).strip() != "0":
        raise AssertionError("the IDE caught exceptions:\n" + client.text("pilot_log", errors=True, lines=20))


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, http.client.HTTPException) as e:
        sys.exit(f"FAILED: {e}")
