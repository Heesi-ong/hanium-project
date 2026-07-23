# Video LLM 보존형 재분석 설계

작성일: 2026-07-23  
상태: 권장안 채택, R1~R4 코드 구현 완료, R5 주요 DB·스토리지 검증 완료

## 1. 목표

`DEGRADED` 정책에서 `generationMode=FALLBACK`으로 완료된 결과를 사용자가 실제
Video LLM으로 다시 분석할 수 있게 한다. 다음 불변 조건을 지킨다.

1. 기존 결과와 상태를 덮어쓰지 않는다.
2. 새 분석도 원본 사용자만 요청·조회·삭제할 수 있다.
3. 재분석 비용과 일일·월간 한도를 다시 적용한다.
4. 재분석은 샘플 대체를 성공으로 인정하지 않고 `REAL`만 성공으로 인정한다.
5. 원본 영상 보존·삭제가 원본 작업과 재분석 작업 사이에서 서로 깨지지 않는다.
6. 동시 요청으로 같은 재분석 작업이 여러 건 생성되지 않는다.

## 2. 현재 구조에서 바로 구현하면 안 되는 이유

현재 `uploaded_videos`와 물리 저장 키가 job에 결합돼 있다.

- DB: `uploaded_videos.job_id`가 unique다.
- 로컬: `storage/uploads/{jobId}/...`
- MinIO: `uploads/{jobId}/...`
- 결과 삭제: 해당 job의 upload/result prefix를 함께 삭제한다.
- 원본 보존 정리: 완료 job 기준으로 `uploaded_videos` 행과 upload prefix를 삭제한다.

따라서 새 child job에 기존 `storedFilePath`만 복사하면 새 job의 MinIO 키에는 객체가 없고,
원본 job 삭제·retention 시 child가 참조하던 물리 영상도 사라진다. 반대로 기존 job을
`UPLOADED`로 초기화하면 완료 결과와 상태·감사 이력을 덮어쓴다.

## 3. 선택지 비교

| 선택지 | 장점 | 문제 | 판정 |
|---|---|---|---|
| 같은 job을 초기화 | 스키마 변경이 작음 | 기존 결과·완료 시각·실패 이력 덮어쓰기, 동시 조회 불일치 | 제외 |
| 영상을 child job에 물리 복사 | job 결합 구조 유지 | 최대 500MB 중복 저장·복사 지연, 삭제/실패 보상 복잡 | 임시방편이라 제외 |
| 결과 revision만 추가 | 영상 복제 없음 | job 상태와 결과 revision의 의미가 분리되고 기존 API 전체가 revision-aware해야 함 | 장기 대안 |
| **불변 video asset을 여러 job이 공유** | 결과 불변, 저장 중복 없음, 일반 재분석에도 확장 가능 | asset 생명주기·FK·retention 전환 필요 | **권장** |

## 4. 권장 도메인 모델

### 4.1 Video asset

기존 `uploaded_videos` 행을 “한 job의 부속물”이 아니라 “불변 원본 영상 asset”으로
점진 전환한다. 기존 `job_id`는 migration 동안 물리 저장 namespace를 가리키는
`storage_job_id` 역할로 유지한다.

`analysis_jobs`에 다음 필드를 추가한다.

- `video_asset_id`: `uploaded_videos.id` FK, `ON DELETE SET NULL`
- `source_job_id`: 재분석의 원본 jobId, 일반 업로드는 null
- `analysis_kind`: `STANDARD | VIDEO_LLM_REANALYSIS`
- `video_llm_generation_mode`: 최종 `REAL | FALLBACK | MOCK | SKIPPED`, 실행 전에는 null

신규 업로드는 asset 한 건과 STANDARD job 한 건을 연결한다. 재분석은 새 job만 만들고
같은 `video_asset_id`를 참조한다.

### 4.2 결과과 lineage

- 원본과 재분석 결과는 각각 `results/{jobId}/`에 저장한다.
- 원본 job은 수정하지 않는다.
- 목록·상세 응답에 `sourceJobId`, `analysisKind`, `videoLlmGenerationMode`를 노출한다.
- 원본 상세에는 최신 재분석 job 링크를, 재분석 상세에는 원본 결과 링크를 제공한다.
- 메모는 job별로 독립 보관한다.

