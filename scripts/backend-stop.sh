#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$ROOT_DIR/.runtime/backend.pid"
SESSION_NAME="hanium-backend"
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

stop_if_project_backend() {
  local pid="$1"

  [[ -n "$pid" ]] || return 0
  if ! kill -0 "$pid" >/dev/null 2>&1; then
    return 0
  fi
  if ! is_project_backend_process "$pid"; then
    echo "Refusing to stop non-project process: pid=$pid" >&2
    return 0
  fi
  kill "$pid" >/dev/null 2>&1 || true
}

if [[ -f "$PID_FILE" ]]; then
  PID="$(cat "$PID_FILE")"
  stop_if_project_backend "$PID"
fi

screen -S "$SESSION_NAME" -X quit >/dev/null 2>&1 || true

while IFS= read -r PID; do
  stop_if_project_backend "$PID"
done < <(port_pids)

for _ in {1..20}; do
  if [[ -z "$(port_pids)" ]]; then
    rm -f "$PID_FILE"
    echo "Backend stopped"
    exit 0
  fi
  sleep 0.5
done

echo "Backend did not stop because port $PORT is still in use." >&2
exit 1
