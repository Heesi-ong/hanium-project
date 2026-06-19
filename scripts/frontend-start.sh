#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mkdir -p "$ROOT_DIR/.runtime"

if lsof -nP -iTCP:5173 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Frontend start aborted because port 5173 is already in use." >&2
  exit 1
fi

echo "Starting frontend in the current terminal on http://127.0.0.1:5173"
echo "Keep this terminal open. Press Ctrl-C to stop the frontend."
exec "$ROOT_DIR/scripts/frontend-run.sh"
