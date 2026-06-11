#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_FILE="${1:-$(find "$ROOT_DIR/.runtime/backups" -maxdepth 1 -type f -name '*.sql' -print | sort -r | head -1)}"
[[ -n "$BACKUP_FILE" && -f "$BACKUP_FILE" ]]

set -a
source "$ROOT_DIR/Back/.env"
set +a

VERIFY_DB="${DB_NAME}_backup_verify_$$"
BACKUP_USER="${DB_BACKUP_USER:-root}"
BACKUP_PASSWORD="${DB_BACKUP_PASSWORD:-}"
MYSQL=(/opt/homebrew/opt/mysql/bin/mysql -h "$DB_HOST" -P "$DB_PORT" -u "$BACKUP_USER")
trap 'MYSQL_PWD="$BACKUP_PASSWORD" "${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$VERIFY_DB\`" >/dev/null' EXIT

MYSQL_PWD="$BACKUP_PASSWORD" "${MYSQL[@]}" -e "CREATE DATABASE \`$VERIFY_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
MYSQL_PWD="$BACKUP_PASSWORD" "${MYSQL[@]}" "$VERIFY_DB" < "$BACKUP_FILE"
MYSQL_PWD="$BACKUP_PASSWORD" "${MYSQL[@]}" "$VERIFY_DB" -Nse \
  "SELECT CONCAT('users=', COUNT(*)) FROM users; SELECT CONCAT('migrations=', COUNT(*)) FROM schema_migrations;"