## 5. API 계약

### 5.1 요청

`POST /api/analysis/{sourceJobId}/video-llm-reanalysis`

```json
{
  "useOpenAi": true
}
```

성공 시 HTTP 202:

```json
{
  "success": true,
  "message": "실제 Video LLM 재분석 요청이 접수되었습니다.",
  "data": {
    "sourceJobId": "20260723120000-aaaaaaaa",
    "reanalysisJobId": "20260723121500-bbbbbbbb",
    "status": "QUEUED"
  }
}
```

### 5.2 사전 조건

- source job 소유권 일치
- source status가 `COMPLETED`
- 저장된 `videoLlmGenerationMode`가 `FALLBACK`
- `video_asset_id`와 실제 로컬 또는 MinIO 원본 중 하나가 존재
- 같은 source의 QUEUED/RUNNING 재분석이 없음
- 전역·사용자 큐 여유가 있음
- Video LLM 일일·월간 예산 여유가 있음

조건 불충족은 404/403/409/410/429로 구분한다. 원본 영상이 retention으로 삭제된 경우
`410 VIDEO_SOURCE_EXPIRED`를 반환해 일반 404와 구분한다.

### 5.3 실행 의미

첫 구현은 소스의 중간 파일을 암묵적으로 복사하지 않고 **같은 원본 asset으로 전체 분석
파이프라인을 새로 실행**한다. 모델·정량 분석 버전이 달라질 수 있으므로 새 결과라는 의미가
명확하고, 원본 결과와 비교할 수 있다.

- `useVideoLlm=true` 강제
- Video LLM 응답은 `generationMode=REAL`만 성공
- `FALLBACK`, `MOCK`, `SKIPPED`, `UNKNOWN`은 job 실패
- `useOpenAi`는 요청값을 사용하되 OpenAI 예산도 새로 소비
- Video LLM 일일·월간 예산은 접수 과정에서 기존 실행과 동일하게 다시 소비

엔진 요청에 `requireReal=true`를 추가하고, backend도 응답 모드를 다시 검증해 이중으로
보호한다. 전역 정책이 DEGRADED여도 재분석 요청만큼은 fallback을 성공으로 인정하지 않는다.

## 6. 동시성·비용·멱등성

- source job을 `PESSIMISTIC_WRITE`로 잠근 짧은 transaction에서 child job을 생성한다.
- `source_job_id + analysis_kind + active_generation`에 대한 애플리케이션 잠금만 믿지 않는다.
  별도 active-request guard 행 또는 DB unique key를 둬 다중 인스턴스 중복을 막는다.
- 클라이언트가 timeout 후 같은 요청을 반복할 수 있으므로 `Idempotency-Key`를 지원한다.
- 같은 키의 재요청은 기존 child job을 반환한다.
- 예산은 child job 생성과 같은 transaction/원자적 예약 흐름에서 한 번만 차감한다.
- 큐 투입 실패 시 job을 FAILED로 남기고 예약한 예산의 환급 정책을 메트릭과 함께 명시한다.

현재 Redis rate limiter는 엄밀한 예약·환급 원장이 아니므로, 첫 구현에서는 “접수 시 소비,
실행 실패 시 자동 환급 없음”을 사용자 확인 문구에 명시한다. 장기적으로는 DB usage ledger로
전환한다.

## 7. 삭제·retention 정책

### 개별 결과 삭제

- job과 `results/{jobId}/`만 삭제한다.
- 같은 asset을 참조하는 job이 남아 있으면 원본 영상과 asset 행은 유지한다.
- 마지막 참조 job 삭제 시에만 upload prefix와 asset 행을 삭제 outbox로 넘긴다.

### 30일 원본 보존

- 현재 구현은 asset을 참조하는 모든 job을 잠근 뒤, 미완료 참조가 하나라도 있거나 최신
  완료 시각이 30일 이내이면 삭제하지 않는다. 결과적으로 “마지막 분석 완료 후 30일”이다.
- 향후 법적 hold·사용자별 보존 연장처럼 완료 시각과 다른 정책이 필요하면 asset에
  `retain_until`을 추가하고 재분석 접수 transaction에서 연장한다.
