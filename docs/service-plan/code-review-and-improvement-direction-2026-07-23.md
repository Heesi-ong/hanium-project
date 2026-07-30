# 전체 코드 리뷰 및 개선 방향

- 작성일: 2026-07-23
- 기준 브랜치: `main`
- 기준 커밋: `19ae283`
- 검토 범위: `backend/`, `frontend/`, `analysis-engine/`, `video-llm-engine/`, 루트 Compose, CI, 운영/백업/모니터링 설정
- 성격: 현재 작업 트리를 기준으로 한 서비스화 코드 리뷰 및 단계별 개선 제안

## 1. 결론 요약

현재 프로젝트는 더 이상 단순 로컬 MVP 구조가 아니다. 다음 운영 기반은 이미 구현되어 있다.

- Spring Boot API와 독립 analysis worker, DB 기반 비동기 큐
- MySQL + Flyway 운영 스키마 검증
- Redis 기반 rate limit/진행률/분산 락
- MinIO 기반 오브젝트 저장소와 백업·복구 스크립트
- JWT 쿠키 인증, 관리자 권한, 분석 결과 소유권 검증
- analysis-engine 및 video-llm-engine 내부 공유 키 인증
- Actuator/Prometheus/Grafana/Alertmanager 구성
- 백엔드·프론트·Python 엔진 테스트와 Docker/Compose CI

이번 리뷰에서 즉시 빌드나 기본 테스트를 깨뜨리는 **P0 문제는 발견되지 않았다.** 다만 실제 서비스에서 보안 사고, 개인정보 삭제 실패, 계정 복구 불능 또는 운영 장애로 이어질 수 있는 **P1 문제 5개**가 남아 있다.

가장 먼저 처리할 항목은 다음 순서다.

1. 비밀번호 재설정 토큰을 사용자당 1개만 유효하게 만들고 만료/사용 토큰을 정리한다.
2. 운영 SMTP가 없을 때 비밀번호 재설정이 성공한 것처럼 보이지 않도록 정책을 고정한다.
3. MinIO 삭제 실패를 재시도 가능한 영속 작업으로 바꾸고 부분 실패도 실패로 집계한다.
4. 엔진의 URL 다운로드와 로컬 파일 접근 경계를 제한한다.
5. 분산 환경의 보안·설정 실패 정책을 명시하고 기동 시 검증한다.

### 1.1 구현 진행 상태 (2026-07-23 현재 작업 트리)

최초 리뷰 이후 P1 항목의 구현이 진행됐다. 아래 상태는 아직 하나의 검토 가능한 커밋으로 정리되기 전인 **현재 작업 트리**를 기준으로 하며, 완료 표시는 코드와 자동 테스트 범위에서의 완료를 뜻한다. 실제 SMTP, NVIDIA 모델, staging E2E 검증을 대신하지 않는다.

| 항목 | 현재 상태 | 구현·확인 내용 | 남은 운영 게이트 |
|---|---|---|---|
| P1-01 재설정 토큰 | 완료 | 사용자별 기존 활성 토큰 무효화, 변경 성공 후 잔여 토큰 무효화, 만료 토큰 정리 작업 | 운영 DB에서 정리량·락 경합 관측 |
| P1-02 SMTP 정책 | 코드 완료 | 기능 플래그, prod 기동 검증, AES-GCM 암호화 email outbox, lease·backoff·DEAD_LETTER·관리자 재큐잉, SMTP timeout, health/UI·메트릭 | 실제 MySQL V20 migration 및 SMTP 송수신·장애 rehearsal |
| P1-03 삭제 신뢰성 | 코드·장애 복구 실측 완료 | DB 삭제 outbox, 활성 작업 멱등성, lease 복구, 트랜잭션 밖 MinIO I/O, 재시도·DEAD_LETTER·관리자 재큐잉·감사로그, 완료 행 30일 정리, 설정 검증·메트릭. 실제 MySQL V19와 MinIO 중단→PENDING→DEAD_LETTER→관리자 재큐잉→복구→COMPLETED를 확인 | 배포 환경별 주기적 복구 훈련과 경보 수신 확인 |
| P1-04 SSRF·경로 경계 | 코드 완료 | 엔진별 host:port allowlist, redirect 차단, 허용 base directory 검증, 보안 회귀 테스트 | 배포 환경의 실제 MinIO endpoint allowlist와 presigned URL E2E |
| P1-05 설정 검증 | 코드 완료 | backend executor/rate limit/timeout 관계, 두 Python 엔진의 불변 settings 객체·기동 전수 검증, Video LLM mode/backend 교차 검증 | 실제 Compose 기동에서 잘못된 설정 주입 fail-fast 확인 |

P2 보완도 현재 작업 트리에서 시작했다.

| 항목 | 현재 상태 | 구현·확인 내용 | 남은 운영 게이트 |
|---|---|---|---|
| P2-01 JWT 강제 무효화 | 코드·장애 복구 실측 완료 | SHA-256 DB 폐기 원장, Redis 양성·read-through 캐시, DB fallback, DB 판정 실패 시 503 fail-closed, 만료 행 정리, 메트릭·경보. 실제 MySQL V1→V24와 Redis 중단/복구를 확인 | 배포 환경별 장애 훈련과 경보 수신 확인 |
| P2-03 Video LLM 실패 정책 | 핵심 정책 코드 완료 | STRICT/DEGRADED/DISABLED, STRICT 502→작업 실패, DEGRADED만 FALLBACK, readiness/UI 정책 노출, MOCK 제외 fallback 경보 | 실제 NVIDIA 장애 rehearsal |
| P2-03 R1~R4 보존형 재분석 | 코드 완료, R5 주요 DB·스토리지 실측 완료 | V22 asset FK, V23 lineage/mode, V24 멱등·active unique guard, 공유 asset 참조 안전 삭제/retention 잠금, source 선삭제 차단, 202 접수·200 replay·403/409/410/429, 엔진/backend `requireReal` 이중 검증, 상세/목록 lineage 응답, FALLBACK 확인 dialog·child 이동/polling UI. MySQL 8.4 fresh V1→V24, MinIO-only 원본, 동시 10요청, child→source 삭제와 Outbox DEAD_LETTER 관리자 복구 실측 통과 | 실제 NVIDIA REAL·timeout/5xx, 500MB 경계, 원본/재분석 직접 비교 UX |

추가 감사에서 발견한 두 회귀 가능성도 현재 작업 트리에서 보완했다.

