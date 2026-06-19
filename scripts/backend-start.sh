#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SESSION_NAME="hanium-backend"
HOST="127.0.0.1"
PORT="${BACKEND_PORT:-8000}"

port_pids() {
  lsof -nP -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true
}

process_cwd() {
  lsof -a -p "$1" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1
}

process_command() {
  ps -p "$1" -o command= 2>/dev/null || true
}

is_project_backend_process() {
  local pid="$1"
  local cwd
  local command

  cwd="$(process_cwd "$pid")"
  command="$(process_command "$pid")"

  [[ "$cwd" == "$ROOT_DIR"* || "$command" == *"$ROOT_DIR"* || ( "$cwd" == "$ROOT_DIR/Back"* && "$command" == *"app.main:app"* ) ]]
}

describe_process() {
  local pid="$1"
  local cwd
  local command

  cwd="$(process_cwd "$pid")"
  command="$(process_command "$pid")"
  echo "pid=$pid cwd=${cwd:-unknown} command=${command:-unknown}"
}

stop_existing_project_backend() {
  local pid
  local found=0

  screen -S "$SESSION_NAME" -X quit >/dev/null 2>&1 || true

  while IFS= read -r pid; do
    [[ -n "$pid" ]] || continue
    found=1
    if ! is_project_backend_process "$pid"; then
      echo "Backend start aborted because port $PORT is used by a non-project process." >&2
      describe_process "$pid" >&2
      exit 1
    fi
    echo "Stopping existing project backend on port $PORT: $(describe_process "$pid")"
    kill "$pid" >/dev/null 2>&1 || true
  done < <(port_pids)

  [[ "$found" -eq 1 ]] || return 0

  for _ in {1..20}; do
    if [[ -z "$(port_pids)" ]]; then
      return 0
    fi
    sleep 0.5
  done

  echo "Backend start aborted because project backend did not release port $PORT." >&2
  exit 1
}

mkdir -p "$ROOT_DIR/.runtime"
stop_existing_project_backend

screen -dmS "$SESSION_NAME" "$ROOT_DIR/scripts/backend-run.sh"

for _ in {1..20}; do
  if curl -fsS "http://$HOST:$PORT/health" >/dev/null 2>&1 &&
    curl -fsS "http://$HOST:$PORT/readiness" |
      "$ROOT_DIR/.venv/bin/python" -c 'import json, sys; sys.exit(0 if json.load(sys.stdin).get("status") == "ready" else 1)' >/dev/null 2>&1; then
    echo "Backend started in screen session '$SESSION_NAME' on $HOST:$PORT"
    exit 0
  fi
  sleep 0.5
done

echo "Backend failed to start. Check $ROOT_DIR/.runtime/backend.log" >&2
exit 1
