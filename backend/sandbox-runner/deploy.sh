#!/usr/bin/env bash
# Deploy the Jarvis sandbox runner to a VPS over SSH.
#
# Usage:
#   VPS=user@your-vps ./deploy.sh
#   VPS=user@your-vps SANDBOX_TOKEN=$(openssl rand -hex 24) ./deploy.sh
#
# Requires: ssh access to the VPS, docker + docker compose installed there.
set -euo pipefail

VPS="${VPS:?set VPS=user@host}"
DIR="${DIR:-~/jarvis-sandbox}"
TOKEN="${SANDBOX_TOKEN:-$(openssl rand -hex 24)}"

echo "==> Copying sandbox runner to $VPS:$DIR"
ssh "$VPS" "mkdir -p $DIR"
scp server.py Dockerfile docker-compose.yml "$VPS:$DIR/"

echo "==> Building and starting"
ssh "$VPS" "cd $DIR && echo SANDBOX_TOKEN=$TOKEN > .env && docker compose up -d --build"

echo "==> Health check"
ssh "$VPS" "sleep 2 && curl -fsS http://localhost:8700/health"
echo
echo "==> Smoke test (runs 'echo hello' in the sandbox)"
ssh "$VPS" "curl -fsS -X POST http://localhost:8700/ \
  -H 'X-Sandbox-Token: $TOKEN' -H 'Content-Type: application/json' \
  -d '{\"command\": \"echo hello from the sandbox\"}'"
echo
echo
echo "Done. In the Jarvis app settings:"
echo "  Sandbox runner URL:   http://<your-vps-ip>:8700/   (put HTTPS in front for real use)"
echo "  Sandbox token:        $TOKEN"
