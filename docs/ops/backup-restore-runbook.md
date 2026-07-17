# MySQL 백업/복구 런북

## 백업

- 스크립트: `scripts/backup-mysql.sh`
- 운영 배선: `docker-compose.yml`의 `backup` 서비스가 주기적으로 실행합니다(기본 24시간 간격, `BACKUP_INTERVAL_HOURS`). 실패한 시도는 정상 주기까지 기다리지 않고 `BACKUP_FAILURE_RETRY_MINUTES`(기본 15분) 후 다시 실행합니다.
- 실행 이미지: `infra/backup/Dockerfile`이 `mysql:8.4`에 공식 `minio/mc` 바이너리를 멀티 스테이지로 포함합니다. 컨테이너 시작 때 외부 바이너리를 내려받지 않으며, AMD64/ARM64 플랫폼에 맞는 `mc`가 이미지 빌드 시 고정됩니다.
- 저장 위치: `storage/backups/*.sql.gz` (기본 보존 기간 14일, `BACKUP_RETENTION_DAYS`)
- 무결성: 매 백업 후 `gzip -t`와 최소 파일 크기(200바이트) 검사를 통과해야 성공으로 기록됨
- 메트릭: `storage/metrics/mysql_backup.prom`에 로컬 백업 상태(`mysql_backup_last_run_status`, `mysql_backup_last_success_timestamp_seconds`)와 MinIO 반출 상태(`mysql_backup_remote_export_configured`, `mysql_backup_remote_export_last_run_status`, 마지막 시도·성공 시각)를 별도로 기록합니다. 실패한 다음 실행에서도 마지막 성공 시각은 보존됩니다.
- 로그: `storage/logs/backup.log`
- 원격 반출: 로컬 백업 성공 및 무결성 검사 통과 후, `MinIO` 버킷 `MINIO_BACKUP_BUCKET_NAME`(기본 `hanium-backups`, 사용자 파일이 들어가는 버킷과는 별도)으로 업로드합니다. 로컬/개발 기본값(`BACKUP_REMOTE_REQUIRED=false`)에서는 실패해도 로컬 백업을 보존하고 실행은 성공하지만 원격 상태 메트릭은 0이 됩니다. 운영 오버레이는 `BACKUP_REMOTE_REQUIRED=true`를 강제해 원격 반출 실패를 종료 코드 1로 전파합니다. 두 경우 모두 로컬 백업 성공 상태와 원격 반출 상태는 서로 덮어쓰지 않습니다.
- 암호화(선택): `BACKUP_ENCRYPTION_PASSPHRASE`를 설정하면 백업이 openssl AES-256-CBC(PBKDF2, 기본 200000회 반복)로 암호화된 `.sql.gz.enc`로 저장되고 평문 `.sql.gz`는 삭제됩니다. 암호화 파일은 저장 직후 스크립트가 복호화+`gzip -t`로 라운드트립을 실제 검증한 뒤에만 성공으로 기록합니다. 원격 반출·보존 정리는 암호화 파일(`.enc` 포함) 기준으로 동작합니다. `BACKUP_ENCRYPTION_REQUIRED=true`면 키가 없을 때 백업을 실패시켜 평문 백업을 금지합니다.
  - **키 보관 주의**: `BACKUP_ENCRYPTION_PASSPHRASE`는 백업 파일과 **다른 위치**(비밀 관리자/금고)에 보관하세요. 이 값을 잃으면 암호화 백업은 복구할 수 없습니다. 원격(MinIO) 반출을 쓰는 경우, DB 덤프에 사용자 이메일·비밀번호 해시가 들어가므로 암호화 설정을 권장합니다.
  - **결정(2026-07-16)**: 원격 반출을 쓰므로 암호화를 **켜기로 결정**. `docker-compose.yml`의 `backup` 서비스에 `BACKUP_ENCRYPTION_PASSPHRASE`/`_REQUIRED`/`_PBKDF2_ITER`가 배선돼 있어, `.env`에 값만 채우면 컨테이너 백업에 적용됩니다.
  - **활성화 방법(컨테이너)**: (1) 강한 패스프레이즈 생성 `openssl rand -base64 32`, (2) `.env`에 `BACKUP_ENCRYPTION_PASSPHRASE=<생성값>`, (3) `docker compose up -d backup` 재기동. 운영 오버레이(`docker-compose.prod.yml`)는 이 값이 비어 있으면 compose 단계에서 실패하고 `BACKUP_ENCRYPTION_REQUIRED=true`를 강제합니다. 로컬/개발에서 같은 정책을 적용하려면 `.env`에 `BACKUP_ENCRYPTION_REQUIRED=true`도 설정합니다. 첫 백업이 `.sql.gz.enc`로 생성되는지 `storage/logs/backup.log`에서 `backup encrypted` 로그로 확인합니다. 암호화 파일의 격리 MySQL 복구는 아래 2026-07-17 리허설에서 검증했습니다.

## 복구 (비상시 절차)

**주의**: `backup-mysql.sh`는 `mysqldump --databases hanium_dev`로 덤프를 뜨기 때문에, 백업 파일 안에 `CREATE DATABASE hanium_dev`/`USE hanium_dev;`가 이미 포함되어 있습니다. `scripts/restore-mysql.sh`를 실행하면 **항상 그 이름(`hanium_dev`)의 스키마로 복구**되며, 스크립트에 넘기는 `DB_NAME` 환경변수는 복구 전후 sanity check(테이블 개수 확인)에만 쓰입니다. 실제 운영 DB에 복구를 실행하기 전, 반드시 대상 컨테이너/인스턴스가 맞는지 재확인하세요.

