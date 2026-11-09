"""Restart the runIde sandbox with the current build, so an agent can load its changes without help.

    python scripts/restart_sandbox.py [--url http://127.0.0.1:63400/mcp] [--token T] [--log runIde.log]

Exits a running sandbox through IDE Pilot (without the exit confirmation), waits until its port is free, starts
./gradlew runIde detached (output to --log), and waits until the new server answers. The token comes from --token,
IDEPILOT_TOKEN, or idePilotToken in ~/.gradle/gradle.properties. Stdlib only.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def gradle_token():
    path = os.path.join(os.path.expanduser("~"), ".gradle", "gradle.properties")
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                key, _, value = line.strip().partition("=")
                if key.strip() == "idePilotToken":
                    return value.strip()
    except OSError:
        pass
    return None


def status(url, token):
    """The HTTP status of an MCP ping, None if nothing listens, or 0 if it does not answer in time (busy or exiting)."""
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "ping"}).encode("utf-8")
    request = urllib.request.Request(url, body, {"Content-Type": "application/json", "Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status
    except urllib.error.HTTPError as e:
        return e.code
    except urllib.error.URLError as e:
        # refused: the port is free; anything else (a timeout, a reset) means a process still holds it
        return None if isinstance(e.reason, ConnectionRefusedError) else 0
    except ConnectionRefusedError:
        return None
    except OSError:
        return 0


def exit_ide(url, token):
    # not pilot_action Exit: with a project open it asks "Are you sure you want to exit?"; exitConfirmed skips that
    script = "com.intellij.openapi.application.ApplicationManager.application.exit(false, true, false)"
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                       "params": {"name": "pilot_evaluate", "arguments": {"script": script, "timeout": 5}}}).encode("utf-8")
    request = urllib.request.Request(url, body, {"Content-Type": "application/json", "Authorization": "Bearer " + token})
    try:
        urllib.request.urlopen(request, timeout=10).read()
    except OSError:
        pass  # the IDE may close the connection while exiting


def system_path(url, token):
    """The IDE's system directory, as its pilot_evaluate gives it; None if it does not answer."""
    script = "com.intellij.openapi.application.PathManager.systemPath"
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                       "params": {"name": "pilot_evaluate", "arguments": {"script": script, "timeout": 5}}}).encode("utf-8")
    request = urllib.request.Request(url, body, {"Content-Type": "application/json", "Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            result = json.load(response).get("result") or {}
    except (OSError, ValueError):
        return None
    return None if result.get("isError") else result["content"][-1]["text"].strip()


def is_sandbox(path):
    """Whether path is inside this checkout's runIde sandbox."""
    sandbox = os.path.normcase(os.path.join(ROOT, ".intellijPlatform", "sandbox"))
    return path is not None and os.path.normcase(os.path.abspath(path)).startswith(sandbox + os.sep)


def wait(condition, seconds, what):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if condition():
            return
        time.sleep(1)
    sys.exit(f"Timed out after {seconds} s waiting for {what}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="http://127.0.0.1:63400/mcp")
    parser.add_argument("--token", default=os.environ.get("IDEPILOT_TOKEN") or gradle_token())
    parser.add_argument("--log", default=os.path.join(ROOT, "build", "runIde.log"))
    args = parser.parse_args()
    if not args.token:
        sys.exit("Give --token, set IDEPILOT_TOKEN, or set idePilotToken in ~/.gradle/gradle.properties")

    code = status(args.url, args.token)
    if code == 401:
        sys.exit("The server on that port refuses the token; is it another IDE?")
    if code not in (None, 0, 200):
        sys.exit(f"Something else holds that port (HTTP {code}); not the sandbox")
    # the same token opens every IDE with the plugin: exit only this checkout's sandbox, never the IDE it is developed in
    if code == 200 and not is_sandbox(path := system_path(args.url, args.token)):
        sys.exit(f"The IDE on that port is not this checkout's sandbox (system path {path}); not exiting it")
    if code is not None:
        print("Exiting the running sandbox", flush=True)
        exit_ide(args.url, args.token)
        wait(lambda: status(args.url, args.token) is None, 60, "the sandbox to exit")

    print(f"Starting runIde, output in {args.log}", flush=True)
    os.makedirs(os.path.dirname(args.log), exist_ok=True)
    gradlew = os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
    flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    with open(args.log, "w", encoding="utf-8") as log:
        port = urllib.parse.urlsplit(args.url).port or 63400
        subprocess.Popen([gradlew, "runIde", f"-PidePilotPort={port}"], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                         creationflags=flags, start_new_session=os.name != "nt")
    wait(lambda: status(args.url, args.token) == 200, 300, f"the new sandbox's server (see {args.log})")
    print("Sandbox up", flush=True)


if __name__ == "__main__":
    main()
