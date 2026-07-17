#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

BACKUP_DIR="${BACKUP_DIR:-$PROJECT_ROOT/storage/backups}"
BACKUP_RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-14}"
BACKUP_LOG_PATH="${BACKUP_LOG_PATH:-$PROJECT_ROOT/storage/logs/backup.log}"
BACKUP_METRICS_PATH="${BACKUP_METRICS_PATH:-$PROJECT_ROOT/storage/metrics/mysql_backup.prom}"
MYSQLDUMP_BIN="${MYSQLDUMP_BIN:-mysqldump}"

# 백업 암호화 설정.
# BACKUP_ENCRYPTION_PASSPHRASE가 설정되면 .sql.gz 백업을 openssl AES-256으로 암호화한
# .sql.gz.enc 파일을 만들고 평문(.sql.gz)은 삭제합니다. 복구 시 같은 값이 필요합니다.
# 미설정 시에는 기존처럼 평문 백업만 남기되 경고를 남깁니다. 운영에서 암호화를 강제하려면
# BACKUP_ENCRYPTION_REQUIRED=true 로 두면 키가 없을 때 백업을 실패시킵니다.
BACKUP_ENCRYPTION_PASSPHRASE="${BACKUP_ENCRYPTION_PASSPHRASE:-}"
BACKUP_ENCRYPTION_REQUIRED="${BACKUP_ENCRYPTION_REQUIRED:-false}"
BACKUP_ENCRYPTION_PBKDF2_ITER="${BACKUP_ENCRYPTION_PBKDF2_ITER:-200000}"
BACKUP_REMOTE_REQUIRED="${BACKUP_REMOTE_REQUIRED:-false}"
OPENSSL_BIN="${OPENSSL_BIN:-openssl}"

LOCAL_BACKUP_STATUS=0
REMOTE_EXPORT_CONFIGURED=0
REMOTE_EXPORT_STATUS=-1

log() {
    local message="$1"
    local line
    line="$(date '+%Y-%m-%dT%H:%M:%S%z') [backup-mysql] $message"
    echo "$line"

    mkdir -p "$(dirname "$BACKUP_LOG_PATH")"
    printf '%s\n' "$line" >> "$BACKUP_LOG_PATH"
}

metric_value() {
    local metric_name="$1"
    [[ -f "$BACKUP_METRICS_PATH" ]] || return 0
    awk -v metric_name="$metric_name" '$1 == metric_name { print $2; exit }' "$BACKUP_METRICS_PATH"
}

write_metrics() {
    local now
    local previous_local_success
    local previous_remote_success
    now="$(date +%s)"
    previous_local_success="$(metric_value mysql_backup_last_success_timestamp_seconds)"
    previous_remote_success="$(metric_value mysql_backup_remote_export_last_success_timestamp_seconds)"
    mkdir -p "$(dirname "$BACKUP_METRICS_PATH")"
    local tmp_metrics_file="$BACKUP_METRICS_PATH.tmp"

    {
        echo "# HELP mysql_backup_last_run_status Result of the most recent mysqldump backup attempt (1=success, 0=failure)"
        echo "# TYPE mysql_backup_last_run_status gauge"
        echo "mysql_backup_last_run_status $LOCAL_BACKUP_STATUS"
        echo "# HELP mysql_backup_last_attempt_timestamp_seconds Unix timestamp of the most recent backup attempt"
        echo "# TYPE mysql_backup_last_attempt_timestamp_seconds gauge"
        echo "mysql_backup_last_attempt_timestamp_seconds $now"
        echo "# HELP mysql_backup_last_success_timestamp_seconds Unix timestamp of the most recent successful local backup"
        echo "# TYPE mysql_backup_last_success_timestamp_seconds gauge"
        if [[ "$LOCAL_BACKUP_STATUS" == "1" ]]; then
            echo "mysql_backup_last_success_timestamp_seconds $now"
        elif [[ -n "$previous_local_success" ]]; then
            echo "mysql_backup_last_success_timestamp_seconds $previous_local_success"
        fi
        echo "# HELP mysql_backup_remote_export_configured Whether all MinIO remote export settings are configured (1=yes, 0=no)"
        echo "# TYPE mysql_backup_remote_export_configured gauge"
        echo "mysql_backup_remote_export_configured $REMOTE_EXPORT_CONFIGURED"
        echo "# HELP mysql_backup_remote_export_last_run_status Result of the most recent remote export attempt (1=success, 0=failure, -1=not attempted)"
        echo "# TYPE mysql_backup_remote_export_last_run_status gauge"
        echo "mysql_backup_remote_export_last_run_status $REMOTE_EXPORT_STATUS"
        if [[ "$REMOTE_EXPORT_STATUS" != "-1" ]]; then
            echo "# HELP mysql_backup_remote_export_last_attempt_timestamp_seconds Unix timestamp of the most recent remote export attempt"
            echo "# TYPE mysql_backup_remote_export_last_attempt_timestamp_seconds gauge"
            echo "mysql_backup_remote_export_last_attempt_timestamp_seconds $now"
        fi
        echo "# HELP mysql_backup_remote_export_last_success_timestamp_seconds Unix timestamp of the most recent successful remote export"
        echo "# TYPE mysql_backup_remote_export_last_success_timestamp_seconds gauge"
        if [[ "$REMOTE_EXPORT_STATUS" == "1" ]]; then
            echo "mysql_backup_remote_export_last_success_timestamp_seconds $now"
        elif [[ -n "$previous_remote_success" ]]; then
            echo "mysql_backup_remote_export_last_success_timestamp_seconds $previous_remote_success"
        fi
    } > "$tmp_metrics_file"

    mv "$tmp_metrics_file" "$BACKUP_METRICS_PATH"
}