- 만료 시 모든 job에서 asset FK를 null로 만든 뒤 asset 행과 물리 파일을 삭제한다.
- 결과 JSON과 lineage는 유지하며 영상만 재생·재분석 불가 상태가 된다.

개인정보 고지의 “분석 완료 후 기본 30일”은 여러 분석이 같은 asset을 공유하는 경우
“마지막 분석 완료 후 기본 30일”로 문구를 갱신해야 한다.

## 8. 단계별 구현

### R1. 참조 모델 도입

- **R1a 완료**: Flyway V22로 `analysis_jobs.video_asset_id`와 FK/index를 추가하고,
  기존 `analysis_jobs.job_id = uploaded_videos.job_id` 기준으로 backfill한다.
- **R1a 완료**: 신규 업로드가 저장된 asset ID를 job에 기록한다.
- **R1b 완료**: 분석 worker·결과 목록·영상 재생을 asset FK 우선, 기존 jobId lookup
  fallback으로 전환하고 MinIO 키에는 asset의 storage namespace를 사용한다.
- **R1c 완료**: V23으로 `analysis_kind`, `source_job_id`,
  `video_llm_generation_mode`와 조회 index를 추가하고 신규 완료 작업의 mode를 DB에 기록한다.
- 기존 완료 결과의 mode는 추측해 migration하지 않고 별도 재개 가능한 backfill 대상으로 둔다.
- MySQL migration + `ddl-auto: validate`, 기존 API 회귀 테스트

### R2. 참조 안전 삭제·retention

- **R2a 완료**: 결과 삭제 시 job/result만 먼저 제거하고, 같은 `video_asset_id` 참조가
  남아 있으면 asset 행·upload prefix·로컬 원본을 유지한다. 마지막 참조에서만 asset과
  upload 삭제 outbox를 생성한다. 회원 탈퇴·관리자 삭제도 같은 서비스 경로를 사용한다.
- **R2a 완료**: 기존 완료 시각 기반 retention은 같은 asset을 참조하는 미완료 작업 또는
  보존 기준일보다 최신 완료 작업이 있으면 물리 원본 삭제를 건너뛴다. 같은 asset 후보는
  한 실행에서 한 번만 처리한다.
- **R2a 검증 완료**: 공유 참조가 남은 개별 결과 삭제와 최신 child가 남은 retention
  시나리오를 회귀 테스트로 고정했다.
- **R2b 보강 완료**: retention은 참조 job을 ID 순으로 잠근 뒤 asset을 잠그고 참조를
  transaction 안에서 재검사한다. DB asset 삭제와 MinIO outbox가 커밋된 뒤에만 로컬 파일을
  삭제해, 재분석 접수 race·FK 교착·enqueue 실패 시 로컬 선삭제를 막는다.
- 명시적 `retain_until` 컬럼 대신 모든 공유 참조의 상태·최신 완료 시각으로 “마지막 분석 완료
  후 30일”을 계산한다. 정책 변경이나 법적 hold가 필요해지면 별도 asset 만료 컬럼으로 전환한다.
- 실제 MySQL V22~V24 migration, `ON DELETE SET NULL`, child→source 삭제 순서와 MinIO
  장애→재시도→DEAD_LETTER→관리자 재큐잉→복구를 R5에서 확인했다.
- source를 먼저 삭제하면 dangling lineage가 생기므로, child 참조가 하나라도 남아 있으면
  source 삭제를 409로 거부한다. 회원 탈퇴는 최신순으로 child를 먼저 삭제하므로 전체 삭제
  계약은 유지된다.

### R3. 보존형 재분석 API

- **R3a 완료**: V24에 SHA-256 idempotency hash unique와 active child generated-column
  unique guard를 추가했다. source/asset 비관적 잠금 아래 새 child를 생성하며 같은 키 replay는
  기존 child를 HTTP 200으로, 최초 접수는 HTTP 202로 반환한다.
- **R3b 완료**: source 소유권, STANDARD+COMPLETED+FALLBACK, 로컬/MinIO 원본 존재,
  활성 child 없음, 큐와 Video LLM 일일·월간 여유를 검사한다. 만료된 영상은 410,
  활성 중복은 409, 한도는 429로 구분한다.
- **R3c 완료**: 엔진 요청에 `requireReal=true`를 전달한다. DEGRADED에서도 FALLBACK을
  금지하고 DISABLED의 MOCK도 503으로 거부하며, backend가 최종 `generationMode=REAL`을
  다시 검증한다.