- 스토리지 삭제 DEAD_LETTER 수동 재큐잉 시 `attemptCount`, `lastError`, `completedAt`을 초기화해 새 재시도 예산을 부여한다.
- `analysis.job.timeout-minutes`와 `analysis.stuck-job.max-running-minutes`가 0 또는 음수이면 backend가 기동 단계에서 실패한다.
- 동일 reason·prefix의 활성 삭제 작업은 전용 DB enqueue lock과 unique key로 한 건만 생성한다.
- worker는 짧은 DB transaction에서 처리 token과 lease만 선점하고 MinIO 삭제는 transaction 밖에서 실행한다. 중간에 프로세스가 종료되면 lease 만료 뒤 다른 worker가 다시 선점한다.
- 완료된 outbox 행은 기본 30일 보존 후 500건 단위 transaction으로 정리하고, DEAD_LETTER는 자동 삭제하지 않는다.
- 비밀번호 재설정 요청은 사용자 행을 잠가 동시 요청에서도 활성 토큰/PENDING 이메일을 각각 한 건만 유지한다.
- reset link는 token hash를 AAD로 사용하는 AES-GCM 암호문으로만 email outbox에 저장한다. 성공·취소 시 수신 이메일과 암호문을 즉시 지우고, 토큰 만료 DEAD_LETTER도 자동 취소한다.
- SMTP 호출은 DB transaction 밖에서 실행하며 connection/read/write timeout, 지수 backoff, DEAD_LETTER 목록·관리자 재큐잉과 감사로그를 제공한다.
- SMTP는 provider idempotency가 없으므로 발송 성공 직후 worker가 종료되면 같은 유효 링크가 한 번 더 발송될 수 있다. 데이터 유실보다 at-least-once 전달을 선택한 정책이며 메일 내용은 동일 토큰을 사용한다.
- 두 Python 엔진은 환경변수를 lifespan에서 단일 불변 settings 객체로 한 번 파싱한다. 모델 풀·semaphore도 검증된 snapshot으로 구성하며 요청 중 환경변수 변경은 런타임 동작을 바꾸지 않는다.
- Video LLM 실제 모드는 `VIDEO_LLM_BACKEND=external-api`와 NVIDIA 키가 함께 있어야 하며, 숫자·URL·절대경로·host:port allowlist와 chunk/max-duration 교차 조건이 잘못되면 readiness 전에 기동 실패한다.

## 2. 확인한 현재 상태

### 2.1 구조와 실행 단위

현재 핵심 실행 단위는 `backend`, `frontend`, `analysis-engine`, `video-llm-engine` 네 서비스이며, `analysis-worker`는 backend 이미지에 실행 역할 설정을 다르게 주어 독립 배포한다. 과거 `Back`/`Front` 구조를 기준으로 한 분석은 현재 코드에 적용하지 않았다.

### 2.2 비동기 분석 파이프라인

- 분석 접수와 실행이 DB의 `AnalysisJob` 상태로 분리돼 있다.
- worker poller는 비관적 행 잠금으로 `QUEUED` 작업을 선점한다.
- 전역/사용자별 큐 상한, 작업 timeout, stuck-job watchdog, 취소, 수동 재시도와 dead-letter 상태가 존재한다.
- 독립 worker 재기동과 다중 인스턴스를 고려한 분산 락/선점 구조가 있다.

따라서 “긴 분석이 HTTP 요청 안에서 끝까지 실행된다” 또는 “비동기 큐가 없다”는 과거 문제는 현재 기준으로 해소됐다.

### 2.3 인증·권한·파일 보호

- `/api/**`는 공개 엔드포인트를 제외하고 인증이 필요하다.
- 분석·결과·코치 API가 현재 사용자 id로 소유권을 검증한다.
- 영상 재생은 짧은 수명의 별도 access token을 사용한다.
- 프론트는 JWT를 localStorage에 저장하지 않고 HttpOnly 쿠키를 사용한다.
- 업로드 확장자뿐 아니라 매직 바이트·크기·재생 시간을 검사한다.
- 운영에서는 API 문서 비공개, secure cookie, ffprobe fail-closed, MinIO write-required를 적용한다.

### 2.4 데이터·운영 기반

- dev/prod는 MySQL + Flyway + `ddl-auto: validate`를 사용한다.
- 운영 Compose는 DB/Redis/엔진 포트를 외부에 노출하지 않고 nginx만 공개한다.
- 백업은 gzip 무결성 검사, 선택/운영 강제 암호화, MinIO 원격 반출, 명시적 `--force` 복구를 지원한다.
- 컨테이너 자원 제한, non-root 엔진 실행, capability 제한, 헬스체크가 존재한다.

## 3. 발견한 문제

우선순위 정의:

- **P0**: 현재 배포/핵심 기능을 즉시 깨뜨리거나 외부에서 바로 악용 가능한 문제
- **P1**: 운영 전 해결해야 하는 보안·데이터·계정 복구·배포 안정성 문제
- **P2**: 단기간 내 해결할 신뢰성·관측성·유지보수 문제
- **P3**: 지속 개선 항목

### P1-01. 여러 비밀번호 재설정 토큰이 동시에 유효하며 정리되지 않는다

**근거**

- `PasswordResetService.requestPasswordReset()`은 요청마다 새 토큰을 저장하지만 기존 토큰을 무효화하지 않는다.
- `confirmPasswordReset()`은 사용된 토큰 한 건만 `usedAt`으로 표시한다.
- `PasswordResetTokenRepository`에는 토큰 해시 단건 조회만 있고 사용자별 무효화·만료 삭제 쿼리가 없다.

**영향**

- 사용자가 새 재설정 링크로 비밀번호를 바꾼 뒤에도 이전 메일의 미사용 링크가 만료 전까지 계속 유효하다.
- 탈취된 오래된 링크로 비밀번호를 다시 바꿀 수 있다.
- 만료·사용 토큰 행이 계속 누적된다.

**수정 방향**

- 정책을 “사용자당 활성 재설정 토큰 1개”로 고정한다.
- 새 토큰 발급 전 해당 사용자의 모든 미사용 토큰을 무효화하거나 삭제한다.
- 비밀번호 변경 성공 시 같은 사용자의 나머지 활성 토큰도 모두 무효화한다.
- `expires_at` 기준 정리 스케줄러와 삭제 메트릭을 추가한다.
- 동시 재설정 요청을 고려해 사용자 행 잠금 또는 사용자별 원자적 update/delete 후 insert를 한 트랜잭션에서 수행한다.

**예상 수정 파일**

- `PasswordResetTokenRepository.java`
- `PasswordResetService.java`
- 필요 시 `PasswordResetToken.java`, 신규 cleanup service
- `AuthControllerIntegrationTest.java` 또는 전용 통합 테스트

