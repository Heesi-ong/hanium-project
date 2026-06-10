#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SESSION_NAME="hanium-backend"

mkdir -p "$ROOT_DIR/.runtime"
screen -S "$SESSION_NAME" -X quit >/dev/null 2>&1 || true

if lsof -nP -iTCP:8000 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Backend start aborted because port 8000 is already in use." >&2
  exit 1
fi

screen -dmS "$SESSION_NAME" "$ROOT_DIR/scripts/backend-run.sh"

for _ in {1..20}; do
  if curl -fsS http://127.0.0.1:8000/health >/dev/null 2>&1; then
    echo "Backend started in screen session '$SESSION_NAME' on 127.0.0.1:8000"
    exit 0
  fi
  sleep 0.5
done

echo "Backend failed to start. Check $ROOT_DIR/.runtime/backend.log" >&2
exit 1
