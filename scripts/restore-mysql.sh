#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RESTORE_LOG_PATH="${RESTORE_LOG_PATH:-${BACKUP_LOG_PATH:-$PROJECT_ROOT/storage/logs/restore.log}}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"
# 암호화 백업(.sql.gz.enc)을 복구할 때 필요한 값. backup-mysql.sh와 같은 값이어야 합니다.
BACKUP_ENCRYPTION_PASSPHRASE="${BACKUP_ENCRYPTION_PASSPHRASE:-}"
BACKUP_ENCRYPTION_PBKDF2_ITER="${BACKUP_ENCRYPTION_PBKDF2_ITER:-200000}"
OPENSSL_BIN="${OPENSSL_BIN:-openssl}"
FORCE_RESTORE=false
BACKUP_FILE="${RESTORE_BACKUP_FILE:-}"

usage() {
    cat <<'USAGE'
Usage: scripts/restore-mysql.sh [--force] [backup-file.sql.gz|.sql.gz.enc]

Restores one gzip-compressed MySQL dump into the DB_NAME target.
If the file ends with .enc it is decrypted first with openssl (AES-256/PBKDF2).

Required environment:
  DB_HOST DB_PORT DB_NAME DB_USERNAME DB_PASSWORD

Optional environment (for .enc encrypted backups):
  BACKUP_ENCRYPTION_PASSPHRASE   Must match the value used at backup time.
  BACKUP_ENCRYPTION_PBKDF2_ITER  PBKDF2 iterations (default 200000).

Options:
  --force    Allow restore when the target database already has tables.

The backup file can also be passed with RESTORE_BACKUP_FILE.
USAGE
}

log() {
    local message="$1"
    local line
    line="$(date '+%Y-%m-%dT%H:%M:%S%z') [restore-mysql] $message"
    echo "$line"

    mkdir -p "$(dirname "$RESTORE_LOG_PATH")"
    printf '%s\n' "$line" >> "$RESTORE_LOG_PATH"
}

require_env() {
    local name="$1"
    if [[ -z "${!name:-}" ]]; then
        log "ERROR: required environment variable $name is empty"
        exit 1
    fi
}

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --force)
            FORCE_RESTORE=true
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            log "ERROR: unknown option: $1"
            usage
            exit 1
            ;;
        *)
            if [[ -n "$BACKUP_FILE" ]]; then
                log "ERROR: multiple backup files were provided"
                usage
                exit 1
            fi
            BACKUP_FILE="$1"
            ;;
    esac
    shift
done

require_env DB_HOST
require_env DB_PORT
require_env DB_NAME
require_env DB_USERNAME
require_env DB_PASSWORD

if [[ -z "$BACKUP_FILE" ]]; then
    log "ERROR: backup file path is required"
    usage
    exit 1
fi

if [[ ! -r "$BACKUP_FILE" || ! -f "$BACKUP_FILE" ]]; then
    log "ERROR: backup file does not exist or is not readable: $BACKUP_FILE"
    exit 1
fi

if ! command -v "$MYSQL_BIN" >/dev/null 2>&1; then
    log "ERROR: mysql executable not found: $MYSQL_BIN"
    exit 1
fi

# .enc 확장자면 암호화 백업으로 보고 복호화 스트림을 사용합니다.
IS_ENCRYPTED=false
case "$BACKUP_FILE" in
    *.enc)
        IS_ENCRYPTED=true
        ;;
esac

if [[ "$IS_ENCRYPTED" == true ]]; then
    if [[ -z "$BACKUP_ENCRYPTION_PASSPHRASE" ]]; then
        log "ERROR: 암호화 백업(.enc)인데 BACKUP_ENCRYPTION_PASSPHRASE가 비어 있습니다: $BACKUP_FILE"
        exit 1
    fi
    if ! command -v "$OPENSSL_BIN" >/dev/null 2>&1; then
        log "ERROR: 암호화 백업 복구에 필요한 openssl을 찾을 수 없습니다: $OPENSSL_BIN"
        exit 1
    fi
fi

# 백업 파일을 평문 SQL 스트림으로 풀어 stdout에 씁니다(암호화면 복호화 후 gunzip).
sql_stream() {
    if [[ "$IS_ENCRYPTED" == true ]]; then
        "$OPENSSL_BIN" enc -d -aes-256-cbc -pbkdf2 -iter "$BACKUP_ENCRYPTION_PBKDF2_ITER" \
            -in "$BACKUP_FILE" -pass env:BACKUP_ENCRYPTION_PASSPHRASE | gzip -dc
    else
        gzip -dc "$BACKUP_FILE"
    fi
}

# 무결성 검증: 복호화+gunzip이 끝까지 성공하는지 확인합니다(잘못된 키면 여기서 실패).
if ! sql_stream >/dev/null; then
    log "ERROR: backup integrity check failed (복호화/gzip 실패): $BACKUP_FILE"
    exit 1
fi

mysql_query() {
    MYSQL_PWD="$DB_PASSWORD" "$MYSQL_BIN" \
        --host="$DB_HOST" \
        --port="$DB_PORT" \
        --user="$DB_USERNAME" \
        --protocol=tcp \
        --batch \
        --skip-column-names \
        --execute "$1"
}

target_table_count="$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '${DB_NAME}' AND table_type = 'BASE TABLE';" | tr -d '[:space:]')"
if ! [[ "$target_table_count" =~ ^[0-9]+$ ]]; then
    log "ERROR: failed to inspect target database table count: db=$DB_NAME value=$target_table_count"
    exit 1
fi

if [[ "$target_table_count" -gt 0 && "$FORCE_RESTORE" != true ]]; then
    log "ERROR: target database is not empty: db=$DB_NAME tables=$target_table_count; rerun with --force only after confirming the target is safe to overwrite"
    exit 1
fi

log "restore started: db=$DB_NAME host=$DB_HOST port=$DB_PORT backup=$BACKUP_FILE force=$FORCE_RESTORE existingTables=$target_table_count"

if sql_stream | MYSQL_PWD="$DB_PASSWORD" "$MYSQL_BIN" \
    --host="$DB_HOST" \
    --port="$DB_PORT" \
    --user="$DB_USERNAME" \
    --protocol=tcp; then
    log "restore completed: $BACKUP_FILE"
else
    log "ERROR: mysql restore failed"
    exit 1
fi

restored_table_count="$(mysql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '${DB_NAME}' AND table_type = 'BASE TABLE';" | tr -d '[:space:]')"
estimated_rows="$(mysql_query "SELECT COALESCE(SUM(table_rows), 0) FROM information_schema.tables WHERE table_schema = '${DB_NAME}' AND table_type = 'BASE TABLE';" | tr -d '[:space:]')"

if [[ "$restored_table_count" -lt 1 ]]; then
    log "ERROR: restore sanity check failed: db=$DB_NAME has no restored tables"
    exit 1
fi

log "restore sanity check passed: db=$DB_NAME tables=$restored_table_count estimatedRows=$estimated_rows"
