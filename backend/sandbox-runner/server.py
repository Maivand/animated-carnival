#!/usr/bin/env python3
"""Jarvis sandbox runner.

The execution backend for the app's CodeWorkbench (docs/JARVIS.md, sandbox
protocol). Receives a workspace snapshot and a command, runs the command in a
throwaway directory, and returns stdout/stderr/exit code:

    POST /  (X-Sandbox-Token: <token>)
    { "command": "pytest -q",
      "files": [ {"path": "src/main.py", "content": "..."} ],
      "timeout": 60 }

    -> { "exit_code": 0, "stdout": "...", "stderr": "..." }

    GET /health -> { "status": "ok" }

Zero dependencies (Python 3.8+ stdlib only). Refuses to serve until
SANDBOX_TOKEN is set. Run it inside the provided Docker container on any VPS;
never expose it without the token and preferably keep it behind HTTPS.
"""

import json
import os
import subprocess
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ.get("SANDBOX_TOKEN", "")
PORT = int(os.environ.get("SANDBOX_PORT", "8700"))
DEFAULT_TIMEOUT = int(os.environ.get("SANDBOX_TIMEOUT", "60"))
MAX_TIMEOUT = int(os.environ.get("SANDBOX_MAX_TIMEOUT", "300"))
MAX_BODY_BYTES = 20 * 1024 * 1024
MAX_OUTPUT_CHARS = 64_000


def clamp_output(text):
    if len(text) <= MAX_OUTPUT_CHARS:
        return text
    return text[:MAX_OUTPUT_CHARS] + "\n...[truncated]"


class Handler(BaseHTTPRequestHandler):
    server_version = "JarvisSandbox/1.0"

    def _send(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "ok"})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if not TOKEN:
            self._send(503, {"error": "server misconfigured: SANDBOX_TOKEN not set"})
            return
        if self.headers.get("X-Sandbox-Token") != TOKEN:
            self._send(401, {"error": "bad or missing X-Sandbox-Token"})
            return

        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_BODY_BYTES:
            self._send(413, {"error": "body missing or too large"})
            return
        try:
            request = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            self._send(400, {"error": "invalid JSON"})
            return

        command = request.get("command", "")
        if not isinstance(command, str) or not command.strip():
            self._send(400, {"error": "missing command"})
            return
        files = request.get("files", [])
        timeout = min(int(request.get("timeout", DEFAULT_TIMEOUT)), MAX_TIMEOUT)

        with tempfile.TemporaryDirectory(prefix="jarvis-run-") as workdir:
            try:
                self._write_files(workdir, files)
            except ValueError as error:
                self._send(400, {"error": str(error)})
                return
            self._send(200, self._run(command, workdir, timeout))

    @staticmethod
    def _write_files(workdir, files):
        root = os.path.realpath(workdir)
        for entry in files:
            path = entry.get("path", "")
            target = os.path.realpath(os.path.join(root, path))
            if not path or os.path.isabs(path) or not target.startswith(root + os.sep):
                raise ValueError("illegal path: %s" % path)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w", encoding="utf-8") as handle:
                handle.write(entry.get("content", ""))

    @staticmethod
    def _run(command, workdir, timeout):
        try:
            completed = subprocess.run(
                command, shell=True, cwd=workdir,
                capture_output=True, text=True, timeout=timeout,
                env={**os.environ, "HOME": workdir},
            )
            return {
                "exit_code": completed.returncode,
                "stdout": clamp_output(completed.stdout),
                "stderr": clamp_output(completed.stderr),
            }
        except subprocess.TimeoutExpired as expired:
            return {
                "exit_code": 124,
                "stdout": clamp_output(expired.stdout or ""),
                "stderr": clamp_output((expired.stderr or "")
                                       + "\n[killed: exceeded %ss timeout]" % timeout),
            }

    def log_message(self, fmt, *args):  # quiet default request logging
        pass


def main():
    if not TOKEN:
        print("WARNING: SANDBOX_TOKEN is not set; all requests will be refused.")
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print("Jarvis sandbox runner listening on :%d" % PORT)
    server.serve_forever()


if __name__ == "__main__":
    main()