### P1-02. 운영 SMTP가 없어도 재설정 요청은 성공하고 토큰만 쌓인다

**근거**

- prod에서 `SMTP_HOST`가 비어 있으면 `PasswordResetEmailSender`는 오류 로그만 남기고 반환한다.
- startup validator도 경고만 남기므로 서비스는 정상 기동한다.
- 컨트롤러는 계정 존재 여부를 숨기기 위해 항상 동일한 성공 응답을 반환한다.

**영향**

- 운영자는 서비스가 정상이라고 볼 수 있지만 모든 사용자의 계정 복구가 실제로는 불가능하다.
- 발송되지 않은 토큰은 DB에 저장된다.
- 장애가 사용자 문의가 들어올 때까지 탐지되지 않을 수 있다.

**수정 방향**

- 운영 정책을 둘 중 하나로 명확히 선택한다.
  - 비밀번호 재설정을 필수 기능으로 보면 prod 기동을 fail-fast한다.
  - 선택 기능이면 `PASSWORD_RESET_ENABLED=false` 같은 기능 플래그를 두고 UI/API/readiness에서 비활성 상태를 명시한다.
- SMTP 연결/인증 상태를 readiness 또는 별도 메트릭으로 노출한다.
- 이메일 발송은 트랜잭션 안의 동기 네트워크 호출 대신 outbox/비동기 발송으로 분리하고, 발송 성공·실패·재시도를 저장한다.
- 사용자 열거 방지를 위한 외부 응답 문구는 현재처럼 동일하게 유지한다.

### P1-03. MinIO 삭제 부분 실패와 재시도 실패가 개인정보 삭제 성공으로 처리된다

**근거**

- `MinioObjectStorage.deleteObjectsWithPrefix()`는 개별 오브젝트 삭제 오류를 로그만 남기고 정상 반환한다.
- 결과 삭제는 DB 커밋 뒤 로컬/MinIO 파일을 best-effort로 지우며 실패를 사용자 응답에 반영하지 않는다.
- `StorageCleanupService`는 로컬 고아 디렉터리를 발견했을 때만 대응하는 MinIO prefix를 삭제한다. 로컬 디렉터리가 먼저 사라지고 MinIO 삭제만 실패하면 다음 주기에 같은 prefix를 다시 발견할 근거가 없다.
- 원본 영상 retention도 MinIO 실패를 로그만 남기고 성공 카운트에 포함할 수 있다.

**영향**

- 사용자 결과 삭제나 회원 탈퇴 API가 성공했는데 영상·결과 JSON이 MinIO에 남을 수 있다.
- 보존 기간 30일 정책이 실제 오브젝트 저장소에서는 지켜지지 않을 수 있다.
- 로그를 놓치면 영구 고아 데이터가 된다.

**수정 방향**

- MinIO batch delete에서 오류 목록이 하나라도 있으면 구조화된 실패 결과 또는 예외를 반환한다.
- DB에 `storage_deletion_tasks` 같은 삭제 outbox를 만들고 `jobId`, prefix, 사유, 상태, 시도 횟수, 다음 재시도 시각을 저장한다.
- API 트랜잭션은 업무 데이터 삭제와 outbox 생성까지 원자적으로 처리하고, worker가 지수 backoff로 물리 삭제를 재시도한다.
- 일정 횟수 초과 시 dead-letter와 관리자 재처리 API/대시보드를 제공한다.
- `object_delete_pending`, `object_delete_failed`, `retention_overdue_objects` 메트릭과 경보를 추가한다.
- 삭제 완료를 엄격히 보장해야 하는 개인정보 요청은 “접수됨/삭제 처리 중/완료/실패” 상태를 구분한다.

### P1-04. 두 Python 엔진의 다운로드 URL 경계가 SSRF를 충분히 막지 못한다

**근거**

- analysis-engine은 `videoDownloadUrl`을 별도 scheme/host 검증 없이 `requests.get()`에 전달한다.
- video-llm-engine은 http/https와 netloc만 검사하며, loopback·link-local·사설망·클라우드 metadata 주소와 redirect 대상은 제한하지 않는다.
- analysis-engine의 로컬 `videoPath`도 허용 base directory 검증 없이 존재하는 파일이면 사용한다. video-llm-engine에는 동일 목적의 base directory 검증이 있다.

**영향**

- 내부 공유 키가 노출되거나 backend가 침해되면 엔진을 내부망 스캔·metadata 접근·대용량 내부 응답 다운로드에 악용할 수 있다.
- 방어가 “엔진 키가 절대 노출되지 않는다”는 단일 가정에 의존한다.

**수정 방향**

- 가장 안전한 방식은 엔진이 임의 URL을 받지 않고 `objectKey`만 받아 MinIO SDK와 고정 endpoint로 다운로드하게 하는 것이다.
- URL 방식을 유지하면 허용 host/port 목록, DNS 해석 후 사설·loopback·link-local 차단, redirect 비활성화 또는 매 redirect 재검증을 적용한다.
- analysis-engine에도 `ANALYSIS_ENGINE_ALLOWED_VIDEO_BASE_DIR` 경계 검사를 추가한다.
- 두 엔진이 공통으로 쓰는 URL/path 검증 모듈과 SSRF 테스트를 만든다.

### P1-05. 중요 설정의 유효성 검증이 분산돼 일부 오류는 첫 요청 때만 드러난다

**근거**

- 일부 `@ConfigurationProperties` record에는 `@Validated`, `@NotBlank`, `@Min` 등의 제약이 없다.
- rate limit 값은 해당 bucket의 첫 요청에서 검증된다.
- timeout, retention, poller batch, queue 상한 등은 여러 `@Value`와 수동 변환에 흩어져 있다.
- Python 엔진도 일부 값은 lifespan에서 검증하지만 chunk/세마포어/URL 정책 등은 import 시 또는 실제 호출 시점에 해석된다.

**영향**

- 배포와 헬스체크는 성공한 뒤 실제 사용자 요청에서 설정 오류가 발견될 수 있다.
- `0`, 음수, 잘못된 단위 조합이 스케줄러 중단, 큐 정지, 무제한 보존 또는 mock fallback을 만들 수 있다.

**수정 방향**

- backend 설정을 도메인별 typed `@ConfigurationProperties`로 통합하고 Bean Validation을 적용한다.
- 교차 조건도 검증한다. 예: `corePoolSize <= maxPoolSize`, `jobTimeout < stuckThreshold`, prod의 필수 endpoint/secret/SMTP 정책.
- Python 엔진은 Pydantic Settings 또는 단일 settings 객체로 모든 환경변수를 기동 시 한 번 검증한다.
- readiness는 “프로세스가 뜸”과 “실제 기능 수행 가능”을 구분한다.

