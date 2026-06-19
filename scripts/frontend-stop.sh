#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$ROOT_DIR/.runtime/frontend.pid"

if [[ -f "$PID_FILE" ]]; then
  PID="$(cat "$PID_FILE")"
  if kill -0 "$PID" >/dev/null 2>&1; then
    kill "$PID"
  fi
fi

for _ in {1..20}; do
  if ! lsof -nP -iTCP:5173 -sTCP:LISTEN >/dev/null 2>&1; then
    rm -f "$PID_FILE"
    echo "Frontend stopped"
    exit 0
  fi
  sleep 0.5
done

echo "Frontend did not stop because port 5173 is still in use." >&2
exit 1
