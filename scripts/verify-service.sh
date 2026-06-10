#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set -a
source "$ROOT_DIR/Back/.env"
set +a

HEALTH="$(curl -fsS http://127.0.0.1:8000/health)"
READINESS="$(curl -fsS http://127.0.0.1:8000/readiness)"

printf '%s' "$HEALTH" | jq -e '.status == "ok" and .database == "connected"' >/dev/null
printf '%s' "$READINESS" | jq -e '
  .status == "ready"
  and .checks.database.ok
  and .checks.worker.ok
  and .checks.ollama.ok
  and .checks.disk.ok
' >/dev/null

MYSQL_PWD="$DB_PASSWORD" /opt/homebrew/opt/mysql/bin/mysql \
  -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$DB_NAME" -N \
  -e "SELECT COUNT(*) FROM schema_migrations;" >/dev/null

curl -fsSI http://127.0.0.1:5173/ >/dev/null

echo "Service verification passed"
printf '%s\n' "$READINESS" | jq '{status, checks: {database: .checks.database, worker: .checks.worker, ollama: .checks.ollama, disk: .checks.disk}}'
