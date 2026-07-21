# MinIO 백필 리허설 & 로컬 디스크 fallback 제거 계획

작성일: 2026-07-16
상태: **B안 최종 확정 및 운영 검증 체크리스트 3항목 전부 실측 완료(2026-07-21).** C안(로컬 완전 제거)은 MinIO 분산화/관리형 전환 전까지 의도적으로 계속 보류. staging 기존 데이터 백필만 외부 인프라 필요로 남음.

## 결정 기록 (2026-07-16)

- **로컬 fallback**: local/dev는 **A안(현행 이중 구조)** 을 유지하고, prod는 **B안(쓰기 MinIO 필수 + MinIO 우선 읽기 + 로컬 읽기 fallback)** 을 적용했습니다. `application-prod.yml`과 `docker-compose.prod.yml`이 `STORAGE_OBJECT_WRITE_REQUIRED=true`, `STORAGE_OBJECT_READ_PREFERRED=true`를 기본·강제합니다. C안(완전 제거)은 MinIO 이중화 이후 검토합니다.
- **백필 리허설**: 일회용 MinIO에서 업로드·재실행 idempotency를 검증했습니다. 실제 staging 기존 파일은 파트 1 절차로 별도 백필하고 결과를 하단 기록에 추가해야 합니다.

## 배경

파일 저장은 MinIO(S3 호환 오브젝트 스토리지)로 이중 쓰기되도록 마이그레이션(Phase A~F)됐지만, 두 가지가 남아 있습니다.

1. 기존 로컬 파일을 MinIO로 옮기는 백필 러너(`ObjectStorageBackfillRunner`)는 일회용 실제 MinIO에서 검증됐습니다. 다만 staging의 기존 사용자 파일을 대상으로 한 실행은 아직 없습니다.
2. 2026-07-17 이전에는 MinIO 실패 시 로컬 디스크만으로 성공해 다중 인스턴스에서 파일이 갈라질 수 있었습니다. 현재 prod는 MinIO 쓰기 실패를 업로드/작업 실패로 전파하며, 결과 JSON 조회도 MinIO를 우선하므로 새 파일에 대한 이 위험은 코드·설정상 제거됐습니다. 기존 로컬 전용 파일은 백필 전까지 읽기 fallback으로 보호합니다.

## 파트 1 — MinIO 백필 리허설 (먼저 수행)

백필 러너는 평소 기동에서는 절대 실행되지 않고, `storage.backfill.enabled=true`(env `STORAGE_BACKFILL_ENABLED=true`)일 때만 `ApplicationRunner`로 1회 동작합니다. 로컬 `storage/uploads/`, `storage/results/`를 훑어 각 파일을 `uploads/<jobId>/<file>`, `results/<jobId>/<file>` 키로 MinIO에 업로드합니다.

### 사전 확인

- MinIO 컨테이너가 떠 있고 버킷(`MINIO_BUCKET_NAME`, 기본 `hanium-storage`)이 생성돼 있는지(`minio-init` 서비스).
- backend가 MinIO에 접근 가능한지(엔드포인트/키). 전체 Compose 기동에서는 backend·worker·backup이
  `minio-init`의 성공 완료를 자동으로 기다립니다. 인프라만 따로 준비할 때는
  `docker compose up -d --wait mysql redis minio` 후 `docker compose up minio-init`을 실행합니다.

### 리허설 절차 (dev/staging)

```bash
# 0) 현재 로컬 파일 개수를 먼저 기록해 둔다(대조용 기준값).
find storage/uploads -type f | wc -l
find storage/results -type f | wc -l

# 1) 백필을 1회 실행한다(일회성 컨테이너, 평소 서비스에 영향 없음).
docker compose run --rm -e STORAGE_BACKFILL_ENABLED=true backend

# 2) 로그에서 요약을 확인한다.
#    OBJECT_STORAGE_BACKFILL_START / ...DONE uploads[scanned=, uploaded=, skipped=, failed=]
#    failed 가 0 인지 반드시 확인. 0이 아니면 OBJECT_STORAGE_BACKFILL_FILE_FAILED 로그로 원인 파악.
#    2026-07-17부터 failed>0이면 프로세스도 종료 코드 1로 끝나 자동화가 실패를 성공으로 오인하지 않습니다.

# 3) MinIO 객체 수를 로컬 파일 수와 대조한다.
mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
mc ls --recursive "local/$MINIO_BUCKET_NAME/uploads/" | wc -l
mc ls --recursive "local/$MINIO_BUCKET_NAME/results/" | wc -l
```