### P2-01. Redis 장애 시 JWT 강제 무효화가 fail-open이다

**근거**

- `JwtBlacklist.isBlacklisted()`는 Redis 예외 시 `false`를 반환한다.
- 로그아웃 시 blacklist 저장도 실패를 호출자에게 알리지 않는다.

**영향**

- 브라우저 쿠키는 삭제돼도 탈취된 bearer token은 Redis 장애 중 만료 시각까지 계속 사용될 수 있다.
- 운영자가 계정 정지·비밀번호 변경·로그아웃을 “즉시 세션 종료”로 이해하면 실제 보장과 차이가 난다.

**수정 방향**

- 현재 30분 access token을 더 짧게 하고 refresh token 회전/세션 테이블을 도입하는 방향을 검토한다.
- 최소 변경으로는 Redis 장애 메트릭·경보와 보안 민감 API의 fail-closed 정책을 분리한다.
- 사용자에게 제공하는 보안 정책에 강제 무효화의 최대 지연을 명시한다.

**현재 작업 트리의 보완**

- 로그아웃된 access token의 raw 값 대신 SHA-256 해시와 원래 만료 시각을
  `revoked_access_tokens` DB 원장에 먼저 커밋한다.
- 서명·만료 검증을 무효화 원장 조회보다 먼저 수행해, 임의의 bearer 문자열이 Redis miss와
  DB 조회를 반복 유발하지 못하게 한다.
- Redis는 폐기된 토큰만 빠르게 판정하는 양성 캐시로 사용한다. Redis miss 또는 장애 시
  DB 원장을 조회하므로 Redis 재시작·장애 중에도 로그아웃 무효화가 유지된다.
- DB 원장에서 폐기 토큰을 확인하면 남은 JWT 수명만큼 Redis 양성 캐시를 다시 채운다.
  따라서 장애 중 로그아웃된 토큰도 Redis 복구 후 첫 요청만 DB를 사용하고 다음 요청부터
  Redis에서 판정한다.
- Redis 연결·명령 timeout을 각각 `REDIS_CONNECT_TIMEOUT_MS`,
  `REDIS_COMMAND_TIMEOUT_MS`로 명시하고 기본값을 2초로 제한한다. 0 또는 음수이면
  backend가 기동을 거부한다.
- DB 원장 읽기까지 실패하면 JWT 필터가 인증을 허용하지 않고 구조화된 503
  `AUTH_SESSION_SERVICE_UNAVAILABLE` 응답을 반환한다.
- DB 원장 쓰기가 실패한 로그아웃도 성공으로 표시하지 않고 503을 반환하며, 클라이언트가
  복구 뒤 다시 로그아웃할 수 있도록 성공 쿠키 삭제 헤더를 보내지 않는다.
- 만료된 원장 행은 worker가 500건 단위로 정리한다. DB/Redis 실패 counter와
  `JwtRevocationDatabaseUnavailable`, `JwtRevocationRedisCacheFailure` 경보를 추가했다.
- 운영 비용은 Redis miss마다 기존 사용자 조회 외에 DB 존재 확인 쿼리 한 건이 추가되는
  것이다. refresh token·세션 테이블 전환 전까지 보안 보장과 변경 범위를 절충한 구조다.

**2026-07-27 실제 장애·복구 검증**

- 격리 MySQL 8.4에 V1부터 V24까지 적용하고 `ddl-auto: validate` 기동을 확인했다.
- timeout 명시 전 Redis 중단 요청은 Lettuce 기본 명령 timeout 때문에 약 60초 동안
  응답하지 않았다. 코드의 DB fallback만으로는 운영 가능한 장애 대응이 아니었다.
- 기본 timeout을 2초로 제한한 뒤 Redis 중단 상태에서 기존 폐기 토큰 401(0.572초),
  신규 로그아웃 200(0.067초), 해당 토큰 재사용 401(0.013초), 정상 토큰 200(0.048초)을
  확인했다.
- DB에는 서로 다른 64자 SHA-256 해시 두 건이 원래 만료시각과 함께 남았고,
  `redis_read_failure=4`, `redis_write_failure=1`, `database_hit=3` 메트릭이 증가했다.
- Redis 복구 후 장애 중 폐기된 토큰의 첫 요청은 DB hit로 캐시를 1건 추가했고
  두 번째 요청은 Redis hit로 처리됐다. 응답은 각각 401(0.051초), 401(0.006초)이었다.
- 검증에 사용한 backend 프로세스, 격리 MySQL·Redis 컨테이너와 토큰 응답 임시 파일은
  검증 후 제거했다. 기존 Compose 볼륨과 사용자 DB는 수정하지 않았다.

**남은 검증**

- staging에서 동일 훈련을 반복하고 `JwtRevocationDatabaseUnavailable`,
  `JwtRevocationRedisCacheFailure` 경보가 실제 온콜 채널에 도착하는지 확인한다.

### P2-02. 핵심 로직이 초대형 파일에 집중돼 변경 위험이 높다

현재 주요 파일 크기:

- `analysis-engine/app/api/basic_analysis.py`: 2,442행
- `video-llm-engine/app/api/video_llm_analysis.py`: 1,209행
- `AnalysisCommandService.java`: 925행
- `OpenAiClient.java`: 871행
- `ResultDetailPage.jsx`: 849행
- `ResultListPage.jsx`: 778행
- `UploadPage.jsx`: 738행

**영향**

- 분석 점수, I/O, 외부 API, 응답 조립, fallback이 한 파일에서 결합돼 회귀 범위가 커진다.
- 리뷰가 어려워지고 단위 테스트가 내부 구현에 강하게 결합된다.

**수정 방향**

- 정량 분석 공식은 변경하지 않고 `video_io`, `frame_sampling`, `audio`, `pose`, `face`, `scoring`, `response` 모듈로 이동한다.
- Video LLM은 `settings`, `download`, `segmenter`, `nvidia_client`, `normalizer`, `fallback`으로 분리한다.
- backend orchestration은 상태 전이와 각 외부 단계 실행기를 분리한다.
- 프론트 페이지는 data hook, filter/sort model, section component로 나눈다.
- 한 번에 재작성하지 말고 characterization test를 먼저 고정한 뒤 파일 단위로 이동한다.

### P2-03. 실제 Video LLM 장애가 mock 결과를 반환해 job 성공으로 보일 수 있다

**최초 근거**

