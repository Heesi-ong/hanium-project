#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

BACKUP_DIR="${BACKUP_DIR:-$PROJECT_ROOT/storage/backups}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
BACKUP_LOG_PATH="${BACKUP_LOG_PATH:-$PROJECT_ROOT/storage/logs/backup.log}"
BACKUP_METRICS_PATH="${BACKUP_METRICS_PATH:-$PROJECT_ROOT/storage/metrics/mysql_backup.prom}"
MYSQLDUMP_BIN="${MYSQLDUMP_BIN:-mysqldump}"

log() {
    local message="$1"
    local line
    line="$(date '+%Y-%m-%dT%H:%M:%S%z') [backup-mysql] $message"
    echo "$line"

    mkdir -p "$(dirname "$BACKUP_LOG_PATH")"
    printf '%s\n' "$line" >> "$BACKUP_LOG_PATH"
}

write_metric() {
    local status="$1"  # 1=success, 0=failure
    local now
    now="$(date +%s)"
    mkdir -p "$(dirname "$BACKUP_METRICS_PATH")"
    local tmp_metrics_file="$BACKUP_METRICS_PATH.tmp"

    {
        echo "# HELP mysql_backup_last_run_status Result of the most recent mysqldump backup attempt (1=success, 0=failure)"
        echo "# TYPE mysql_backup_last_run_status gauge"
        echo "mysql_backup_last_run_status $status"
        echo "# HELP mysql_backup_last_attempt_timestamp_seconds Unix timestamp of the most recent backup attempt"
        echo "# TYPE mysql_backup_last_attempt_timestamp_seconds gauge"
        echo "mysql_backup_last_attempt_timestamp_seconds $now"
        if [[ "$status" == "1" ]]; then
            echo "# HELP mysql_backup_last_success_timestamp_seconds Unix timestamp of the most recent successful backup"
            echo "# TYPE mysql_backup_last_success_timestamp_seconds gauge"
            echo "mysql_backup_last_success_timestamp_seconds $now"
        fi
    } > "$tmp_metrics_file"

    mv "$tmp_metrics_file" "$BACKUP_METRICS_PATH"
}

require_env() {
    local name="$1"
    if [[ -z "${!name:-}" ]]; then
        log "ERROR: required environment variable $name is empty"
        write_metric 0
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
        write_metric 0
        exit 1
    fi

    size="$(wc -c < "$file" | tr -d '[:space:]')"
    if [[ "$size" -lt 200 ]]; then
        log "ERROR: backup file suspiciously small (possibly empty dump): $file ($size bytes)"
        rm -f "$file"
        write_metric 0
        exit 1
    fi
}

upload_to_object_storage() {
    local file="$1"
    local object_name
    object_name="$(basename "$file")"

    if [[ -z "${MINIO_ENDPOINT:-}" || -z "${MINIO_ACCESS_KEY:-}" || -z "${MINIO_SECRET_KEY:-}" || -z "${MINIO_BACKUP_BUCKET_NAME:-}" ]]; then
        log "MinIO 원격 반출 건너뜀: MINIO_ENDPOINT/MINIO_ACCESS_KEY/MINIO_SECRET_KEY/MINIO_BACKUP_BUCKET_NAME 중 일부가 설정되지 않았습니다."
        return 0
    fi

    if ! command -v mc >/dev/null 2>&1; then
        log "MinIO 원격 반출 건너뜀: mc 클라이언트를 찾을 수 없습니다."
        return 0
    fi

    if mc alias set backup-remote "$MINIO_ENDPOINT" "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" >/dev/null 2>&1 \
        && mc mb --ignore-existing "backup-remote/$MINIO_BACKUP_BUCKET_NAME" >/dev/null 2>&1 \
        && mc cp "$file" "backup-remote/$MINIO_BACKUP_BUCKET_NAME/$object_name" >/dev/null 2>&1; then
        log "MinIO 원격 반출 완료: $MINIO_BACKUP_BUCKET_NAME/$object_name"
    else
        log "WARNING: MinIO 원격 반출 실패 (로컬 백업은 정상 완료됨): $MINIO_BACKUP_BUCKET_NAME/$object_name"
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
    --no-tablespaces \
    --databases "$DB_NAME" \
    | gzip -c > "$tmp_file"; then
    mv "$tmp_file" "$backup_file"
    validate_backup_file "$backup_file"
    log "backup completed: $backup_file ($(du -h "$backup_file" | awk '{print $1}'))"
    write_metric 1
    upload_to_object_storage "$backup_file"
else
    rm -f "$tmp_file"
    log "ERROR: mysqldump failed; incomplete backup removed"
    write_metric 0
    exit 1
fi

deleted_count=0
while IFS= read -r old_backup_file; do
    rm -f "$old_backup_file"
    deleted_count=$((deleted_count + 1))
    log "deleted old backup: $old_backup_file"
done < <(find "$BACKUP_DIR" -type f -name "${safe_db_name}_*.sql.gz" -mtime +"$BACKUP_RETENTION_DAYS" -print)

log "retention cleanup completed: deleted=$deleted_count retentionDays=$BACKUP_RETENTION_DAYS"