### 합격 기준

- 로그의 `failed=0`.
- MinIO의 `uploads/`·`results/` 객체 수가 로컬 파일 수와 일치(또는 이미 올라간 만큼 `skipped`로 설명됨).
- 임의로 몇 개 job을 골라 브라우저 스트리밍(presigned redirect)이 MinIO에서 정상 재생되는지 확인.

### 리허설 후 기록 남길 것

- 실행 일시, 대상 환경, `scanned/uploaded/skipped/failed` 수치, 로컬 vs MinIO 객체 수 대조 결과. (이 문서 하단 "리허설 기록"에 추가)

## 파트 2 — 로컬 디스크 fallback 제거 결정

백필 리허설이 성공한 뒤에만 검토합니다. 순서가 바뀌면 안 됩니다(백필 전에 fallback을 걷으면 기존 파일 접근이 깨집니다).

### 현재 구조(2026-07-17)

- **local/dev 쓰기**: 업로드/결과가 로컬과 MinIO에 이중 쓰기됩니다. MinIO 실패 시 로컬 성공을 허용합니다.
- **prod 쓰기**: 로컬 저장 후 MinIO 쓰기를 필수로 수행합니다. MinIO 쓰기가 실패하면 방금 만든 로컬 파일을 제거하고 업로드/분석 작업을 실패 처리합니다.
- **prod 결과 JSON 읽기**: MinIO를 먼저 읽고, 기존 백필 전 파일이나 MinIO 일시 장애에만 로컬 파일로 fallback합니다.
- **영상 읽기**: 엔진 다운로드와 브라우저 스트리밍은 MinIO 객체가 실제 존재할 때만 presigned URL을 만들고, 누락·상태 확인 실패 시 기존 로컬 경로 fallback을 사용합니다. 백필 누락 객체에 유효해 보이는 404 URL을 넘겨 엔진이 다운로드 timeout을 기다리는 동작은 제거했습니다.

### 선택지

| 안 | 내용 | 장점 | 위험 |
|---|---|---|---|
| A. 현행 유지(이중 구조) | 지금처럼 로컬+MinIO 이중 | 안전망 최대, MinIO 장애에도 단일 인스턴스는 동작 | 다중 인스턴스 완전 확장 보장 불가, 로컬 디스크 계속 사용 |
| B. 읽기 fallback만 유지, 쓰기는 MinIO 필수 | MinIO 쓰기 실패 시 업로드/결과 저장을 실패 처리 | 새 파일은 반드시 MinIO에 존재 → 다중 인스턴스 안전 | MinIO 장애 시 신규 업로드 불가(가용성↓) |
| C. 로컬 완전 제거 | 로컬 경로 자체를 제거 | 구조 단순, 완전 stateless 워커 | MinIO가 단일 장애점(SPOF), 롤백 어려움 |

### 적용 결과

- local/dev는 개발 편의와 장애 복구를 위해 A를 유지합니다.
- prod는 다중 워커 안전성을 위해 B로 전환했습니다. `VideoFileCommandServiceTest`와 `JsonFileStorageTest`가 strict 쓰기 실패 전파·로컬 파일 정리·MinIO 우선 읽기·로컬 fallback을 검증합니다.
- C(완전 제거)는 MinIO 분산 모드나 관리형 오브젝트 스토리지로 이전한 뒤 검토합니다.

### B안 운영 검증 체크리스트