- `VIDEO_LLM_ENABLED=true`에서도 실제 호출 예외는 `FALLBACK` mock 응답으로 바뀐다.
- 결과 JSON과 UI badge, backend 메트릭에는 REAL/FALLBACK/MOCK이 구분돼 있어 완전히 숨겨지지는 않는다.

**현재 작업 트리의 보완**

- `VIDEO_LLM_POLICY=STRICT|DEGRADED|DISABLED`를 도입했다. 정책 값이 비었을 때만 기존
  `VIDEO_LLM_ENABLED`에서 DEGRADED/DISABLED를 유도한다.
- STRICT에서는 실제 호출 예외를 구조화된 502로 반환하고 backend가
  `VIDEO_LLM_ENGINE_ERROR`로 작업을 실패 처리한다.
- DEGRADED만 FALLBACK 샘플 응답을 허용하며 결과 목록·비교·관리 화면에 실제 분석 실패와
  샘플 대체 경고를 표시한다.
- 2026-07-27 후속 리뷰에서 긴 영상 세그먼트 일부만 성공해도 불완전한 결과가 REAL로
  반환되는 계약 위반을 확인했다. 모든 세그먼트 생성·NVIDIA 호출이 성공해야만 REAL로
  병합하며, 하나라도 실패하면 STRICT/requireReal은 실패하고 DEGRADED 일반 분석만
  전체 FALLBACK으로 전환하도록 수정했다.
- readiness와 상태 화면에 policy를 노출한다. fallback 경보는 의도된 DISABLED/MOCK을 제외하고
  REAL+FALLBACK 시도 중 FALLBACK 비율만 계산한다.

**남은 문제와 방향**

- 배포 환경별 정책을 확정하고 실제 NVIDIA timeout/5xx를 주입해 STRICT 작업 실패와 DEGRADED
  샘플 대체를 E2E로 검증해야 한다.
- 2026-07-27 후속 보완으로 backend가 영상 길이와 엔진 공통 청크 길이로 예상 NVIDIA
  세그먼트 호출 수를 계산해 Redis Lua로 월간 잔여 용량 안에서 원자 예약한다. 길이
  재확인이 실패하면 업로드 최대 길이 기준으로 보수 예약하며, 실제 Redis에서 7+13 단위
  예약 후 추가 1단위가 거부되고 카운터가 20에 유지되는 것을 확인했다.
- 가중 예약 전용 API만 용량 초과 시 카운터를 유지하고, 로그인·코치 등 기존 단일 rate
  limit은 거절 시도도 카운트하는 기존 보안·UI 계약을 보존했다.
- Video LLM 비-live 테스트 177건, backend 전체 테스트 436건(외부 연동 9건 skip),
  개발·운영 Compose config와 세 서비스의 공통 청크 값 전달을 확인했다. 가중 예약
  검증용 격리 Redis 컨테이너는 종료·제거했다.
- 완료된 DEGRADED 결과는 일반 `/retry` 대신 보존형 재분석 API를 사용한다. 원본 결과는
  유지하고, 비용·사용 한도를 다시 소비하는 새 child job을 생성한다.
- 보존형 재분석의 권장 모델과 단계별 migration/API/retention 계약은
  `docs/service-plan/video-llm-reanalysis-design-2026-07-23.md`에 정리했다. 기존 job 초기화나
  영상 물리 복사 대신 불변 video asset을 여러 analysis job이 공유하는 구조를 권장한다.
- R1/R2 기반으로 V22 `video_asset_id`, V23 `analysis_kind`·`source_job_id`·최종
  `video_llm_generation_mode`를 추가했고, 신규 업로드·worker·결과 목록·영상 재생을 asset
  참조 우선으로 전환했다. 개별 결과 삭제는 마지막 참조에서만 원본을 삭제하며, retention은
  같은 asset의 최신 또는 미완료 참조가 있으면 삭제를 보류한다.
- backend에는 `POST /api/analysis/{sourceJobId}/video-llm-reanalysis`를 열었다. 원본/asset
  잠금, SHA-256 멱등 키, active child DB guard, 큐·비용 사전 확인, `requireReal` 이중 검증을
  함께 적용했다.
- R4에서 목록·상세 응답에 `analysisKind`, `sourceJobId`, 저장된
  `videoLlmGenerationMode`를 노출하고 원본 상세에는 최신 재분석 job ID를 제공한다. 결과
  파일이 아직 없는 QUEUED/RUNNING child도 상태 shell을 반환하므로 기존 상세 polling을
  재사용한다.
- 프론트 상세는 STANDARD+COMPLETED+FALLBACK에서만 실제 Video LLM 재분석 버튼을 표시한다.
  확인 dialog에 비용·한도 재소비와 새 결과 생성을 알리고, 네트워크 재시도에도 같은
  `Idempotency-Key`를 유지한 뒤 child 상세로 이동한다. 원본/최신 재분석 링크는 제공하지만
  두 결과를 한 번에 선택하는 직접 비교 UX는 후속 보완이다.

### P2-04. 빌드 재현성과 예정된 도구 변경 대응이 필요하다

**근거**

- Gradle 9.5.1은 JDK toolchain repository 미설정이 Gradle 10에서 실패한다고 경고했다.
- 테스트의 `@MockBean` 사용이 제거 예정 경고 30건을 발생시켰다.
- `certbot/certbot:latest`, 주요 Docker base tag, GitHub Actions major tag를 digest/SHA로 고정하지 않았다.

**수정 방향**

- Gradle toolchain resolver repository를 명시한다.
- `@MockBean`을 현재 Spring Boot가 권장하는 대체 방식으로 점진 전환한다.
- 운영 이미지와 Actions를 digest 또는 commit SHA로 고정하고 Dependabot/Renovate가 갱신하게 한다.
- SBOM과 이미지 서명/출처 검증을 배포 gate에 추가한다.

### P2-05. 테스트 통과와 실제 운영 검증 사이에 남은 공백이 있다

- backend 기본 테스트의 MinIO/Redis 외부 연동 8건은 환경 플래그가 없어 이번 로컬 실행에서 skip됐다. CI에는 별도 MinIO 통합 job이 있다.
- 현재 작업 트리에는 실제 업로드→DB 큐→worker→analysis-engine→결과 조회 E2E와 CI job이 추가 중이지만 아직 커밋되지 않았고 이번 리뷰에서 실행하지 않았다.
- 실제 NVIDIA API 테스트 1건은 API 키가 없어 deselect/skip됐다.
- staging 도메인, TLS 인증서 발급·갱신, SMTP 실발송, 백업 원격 복구, 장애 주입은 이번 리뷰에서 실행하지 않았다.