require_env() {
    local name="$1"
    if [[ -z "${!name:-}" ]]; then
        log "ERROR: required environment variable $name is empty"
        write_metrics
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
    write_metrics
    exit 1
fi

for boolean_name in BACKUP_ENCRYPTION_REQUIRED BACKUP_REMOTE_REQUIRED; do
    if [[ "${!boolean_name}" != "true" && "${!boolean_name}" != "false" ]]; then
        log "ERROR: $boolean_name must be true or false"
        write_metrics
        exit 1
    fi
done

if ! command -v "$MYSQLDUMP_BIN" >/dev/null 2>&1; then
    log "ERROR: mysqldump executable not found: $MYSQLDUMP_BIN"
    write_metrics
    exit 1
fi

if [[ -n "$BACKUP_ENCRYPTION_PASSPHRASE" ]]; then
    if ! command -v "$OPENSSL_BIN" >/dev/null 2>&1; then
        log "ERROR: BACKUP_ENCRYPTION_PASSPHRASE가 설정됐지만 openssl 실행 파일을 찾을 수 없습니다: $OPENSSL_BIN"
        write_metrics
        exit 1
    fi
elif [[ "$BACKUP_ENCRYPTION_REQUIRED" == "true" ]]; then
    log "ERROR: BACKUP_ENCRYPTION_REQUIRED=true 이지만 BACKUP_ENCRYPTION_PASSPHRASE가 비어 있습니다"
    write_metrics
    exit 1
else
    log "WARNING: 백업 암호화 키(BACKUP_ENCRYPTION_PASSPHRASE)가 없어 평문 백업으로 진행합니다. 원격 보관 시 노출 위험이 있습니다."
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
        write_metrics
        exit 1
    fi

    size="$(wc -c < "$file" | tr -d '[:space:]')"
    if [[ "$size" -lt 200 ]]; then
        log "ERROR: backup file suspiciously small (possibly empty dump): $file ($size bytes)"
        rm -f "$file"
        write_metrics
        exit 1
    fi
}

# 평문 .sql.gz를 openssl AES-256-CBC(PBKDF2)로 암호화한 .sql.gz.enc를 만들고,
# 복호화+gzip 무결성을 실제로 검증한 뒤 평문을 삭제합니다. 성공 시 암호화 파일 경로를
# 전역 변수 ENCRYPTED_BACKUP_FILE에 담아 반환합니다. 키가 없으면 아무 것도 하지 않습니다.
ENCRYPTED_BACKUP_FILE=""
encrypt_backup_file() {
    local file="$1"
    local enc_file="$file.enc"

    if [[ -z "$BACKUP_ENCRYPTION_PASSPHRASE" ]]; then
        return 0
    fi

    if ! "$OPENSSL_BIN" enc -aes-256-cbc -salt -pbkdf2 -iter "$BACKUP_ENCRYPTION_PBKDF2_ITER" \
        -in "$file" -out "$enc_file" -pass env:BACKUP_ENCRYPTION_PASSPHRASE; then
        log "ERROR: 백업 암호화 실패: $file"
        rm -f "$enc_file"
        write_metric 0
        exit 1
    fi

    # 복호화 라운드트립 검증: 잘못된 키/깨진 암호문이면 여기서 실패합니다.
    if ! "$OPENSSL_BIN" enc -d -aes-256-cbc -pbkdf2 -iter "$BACKUP_ENCRYPTION_PBKDF2_ITER" \
        -in "$enc_file" -pass env:BACKUP_ENCRYPTION_PASSPHRASE | gzip -t; then
        log "ERROR: 암호화 백업 복호화 검증 실패(키/무결성): $enc_file"
        rm -f "$enc_file"
        write_metric 0
        exit 1
    fi

    rm -f "$file"
    ENCRYPTED_BACKUP_FILE="$enc_file"
    log "backup encrypted: $enc_file ($(du -h "$enc_file" | awk '{print $1}'))"
}