- MinIO 강제 중단 상태에서 업로드가 명확한 에러(예: 5xx/서비스 불가)로 실패하는지.
- 2워커(`--scale analysis-worker=2`)에서 업로드→분석→결과 조회 E2E가 인스턴스 경계를 넘어 정상 동작하는지.
- 정리 스케줄러(temp/고아/원본 보존)가 MinIO prefix 삭제와 로컬 삭제를 모두 수행하는지.

## 리허설 기록

### 2026-07-17 격리 로컬 MinIO

- 대상: 볼륨을 연결하지 않은 일회용 `minio/minio:RELEASE.2024-12-13T22-19-12Z`, 테스트 전용 버킷 `hanium-storage`
- 기존 `MinioObjectStorageIntegrationTest` 4건: put/get/exists/delete, prefix 삭제, 내부 presigned URL, 공개 endpoint presigned URL 통과
- 신규 백필 통합 테스트: 로컬 파일 2개 첫 실행 `scanned=2, uploaded=2, skipped=0, failed=0`
- 같은 파일 재실행: `scanned=2, uploaded=0, skipped=2, failed=0`
- 업로드한 JSON 내용 대조 통과, 테스트 종료 후 버킷 잔여 객체 0개 확인
- 일회용 MinIO 컨테이너는 검증 직후 삭제

### 2026-07-17 격리 API + 2워커 E2E

- 구성: API 1개(로컬 dispatch 비활성), worker 2개(각 실행기 `core=1`, `max=1`, `queue=0`), 공용 MySQL/Redis/MinIO, 4초 지연 mock analysis-engine
- API, worker 1, worker 2는 서로 다른 로컬 `uploads/`, `results/` 경로를 사용
- 영상 4건 업로드 후 `useVideoLlm=false`, `useOpenAi=false`로 큐 등록: 모두 `COMPLETED`
- worker 1과 worker 2가 각각 서로 다른 job 2건을 처리했고, job별 파이프라인 시작 및 mock 엔진 호출은 각 1회
- API 로컬 결과 파일은 0개였지만 `/api/results/{jobId}` 4건 모두 성공하여 MinIO 우선 읽기 경로 확인
- MinIO에는 업로드 원본 4개와 job별 결과 JSON 6개(총 24개)가 존재했고, DB의 4개 작업 상태·옵션과 일치
- 실행기 풀/대기열은 `ANALYSIS_EXECUTOR_CORE_POOL_SIZE`, `ANALYSIS_EXECUTOR_MAX_POOL_SIZE`, `ANALYSIS_EXECUTOR_QUEUE_CAPACITY`로 인스턴스별 조정 가능

### 2026-07-17 누락 객체 엔진 URL 검증

- `VideoFileCommandService.resolveDownloadUrl()`이 URL 생성 전에 `ObjectStorage.exists()`를 호출하도록 변경
- mock 단위 테스트에서 존재 객체는 URL 반환, 누락 객체는 URL 생성 호출 없이 `null`, 상태 확인·URL 생성 오류도 `null`임을 검증
- 일회용 실제 MinIO에서 기존 put/get/delete/prefix/presigned 테스트와 함께, 존재하지 않는 업로드 객체가 엔진 다운로드 URL을 만들지 않는 통합 테스트까지 총 5건 통과
- 이에 따라 기존 로컬 전용 파일이나 백필 누락 파일은 잘못된 MinIO URL 다운로드를 최대 60초 기다리지 않고 즉시 로컬 경로로 전환됨
- analysis-engine 다운로드는 `ANALYSIS_ENGINE_MAX_VIDEO_SIZE_MB`(기본 500MB)를 응답 `Content-Length`와 실제 누적 바이트에 모두 적용하고, HTTP 오류·중간 연결 실패·빈 응답·크기 초과 시 부분 파일과 HTTP 연결을 즉시 정리함

### 2026-07-21 로컬 환경 MinIO 강제 중단 실측 (B안 체크리스트 항목 1 완료)