**수정 방향**

- 현재 추가 중인 분석 pipeline E2E를 CI에서 실제 통과시킨 뒤 병합한다.
- nightly 또는 수동 protected environment에서 NVIDIA/SMTP/TLS/backup restore 검증을 분리한다.
- 정기 restore rehearsal과 결과 증적을 남긴다.

### P3-01. local H2가 MySQL/Flyway 차이를 숨길 수 있다

local 프로필은 H2 + `ddl-auto: update`, dev/prod는 MySQL + Flyway다. 이미 CI에 실제 MySQL boot smoke가 있어 과거보다 안전하지만, 개발자가 local 테스트만 보고 마이그레이션 호환성을 놓칠 수 있다.

**개선 방향**

- 빠른 단위 테스트는 H2를 유지하되, 기본 개발 실행은 Compose MySQL dev profile을 권장한다.
- schema 관련 PR은 MySQL integration test를 필수 gate로 둔다.

### P3-02. 로그인 응답 본문에도 access token이 남아 있다

프론트는 HttpOnly 쿠키만 사용하지만 로그인 응답 DTO는 하위 호환을 위해 token을 본문에도 제공한다. 즉시 취약점은 아니지만 쿠키 전환의 경계를 흐리고 새 클라이언트가 다시 브라우저 저장소에 token을 보관하게 만들 수 있다.

**개선 방향**

- bearer client 사용 현황을 확인한 뒤 API 버전을 나눠 웹 로그인 응답에서 token을 제거한다.
- 제거 전 deprecation 기간과 마이그레이션 가이드를 둔다.

### P3-03. README의 Video LLM 설명이 현재 구현과 서로 충돌한다

최초 감사에서는 루트 README의 실행 안내 앞부분이 “현재는 mock 응답만 반환”한다고
설명하지만, 같은 문서 뒤에서는 실제 hosted 호출을 안내해 서로 충돌했다.

**영향**

- 새 개발자나 운영자가 실제 모델 경로가 미구현이라고 오해하거나, 반대로 검증되지 않은 local-model 경로까지 완성된 것으로 오해할 수 있다.

**현재 작업 트리의 보완**

- README, `.env.example`, Compose, 배포 체크리스트와 `docs/PROJECT_STRUCTURE.md`를
  `STRICT`, `DEGRADED`, `DISABLED` 정책 기준으로 함께 갱신했다.
- `external-api` 실제 호출과 `local-model` 이미지 의존성 프로필을 구분하고,
  정책·backend 설치 형태의 교차 조건을 기동 시 검증한다.
- 문서의 기준 커밋/갱신일을 현재 상태에 맞춘다.

## 4. 수정하지 않은 항목

이번 작업은 리뷰·문서화 단계이므로 애플리케이션 코드는 수정하지 않았다. 또한 다음 항목은 이미 현재 코드에서 구현돼 있어 신규 문제로 반복 제시하지 않았다.

- DB 큐와 독립 worker
- 사용자별 결과 소유권 검증
- 내부 엔진 API key fail-closed
- 업로드 매직 바이트·크기 제한
- MySQL/Flyway 운영 프로필
- 프론트/백엔드 주요 API 계약
- OpenAI/Video LLM generation mode 표시
- 백업 암호화와 `--force` 복구 안전장치

## 5. 단계별 개선 실행 계획

### 1단계: 계정 복구와 데이터 삭제 보장

목표: P1-01, P1-02, P1-03 해결

1. 재설정 토큰 단일 활성화·일괄 무효화·정리 스케줄러 구현
2. SMTP 필수/선택 정책 결정 및 readiness/feature flag 구현
3. 오브젝트 삭제 outbox, retry, dead-letter, 메트릭 구현
4. 회원 탈퇴·결과 삭제·retention 통합 테스트 추가

완료 기준:

- 이전 재설정 링크가 새 발급/성공 직후 사용할 수 없음
- SMTP 미설정 운영 배포가 정책에 따라 기동 실패 또는 기능 비활성으로 명확히 표시됨
- MinIO 장애 후 복구하면 미삭제 객체가 자동 재시도로 제거됨

### 2단계: 엔진 입력 경계와 설정 fail-fast

목표: P1-04, P1-05 해결

1. URL 대신 MinIO object key 계약으로 전환하거나 공통 SSRF 차단 구현
2. analysis-engine 로컬 path allowlist 적용
3. backend typed properties + Bean Validation 통합
4. Python settings 객체와 기동 시 전수 검증

완료 기준:

- loopback/private/link-local/redirect 우회 URL 테스트가 모두 거절됨
- 잘못된 timeout/queue/retention 설정은 readiness 이전에 기동 실패함

### 3단계: 세션·fallback 운영 정책과 관측성

목표: P2-01, P2-03 해결

1. Redis 장애 시 세션 무효화 정책 결정
2. Video LLM STRICT/DEGRADED 정책 구현
3. fallback, 삭제 지연, 토큰 정리, SMTP 발송 지표와 경보 추가

### 4단계: 점진적 모듈 분리

목표: P2-02 해결

1. characterization test 확충
2. Python 엔진 I/O와 분석 도메인 분리
3. backend orchestration 단계 분리
4. 프론트 대형 페이지의 hook/component 분리

정량 분석 점수 계산식과 결과 JSON 계약은 별도 승인 없이 변경하지 않는다.

### 5단계: 공급망·실환경 검증

목표: P2-04, P2-05, P3 항목 개선

1. Gradle/Spring 테스트 API 마이그레이션
2. Actions·이미지 digest 고정, SBOM/서명
3. 분석 pipeline E2E CI 통과
4. staging TLS/SMTP/NVIDIA/backup restore 리허설

## 6. 실행한 검증

| 영역 | 명령/방법 | 결과 |
| --- | --- | --- |
| Backend | `./gradlew test --rerun-tasks --no-daemon --warning-mode all` | 성공, 299 tests, 실패 0, 오류 0, skip 8 |
| Backend coverage | JaCoCo XML 집계 | line 87.75% (covered 3,955 / missed 552) |
| Frontend lint | `npm run lint` | 성공 |
| Frontend unit/component | `npm run test` | 45 files, 210 tests 성공 |
| Frontend build | `npm run build` | 성공 |
| Frontend dependency audit | `npm audit --audit-level=high` | 취약점 0 |
| Analysis engine | `.venv/bin/python -m pytest -q` | 118 tests 성공 |
| Video LLM engine | `.venv/bin/python -m pytest -q` | 132 tests 성공, live test 1 deselected |
| Compose | base 및 prod overlay `docker compose ... config --quiet` | 필수 더미 환경변수 주입 후 성공 |