- **자동 검증 완료**: 생성·멱등 replay·타 사용자 403·활성 중복 409·헤더 누락 400,
  원본 만료·예산, 엔진 DEGRADED/DISABLED와 backend 비-REAL 응답을 테스트했다.
- **R5 실측 완료**: MySQL 8.4 기존 V18→V24와 빈 스키마 V1→V24, Hibernate
  `ddl-auto: validate`, generated column/두 unique index, MinIO-only 원본 재분석 접수,
  API 전용 모드 동시 10요청(202 한 건·409 아홉 건), 동일 키 200 replay를 확인했다.
- **R5 삭제·복구 실측 완료**: 격리 MySQL 8.4에서 source 우선 삭제 409와 child→source
  순서의 마지막 참조 asset/outbox 삭제를 확인했다. 실제 MinIO 중단 시 outbox가 PENDING
  재시도로 남고 복구 후 COMPLETED·객체 0건이 되는 것도 확인했다.
- **R5 DEAD_LETTER 복구 실측 완료**: 실제 장애를 3회 유지해 DEAD_LETTER로 전환하고,
  관리자 목록 조회·재큐잉·감사로그와 MinIO healthy 이후 COMPLETED·객체 0건을 확인했다.
- **남은 운영 게이트**: 실제 NVIDIA timeout/5xx 후 FAILED와 복구 후 REAL 완료, 비용·quota
  메트릭, 500MB 경계.

### R4. UI

- **R4a 완료**: STANDARD+COMPLETED+FALLBACK 결과에만 “실제 Video LLM으로 다시 분석”
  버튼을 표시한다.
- **R4a 완료**: 비용·사용 한도 재소비와 기존 결과 보존/새 결과 생성을 확인 dialog에
  명시한다.
- **R4a 완료**: 브라우저 요청별 `Idempotency-Key`를 만들고 실패 후 재시도에도 같은 키를
  유지한다. 접수 뒤 child 상세로 이동해 기존 polling UX를 재사용한다.
- **R4a 완료**: 상세/목록 응답에 lineage와 저장된 generation mode를 노출하고, 결과 파일이
  아직 없는 child에는 상태 shell을 반환한다.
- **R4a 완료**: 원본 상세에서 최신 재분석 결과, child 상세에서 원본 결과로 이동하는 링크를
  제공한다.
- **R4b 후속**: 결과 비교 화면에서 source/child를 한 번에 선택하는 직접 비교 shortcut을
  추가한다.

### R5. 운영 검증

- **완료**: 실제 MySQL 8.4 기존 V18→V24 및 빈 DB V1→V24 migration과 schema validate
- **완료**: 실제 MinIO-only 원본으로 child 접수
- **완료**: API 전용 모드 동시 10요청에서 child 1건(202 1건, 409 9건), 동일 키 replay 200
- **완료**: source 선삭제 409, child→source 순서에서 마지막 참조에만 asset/upload outbox 삭제
- **완료**: 실제 MinIO 중단 시 PENDING 재시도 보존, 복구 후 COMPLETED와 객체 삭제
- **완료**: MinIO 재시도 소진→DEAD_LETTER→관리자 목록·재큐잉·감사로그→실제 삭제
- **미완료**: 500MB 경계 asset 공유 시 저장 중복 없음 확인
- **미완료**: NVIDIA timeout/5xx에서 child FAILED, REAL 복구 후 성공 확인

## 9. 도입하지 않을 때의 차이

이 모델을 도입하지 않으면 사용자는 FALLBACK 결과를 확인해도 같은 영상을 다시 업로드해야
하고, 원본 결과와 새 결과의 lineage가 끊긴다. 완료 job을 억지로 재사용하면 이전 결과와
감사 이력을 잃는다. 물리 복사를 선택하면 영상 저장량과 삭제 실패 면적이 재분석 횟수만큼
증가한다.

도입하면 migration·reference-aware 삭제 비용이 생기지만, 원본 하나로 여러 분석 실행을
안전하게 보존할 수 있어 Video LLM 재분석뿐 아니라 모델 버전 비교·A/B 분석·관리자 재처리에도
같은 기반을 재사용할 수 있다.
