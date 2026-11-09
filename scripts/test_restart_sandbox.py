"""Tests that restart_sandbox exits only this checkout's sandbox, against a fake IDE Pilot server.

    python -m unittest scripts/test_restart_sandbox.py
"""
import http.server
import json
import os
import sys
import threading
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import restart_sandbox  # noqa: E402


class FakeIde(http.server.BaseHTTPRequestHandler):
    system_path = ""
    scripts = []

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if request["method"] == "tools/call":
            FakeIde.scripts.append(request["params"]["arguments"]["script"])
            result = {"content": [{"type": "text", "text": FakeIde.system_path + "\n"}], "isError": False}
        else:
            result = {}
        body = json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class RestartSandboxTest(unittest.TestCase):
    def setUp(self):
        self.server = http.server.HTTPServer(("127.0.0.1", 0), FakeIde)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/mcp"
        FakeIde.scripts = []

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def run_main(self):
        argv = ["restart_sandbox.py", "--url", self.url, "--token", "t"]
        with mock.patch.object(sys, "argv", argv), mock.patch("subprocess.Popen") as popen:
            with self.assertRaises(SystemExit) as exited:
                restart_sandbox.main()
        return exited.exception, popen

    def test_refuses_an_ide_outside_the_sandbox(self):
        FakeIde.system_path = r"C:\Users\someone\AppData\Local\JetBrains\IntelliJIdea2025.2"
        exited, popen = self.run_main()
        self.assertIn("not this checkout's sandbox", str(exited.code))
        self.assertFalse([s for s in FakeIde.scripts if ".exit(" in s], "asked the IDE to exit")
        popen.assert_not_called()

    def test_is_sandbox(self):
        sandbox = os.path.join(restart_sandbox.ROOT, ".intellijPlatform", "sandbox")
        self.assertTrue(restart_sandbox.is_sandbox(os.path.join(sandbox, "IdePilot", "IU-2025.2", "system_runIde")))
        self.assertFalse(restart_sandbox.is_sandbox(sandbox + "-other" + os.sep + "system"))
        self.assertFalse(restart_sandbox.is_sandbox(os.path.join(restart_sandbox.ROOT, "build")))
        self.assertFalse(restart_sandbox.is_sandbox(None))


if __name__ == "__main__":
    unittest.main()
