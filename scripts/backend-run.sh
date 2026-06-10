#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR/Back"
echo "$$" > "$ROOT_DIR/.runtime/backend.pid"
exec "$ROOT_DIR/.venv/bin/uvicorn" app.main:app --host 127.0.0.1 --port 8000 \
  >> "$ROOT_DIR/.runtime/backend.log" 2>&1