추가 관찰:

- 백엔드 최초 `clean test`는 Gradle 캐시를 사용했으므로 검증 근거로 삼지 않고 `--rerun-tasks` 결과를 기준으로 했다.

### 6.1 구현 진행분 재검증 (2026-07-23)

- `git diff --check`: 통과
- backend `./gradlew clean test --rerun-tasks --no-daemon`: 403 tests 중 395 통과,
  8 skipped, failures/errors 0
- backend 보완 대상 테스트 8개 강제 재실행: 통과
- frontend `npm run build`, `npm run lint`: 통과
- frontend `npm test -- --run`: 45 files, 216 tests 통과
- analysis-engine `.venv/bin/python -m pytest -q`: 144 tests 통과
- video-llm-engine `.venv/bin/python -m pytest -q`: 177 tests 통과, 1 deselected
- `docker compose config --quiet`: 통과

첫 Python 테스트 시도는 루트 공용 `.venv`에 pytest가 없어 실행되지 않았고, 각 엔진의 `.venv`로 바로잡은 결과를 위 검증 근거로 사용했다.
- Gradle 10 toolchain 경고와 `@MockBean` 제거 예정 경고 35건이 확인됐다.
- P1/P2 보완 후 base/prod `docker compose config --quiet`는 다시 통과했다.
- Prometheus alert/rule-test YAML은 파싱됐지만 로컬에 `promtool`이 없어
  `promtool test rules`는 실행하지 못했다.

### 6.2 R5 MySQL·MinIO·동시성 실측 (2026-07-23)

- 기존 dev MySQL 8.4 스키마를 V18에서 V24까지 올리고 Flyway 24개 validation과 Hibernate
  `ddl-auto: validate`, backend `/api/health`, actuator `UP`을 확인했다.
- 이 과정에서 V21 `revoked_access_tokens.token_hash`와 V24
  `analysis_jobs.reanalysis_idempotency_key_hash`가 migration에서는 `CHAR(64)`, JPA에서는
  `VARCHAR(64)`로 해석돼 기동을 차단하는 문제를 발견했다. 두 엔티티를 고정 길이
  `CHAR(64)`로 맞춘 뒤 재기동이 통과했다.
- 기존 볼륨과 분리된 일회용 MySQL 8.4 빈 스키마에도 V1→V24 전체 24개 migration을 적용했고,
  마지막 이력 `version=24, success=1`, backend health를 확인한 뒤 컨테이너를 삭제했다.
- 938 KiB 실제 MP4를 `write-required/read-preferred=true`로 업로드하고 MinIO 객체를 직접
  확인한 뒤 로컬 원본을 격리했다. MinIO만 남은 상태에서도 재분석 접수가 202로 성공했다.
- API 전용 모드(`worker.enabled=false`, `dispatch.local-on-run=false`)에서 같은 source에
  서로 다른 키 10개를 동시에 보냈다. 결과는 202 한 건, 409 아홉 건이며 DB active child도
  한 건이었다. 성공 키 replay는 HTTP 200과 동일 child ID를 반환했다.
- 첫 동시성 시도에서 local dispatch를 끄지 않아 첫 child가 즉시 FAILED된 뒤 두 번째 요청이
  합법적으로 접수되는 현상도 확인했다. 이는 active 중복이 아니라 “실패로 guard 해제 후 새
  접수”이며, 순수 접수 경합 검증은 API 전용 모드로 다시 수행했다.
- `.env.example`은 `MINIO_ROOT_USER/PASSWORD`를 표준으로 안내하지만 직접 실행 경로는
  `MINIO_ACCESS_KEY/SECRET_KEY`만 읽던 불일치를 수정했다. access/secret이 없을 때 root
  변수를 사용하는 fallback으로 dev 직접 기동을 재검증했다.
- 테스트용 계정·source/child/asset DB 행, MinIO 객체, 로컬·임시 파일은 모두 삭제했고 기존
  Compose 볼륨은 보존한 채 데이터 계층을 원래의 정지 상태로 복원했다.

### 6.3 R5 lineage 삭제 순서·MinIO Outbox 장애 복구 실측 (2026-07-23)

- 기존 구현은 source를 먼저 삭제해도 공유 asset만 보존하고 child의 `source_job_id`는 그대로
  남겨, child 상세의 원본 링크가 404가 되는 dangling lineage를 허용했다. 실행 중 child도
  source 계약을 잃을 수 있었다.
- source 행의 비관적 잠금 아래 `existsBySourceJobId`를 확인해 child가 하나라도 남아 있으면
  HTTP 409 `ANALYSIS_DELETE_NOT_ALLOWED`로 거부하고 “재분석 결과를 먼저 삭제”하도록 바꿨다.
  재분석 생성도 같은 source 행을 잠그므로 판정 직후 새 child가 끼어드는 race가 없다.
- H2와 격리 MySQL 8.4 모두에서 source 우선 삭제 409, lineage·asset·outbox 무변경을 확인했다.
  이후 child를 삭제하면 child result outbox만 생기고 asset은 유지되며, source를 마지막으로
  삭제할 때 source result와 upload outbox가 생성되고 asset 행이 제거되는 것을 확인했다.
- 실제 MinIO에 24바이트 검증 객체를 만든 뒤 MinIO를 중단하고 격리 MySQL outbox 한 건을
  처리했다. 작업은 `PENDING`, `attempt_count=1`, 오류 있음, processing token 해제, 미래
  `next_attempt_at` 상태로 영속돼 삭제 요청이 유실되지 않았다.
- MinIO 복구 후 같은 작업을 재시도해 `COMPLETED`, 오류·active key 해제, 완료 시각 기록과
  실제 객체 0건을 확인했다. MinIO I/O는 DB transaction 밖에서 실행됐다.
- 별도 격리 MySQL에서 최대 시도 횟수를 3으로 두고 MinIO 장애를 유지해
  `PENDING:1 → PENDING:2 → DEAD_LETTER:3`을 재현했다. 관리자 목록 API는 해당 작업을
  HTTP 200으로 노출했고, 관리자 재큐잉 API는 `PENDING`, `attempt_count=0`, 오류·lease
  초기화와 `REQUEUE_STORAGE_DELETION_TASK` 감사로그를 같은 흐름에서 기록했다.
- MinIO 컨테이너가 시작됐지만 아직 health-ready가 아니던 첫 재시도는 다시 `PENDING:1`로
  안전하게 남았다. healthy 이후 다음 시도에서 `COMPLETED`, 오류·active key·lease 해제,
  실제 객체 0건을 확인했고 관리자 DEAD_LETTER 목록에서도 제거됐다.
