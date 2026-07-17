#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/backup-mysql-test.XXXXXX")"

cleanup() {
    rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

mkdir -p "$TEST_ROOT/bin"

cat > "$TEST_ROOT/bin/fake-mysqldump" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${MYSQLDUMP_TEST_FAIL:-false}" == "true" ]]; then
    exit 42
fi
printf '%s\n' 'CREATE DATABASE `backup_test`;' 'USE `backup_test`;' 'CREATE TABLE probe (id INT PRIMARY KEY, payload VARCHAR(255));'
for id in $(seq 1 20); do
    printf "INSERT INTO probe VALUES (%d, 'backup regression payload %d');\n" "$id" "$id"
done
SCRIPT

cat > "$TEST_ROOT/bin/mc" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == "cp" && "${MC_TEST_FAIL:-false}" == "true" ]]; then
    exit 17
fi
exit 0
SCRIPT

chmod +x "$TEST_ROOT/bin/fake-mysqldump" "$TEST_ROOT/bin/mc"

metric_value() {
    local metrics_file="$1"
    local metric_name="$2"
    awk -v metric_name="$metric_name" '$1 == metric_name { print $2; exit }' "$metrics_file"
}

run_backup() {
    local case_root="$1"
    shift
    mkdir -p "$case_root/backups" "$case_root/logs" "$case_root/metrics"
    env \
        PATH="$TEST_ROOT/bin:$PATH" \
        MYSQLDUMP_BIN=fake-mysqldump \
        DB_HOST=127.0.0.1 \
        DB_PORT=3306 \
        DB_NAME=backup_test \
        DB_USERNAME=backup_user \
        DB_PASSWORD=backup_password \
        BACKUP_DIR="$case_root/backups" \
        BACKUP_LOG_PATH="$case_root/logs/backup.log" \
        BACKUP_METRICS_PATH="$case_root/metrics/mysql_backup.prom" \
        MINIO_ENDPOINT=http://minio:9000 \
        MINIO_ACCESS_KEY=test-access \
        MINIO_SECRET_KEY=test-secret \
        MINIO_BACKUP_BUCKET_NAME=backup-bucket \
        "$@" \
        bash "$PROJECT_ROOT/scripts/backup-mysql.sh"
}

success_root="$TEST_ROOT/success"
run_backup "$success_root" BACKUP_REMOTE_REQUIRED=true
success_metrics="$success_root/metrics/mysql_backup.prom"
[[ "$(metric_value "$success_metrics" mysql_backup_last_run_status)" == "1" ]]
[[ "$(metric_value "$success_metrics" mysql_backup_remote_export_configured)" == "1" ]]
[[ "$(metric_value "$success_metrics" mysql_backup_remote_export_last_run_status)" == "1" ]]
local_success_timestamp="$(metric_value "$success_metrics" mysql_backup_last_success_timestamp_seconds)"
remote_success_timestamp="$(metric_value "$success_metrics" mysql_backup_remote_export_last_success_timestamp_seconds)"
[[ -n "$local_success_timestamp" && -n "$remote_success_timestamp" ]]

if run_backup "$success_root" BACKUP_REMOTE_REQUIRED=true MYSQLDUMP_TEST_FAIL=true; then
    echo "expected mysqldump failure" >&2
    exit 1
fi
[[ "$(metric_value "$success_metrics" mysql_backup_last_run_status)" == "0" ]]
[[ "$(metric_value "$success_metrics" mysql_backup_last_success_timestamp_seconds)" == "$local_success_timestamp" ]]
[[ "$(metric_value "$success_metrics" mysql_backup_remote_export_last_success_timestamp_seconds)" == "$remote_success_timestamp" ]]

required_failure_root="$TEST_ROOT/required-failure"
if run_backup "$required_failure_root" BACKUP_REMOTE_REQUIRED=true MC_TEST_FAIL=true; then
    echo "expected required remote export failure" >&2
    exit 1
fi
required_failure_metrics="$required_failure_root/metrics/mysql_backup.prom"
[[ "$(metric_value "$required_failure_metrics" mysql_backup_last_run_status)" == "1" ]]
[[ "$(metric_value "$required_failure_metrics" mysql_backup_remote_export_last_run_status)" == "0" ]]
find "$required_failure_root/backups" -type f -name '*.sql.gz' | grep -q .

best_effort_root="$TEST_ROOT/best-effort"
run_backup "$best_effort_root" BACKUP_REMOTE_REQUIRED=false MC_TEST_FAIL=true
best_effort_metrics="$best_effort_root/metrics/mysql_backup.prom"
[[ "$(metric_value "$best_effort_metrics" mysql_backup_last_run_status)" == "1" ]]
[[ "$(metric_value "$best_effort_metrics" mysql_backup_remote_export_last_run_status)" == "0" ]]

echo "backup-mysql regression tests passed"
