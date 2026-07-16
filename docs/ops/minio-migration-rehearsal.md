# MinIO 파일 저장소 백필 리허설

## 목적

`ObjectStorageBackfillRunner`는 로컬 디스크에만 남아 있는 기존 업로드 영상과 결과 JSON을 MinIO
버킷으로 1회 백필하기 위한 운영용 러너다. 코드는
`backend/src/main/java/com/hanium/presentation/application/storage/ObjectStorageBackfillRunner.java`에
있고, `STORAGE_BACKFILL_ENABLED=true`일 때만 실행된다.

이 문서는 실제 MinIO가 떠 있는 로컬 `docker compose` 환경에서, 마이그레이션 이전 상태를 흉내 낸
테스트 파일을 백필하고 재실행 시 skip 되는지 확인한 리허설 기록이다.

## 실행 전 주의사항

- 실제 운영 데이터가 있는 환경에서 바로 실행하지 않는다.
- `.env`나 `docker-compose.yml`에 `STORAGE_BACKFILL_ENABLED=true`를 영구적으로 남기지 않는다.
- 백필 전후에는 MinIO에 생성한 테스트 오브젝트와 로컬 테스트 파일을 삭제한다.
- 현재 개발 DB가 JPA schema-validation을 통과하지 못하면 애플리케이션이 백필 러너까지 도달하지 못한다.
  이 리허설에서는 해당 문제를 별도로 확인하고, 백필 자체 검증을 위해 `SPRING_JPA_HIBERNATE_DDL_AUTO=none`
  1회성 오버라이드로 실행했다.

## 리허설 기록

### 2026-07-16 리허설 (로컬 docker compose MinIO, 테스트 파일)

- 대상 MinIO: `minio/minio:RELEASE.2024-12-13T22-19-12Z`
- 버킷: `hanium-storage`
- 테스트 업로드 파일: `storage/uploads/rehearsal-backfill-test-001/original.mp4`
- 테스트 결과 파일: `storage/results/rehearsal-backfill-test-001/final-result.json`
- 테스트 파일 내용:

```text
rehearsal fake video content
{"rehearsal": true}
```

#### MinIO 기동 확인

```text
NAME                    IMAGE                                      COMMAND                  SERVICE   CREATED       STATUS                 PORTS
haniumproject-minio-1   minio/minio:RELEASE.2024-12-13T22-19-12Z   "/usr/bin/docker-ent…"   minio     2 hours ago   Up 2 hours (healthy)   127.0.0.1:9000-9001->9000-9001/tcp
```

`minio-init` 로그:

```text
Added `local` successfully.
Bucket created successfully `local/hanium-storage`.
MinIO bucket ready: hanium-storage
```

#### 백필 전 MinIO 상태

`mc ls`는 두 prefix 모두 아무 항목도 출력하지 않았다. 개별 오브젝트 `mc stat` 결과:

```text
mc: <ERROR> Unable to stat `local/hanium-storage/uploads/rehearsal-backfill-test-001/original.mp4`. Object does not exist.
mc: <ERROR> Unable to stat `local/hanium-storage/results/rehearsal-backfill-test-001/final-result.json`. Object does not exist.
```

#### 첫 실행

처음 `docker compose run --rm -e STORAGE_BACKFILL_ENABLED=true backend`를 실행했을 때, 백필 러너까지
도달하기 전에 현재 개발 DB 스키마 검증에서 실패했다.

```text
Schema-validation: wrong column type encountered in column [content] in table [ai_coach_messages]; found [text (Types#LONGVARCHAR)], but expecting [tinytext (Types#CLOB)]
```

백필 동작 자체를 확인하기 위해 다음 1회성 환경변수로 JPA 스키마 검증만 우회했다. Flyway 검증은 그대로
수행됐다.

```text
docker compose run --rm -e STORAGE_BACKFILL_ENABLED=true -e SPRING_JPA_HIBERNATE_DDL_AUTO=none backend
```

실행 로그:

