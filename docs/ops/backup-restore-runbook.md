# MySQL 백업/복구 런북

## 백업

- 스크립트: `scripts/backup-mysql.sh`
- 운영 배선: `docker-compose.yml`의 `backup` 서비스가 주기적으로 실행 (기본 24시간 간격, `BACKUP_INTERVAL_HOURS`)
- 저장 위치: `storage/backups/*.sql.gz` (기본 보존 기간 14일, `BACKUP_RETENTION_DAYS`)
- 무결성: 매 백업 후 `gzip -t`와 최소 파일 크기(200바이트) 검사를 통과해야 성공으로 기록됨
- 메트릭: `storage/metrics/mysql_backup.prom` (`mysql_backup_last_run_status`, `mysql_backup_last_success_timestamp_seconds`) — Prometheus/Grafana에서 조회 가능
- 로그: `storage/logs/backup.log`
- 원격 반출: 로컬 백업 성공 및 무결성 검사 통과 후, `MinIO` 버킷 `MINIO_BACKUP_BUCKET_NAME`(기본 `hanium-backups`, 사용자 파일이 들어가는 버킷과는 별도)으로 best-effort 업로드를 시도합니다. MinIO 업로드가 실패해도 로컬 백업 성공 여부와 `mysql_backup_last_run_status` 메트릭에는 영향을 주지 않습니다. 실패 시 `backup.log`에 `WARNING: MinIO 원격 반출 실패` 로그가 남습니다.

## 복구 (비상시 절차)

**주의**: `backup-mysql.sh`는 `mysqldump --databases hanium_dev`로 덤프를 뜨기 때문에, 백업 파일 안에 `CREATE DATABASE hanium_dev`/`USE hanium_dev;`가 이미 포함되어 있습니다. `scripts/restore-mysql.sh`를 실행하면 **항상 그 이름(`hanium_dev`)의 스키마로 복구**되며, 스크립트에 넘기는 `DB_NAME` 환경변수는 복구 전후 sanity check(테이블 개수 확인)에만 쓰입니다. 실제 운영 DB에 복구를 실행하기 전, 반드시 대상 컨테이너/인스턴스가 맞는지 재확인하세요.

1. 복구할 백업 파일을 고른다: `ls -t storage/backups/*.sql.gz | head -1`
2. 대상 MySQL 인스턴스의 `DB_HOST`/`DB_PORT`/`DB_USERNAME`/`DB_PASSWORD`를 환경변수로 설정한다. `DB_NAME`은 반드시 `hanium_dev`로 둔다.
3. `./scripts/restore-mysql.sh <backup-file>` 실행. 대상 스키마에 테이블이 이미 있으면 안전을 위해 거부되며, 의도적으로 덮어쓰려면 `--force`를 붙인다.
4. 스크립트가 복구 후 테이블 개수/예상 행 수를 sanity check로 자동 출력한다. 0이면 실패로 간주하고 원인을 조사한다.

## 리허설 기록

### 2026-07-15 리허설 (일회용 컨테이너, 개발 데이터 영향 없음)

- 사용한 백업 파일: `storage/backups/hanium_dev_20260714_065215.sql.gz`
- 대상: 일회용 `mysql:8.4` 컨테이너 (`hanium-restore-rehearsal`, 포트 3309, 리허설 종료 후 삭제됨)
- 결과: 복구 성공. `scripts/restore-mysql.sh` 로그:

```text
2026-07-15T18:48:40+0900 [restore-mysql] restore started: db=hanium_dev host=127.0.0.1 port=3309 backup=storage/backups/hanium_dev_20260714_065215.sql.gz force=false existingTables=0
2026-07-15T18:48:40+0900 [restore-mysql] restore completed: storage/backups/hanium_dev_20260714_065215.sql.gz
2026-07-15T18:48:40+0900 [restore-mysql] restore sanity check passed: db=hanium_dev tables=5 estimatedRows=13
```

- 복구된 테이블 확인:

```text
mysql: [Warning] Using a password on the command line interface can be insecure.
user_count
4
job_count
4
version	description
1	init schema
2	create users table
3	add owner to analysis jobs
4	add retry count to analysis jobs
5	add cancel requested to analysis jobs
6	add run options to analysis jobs
7	add terms agreement to users
8	create password reset tokens
9	add password changed at to users
```

- 결론: `storage/backups/hanium_dev_20260714_065215.sql.gz` 백업은 일회용 MySQL 8.4 컨테이너에 정상 복구됐다. 복구 후 `hanium_dev` 스키마에 5개 테이블이 생성됐고, `users` 4건, `analysis_jobs` 4건, Flyway V1~V9 이력이 확인됐다. 리허설 컨테이너는 확인 직후 `docker rm -f hanium-restore-rehearsal`로 삭제했다.

## 남은 개선 과제

- 이 리허설은 수동으로 1회 실행됐다. CI에 정기적인 자동 복구 리허설 job은 아직 없다.
- 원격 보관(오프사이트 백업 복사본), 백업 파일 암호화는 아직 없다.