1. 복구할 백업 파일을 고른다: `ls -t storage/backups/*.sql.gz* | head -1` (암호화 파일은 `.sql.gz.enc`)
2. 대상 MySQL 인스턴스의 `DB_HOST`/`DB_PORT`/`DB_USERNAME`/`DB_PASSWORD`를 환경변수로 설정한다. `DB_NAME`은 반드시 `hanium_dev`로 둔다. **암호화 백업(`.enc`)이면 백업 당시와 동일한 `BACKUP_ENCRYPTION_PASSPHRASE`도 함께 설정**한다(없으면 복구가 거부됨).
3. `./scripts/restore-mysql.sh <backup-file>` 실행. 파일이 `.enc`면 자동으로 복호화 후 복구한다. 대상 스키마에 테이블이 이미 있으면 안전을 위해 거부되며, 의도적으로 덮어쓰려면 `--force`를 붙인다.
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

### 2026-07-17 암호화 백업 리허설

- 대상: 기존 볼륨을 연결하지 않은 일회용 `mysql:8.4` 컨테이너 (`hanium-backup-restore-it-20260717`, 포트 13316)
- 원본: `hanium_restore_it.restore_probe` 테이블의 `(1, alpha)`, `(2, beta)`, `(3, gamma)` 3개 행
- 백업: `BACKUP_ENCRYPTION_REQUIRED=true`와 테스트 전용 패스프레이즈로 `scripts/backup-mysql.sh` 실행
- 생성 결과: `.sql.gz.enc` 파일만 남고 평문 `.sql.gz`는 삭제됨
- 실패 경로: 잘못된 패스프레이즈로 `scripts/restore-mysql.sh` 실행 시 복호화·gzip 무결성 검사에서 종료 코드 1로 거부됨
- 정상 경로: 테스트 DB를 삭제·빈 스키마로 재생성한 뒤 올바른 패스프레이즈로 복원, `tables=1`, `estimatedRows=3` sanity check 통과
- 데이터 대조: 복원 후 세 행이 원본과 정확히 일치함
- 정리: 검증 직후 일회용 컨테이너와 `/private/tmp` 백업·로그를 모두 삭제함

### 2026-07-17 전용 백업 이미지 ARM64 원격 반출 리허설

- 대상: ARM64 Docker 환경의 `hanium-backup:it`, 일회용 `mysql:8.4`, 일회용 MinIO
- 이미지: `mc --version`의 런타임이 `linux/arm64`임을 확인하고 `mysqldump`, `gzip`, `openssl`, `mc` 실행 파일을 모두 검사함
- 실행: 테스트 DB 3행을 `BACKUP_RUN_ONCE=true`, 암호화 필수 설정으로 백업하고 MinIO `hanium-backups` 버킷으로 반출함
- 결과: 로컬에는 `.sql.gz.enc` 한 개만 남았고 `mysql_backup_last_run_status 1` 메트릭과 원격 반출 완료 로그가 기록됨
- 원격 대조: MinIO에서 다시 내려받은 928바이트 객체가 로컬 암호화 파일과 `cmp` 및 SHA-256(`1be7fe1ee085af87a56b6a0c2597fc9790a4443bc33d22d30eaeec8b1dc72701`) 기준으로 일치함
- 복호화 대조: 내려받은 객체를 AES-256-CBC/PBKDF2 200000회 설정으로 복호화해 원본 3행이 포함된 SQL을 확인함
- 실패 경로: 잘못된 `BACKUP_INTERVAL_HOURS`와 필수 DB 환경변수 누락은 모두 종료 코드 1로 거부되고, 1회 실행의 백업 실패도 컨테이너 종료 코드로 전파됨

### 2026-07-17 원격 반출 상태 회귀 검증

- `scripts/tests/test-backup-mysql.sh`가 fake `mysqldump`와 `mc`로 로컬/원격 성공, mysqldump 실패, 필수 원격 반출 실패, 개발용 best-effort 실패를 분리 검증함
- 원격 반출 성공 시 로컬·원격 상태가 각각 1이고 두 마지막 성공 시각이 생성됨
- 이후 mysqldump 실패 시 로컬 상태는 0으로 바뀌지만 이전 로컬·원격 성공 시각은 보존됨
- `BACKUP_REMOTE_REQUIRED=true`에서 `mc cp` 실패 시 로컬 파일은 보존되고 로컬 상태 1·원격 상태 0·프로세스 종료 코드 1이 됨
- `BACKUP_REMOTE_REQUIRED=false`에서는 동일한 원격 실패가 원격 상태 0으로 관측되면서 프로세스는 성공해 개발 환경의 기존 best-effort 동작을 유지함
- 실제 ARM64 전용 이미지에서도 정상 MinIO는 로컬·원격 상태 1과 종료 코드 0, 잘못된 MinIO 포트는 암호화 로컬 파일 보존·로컬 상태 1·원격 상태 0·1회 실행 종료 코드 1로 확인함

## 남은 개선 과제

- 평문·암호화 백업 모두 일회용 MySQL 8.4에서 수동 복구가 검증됐다. CI에 정기적인 자동 복구 리허설 job은 아직 없다.
- 전용 ARM64 이미지의 MySQL→암호화→MinIO 업로드와 다운로드·복호화 무결성은 검증됐다. 실제 운영 DB에서 생성된 객체를 별도 복구 인스턴스에 복원하는 운영 리허설은 배포 환경에서 추가로 수행해야 한다.
