#!/usr/bin/env bash
set -euo pipefail

BACKUP_INTERVAL_HOURS="${BACKUP_INTERVAL_HOURS:-24}"
BACKUP_FAILURE_RETRY_MINUTES="${BACKUP_FAILURE_RETRY_MINUTES:-15}"
BACKUP_RUN_ONCE="${BACKUP_RUN_ONCE:-false}"
BACKUP_LOG_PATH="${BACKUP_LOG_PATH:-/logs/backup.log}"

log_loop() {
    local message="$1"
    local line
    line="$(date '+%Y-%m-%dT%H:%M:%S%z') [backup-loop] $message"
    echo "$line"
    mkdir -p "$(dirname "$BACKUP_LOG_PATH")"
    printf '%s\n' "$line" >> "$BACKUP_LOG_PATH"
}

if ! [[ "$BACKUP_INTERVAL_HOURS" =~ ^[1-9][0-9]*$ ]]; then
    log_loop "ERROR: BACKUP_INTERVAL_HOURS must be a positive integer"
    exit 1
fi

if ! [[ "$BACKUP_FAILURE_RETRY_MINUTES" =~ ^[1-9][0-9]*$ ]]; then
    log_loop "ERROR: BACKUP_FAILURE_RETRY_MINUTES must be a positive integer"
    exit 1
fi

if [[ "$BACKUP_RUN_ONCE" != "true" && "$BACKUP_RUN_ONCE" != "false" ]]; then
    log_loop "ERROR: BACKUP_RUN_ONCE must be true or false"
    exit 1
fi

for required_tool in mysqldump gzip openssl mc; do
    if ! command -v "$required_tool" >/dev/null 2>&1; then
        log_loop "ERROR: required backup tool is missing: $required_tool"
        exit 1
    fi
done

shutdown_requested=false
sleep_pid=""

request_shutdown() {
    shutdown_requested=true
    if [[ -n "$sleep_pid" ]]; then
        kill "$sleep_pid" 2>/dev/null || true
    fi
}

trap request_shutdown TERM INT

while [[ "$shutdown_requested" == "false" ]]; do
    backup_exit_code=0
    /bin/bash /scripts/backup-mysql.sh || backup_exit_code=$?

    if [[ "$backup_exit_code" -ne 0 ]]; then
        log_loop "ERROR: backup run failed"
    fi

    if [[ "$BACKUP_RUN_ONCE" == "true" ]]; then
        exit "$backup_exit_code"
    fi

    if [[ "$shutdown_requested" == "true" ]]; then
        break
    fi

    if [[ "$backup_exit_code" -eq 0 ]]; then
        sleep_seconds="$((BACKUP_INTERVAL_HOURS * 3600))"
    else
        sleep_seconds="$((BACKUP_FAILURE_RETRY_MINUTES * 60))"
        log_loop "retrying failed backup in ${BACKUP_FAILURE_RETRY_MINUTES} minutes"
    fi

    sleep "$sleep_seconds" &
    sleep_pid=$!
    wait "$sleep_pid" || true
    sleep_pid=""
done

log_loop "backup loop stopped"