```text
{"@timestamp":"2026-07-16T02:39:17.172472055Z","@version":"1","message":"OBJECT_STORAGE_BACKFILL_START","logger_name":"com.hanium.presentation.application.storage.ObjectStorageBackfillRunner","thread_name":"main","level":"INFO","level_value":20000,"LOG_PATH":"/storage/logs"}
{"@timestamp":"2026-07-16T02:39:30.446771672Z","@version":"1","message":"OBJECT_STORAGE_BACKFILL_DONE uploads[scanned=195, uploaded=195, skipped=0, failed=0] results[scanned=38, uploaded=38, skipped=0, failed=0]","logger_name":"com.hanium.presentation.application.storage.ObjectStorageBackfillRunner","thread_name":"main","level":"INFO","level_value":20000,"LOG_PATH":"/storage/logs"}
```

#### 백필 후 MinIO 내용 확인

```text
Added `local` successfully.
[2026-07-16 02:39:17 UTC]    29B STANDARD original.mp4
rehearsal fake video content
[2026-07-16 02:39:30 UTC]    20B STANDARD final-result.json
{"rehearsal": true}
```

`mc ls`로 두 오브젝트가 생성된 것을 확인했고, `mc cat` 출력이 로컬 테스트 파일 내용과 일치했다.

#### 두 번째 실행

동일한 명령을 한 번 더 실행했다.

```text
{"@timestamp":"2026-07-16T02:40:16.127629346Z","@version":"1","message":"OBJECT_STORAGE_BACKFILL_START","logger_name":"com.hanium.presentation.application.storage.ObjectStorageBackfillRunner","thread_name":"main","level":"INFO","level_value":20000,"LOG_PATH":"/storage/logs"}
{"@timestamp":"2026-07-16T02:40:17.917710958Z","@version":"1","message":"OBJECT_STORAGE_BACKFILL_DONE uploads[scanned=195, uploaded=0, skipped=195, failed=0] results[scanned=38, uploaded=0, skipped=38, failed=0]","logger_name":"com.hanium.presentation.application.storage.ObjectStorageBackfillRunner","thread_name":"main","level":"INFO","level_value":20000,"LOG_PATH":"/storage/logs"}
```

두 번째 실행에서는 신규 업로드가 0건이고 모든 대상이 skipped로 집계됐다. 실패도 0건이었다.

#### 정리

MinIO 테스트 오브젝트 삭제:

```text
Added `local` successfully.
Removed `local/hanium-storage/uploads/rehearsal-backfill-test-001/original.mp4`.
Removed `local/hanium-storage/results/rehearsal-backfill-test-001/final-result.json`.
```

삭제 후 개별 오브젝트 `mc stat` 결과:

```text
mc: <ERROR> Unable to stat `local/hanium-storage/uploads/rehearsal-backfill-test-001/original.mp4`. Object does not exist.
mc: <ERROR> Unable to stat `local/hanium-storage/results/rehearsal-backfill-test-001/final-result.json`. Object does not exist.
```

로컬 테스트 파일 삭제 및 `.env` 확인:

```text
local upload dir: removed
local result dir: removed
.env STORAGE_BACKFILL_ENABLED: not set
```

## 결론

`ObjectStorageBackfillRunner`는 실제 MinIO가 떠 있는 로컬 `docker compose` 환경에서 로컬 uploads/results
파일을 MinIO로 업로드했다. `mc cat`으로 내려받은 내용도 원본 테스트 파일과 일치했다. 같은 백필을
재실행했을 때 이미 존재하는 오브젝트는 업로드하지 않고 skipped로 집계되어 idempotent 동작도 확인했다.

다만 현재 개발 DB에는 `ai_coach_messages.content` 컬럼 타입과 JPA 검증 기대값이 맞지 않는 문제가 있어,
기본 실행만으로는 애플리케이션이 백필 러너까지 도달하지 못했다. 이 리허설은 백필 자체를 검증하기 위해
`SPRING_JPA_HIBERNATE_DDL_AUTO=none`을 1회성으로 넘겨 진행했다.

## 남은 개선 과제

- 실제 운영 데이터 또는 운영에 준하는 오래된 로컬 파일 전체에 대한 백필은 아직 실행하지 않았다.
- CI에 정기적인 MinIO 백필 리허설 job은 없다.
- 운영에서 백필을 실행하기 전, 현재 개발 DB에서 발견된 JPA schema-validation 불일치와 같은 환경 기동
  문제를 먼저 해소해야 한다.
