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

**근거와 현재 완화**

- `VIDEO_LLM_ENABLED=true`에서도 실제 호출 예외는 `FALLBACK` mock 응답으로 바뀐다.
- 결과 JSON과 UI badge, backend 메트릭에는 REAL/FALLBACK/MOCK이 구분돼 있어 완전히 숨겨지지는 않는다.

**남은 문제**

- 사용자는 분석 완료를 실제 모델 성공으로 오해할 수 있고, 운영 KPI도 단순 완료율만 보면 품질 저하를 놓칠 수 있다.

**수정 방향**

- 제품 정책을 `STRICT`, `DEGRADED`, `DISABLED`로 명시한다.
- STRICT에서는 real 실패 시 단계/작업을 실패시키고, DEGRADED에서는 결과 상단에 명확한 경고와 재시도 액션을 제공한다.
- fallback 비율 SLO와 경보를 추가한다.

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

루트 README의 실행 안내 앞부분은 “현재는 mock 응답만 반환”한다고 설명하지만, 같은 문서 뒤에서는 `VIDEO_LLM_ENABLED=true`와 NVIDIA API key를 이용한 실제 hosted 호출을 안내한다. CI 파일 상단 주석에도 mock 중심이라는 오래된 표현이 일부 남아 있다.

**영향**

- 새 개발자나 운영자가 실제 모델 경로가 미구현이라고 오해하거나, 반대로 검증되지 않은 local-model 경로까지 완성된 것으로 오해할 수 있다.

**개선 방향**

- 모드를 `mock`, `external NVIDIA hosted`, `local-model 미구현/준비 중`으로 명확히 구분한다.
- README, `.env.example`, Compose, CI 주석, `docs/PROJECT_STRUCTURE.md`를 한 변경에서 함께 갱신한다.
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
- Gradle 10 toolchain 경고와 `@MockBean` 제거 예정 경고 30건이 확인됐다.

## 7. 검증하지 못한 항목과 남은 위험

- 실행 중인 전체 Docker stack에 실제 영상을 업로드하는 E2E
- 실제 NVIDIA API 품질·최대 영상·quota·비용
- 실제 SMTP 계정으로 메일 발송/반송 처리
- 공개 도메인의 TLS 최초 발급과 자동 갱신
- 원격 MinIO 백업을 빈 MySQL에 복원하는 이번 시점의 리허설
- Redis/MinIO/worker 강제 종료를 포함한 chaos test
- 정식 침투 테스트와 개인정보/법률 준수 검토

정적 리뷰와 현재 테스트가 통과했다는 사실은 위 실환경 검증을 대체하지 않는다.

## 8. 다음 우선순위

다음 구현 작업은 **1단계의 P1-01 비밀번호 재설정 토큰 단일 활성화**부터 시작하는 것이 가장 안전하다. 변경 범위가 비교적 작고 보안 효과가 명확하며, 이후 SMTP/outbox 작업의 데이터 모델 기준도 함께 정리할 수 있다.
