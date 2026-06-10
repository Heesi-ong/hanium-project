#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="$ROOT_DIR/.runtime/backups"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

set -a
source "$ROOT_DIR/Back/.env"
set +a
mkdir -p "$BACKUP_DIR"
BACKUP_FILE="$BACKUP_DIR/${DB_NAME}-${TIMESTAMP}.sql"
BACKUP_USER="${DB_BACKUP_USER:-root}"
BACKUP_PASSWORD="${DB_BACKUP_PASSWORD:-}"
trap 'rm -f "$BACKUP_FILE"' ERR

MYSQL_PWD="$BACKUP_PASSWORD" /opt/homebrew/opt/mysql/bin/mysqldump \
  -h "$DB_HOST" -P "$DB_PORT" -u "$BACKUP_USER" \
  --single-transaction --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF \
  --routines --triggers "$DB_NAME" \
  > "$BACKUP_FILE"

echo "$BACKUP_FILE"