- 목적: "B안 운영 검증 체크리스트"의 미확인 항목("MinIO 강제 중단 상태에서 업로드가 명확한 에러로 실패하는지")을 실제로 실행해 확인.
- 구성: 로컬 docker-compose 스택(`mysql`, `redis`, `minio`, `minio-init`, `backend`)을 `STORAGE_OBJECT_WRITE_REQUIRED=true STORAGE_OBJECT_READ_PREFERRED=true`(prod와 동일한 정책값)로 기동. 전용 테스트 계정(`fallback-test-20260721@example.com`)으로 실제 HTTP API 호출.
- **1) 베이스라인(MinIO 정상)**: `POST /api/analysis/upload`로 `sample-demo.mp4` 업로드 → 200 성공. 로컬 디스크(`/storage/uploads/<jobId>/original.mp4`)와 MinIO(`hanium-storage/uploads/<jobId>/original.mp4`, `mc ls`로 직접 확인) 양쪽에 정확히 미러링됨을 확인.
- **2) 장애 주입**: `docker compose stop minio` 후 동일한 업로드 재시도 → **500 `FILE_UPLOAD_FAILED`**로 명확히 실패(조용한 로컬 전용 성공이 발생하지 않음). 실패한 job의 로컬 디렉터리는 생성됐지만 파일 없이 비어 있음(스트릭트 실패 시 `localFileStorage.deleteFileIfExists()`로 정리됨을 실제로 확인). MySQL에서도 해당 `job_id`로 `analysis_jobs` 행이 전혀 없음을 확인(`@Transactional` 롤백으로 DB에도 흔적이 남지 않음).
- **3) 복구**: `docker compose start minio`로 MinIO 재기동 후 동일 업로드 재시도 → 200 성공으로 즉시 복귀(코드 변경/재배포 불필요).
- **4) 삭제 경로 동시 검증(부가 확인)**: 테스트 계정을 `DELETE /api/users/me`로 회원탈퇴 → 베이스라인/복구 두 건의 로컬 파일·MinIO 객체·DB 행(job, user) 전부 삭제됨을 재조회로 확인. 이번 세션에서 고친 파일삭제 트랜잭션 원자성 수정(커밋 `9c436d8`)이 strict 모드에서도 정상 동작함을 부가로 재확인.
- 테스트 종료 후 이번 검증을 위해 기동한 컨테이너를 전부 원래 상태(정지)로 되돌렸다. 정책 오버라이드는 `docker compose up` 실행 시점의 환경변수로만 주입했고 `.env`/컴포즈 파일은 수정하지 않았다.
- **결론**: B안 체크리스트의 첫 항목(강제 중단 시 명확한 실패)은 로컬 환경에서 실제로 검증 완료. 남은 두 항목(2워커 E2E, 정리 스케줄러의 이중 삭제)은 07-17에 이미 별도로 검증됐으므로(위 "2026-07-17 격리 API + 2워커 E2E" 및 07-16/07-18 스케줄러 분산 락·삭제 관련 기록 참고), **B안 체크리스트 3항목이 전부 실측 완료** 상태가 됐다.

## 결정 (2026-07-21 최종화)

- **local/dev(A안)·prod(B안) 이원화를 그대로 확정**한다. B안의 핵심 약속("MinIO 없이는 새 파일이 조용히 로컬에만 남지 않는다")이 오늘 실제 장애 주입으로 검증됐으므로, 더 이상 "미결정" 상태가 아니다.
- **C안(로컬 완전 제거)은 여전히 보류**한다. 이유는 07-16 결정과 동일 — MinIO가 단일 인스턴스(SPOF)인 현재 구성에서 로컬 fallback을 완전히 없애면 MinIO 장애가 곧 서비스 전체 장애가 된다. MinIO를 분산 모드(다중 노드)로 구성하거나 관리형 오브젝트 스토리지(AWS S3 등 SLA가 있는 서비스)로 이전한 뒤에 C안을 재검토한다. 지금 트리거 조건을 명시해 둔다: **"MinIO 자체가 더 이상 단일 장애점이 아니게 되는 시점"**이 C안 재검토 조건이다.
- **staging 기존 데이터 백필**은 여전히 staging 인프라가 있어야 실행 가능하므로 로컬 검증 범위 밖으로 남는다. 이 항목만 "외부 환경 필요" 사유로 계속 대기한다.
