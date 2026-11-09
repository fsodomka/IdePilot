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
            "pilot_wait_for", "pilot_evaluate", "pilot_screenshot", "pilot_window", "pilot_editor", "pilot_settings", "pilot_scroll"} <= names, names

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
    # before opening, so the IDE sets the folder up as a Java module and checks the file
    with open(os.path.join(project, "A.java"), "w", encoding="utf-8") as f:  # an error to list and fix
        f.write("class A {\n    void f() {\n        String s = 1;\n    }\n}\n")
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

    step("action find: by menu text, with shortcut, and limited")
    text, _ = client.call("pilot_action", find="reformat code")
    assert re.search(r"^ReformatCode – Reformat Code – \S", text, re.M), text
    text, _ = client.call("pilot_action", find="e", limit=3)
    assert len(text.splitlines()) == 4 and re.fullmatch(r"\.\.\. \d+ more; narrow find", text.splitlines()[-1]), text

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
    client.call("pilot_type", text="replaced by smoke", clear=True)
    for _ in range(25):
        text, _ = client.call("pilot_evaluate", script=editor_text)
        if text == "replaced by smoke":
            break
        time.sleep(0.2)
    assert text == "replaced by smoke", text
    client.call("pilot_type", key="Escape")

    step("hover shows a tooltip over the Project stripe button")
    window = project_window_ref(client, project)
    # the stripe button is on; the Project tool window header has a "Project" button too, without a tooltip
    stripe = ref_of(project_window(client, project), r'push button "Project" \[ref=[^]]+][^\n]*\(on\)')
    assert stripe, "no Project stripe button"
    # the tooltip (HelpTooltip) shows in a heavyweight window owned by the frame, not as an IdeTooltip
    shown = "component.ownedWindows.any { it.showing }"
    client.call("pilot_click", ref=stripe, hover=True)
    for _ in range(25):  # tooltips show after the initial delay
        text, _ = client.call("pilot_evaluate", script=shown, ref=window)
        if text.strip() == "true":
            break
        time.sleep(0.2)
    else:
        state, _ = client.call("pilot_evaluate", ref=window, script="[owned: component.ownedWindows.collect { "
                               "it.class.simpleName + (it.showing ? '' : ' hidden') }, mouse: java.awt.MouseInfo.pointerInfo?.location, "
                               "frame: component.bounds, active: component.active]")
        raise AssertionError("no tooltip after hovering the Project stripe button: " + state)
    client.call("pilot_type", key="Escape")

    step("drag an editor tab onto another reorders them")
    tabs = lambda: re.findall(r'page tab "(\w+\.txt)"', project_window(client, project))
    before = tabs()
    assert sorted(before) == ["hello.txt", "other.txt"], before
    snapshot = project_window(client, project)
    client.call("pilot_click", ref=ref_of(snapshot, rf'page tab "{re.escape(before[1])}"'),
                to=ref_of(snapshot, rf'page tab "{re.escape(before[0])}"'))
    for _ in range(25):
        if tabs() == before[::-1]:
            break
        time.sleep(0.2)
    else:
        raise AssertionError(f"tabs not reordered: {tabs()}")

    step("editor: text, problems, actions at the caret, apply a fix")
    client.call("pilot_open", path=os.path.join(project, "A.java"), line=3)
    client.call("pilot_wait_for", text='page tab "A.java"', idle=True, timeout=60)
    text, _ = client.call("pilot_editor", lines="3")
    assert re.match(r"A\.java – 6 lines, caret 3:\d+\n3\| {9}String s = 1;$", text), text
    for _ in range(50):  # analysis runs after the file opens
        text, _ = client.call("pilot_editor", problems=True)
        if "Incompatible types" in text and "not finished" not in text:
            break
        time.sleep(0.2)
    assert re.search(r"^  3:20 ERROR: Incompatible types", text, re.M), text
    text, _ = client.call("pilot_editor", caret="3:20", actions=True)
    assert "\n  Change variable 's' type to 'int' (error fix)" in text, text
    try:
        client.call("pilot_editor", apply="no such fix")
        raise AssertionError("applying a missing action did not fail")
    except AssertionError as e:
        assert "Change variable 's' type to 'int'" in str(e), e
    client.call("pilot_editor", apply="Change variable 's' type to 'int'")
    for _ in range(25):
        text, _ = client.call("pilot_editor", lines="3")
        if text.endswith("int s = 1;"):
            break
        time.sleep(0.2)
    assert text.endswith("3|         int s = 1;"), text

    step("files changed outside the IDE show after Synchronize")
    client.call("pilot_action", id="SaveAll")  # the fix above is in the document; save it so the overwrite is clean
    client.call("pilot_wait_for", idle=True, timeout=30)
    with open(os.path.join(project, "A.java"), "w", encoding="utf-8") as f:
        f.write("class A {\n    // changed outside\n}\n")
    client.call("pilot_action", id="Synchronize")
    for _ in range(50):
        text, _ = client.call("pilot_editor", lines="2")
        if text.endswith("// changed outside"):
            break
        time.sleep(0.2)
    assert text.endswith("2|     // changed outside"), text

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

    step("scroll: a list to a row by text, by pages, to top and bottom")
    client.call("pilot_evaluate", script="""
        import com.intellij.openapi.ui.DialogWrapper
        import com.intellij.ui.components.*
        def dialog = new DialogWrapper(project, false, DialogWrapper.IdeModalityType.MODELESS) {
            javax.swing.JComponent createCenterPanel() {
                def pane = new JBScrollPane(new JBList((1..300).collect { "scroll item $it".toString() }))
                pane.preferredSize = new java.awt.Dimension(250, 200)
                pane
            }
        }
        dialog.title = 'smoke scroll'
        dialog.init()
        dialog.show()
        null""")
    client.call("pilot_wait_for", text='"scroll item 1"', timeout=10)
    row = ref_of(full_snapshot(client), r'listitem "scroll item 1"')
    text, _ = client.call("pilot_scroll", ref=row, to="SCROLL ITEM 250")
    assert text.startswith('Scrolled to row 250 of 300: "scroll item 250"'), text
    text, _ = client.call("pilot_snapshot", find="scroll item 250")
    assert 'listitem "scroll item 250"' in text, text
    dialog = ref_of(full_snapshot(client), r'^- window "smoke scroll"')
    text, _ = client.call("pilot_scroll", ref=dialog, to="scroll item 1")  # finds the list inside the window
    assert text.startswith('Scrolled to row 1 of 300'), text
    list_row = ref_of(full_snapshot(client), r'listitem "scroll item 1"')
    text, _ = client.call("pilot_scroll", ref=list_row, direction="down", pages=2)
    assert text.startswith("Scrolled down: showing ") and "(top)" not in text, text
    text, _ = client.call("pilot_snapshot", find='"scroll item 1"')
    assert 'listitem "scroll item 1"' not in text, text
    list_row = ref_of(full_snapshot(client), r'listitem "scroll item \d+"')
    text, _ = client.call("pilot_scroll", ref=list_row, direction="bottom")
    assert "(bottom)" in text, text
    text, _ = client.call("pilot_scroll", ref=list_row, direction="top")
    assert "(top)" in text, text
    try:
        client.call("pilot_scroll", ref=list_row, to="no such item")
        raise AssertionError("pilot_scroll found a missing row")
    except AssertionError as e:
        assert "No row of 300" in str(e), e
    step("snapshot allRows: rows that do not show, clicked into view")
    text, _ = client.call("pilot_snapshot", find='"scroll item 150"')
    assert text.startswith("(no line contains"), text
    text, _ = client.call("pilot_snapshot", find='"scroll item 150"', allRows=True)
    assert re.search(r'listitem "scroll item 150" \[ref=[\w.]+] \(offscreen\)', text), text
    text, _ = client.call("pilot_snapshot", find='"scroll item 1"', allRows=True)
    assert re.search(r'listitem "scroll item 1" \[ref=[\w.]+]$', text, re.M), text  # shows: not marked
    client.call("pilot_click", ref=ref_of(client.call("pilot_snapshot", find='"scroll item 150"', allRows=True)[0],
                                          r'listitem "scroll item 150"'))
    text, _ = client.call("pilot_snapshot", find='"scroll item 150"')
    assert re.search(r'listitem "scroll item 150" \[ref=[\w.]+] \(selected\)$', text, re.M), text
    # past the first 200 rows: the 200 around those that show, the first listed saying how many are not
    client.call("pilot_scroll", ref=list_row, direction="bottom")
    text, _ = client.call("pilot_snapshot", find='"scroll item 300"', allRows=True)
    assert re.search(r'listitem "scroll item 300" \[ref=[\w.]+]$', text, re.M), text
    text, _ = client.call("pilot_snapshot", find="rows above not listed", allRows=True)
    assert re.search(r'listitem "scroll item 101" \[ref=[\w.]+] \(offscreen\) \(100 rows above not listed\)$', text, re.M), text
    client.call("pilot_window", ref=dialog, close=True)
    client.call("pilot_wait_for", gone="scroll item", timeout=10)

    step("type hold and release: modifier keys down across calls, carried by keys and clicks")
    # a field that records its key and mouse events, in a dialog's client property, read back by evaluate
    client.call("pilot_evaluate", script="""
        import com.intellij.openapi.ui.DialogWrapper
        import java.awt.event.*
        def dialog = new DialogWrapper(project, false, DialogWrapper.IdeModalityType.MODELESS) {
            javax.swing.JComponent createCenterPanel() {
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
                field
            }
        }
        dialog.title = 'smoke keys'
        dialog.init()
        dialog.show()
        null""")
    client.call("pilot_wait_for", text='window "smoke keys"', timeout=10)
    field = ref_of(full_snapshot(client), r'text "smoke keys"')
    text, _ = client.call("pilot_type", ref=field, hold="ctrl")
    assert text == f"Held ctrl in {field}; holding ctrl until released", text
    client.call("pilot_type", key="F5")
    client.call("pilot_click", ref=field)
    text, _ = client.call("pilot_type", release="ctrl")
    assert text == "Released ctrl", text
    try:
        client.call("pilot_type", release="ctrl")
        raise AssertionError("released a key that was not held")
    except AssertionError as e:
        assert "Not held: ctrl; nothing is held" in str(e), e
    client.call("pilot_click", ref=field)
    client.call("pilot_wait_for", time=0.5)
    text, _ = client.call("pilot_evaluate", script="""
        def field = com.intellij.util.ui.UIUtil.findComponentOfType(
            java.awt.Window.windows.find { it.showing && it.title == 'smoke keys' }.rootPane, com.intellij.ui.components.JBTextField)
        field.getClientProperty('smoke.log').join(', ')""")
    ctrl = 128  # InputEvent.CTRL_DOWN_MASK
    assert text == f"down Ctrl {ctrl}, down F5 {ctrl}, up F5 {ctrl}, click {ctrl}, up Ctrl 0, click 0", text
    client.call("pilot_window", ref=field, close=True)
    client.call("pilot_wait_for", gone='window "smoke keys"', timeout=10)

    step("type: newline and tab in text are refused, and pressed with key")
    # pressed, Enter would close the dialog through its default OK button; typed alone, a text area drops them
    client.call("pilot_evaluate", script="""
        def dialog = new com.intellij.openapi.ui.DialogWrapper(project, false, com.intellij.openapi.ui.DialogWrapper.IdeModalityType.MODELESS) {
            javax.swing.JComponent createCenterPanel() { new javax.swing.JTextArea('smoke area', 3, 20) }
        }
        dialog.title = 'smoke area'
        dialog.init()
        dialog.show()
        null""")
    client.call("pilot_wait_for", text='window "smoke area"', timeout=10)
    area = ref_of(full_snapshot(client), r'text "smoke area"')
    for bad in ("a\nb", "a\tb"):
        try:
            client.call("pilot_type", ref=area, text=bad)
            raise AssertionError(f"typed {bad!r}")
        except AssertionError as e:
            assert "cannot hold newlines, tabs" in str(e), e
    client.call("pilot_type", ref=area, text="a", clear=True)
    client.call("pilot_type", ref=area, key="Enter")  # a text area takes Enter before the dialog's default button
    client.call("pilot_type", ref=area, text="b")
    text, _ = client.call("pilot_evaluate", script="""
        def window = java.awt.Window.windows.find { it.showing && it.title == 'smoke area' }
        window == null ? 'closed' : com.intellij.util.ui.UIUtil.findComponentOfType(window.rootPane, javax.swing.JTextArea).text""")
    assert text == "a\nb", repr(text)
    client.call("pilot_window", ref=area, close=True)
    client.call("pilot_wait_for", gone='window "smoke area"', timeout=10)

    step("window: resize, activate, close a dialog")
    window = project_window_ref(client, project)
    text, _ = client.call("pilot_window", ref=window, size="1000x700")
    assert text.endswith("to 1000x700"), text
    snapshot = full_snapshot_bounds(client)
    assert re.search(r"\[ref=" + re.escape(window) + r"][^\n]* 1000x700", snapshot), "size not applied:\n" + snapshot
    client.call("pilot_window", ref=window)
    text, _ = client.call("pilot_action", id="ShowSettings", ref=window, wait=5)
    assert 'window "Settings"' in text, "the action's result does not list the dialog it opened:\n" + text
    client.call("pilot_wait_for", text='window "Settings"', timeout=30)
    dialog = ref_of(full_snapshot(client), r'^- window "Settings"')

    step("evaluate without ref runs while a modal dialog is open")
    text, _ = client.call("pilot_evaluate", script="1 + 1", timeout=5)
    assert text.strip() == "2", text

    step("type selects a combo box item by its shown text")
    client.call("pilot_wait_for", text='combo box "Theme:"', timeout=30)
    theme = ref_of(full_snapshot(client), r'combo box "Theme:"')
    try:
        client.call("pilot_type", ref=theme, text="no such theme")
        raise AssertionError("selecting a missing item did not fail")
    except AssertionError as e:
        assert "items: " in str(e) and "Light" in str(e) and "LafReference" not in str(e), e
    selected = "component.selectedItem.name"
    laf = "com.intellij.ide.ui.LafManager.instance.currentUIThemeLookAndFeel.name"
    before, _ = client.call("pilot_evaluate", script=laf, ref=dialog)  # without ref, the modal Settings blocks it
    current, _ = client.call("pilot_evaluate", script=selected, ref=theme)
    other = "Light" if current.strip() != "Light" else "Dark"
    client.call("pilot_type", ref=theme, text=other.lower())  # case-insensitive fallback
    text, _ = client.call("pilot_evaluate", script=selected, ref=theme)
    assert text.strip() == other, text
    # the Theme combo applies its choice at once, so the theme changing shows its listeners ran as on a real pick
    text, _ = client.call("pilot_evaluate", script=laf, ref=dialog)
    assert text.strip() == other, text
    client.call("pilot_type", ref=theme, text=before.strip())  # restore
    text, _ = client.call("pilot_evaluate", script=laf, ref=dialog)
    assert text == before, f"theme not restored to {before}: {text}"

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

    step("wait_for console: only output since the last acting call; consoles by session")
    client.call("pilot_evaluate", script="null")  # acts: output before it no longer counts
    try:
        client.call("pilot_wait_for", console="smoke-plain", timeout=1)
        raise AssertionError("pilot_wait_for matched a finished run's console output")
    except AssertionError as e:
        assert "not in new output" in str(e), e
    # the shell run may have reused the finished plain run's tab, so pick the shell's
    text, _ = client.call("pilot_log", consoles=True, session="SMOKE SHELL", lines=5)
    assert re.match(r"== smoke shell \(Run, terminated, exit code 0\b", text) and "== smoke plain" not in text, text
    text, _ = client.call("pilot_log", consoles=True, session="smoke", running=True)
    assert text.startswith("(no running Run or Debug consoles"), text
    text, _ = client.call("pilot_log", sessions=True, session="smoke shell")
    # no pid: a terminal console's process handler does not expose its process
    assert re.fullmatch(r"smoke shell \(Run, terminated, exit code 0(, pid \d+[^)]*)?\)", text.strip()), text

    # a run started by one acting call keeps its output for a wait after another acting call
    client.call("pilot_evaluate", script="""
        def command = com.intellij.openapi.util.SystemInfo.isWindows ? ['cmd.exe', '/c', 'echo smoke-late'] : ['sh', '-c', 'echo smoke-late']
        new com.intellij.execution.RunContentExecutor(project, new com.intellij.execution.process.OSProcessHandler(
            new com.intellij.execution.configurations.GeneralCommandLine(command))).withTitle('smoke late').run()
        null""")
    for _ in range(50):  # pilot_log does not act, so it does not move the marks
        text, _ = client.call("pilot_log", consoles=True, session="smoke late", contains="smoke-late")
        if "\nsmoke-late" in text:
            break
        time.sleep(0.2)
    assert "\nsmoke-late" in text, text
    client.call("pilot_evaluate", script="null")
    client.call("pilot_wait_for", console="smoke-late", session="smoke late", timeout=5)
    client.call("pilot_evaluate", script="null")  # the run was there at the previous call: now its output is old
    try:
        client.call("pilot_wait_for", console="smoke-late", session="smoke late", timeout=1)
        raise AssertionError("pilot_wait_for matched output from before the last two acting calls")
    except AssertionError as e:
        assert "not in new output" in str(e), e

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
    text, _ = client.call("pilot_log", tests=True, session="smoke tests")
    assert re.match(r"== smoke tests \(finished\)\n3 tests: 1 passed, 1 failed, 1 ignored", text), text
    assert "FAILED SmokeSuite.bad" in text and "expected 1 but was 2" in text and "Smoke.java:7" in text, text
    assert "SmokeSuite.good" not in text, text
    text, _ = client.call("pilot_log", tests=True, session="smoke tests", contains="good")
    assert "PASSED SmokeSuite.good (3 ms)" in text and "SmokeSuite.bad" not in text, text
    text, _ = client.call("pilot_log", tests=True, session="no such tab")
    assert text.startswith("(no test runs"), text

    step("settings: registry, Advanced Settings and service state, defaults included")
    text, _ = client.call("pilot_settings", find="ide.experimental.ui")
    assert re.search(r"^registry ide\.experimental\.ui = (true|false)", text, re.M), text
    # set by this test at its start; its default is not written to ide.general.xml
    text, _ = client.call("pilot_settings", find="GeneralSettings.confirmOpenNewProject2")
    assert "app ide.general.xml GeneralSettings.confirmOpenNewProject2 = 0" in text, text
    text, _ = client.call("pilot_settings", find="GeneralSettings.", limit=200)
    assert re.search(r"GeneralSettings\.\w+ = .* \(default\)$", text, re.M), text
    text, _ = client.call("pilot_settings", find="advanced ", limit=1)
    assert re.match(r'advanced \S+ ".+" = .* \((default|changed)\)$', text.splitlines()[0]), text
    text, _ = client.call("pilot_settings", find="zz-no-such-setting")
    assert text.startswith("(no setting contains"), text

    step("log and wait_for: Terminal tabs")
    client.call("pilot_action", id="ActivateTerminalToolWindow", wait=0)
    client.call("pilot_wait_for", text='label "Terminal"', timeout=30)
    for _ in range(50):  # the terminal's editor shows once its session has started; other projects may have one too
        match = re.search(r'label "Terminal".*?text "Editor" \[ref=(e\d+)\]', project_window(client, project), re.S)
        if match:
            break
        time.sleep(0.2)
    assert match, "no Terminal editor:\n" + project_window(client, project)
    marker = "smoketerm-" + os.path.basename(project)[-8:]  # other projects' terminals are also titled Terminal: Local
    # keys typed while the shell starts can arrive reordered ("rmecho smokete-…"): wait for its prompt, which shows the folder
    for _ in range(150):
        text, _ = client.call("pilot_log", consoles=True, session="Terminal", lines=5)
        if os.path.basename(project) in text:
            break
        time.sleep(0.2)
    assert os.path.basename(project) in text, "no shell prompt in the Terminal:\n" + text
    client.call("pilot_type", ref=match.group(1), text="echo " + marker, key="Enter")
    client.call("pilot_wait_for", console=marker, session="Terminal", timeout=60)
    for _ in range(50):  # the command's output line, not only its echo
        text, _ = client.call("pilot_log", consoles=True, session="Terminal", lines=20)
        if marker in text.splitlines():
            break
        time.sleep(0.2)
    assert marker in text.splitlines(), text
    text, _ = client.call("pilot_log", sessions=True, session="terminal")
    assert re.match(r"Terminal: .+ \(Terminal tab, (running|exited|state unknown)\)$", text.splitlines()[0]), text

    step("no IDE errors")
    # but one IDE bug (2025.2): opening a new folder with a .java file in it, as this test does, its directory project
    # configurator refreshes files on the EDT, which SlowOperations logs as an error
    known = ("com.intellij.diagnostic.MessagePool.instance.getFatalErrors(true, true).count { "
             "!(it.throwableText.contains('Slow operations are prohibited on EDT') "
             "&& it.throwableText.contains('runDirectoryProjectConfigurators')) }")
    others, _ = client.call("pilot_evaluate", script=known)
    if others.strip() != "0":
        text, _ = client.call("pilot_log", errors=True, lines=20)
        raise AssertionError("the IDE caught exceptions:\n" + text)


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, OSError, http.client.HTTPException) as e:
        sys.exit(f"FAILED: {e}")
