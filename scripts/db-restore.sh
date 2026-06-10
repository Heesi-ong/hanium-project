#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
  echo "Usage: $0 <backup.sql>" >&2
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set -a
source "$ROOT_DIR/Back/.env"
set +a

echo "Restore target: $DB_NAME"
echo "This command overwrites database contents. Set CONFIRM_RESTORE=$DB_NAME to continue."
[[ "${CONFIRM_RESTORE:-}" == "$DB_NAME" ]]

BACKUP_USER="${DB_BACKUP_USER:-root}"
BACKUP_PASSWORD="${DB_BACKUP_PASSWORD:-}"
MYSQL_PWD="$BACKUP_PASSWORD" /opt/homebrew/opt/mysql/bin/mysql \
  -h "$DB_HOST" -P "$DB_PORT" -u "$BACKUP_USER" "$DB_NAME" < "$1"
