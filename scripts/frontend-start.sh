#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SESSION_NAME="hanium-frontend"

mkdir -p "$ROOT_DIR/.runtime"
screen -S "$SESSION_NAME" -X quit >/dev/null 2>&1 || true

if lsof -nP -iTCP:5173 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Frontend start aborted because port 5173 is already in use." >&2
  exit 1
fi

screen -dmS "$SESSION_NAME" "$ROOT_DIR/scripts/frontend-run.sh"

for _ in {1..20}; do
  if curl -fsS http://127.0.0.1:5173/ >/dev/null 2>&1; then
    echo "Frontend started in screen session '$SESSION_NAME' on 127.0.0.1:5173"
    exit 0
  fi
  sleep 0.5
done

echo "Frontend failed to start. Check $ROOT_DIR/.runtime/frontend.log" >&2
exit 1
