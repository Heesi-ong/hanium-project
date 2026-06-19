#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT_DIR/Back/.env"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Backend env file is missing: $ENV_FILE" >&2
  echo "Create Back/.env from Back/.env.example before running service verification." >&2
  exit 1
fi

set -a
source "$ENV_FILE"
set +a

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to verify service readiness." >&2
  exit 1
fi

MYSQL_BIN="${MYSQL_BIN:-/opt/homebrew/opt/mysql/bin/mysql}"
if [[ ! -x "$MYSQL_BIN" ]]; then
  echo "mysql client not found or not executable: $MYSQL_BIN" >&2
  echo "Set MYSQL_BIN to the mysql client path and retry." >&2
  exit 1
fi

require_http() {
  local name="$1"
  local url="$2"
  local hint="$3"
  local response

  if ! response="$(curl -fsS "$url" 2>/tmp/hanium-verify-service-curl.err)"; then
    echo "$name is not reachable: $url" >&2
    cat /tmp/hanium-verify-service-curl.err >&2 || true
    echo "$hint" >&2
    exit 1
  fi

  printf '%s' "$response"
}

HEALTH="$(require_http "Backend health endpoint" "http://127.0.0.1:8000/health" \
  "Start the backend first: ./scripts/backend-start.sh")"
READINESS="$(require_http "Backend readiness endpoint" "http://127.0.0.1:8000/readiness" \
  "Start the backend first and inspect .runtime/backend.log if readiness stays unavailable.")"

printf '%s' "$HEALTH" | jq -e '.status == "ok" and .database == "connected"' >/dev/null
printf '%s' "$READINESS" | jq -e '
  .status == "ready"
  and .checks.database.ok
  and .checks.queue.ok
  and .checks.worker.ok
  and .checks.storage.ok
  and .checks.models.ok
  and .checks.ollama.ok
  and .checks.disk.ok
' >/dev/null

MYSQL_PWD="$DB_PASSWORD" "$MYSQL_BIN" \
  -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$DB_NAME" -N \
  -e "SELECT COUNT(*) FROM schema_migrations;" >/dev/null

if ! curl -fsSI http://127.0.0.1:5173/ >/dev/null 2>/tmp/hanium-verify-service-frontend.err; then
  echo "Frontend is not reachable: http://127.0.0.1:5173/" >&2
  cat /tmp/hanium-verify-service-frontend.err >&2 || true
  echo "Start the frontend first: ./scripts/frontend-start.sh" >&2
  exit 1
fi

echo "Service verification passed"
printf '%s\n' "$READINESS" |
  jq '{status, checks: {database: .checks.database, queue: .checks.queue, worker: .checks.worker, storage: .checks.storage, models: .checks.models, ollama: .checks.ollama, disk: .checks.disk}}'