- 첫 기본 복구 검증에 사용한 backend, MySQL, MinIO 객체·컨테이너는 제거했고 기존 Compose
  데이터 볼륨과 사용자 DB는 수정하지 않았다.

> 2026-07-27 후속 확인에서 `/private/tmp/hanium-r5-deadletter-*` 파일과 검증용
> 18085·18086·13318·9000·6379 리스너가 남아 있지 않고 작업 트리도 clean인 것을 확인했다.
> 마지막 DEAD_LETTER 리허설의 격리 자원 정리는 완료된 상태다.

## 7. 검증하지 못한 항목과 남은 위험

- 실행 중인 전체 Docker stack에 실제 영상을 업로드하는 E2E
- 실제 NVIDIA API 품질·최대 영상·quota·비용
- 실제 SMTP 계정으로 메일 발송/반송 처리
- 공개 도메인의 TLS 최초 발급과 자동 갱신
- 원격 MinIO 백업을 빈 MySQL에 복원하는 이번 시점의 리허설
- Redis/worker 강제 종료를 포함한 전체 chaos test
- 현재 Flyway가 MySQL 8.4를 공식 검증 범위(MySQL 8.1까지)보다 새 버전으로 경고한다.
  운영 MySQL을 검증된 버전으로 고정하거나 Flyway를 호환 버전으로 올린 뒤 fresh migration을 반복해야 한다.
- `spring.jpa.open-in-view`가 기본 활성화돼 있다. API 직렬화 중 지연 로딩과 예기치 않은
  DB 접근을 막으려면 명시적으로 끄고 결과·관리자 API 회귀 테스트를 수행해야 한다.
- 정식 침투 테스트와 개인정보/법률 준수 검토

정적 리뷰와 현재 테스트가 통과했다는 사실은 위 실환경 검증을 대체하지 않는다.

## 8. 다음 우선순위

다음 우선순위는 **P1 실환경 gate와 Video LLM 실제 모델 검증**이다.

1. staging 정책을 STRICT 또는 DEGRADED로 확정하고 NVIDIA timeout/5xx 장애를 주입해
   child FAILED, REAL 복구 성공, 비용·quota 메트릭을 rehearsal한다.
2. 500MB 경계 영상의 asset 공유 시 저장 중복이 없는지 확인한다.
3. 테스트 SMTP에서 정상 발송과 연결 실패 → backoff → DEAD_LETTER → 관리자 재큐잉 흐름을 rehearsal한다.
4. 잘못된 Python 설정을 의도적으로 주입해 두 엔진이 readiness 전에 종료되는지 Compose에서 확인한다.
5. 원본/재분석 직접 비교 UX를 보완한다.

## 9. 2026-07-31 추가 리뷰: 구간 분할과 회로 차단기 시간 기준 불일치

### 확인한 문제

Video LLM 엔진은 100초를 초과하는 영상을 여러 세그먼트로 잘라 NVIDIA API를 순차
호출한다. 그러나 backend의 `video-llm-engine` 회로 차단기는 단일 NVIDIA 호출
timeout(120초)에 가까운 110초부터 전체 엔진 요청을 slow call로 집계하고 있었다.
따라서 긴 영상이 정상적으로 세그먼트를 처리해 110초를 넘으면 성공 응답도 slow call로
누적되고, 5건 이후 회로가 열려 후속 작업이 실제 호출 없이 실패할 수 있었다.

### 개선 방향과 반영 내용

- 전체 엔진 HTTP read timeout(10분)과 같은 요청 경계를 기준으로 삼아 slow-call
  임계치를 9분으로 조정했다.
- `application.yaml`, 두 backend Compose 실행 단위와 `.env.example` 기본값을 모두
  `9m`으로 맞췄다.
- `VideoLlmEngineClientCircuitBreakerTest`가 실제 등록된 9분 임계치를 검증하도록
  변경했다.

### 남은 위험

이 변경은 정상 장기 요청을 회로 차단기가 장애로 오인하는 문제를 막지만,
video-llm-engine 내부에는 아직 여러 세그먼트 전체를 아우르는 단일 deadline이 없다.
각 세그먼트의 ffmpeg 분할 timeout과 NVIDIA 호출 timeout이 개별 적용되므로 최악의
경우 backend의 10분 read timeout을 넘긴 뒤에도 엔진 스레드가 작업을 계속할 수 있다.
후속 단계에서는 `VIDEO_LLM_TOTAL_TIMEOUT_SECONDS` 같은 전체 deadline을 두고 분할,
세마포어 대기, asset 처리, API 호출·폴링이 남은 시간을 공유하도록 해야 한다.

## 10. 2026-07-31 후속 보완: Video LLM 전체 요청 deadline

위 9절의 남은 위험을 같은 리뷰 회차에서 후속 보완했다.

- `VIDEO_LLM_TOTAL_TIMEOUT_SECONDS`를 추가하고 기본값을 540초로 두어 backend의
  10분 HTTP read timeout보다 먼저 엔진 작업이 끝나도록 했다.
- 요청 시작 시 한 번 계산한 monotonic deadline을 MinIO 다운로드, ffmpeg 세그먼트
  분할, 실제 모델 세마포어 대기, NVIDIA Asset 처리, chat completion과 202 상태
  폴링 전체에 전파했다.
- 각 하위 작업은 독립 timeout과 남은 전체 시간 중 더 짧은 값만 사용한다. 공급자
  응답이 도착했더라도 전체 deadline을 이미 넘겼다면 REAL 결과로 반환하지 않고
  STRICT/`requireReal`은 502, DEGRADED 일반 요청은 전체 FALLBACK 정책을 따른다.
- deadline 만료 뒤 Asset 삭제를 다시 장시간 기다리지는 않는다. 삭제를 건너뛴 경우
  `NVIDIA_VIDEO_LLM_ASSET_CLEANUP_SKIPPED_DEADLINE` 경고 로그를 남긴다.
- 2개 세그먼트 중 첫 공급자 응답에서 deadline이 만료되면 두 번째 호출을 하지 않는
  회귀 테스트를 추가해 불필요한 비용 발생도 함께 차단했다.

남은 실환경 gate는 8절의 NVIDIA timeout/5xx 장애 주입과 복구 후 REAL 성공,
500MB 경계 Asset 경로 확인이다. 정적 테스트는 실제 공급자와 staging 네트워크의
지연·정리 동작을 대체하지 않는다.