upload_to_object_storage() {
    local file="$1"
    local object_name
    object_name="$(basename "$file")"

    if [[ -z "${MINIO_ENDPOINT:-}" && -z "${MINIO_ACCESS_KEY:-}" && -z "${MINIO_SECRET_KEY:-}" && -z "${MINIO_BACKUP_BUCKET_NAME:-}" ]]; then
        log "MinIO 원격 반출 건너뜀: 원격 저장소가 설정되지 않았습니다."
        return 0
    fi

    if [[ -z "${MINIO_ENDPOINT:-}" || -z "${MINIO_ACCESS_KEY:-}" || -z "${MINIO_SECRET_KEY:-}" || -z "${MINIO_BACKUP_BUCKET_NAME:-}" ]]; then
        REMOTE_EXPORT_STATUS=0
        log "WARNING: MinIO 원격 반출 설정이 불완전합니다. MINIO_ENDPOINT/MINIO_ACCESS_KEY/MINIO_SECRET_KEY/MINIO_BACKUP_BUCKET_NAME을 모두 설정해야 합니다."
        return 1
    fi

    REMOTE_EXPORT_CONFIGURED=1

    if ! command -v mc >/dev/null 2>&1; then
        REMOTE_EXPORT_STATUS=0
        log "WARNING: MinIO 원격 반출 실패: mc 클라이언트를 찾을 수 없습니다."
        return 1
    fi

    if mc alias set backup-remote "$MINIO_ENDPOINT" "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" >/dev/null 2>&1 \
        && mc mb --ignore-existing "backup-remote/$MINIO_BACKUP_BUCKET_NAME" >/dev/null 2>&1 \
        && mc cp "$file" "backup-remote/$MINIO_BACKUP_BUCKET_NAME/$object_name" >/dev/null 2>&1; then
        REMOTE_EXPORT_STATUS=1
        log "MinIO 원격 반출 완료: $MINIO_BACKUP_BUCKET_NAME/$object_name"
        return 0
    else
        REMOTE_EXPORT_STATUS=0
        log "WARNING: MinIO 원격 반출 실패 (로컬 백업은 정상 완료됨): $MINIO_BACKUP_BUCKET_NAME/$object_name"
        return 1
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
    encrypt_backup_file "$backup_file"
    final_backup_file="${ENCRYPTED_BACKUP_FILE:-$backup_file}"
    LOCAL_BACKUP_STATUS=1
    remote_export_failed=0
    upload_to_object_storage "$final_backup_file" || remote_export_failed=1
    write_metrics

    if [[ "$remote_export_failed" == "1" && "$BACKUP_REMOTE_REQUIRED" == "true" ]]; then
        log "ERROR: BACKUP_REMOTE_REQUIRED=true이고 MinIO 원격 반출이 실패했습니다. 로컬 백업은 보존됩니다."
        exit 1
    fi
else
    rm -f "$tmp_file"
    log "ERROR: mysqldump failed; incomplete backup removed"
    write_metrics
    exit 1
fi

deleted_count=0
while IFS= read -r old_backup_file; do
    rm -f "$old_backup_file"
    deleted_count=$((deleted_count + 1))
    log "deleted old backup: $old_backup_file"
done < <(find "$BACKUP_DIR" -type f \( -name "${safe_db_name}_*.sql.gz" -o -name "${safe_db_name}_*.sql.gz.enc" \) -mtime +"$BACKUP_RETENTION_DAYS" -print)

log "retention cleanup completed: deleted=$deleted_count retentionDays=$BACKUP_RETENTION_DAYS"
