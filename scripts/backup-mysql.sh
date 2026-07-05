#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

BACKUP_DIR="${BACKUP_DIR:-$PROJECT_ROOT/storage/backups}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
BACKUP_LOG_PATH="${BACKUP_LOG_PATH:-$PROJECT_ROOT/storage/logs/backup.log}"
MYSQLDUMP_BIN="${MYSQLDUMP_BIN:-mysqldump}"

log() {
    local message="$1"
    local line
    line="$(date '+%Y-%m-%dT%H:%M:%S%z') [backup-mysql] $message"
    echo "$line"

    mkdir -p "$(dirname "$BACKUP_LOG_PATH")"
    printf '%s\n' "$line" >> "$BACKUP_LOG_PATH"
}

require_env() {
    local name="$1"
    if [[ -z "${!name:-}" ]]; then
        log "ERROR: required environment variable $name is empty"
        exit 1
    fi
}

require_env DB_HOST
require_env DB_PORT
require_env DB_NAME
require_env DB_USERNAME
require_env DB_PASSWORD

if ! [[ "$BACKUP_RETENTION_DAYS" =~ ^[0-9]+$ ]]; then
    log "ERROR: BACKUP_RETENTION_DAYS must be a non-negative integer"
    exit 1
fi

if ! command -v "$MYSQLDUMP_BIN" >/dev/null 2>&1; then
    log "ERROR: mysqldump executable not found: $MYSQLDUMP_BIN"
    exit 1
fi

mkdir -p "$BACKUP_DIR"

timestamp="$(date '+%Y%m%d_%H%M%S')"
safe_db_name="${DB_NAME//[^A-Za-z0-9_.-]/_}"
backup_file="$BACKUP_DIR/${safe_db_name}_${timestamp}.sql.gz"
tmp_file="$backup_file.tmp"

cleanup_tmp() {
    rm -f "$tmp_file"
}
trap cleanup_tmp EXIT

validate_backup_file() {
    local file="$1"
    local size

    if ! gzip -t "$file"; then
        log "ERROR: backup integrity check failed (corrupt gzip): $file"
        rm -f "$file"
        exit 1
    fi

    size="$(wc -c < "$file" | tr -d '[:space:]')"
    if [[ "$size" -lt 200 ]]; then
        log "ERROR: backup file suspiciously small (possibly empty dump): $file ($size bytes)"
        rm -f "$file"
        exit 1
    fi
}

log "backup started: db=$DB_NAME host=$DB_HOST port=$DB_PORT backup=$backup_file"

if MYSQL_PWD="$DB_PASSWORD" "$MYSQLDUMP_BIN" \
    --host="$DB_HOST" \
    --port="$DB_PORT" \
    --user="$DB_USERNAME" \
    --protocol=tcp \
    --single-transaction \
    --routines \
    --triggers \
    --events \
    --databases "$DB_NAME" \
    | gzip -c > "$tmp_file"; then
    mv "$tmp_file" "$backup_file"
    validate_backup_file "$backup_file"
    log "backup completed: $backup_file ($(du -h "$backup_file" | awk '{print $1}'))"
else
    rm -f "$tmp_file"
    log "ERROR: mysqldump failed; incomplete backup removed"
    exit 1
fi

deleted_count=0
while IFS= read -r old_backup_file; do
    rm -f "$old_backup_file"
    deleted_count=$((deleted_count + 1))
    log "deleted old backup: $old_backup_file"
done < <(find "$BACKUP_DIR" -type f -name "${safe_db_name}_*.sql.gz" -mtime +"$BACKUP_RETENTION_DAYS" -print)

log "retention cleanup completed: deleted=$deleted_count retentionDays=$BACKUP_RETENTION_DAYS"
