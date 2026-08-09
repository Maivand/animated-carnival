# Jarvis Sandbox Runner

The execution backend for the app's write-and-test loop (`run_command` tool).
Stateless: every request brings the whole workspace, runs one command in a
throwaway temp directory, and returns the output. Zero Python dependencies.

## Protocol

```
GET  /health                          -> {"status": "ok"}

POST /   X-Sandbox-Token: <token>
{ "command": "pytest -q",
  "files": [ {"path": "src/main.py", "content": "..."} ],
  "timeout": 60 }

-> { "exit_code": 0, "stdout": "...", "stderr": "..." }
```

`exit_code` 124 means the command was killed at the timeout. Output is
truncated at 64 kB per stream. Paths may not be absolute or escape the
workspace.

## Run locally

```bash
SANDBOX_TOKEN=devtoken python3 server.py
curl -s -X POST http://localhost:8700/ \
  -H 'X-Sandbox-Token: devtoken' -H 'Content-Type: application/json' \
  -d '{"command":"python3 main.py","files":[{"path":"main.py","content":"print(2+2)"}]}'
```

## Deploy to a VPS

```bash
cd backend/sandbox-runner
VPS=user@your-vps ./deploy.sh          # generates a token, builds, starts, smoke-tests
```

The compose file adds memory/pid/cpu limits and `no-new-privileges`. Still:
this service runs arbitrary code by design. Keep the token secret, firewall
the port to your own devices if you can, and put HTTPS (Caddy/nginx) in
front for anything beyond testing. Extend the Dockerfile with the toolchains
your agents need (node, gradle, gcc, ...).

Then in the app: Settings → Sandbox runner URL + Sandbox token.
