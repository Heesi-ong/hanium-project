# 계획 외 사각지대 분석 및 서비스화 진행률 수치화

작성일: 2026-07-03
기준 커밋/브랜치: main (7c89494)
범위: 기존 계획 문서(`additional-gap-analysis.md`, `service-readiness-quality-assessment.md`)에 **이미 등재된 항목(Video LLM mock, 분산 큐, 공유 스토리지, 모델 프리로딩, 백업 리허설, 모니터링 등)은 제외**하고, 어느 문서에도 잡히지 않은 문제만 다룹니다. 모든 항목은 2026-07-03 실제 코드 확인 근거를 포함합니다.

---

## 1. 계획에 없는 문제점과 개선점

### 1.1 보안/인증

**S1. JWT를 브라우저 localStorage에 저장 (High)**
- 근거: `frontend/src/api/apiClient.js:14`, `frontend/src/context/AuthContext.jsx:33`
- 문제: XSS(웹페이지에 악성 스크립트가 끼어드는 공격)가 한 번이라도 성공하면 토큰이 그대로 탈취됩니다. localStorage는 자바스크립트가 자유롭게 읽을 수 있는 저장소이기 때문입니다.
- 개선: HttpOnly 쿠키(자바스크립트가 못 읽는 쿠키) 방식으로 전환하거나, 최소한 토큰 수명 단축 + S2의 무효화 수단과 함께 위험을 관리.

**S2. 토큰 무효화 수단이 전혀 없음 — refresh token, 서버측 로그아웃 부재 (High)**
- 근거: `SecurityConfig.java:82-114`에 발급/검증만 존재. `AuthController`에 logout 엔드포인트 없음, refresh 관련 코드 0건.
- 문제: access token 수명이 120분 단일 구조라서, 탈취된 토큰을 만료 전에 서버가 강제로 죽일 방법이 없습니다. 프론트의 "로그아웃"은 localStorage 삭제일 뿐 토큰 자체는 계속 유효합니다.
- 개선: refresh token + access token 수명 단축(예: 15분), 또는 서버측 토큰 블랙리스트(Redis 활용 가능, 이미 Redis 있음).

**S3. 회원 탈퇴/개인 데이터 삭제 API가 없음 (High, 법적 리스크)**
- 근거: `presentation/controller/`에 회원 삭제 엔드포인트 없음. `StorageCleanupService`는 고아 파일만 삭제.
- 문제: 이 서비스는 사용자의 **얼굴과 음성이 담긴 영상**을 저장합니다. 개인정보보호법상 삭제 요구권 대응 수단(탈퇴 + 영상/결과 일괄 삭제)이 없으면 실서비스 공개가 불가한 수준의 문제입니다.
- 개선: 회원 탈퇴 API(사용자 레코드 + 소유 job + 업로드/결과 파일 연쇄 삭제) 추가. 개인정보처리방침 문서도 필요.

**S4. 로그인 브루트포스 방어 없음 (Medium)**
- 근거: `UserRateLimitFilter.java`는 `/api/analysis/upload`, `/run`, `/retry`만 대상. `/api/auth/login`은 제한 없음.
- 문제: 비밀번호 무차별 대입을 막는 장치(시도 횟수 제한, 계정 잠금)가 없습니다.
- 개선: 로그인 엔드포인트를 rate limit 대상에 추가(IP+이메일 기준). 기존 `UserRateLimiter` 재사용으로 비용 낮음.

**S5. docker-compose가 내부 서비스 포트를 전부 호스트에 공개 (High, 배포 시)**
- 근거: `docker-compose.yml` — mysql `3306`, redis `6379`, analysis-engine `8001`, video-llm-engine `8002` 모두 `ports:`로 호스트 바인딩. Redis는 비밀번호도 없음. MySQL 기본 비밀번호 `changeme`.
- 문제: 이 compose 파일을 그대로 서버에 올리면 DB/Redis/엔진이 인터넷에 노출됩니다. 엔진의 공유 키 인증(A1 해결분)이 있어도, 비밀번호 없는 Redis는 rate limit 조작·캐시 오염 통로가 됩니다.
- 개선: prod용 compose 분리 — backend/frontend만 포트 공개, 나머지는 `expose`(컨테이너 간 통신)만. Redis `requirepass` 설정. `127.0.0.1:` 바인딩 최소 적용.

**S6. TLS/HTTPS 종단이 어디에도 없음 (High, 배포 시)**
- 근거: `infra/nginx/`, `infra/env/`, `infra/mysql/` 디렉터리가 **비어 있음**. compose에 리버스 프록시 서비스 없음. `VITE_API_BASE_URL` 기본값 `http://`.
- 문제: 로그인 비밀번호와 JWT가 평문 HTTP로 전송됩니다. 또한 `docs/PROJECT_STRUCTURE.md`가 infra에 nginx/MySQL 설정이 있다고 기술하고 있어 문서-실체 불일치.
- 개선: nginx(또는 Caddy) 리버스 프록시 + Let's Encrypt 구성을 infra에 추가. PROJECT_STRUCTURE.md의 infra 설명 수정.

### 1.2 운영/안정성

**O1. graceful shutdown 미설정 (Medium)**
- 근거: `server.shutdown: graceful` 설정이 어떤 프로파일에도 없음.
- 문제: 배포/재시작 시 진행 중이던 HTTP 응답과 스레드풀 내 분석 작업이 그대로 끊깁니다. 워치도그가 30분 뒤에야 FAILED 처리하므로, 그동안 사용자는 멈춘 진행률만 보게 됩니다.
- 개선: `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase` 설정, 종료 시 RUNNING job을 즉시 FAILED(재시도 가능) 전환하는 shutdown hook.

**O2. 컨테이너 리소스 제한 없음 (Medium)**
- 근거: `docker-compose.yml`에 `mem_limit`/`deploy.resources` 0건.
- 문제: analysis-engine은 Whisper/MediaPipe를 쓰는 메모리 대식가입니다. 요청이 몰려 메모리가 폭주하면 같은 호스트의 MySQL·backend까지 함께 죽습니다(OOM killer가 무작위로 프로세스를 종료).
- 개선: 서비스별 메모리/CPU 상한 설정, 특히 analysis-engine.

**O3. 저장 용량 쿼터와 영상 길이 제한 없음 (Medium)**
- 근거: `application.yaml:13-14` max-file-size 500MB만 존재. 사용자별 누적 용량, 영상 재생 시간, 보유 개수 제한 코드 없음.
- 문제: rate limit(분당 5회)만으로는 하루 수십 GB 업로드가 가능합니다. 긴 영상은 분석 시간·OpenAI 비용도 비례해서 커집니다. 디스크 고갈은 전 사용자 장애로 이어집니다.
- 개선: 사용자별 누적 용량/보유 개수 상한, 업로드 시 ffprobe로 재생 시간 상한 검증(A2 부분해결 항목과 묶어 처리 가능).

**O4. 정상 데이터의 보존 기간 정책이 없음 (Medium, S3와 연결)**
- 근거: `StorageCleanupService.java`는 temp와 고아 파일만 삭제. 정상 업로드 영상·결과는 무기한 보관.
- 문제: 얼굴/음성 영상의 무기한 보관은 개인정보 최소 보유 원칙에 어긋나고 디스크 비용도 무한 증가합니다.
- 개선: "분석 완료 후 원본 영상 N일 보관 뒤 삭제(결과만 유지)" 같은 명시적 보존 정책 수립 + 스케줄러 구현 + 가입 시 고지.

### 1.3 품질/프로세스

**Q1. CI에 보안·공급망 검사가 없음 (Medium)**
- 근거: `.github/workflows/verify.yml` — test/lint/build만 수행. `npm audit`, `pip-audit`, dependabot 설정, 컨테이너 이미지 빌드 검증 모두 없음.
- 문제: 기존 문서 D에서 의심 버전(`fastapi==0.138.2` 등)까지 지적된 상태인데, 취약점·위조 패키지를 자동으로 걸러낼 장치가 없습니다. Dockerfile이 4개나 있는데 CI에서 이미지 빌드가 깨져도 모릅니다.
- 개선: CI에 `docker build` 검증 job과 의존성 감사 단계 추가, dependabot 활성화.

**Q2. API 계약의 자동 검증 부재 + API 문서화 없음 (Medium)**
- 근거: springdoc/swagger 의존성 0건. 프론트 `api/*.js`와 백엔드 컨트롤러의 일치를 사람이 눈으로 확인하는 구조.
- 문제: 07-02에 실제로 "삭제 API 프론트-백 불일치" 사고가 있었던 프로젝트인데, 같은 유형의 회귀를 막는 자동 장치가 여전히 없습니다. CLAUDE.md 기준 6("계약 테스트")이 계획에는 있으나 구체 작업으로 착수되지 않은 상태.
- 개선: springdoc-openapi 추가 → OpenAPI 스펙 산출 → 프론트 호출 목록과 대조하는 계약 테스트를 CI에 편입.

**Q3. 계획 문서 자체가 이미 stale — 문서 신뢰성 문제 (Low, 그러나 반복 비용 큼)**
- 근거: `additional-gap-analysis.md`와 `service-readiness-quality-assessment.md`는 "스케줄러 분산 락 없음"을 잔여 리스크 3번으로 기재. 그러나 `SchedulerDistributedLock.java`가 존재하고 `.env.example`에 `SCHEDULER_LOCK_*` 변수까지 있음.
- 문제: 해결된 항목이 미해결로 남아 있으면 다음 작업자가 중복 작업을 하거나, 반대로 "문서가 틀리니 다 무시"하게 됩니다.
- 개선: 두 문서의 잔여 리스크 목록 갱신(분산 락 → 해결로 이동, 단 실동작 검증 여부 명기).

---

## 2. 서비스화 진행률 수치화

CLAUDE.md의 서비스 가능 수준 10개 기준별 평가입니다. 산정 방식: 각 기준을 세부 요건으로 쪼개고, 코드 근거가 확인된 요건의 비율로 계산했습니다. 정적 확인 기반이며 실행 검증(부하/침투/E2E)은 반영되지 않았습니다.

| # | 기준 | 진행률 | 근거 |
|---|---|---|---|
| 1 | 빌드/CI 정상 동작 | 85% | 4개 서비스 CI job 분리, backend 테스트 16개, 프론트 lint+build, 엔진 pytest 실행(`verify.yml`). 감점: docker build 미검증, 의존성 감사 없음(Q1), CI 실제 통과 여부는 이 세션에서 미실행 |
| 2 | 4개 실행/배포 단위 분리 | 95% | 각 서비스 Dockerfile + compose 통합 기동 + healthcheck. 감점: prod용 compose 미분리(S5) |
| 3 | local/dev/prod 분리 + 환경변수 주입 | 85% | 3개 프로파일, `.env.example` 정비, prod DB 기본값 없음. 감점: JWT secret 로컬 기본값 잔존(기존 A7), TLS 없음(S6) |
| 4 | 운영 DB + 마이그레이션 | 80% | MySQL + Flyway V1~V5, dev/prod 적용. 감점: 백업 실검증 미완(기존), 복구 리허설 없음 |
| 5 | 비동기 job 구조 | 70% | 스레드풀 비동기 + 상태/진행률/재시도/취소/워치도그 + 낙관적 락 + 스케줄러 분산 락. 감점: 프로세스 내부 큐라 인스턴스 장애 시 job 재배치 불가(기존), graceful shutdown 없음(O1) |
| 6 | 프론트-백 API 계약 일치 | 75% | 07-02 지적된 삭제 API 불일치는 해결로 기록됨. 감점: 자동 계약 검증 없음(Q2) — 일치가 "현재 우연히 유지되는 상태"이지 보장되는 상태가 아님 |
| 7 | 인증/권한/소유권/파일 보호 | 70% | JWT 인증, 소유권 검증, rate limit, 매직바이트, 내부 엔진 키 완료. 감점: localStorage 토큰(S1), 무효화 불가(S2), 탈퇴 없음(S3), 로그인 브루트포스(S4) — 인증이 "있다"에서 "안전하다"까지의 거리 |
| 8 | Video LLM mock 대체 | 15% | 여전히 `mock-video-llm` 응답. 모델 옵션 조사 문서만 존재(계획 단계). 스키마/경계 구조가 잡혀 있는 점만 반영 |
| 9 | OpenAI 설정/timeout/fallback/비용 정책 | 80% | timeout, MOCK/REAL/FALLBACK, 토큰 사용량 로그, 재시도 시 응답 재사용. 감점: 사용자·월별 예산 한도와 초과 차단 없음(기존) |
| 10 | 테스트/로그/모니터링/백업/정리 | 55% | 테스트 3개 언어 존재, rolling 로그, health 공개, cleanup 스케줄러, 백업 스크립트. 감점: metrics/alerting 전무(기존), 백업 리허설 미완(기존), E2E 없음, 정상 데이터 보존 정책 없음(O4) |

**종합: 약 71% (10개 기준 단순 평균)**

해석:
- 07-02 시점 평가("데모 수준", 체감 30~40%)에서 인증·DB·비동기·CI가 채워지며 큰 폭 상승.
- 남은 29%의 구성: 기준 8(Video LLM)이 최대 단일 갭이고, 나머지는 "기능 존재 → 운영 안전" 사이의 마감 작업(무효화 가능한 인증, 자동 계약 검증, 관측가능성, 데이터 정책)에 몰려 있음.
- 71%는 "클로즈드 베타 직전"이지 "공개 가능"이 아님. 특히 S3(개인 데이터 삭제)과 S6(TLS)은 수치상 작아 보여도 공개 서비스의 법적 전제조건이라 게이트(통과 못 하면 공개 불가) 항목으로 취급해야 함.

---

## 3. 권장 처리 순서 (계획 외 항목 한정)

1. S3 회원 탈퇴/데이터 삭제 + O4 보존 정책 — 법적 게이트, 구조 변경 없이 추가 가능
2. S5 prod compose 분리 + S6 TLS — 배포 전 필수, 코드 무관 인프라 작업
3. S2 토큰 무효화 + S4 로그인 rate limit — 기존 Redis/RateLimiter 재사용으로 저비용
4. O1 graceful shutdown + O3 용량/길이 제한 — 설정 중심 저비용
5. Q1 CI 보강 + Q2 계약 테스트 — 회귀 방지 장치
6. Q3 기존 문서 갱신 — 다음 작업 세션 전 필수

---

## 4. 업데이트: 2026-07-04 현재 상태

2026-07-03 이후 S/O/Q 계열 운영 보강 Unit이 다수 반영됐습니다. 아래 판정은 2026-07-04 현재 작업 트리의 실제 파일을 다시 열어 확인한 결과입니다. 기존 2026-07-03 본문은 당시 스냅샷으로 보존합니다.

### 4.1 S/O/Q 항목 최신 판정

**S2. 로그아웃/토큰 무효화 없음**
- **판정: 해결**
- `AuthController.java:110-120`에 `POST /api/auth/logout`이 추가되어 현재 Bearer 토큰의 남은 TTL 동안 블랙리스트 등록을 수행합니다.
- `JwtBlacklist.java:27-52`는 Redis에 토큰 해시를 TTL과 함께 저장하고 조회합니다.
- `SecurityConfig.java:183-187`은 블랙리스트 토큰이면 인증을 만들지 않고 통과시켜 이후 `/api/**` 인증 규칙에서 401이 되게 합니다.
- 한계: `JwtBlacklist.java:49-52`는 Redis 장애 시 fail-open으로 처리하므로 Redis 재시작/장애 중 강제 무효화 보장은 약합니다.

**S3. 회원탈퇴/개인 데이터 삭제 API 없음**
- **판정: 해결**
- `UserController.java:13-24`에 인증 필요 경로인 `DELETE /api/users/me`가 추가됐습니다.
- `UserWithdrawalService.java:40-57`은 비밀번호 재확인 후 소유 분석 데이터를 삭제하고 사용자 계정을 삭제합니다.
- `UserWithdrawalService.java:60-80`은 사용자의 모든 job에 대해 기존 `ResultCommandService.deleteResult(...)`를 재사용합니다.
- 한계: 탈퇴 UI는 아직 frontend에 없습니다.

**S4. 로그인 시도 rate limit 없음**
- **판정: 해결**
- `AuthController.java:85-95`는 로그인 검증 전에 이메일 기준 `"login"` bucket을 차감하고 초과 시 429를 반환합니다.
- `UserRateLimiter.java:32-40`, `UserRateLimiter.java:66-72`는 Long userId뿐 아니라 문자열 key와 login bucket을 지원합니다.
- `application.yaml:85-94`는 `rate-limit.login.capacity/refill-minutes`를 환경변수로 설정합니다.
- 한계: 이메일 기준 제한이라 IP 기준 분산 공격, 계정 잠금 정책은 별도 과제입니다.

**S5. docker-compose 내부 서비스 포트 노출 + Redis 무비밀번호**
- **판정: 해결**
- `docker-compose.yml:30-31`, `docker-compose.yml:49-50`, `docker-compose.yml:64-65`, `docker-compose.yml:82-83`에서 mysql/redis/analysis-engine/video-llm-engine 포트가 `127.0.0.1`에만 바인딩됩니다.
- `docker-compose.yml:48`은 Redis `--requirepass`를 설정하고, `docker-compose.yml:114-116`은 backend에 같은 비밀번호를 전달합니다.
- `application.yaml:20-24`는 `spring.data.redis.password`를 환경변수로 읽습니다.
- 한계: `changeme` 기본값은 개발 편의용이므로 실제 배포 전 강한 값으로 교체해야 합니다.

**S6. TLS/HTTPS 종단 없음**
- **판정: 부분해결**
- `infra/nginx/nginx.conf:1-11`은 HTTP 80에서 ACME challenge를 제외한 요청을 HTTPS로 리다이렉트합니다.
- `infra/nginx/nginx.conf:14-31`은 443 TLS 종단과 `/api/` backend 프록시를 정의합니다.
- `docker-compose.prod.yml:5-19`는 nginx 서비스와 인증서/webroot 볼륨을 구성하고, `docker-compose.prod.yml:21-28`은 certbot renew 컨테이너를 둡니다.
- `docker-compose.prod.yml:30-34`는 prod 오버레이에서 backend/frontend 직접 포트 노출을 제거합니다.
- 한계: 실제 도메인/DNS/Let's Encrypt 발급/HTTPS 접속은 이 환경에서 검증되지 않았습니다.

**O1. graceful shutdown 미설정**
- **판정: 해결**
- `application.yaml:1-13`에 `server.shutdown: graceful`과 `spring.lifecycle.timeout-per-shutdown-phase`가 추가됐습니다.
- `AsyncConfig.java:21-30`은 분석 전용 `ThreadPoolTaskExecutor`가 shutdown 시 작업 완료를 기다리도록 설정합니다.
- `docker-compose.yml:87-97`은 backend에 `stop_grace_period: 40s`와 리소스 제한을 둡니다.
- 한계: 실기동 후 SIGTERM 종료 로그 검증은 별도로 필요합니다. 제한 시간을 넘긴 작업은 여전히 워치도그 복구에 의존합니다.

**O2. 컨테이너 리소스 제한 없음**
- **판정: 해결(실기동 적용 검증 미완)**
- `docker-compose.yml:20-24`, `docker-compose.yml:43-47`, `docker-compose.yml:56-60`, `docker-compose.yml:73-78`, `docker-compose.yml:93-97`, `docker-compose.yml:147-151`에서 6개 서비스 모두에 CPU/메모리 제한이 추가됐습니다.
- `docker-compose.yml:13-14`는 제한값이 로컬/소규모 배포 기준이며 운영 서버 스펙에 맞춰 조정 필요하다고 명시합니다.
- 한계: `docker compose config` 수준의 문법/병합 확인은 됐지만, Docker daemon 환경에서 `HostConfig.Memory`, `NanoCpus` 실제 적용은 아직 확인되지 않았습니다.

**O3. 저장 용량 쿼터와 영상 길이 제한 없음**
- **판정: 구현됨(테스트 미검증)**
- 업로드 용량 초과는 `ErrorCode.java:13`의 `FILE_TOO_LARGE`와 `GlobalExceptionHandler.java:63-70`의 `MaxUploadSizeExceededException` 핸들러로 413 응답을 반환하도록 구현됐습니다.
- 저장 공간 부족 검증은 `StorageProperties.java:5-13`, `application.yaml:66-72`, `VideoFileCommandService.java:91-106`에 구현됐습니다.
- `VideoFileCommandServiceTest.java:70-92`에는 저장 공간 충분/부족 케이스 테스트가 존재합니다.
- 한계: 사용자가 전달한 상태 기준으로 `./gradlew test`는 JDK/네트워크 환경 제약 때문에 아직 통과 확인이 되지 않았습니다. 또한 ffprobe 기반 영상 재생 시간 제한은 여전히 미구현입니다.

**O4. 정상 데이터 보존 기간 정책 없음**
- **판정: 해결**
- `OriginalVideoRetentionService.java:52-63`은 설정된 보존 기간을 지난 `COMPLETED` job을 조회합니다.
- `OriginalVideoRetentionService.java:85-99`는 원본 업로드 디렉토리와 `UploadedVideo` 레코드만 삭제하고 결과 디렉토리는 삭제하지 않습니다.
- `OriginalVideoRetentionServiceTest.java:69-89`는 오래된 COMPLETED job만 삭제되고 FAILED/CANCELLED와 최근 COMPLETED job은 유지됨을 검증합니다.
- 한계: 원본 영상 삭제 후 사용자에게 원본 재다운로드 기능이 필요해질 경우 별도 UX/정책 정리가 필요합니다.

**Q1. CI 보안·공급망 검사 없음**
- **판정: 해결(감사 실패 허용 항목 존재)**
- `.github/workflows/verify.yml:38-42`는 frontend lint/test/build 뒤 `npm audit --audit-level=high`를 실행합니다.
- `.github/workflows/verify.yml:64-68`은 Python 엔진에서 `pip-audit --no-deps --disable-pip`를 실행하되, 현재 알려진 Pillow/pytest 취약점 때문에 `continue-on-error: true`입니다.
- `.github/workflows/verify.yml:76-89`는 4개 이미지 docker build matrix를 실행하고 `fail-fast: false`로 한 이미지 실패가 나머지 빌드를 취소하지 않게 했습니다.
- `.github/dependabot.yml:1-26`은 npm/pip/gradle/github-actions 주간 업데이트를 설정합니다.
- 한계: Python audit은 현재 보고용에 가깝고, 발견 취약점 업그레이드 작업은 별도입니다.

**Q2. API 계약 자동 검증 + API 문서화 없음**
- **판정: 해결**
- `build.gradle:21-31`에 springdoc OpenAPI 의존성이 추가됐습니다.
- `SecurityConfig.java:62-68`에서 `/v3/api-docs/**`, `/swagger-ui/**`가 공개 허용됩니다.
- `ApiContractTest.java:47-66`은 frontend `src/api/*.js`의 `apiClient` 호출이 backend `/api/**` 라우트와 매칭되는지 검증합니다.
- `ApiContractTest.java:68-73`은 `/v3/api-docs`가 공개 JSON을 반환하는지 검증합니다.
- 한계: 현재 정규식은 `apiClient.get/post/put/delete(...)` 패턴을 대상으로 하므로, 다른 HTTP 호출 방식이 생기면 테스트 추출 규칙 확장이 필요합니다.

### 4.2 서비스화 진행률 수치화 갱신

| # | 기준 | 2026-07-04 진행률 | 변경 근거 |
|---|---:|---:|---|
| 1 | 빌드/CI 정상 동작 | 90% | `verify.yml:38-89`에 frontend test/audit, Python compileall/pytest/audit, docker build matrix가 포함됨. 감점: Python audit continue-on-error, Docker 실기동/이미지 빌드 현장 검증은 CI 의존 |
| 2 | 4개 실행/배포 단위 분리 | 95% | 각 서비스 Dockerfile/compose 유지, Python 엔진 Dockerfile은 `python:3.13-slim`으로 CI Python 버전과 맞춤(`analysis-engine/Dockerfile:1`, `video-llm-engine/Dockerfile:1`) |
| 3 | local/dev/prod 분리 + 환경변수 주입 | 90% | 내부 포트/Redis/TLS/nginx/prod overlay가 추가됨(`docker-compose.yml:30-50`, `docker-compose.prod.yml:5-34`). 감점: 운영 secret 강제 검증과 실제 TLS 발급 검증 미완 |
| 4 | 운영 DB + 마이그레이션 | 82% | MySQL/Flyway와 백업 스크립트 유지. 감점: 백업/복구 실제 리허설과 원격 보관/암호화 미완 |
| 5 | 비동기 job 구조 | 78% | 내부 스레드풀, 취소/재시도/워치도그, graceful shutdown, 스케줄러 분산 락까지 있음(`AsyncConfig.java:21-35`, `StuckAnalysisJobWatchdogService.java:60-87`). 감점: 분산 큐 아님 |
| 6 | 프론트-백 API 계약 일치 | 90% | `ApiContractTest.java:47-73`로 프론트 호출과 backend 라우트, OpenAPI 공개를 자동 검증 |
| 7 | 인증/권한/소유권/파일 보호 | 83% | 로그아웃 블랙리스트, 회원탈퇴, 로그인 rate limit, 내부 엔진 키, 매직바이트, O3 저장공간 검증까지 반영. 감점: JWT localStorage 저장(`AuthContext.jsx:8-39`), O3 테스트 미검증 |
| 8 | Video LLM mock 대체 | 15% | `video_llm_analysis.py:25-79`는 여전히 `mock-video-llm` 고정 응답 |
| 9 | OpenAI 설정/timeout/fallback/비용 정책 | 80% | 토큰 사용량 로깅/재사용/재시도 제한 유지. 감점: 사용자별 월간 예산과 관리자 비용 대시보드 없음 |
| 10 | 테스트/로그/모니터링/백업/정리 | 72% | pytest/Vitest/계약 테스트/rolling log/Actuator/cleanup/retention/backup script가 있음. 감점: metrics/alerting 없음, 백업 리허설 미완, O2 런타임 inspect 미검증 |

**종합: 약 78% (10개 기준 단순 평균)**

수치가 2026-07-03의 약 71%에서 오른 이유는 S2/S3/S4/S5/S6, O1/O2/O4, Q1/Q2가 실제 코드/설정으로 들어갔기 때문입니다. 다만 Video LLM mock, 분산 큐/공유 스토리지, 백업 리허설, O3 테스트 검증/영상 길이 제한이 남아 있어 공개 서비스 수준으로 보기는 아직 이릅니다.

### 4.3 권장 처리 순서 갱신

1. **Video LLM mock 교체**: `video_llm_analysis.py:25-79`의 고정 mock 응답을 실제 모델/외부 API 호출로 대체해야 합니다.
2. **S1 JWT localStorage 저장 완화**: `AuthContext.jsx:8-39`, `apiClient.js:12-36`에서 access token을 localStorage에 저장/읽습니다. XSS 방어와 토큰 저장 전략 재검토가 필요합니다.
3. **분산 큐 + 공유 파일 스토리지 검토**: 현재 분석 실행은 `AnalysisCommandService.java:196-210`과 `AsyncConfig.java:21-35`의 프로세스 내부 스레드풀 기반이고, 파일은 `FilePathGenerator.java:17-39`의 로컬 경로 기반입니다.
4. **O3 검증 마무리 + 영상 길이 제한**: `VideoFileCommandService.java:91-106` 저장 공간 검증은 구현됐지만 전체 테스트 통과 확인이 필요하고, `basic_analysis.py:272-308`은 duration을 계산만 할 뿐 업로드 단계에서 길이 제한을 적용하지 않습니다.
5. **백업 리허설/원격 보관/암호화**: `scripts/backup-mysql.sh:60-86`의 백업과 보존 로직은 있으나 실제 MySQL 복구 리허설, 원격 저장, 암호화, 알림은 남아 있습니다.
6. **CI audit 후속 패치**: `.github/workflows/verify.yml:64-68`의 `pip-audit`는 현재 실패 허용이므로, Pillow/pytest 취약점 업그레이드 후 실패 허용을 제거하는 것이 다음 단계입니다.

---

## 5. 업데이트: 2026-07-05 현재 상태

2026-07-04 이후 운영/관측성 계열 Unit이 대거 반영됐습니다. 아래 판정은 2026-07-05 현재 작업 트리의 실제 파일과 `git log`를 다시 열어 확인한 결과이며, 기존 섹션(2026-07-03 원본, 2026-07-04 업데이트)은 당시 스냅샷으로 보존합니다.

### 5.1 신규 반영 항목 판정

**치명 버그 수정: 운영 배포 시 VITE_API_BASE_URL 미설정으로 API 전체 실패 (커밋 59be339)**
- `frontend/src/api/apiClient.js:3`이 `import.meta.env.VITE_API_BASE_URL ?? ""`로 기본값을 상대경로로 바꿨고, `frontend/Dockerfile:12-13`이 빌드 ARG 기본값을 `""`로 둡니다.
- `docker-compose.prod.yml:33-36`은 prod 오버레이에서 `VITE_API_BASE_URL: ""`를 명시해 nginx 뒤에서 `/api/` 상대경로 호출이 되게 합니다(로컬 개발은 `docker-compose.yml:181`의 `http://localhost:8080` 기본값 유지).
- 의미: 이전에는 운영 배포 시 프론트가 localhost:8080을 호출해 **실제 서비스가 불가능한 버그**였습니다. 문서화만 되고 방치되어 있다가 이번에 수정됐습니다.

**O3 마무리: ffprobe 영상 재생 시간 제한 (커밋 af2f3b0)**
- `FfprobeVideoDurationProbe.java:15-39`가 ffprobe 서브프로세스로 재생 시간을 확인하고, 확인 실패/타임아웃 시 fail-open으로 통과시킵니다.
- `VideoFileCommandService.java:59,100`에서 저장 직후 길이 검증을 호출하며, 상한은 `application.yaml:96`(`VIDEO_MAX_DURATION_MINUTES`, 기본 30분)로 주입됩니다.
- 2026-07-04 시점 "영상 길이 제한 여전히 미구현" 감점 요소가 해소됐습니다.

**S1 완화 + 만료 UX (커밋 84c4983, 0f6660a)**
- `application.yaml:52`에서 access token 만료가 30분으로 단축됐습니다(`SECURITY_JWT_EXPIRATION_MINUTES`).
- `frontend/src/api/apiClient.js:43-50`은 401 수신 시 토큰을 지우고 원래 경로를 `sessionStorage`에 보관한 뒤 로그인으로 보내며, 만료 안내 플래그를 남깁니다.
- 한계: 토큰이 여전히 localStorage에 저장되는 구조(S1) 자체는 유지 — HttpOnly 쿠키 전환은 남은 과제입니다.

**S3 마무리: 회원탈퇴 프론트 UI (커밋 d20860b)**
- `frontend/src/pages/AccountPage.jsx:31-95`가 비밀번호 재확인 + 이중 확인 문구와 함께 `withdrawAccount`를 호출합니다. 테스트(`AccountPage.test.jsx`)도 존재합니다. 2026-07-04의 "탈퇴 UI 없음" 한계가 해소됐습니다.

**Video LLM 벤더중립 골격 (커밋 b1f5674) — 과대평가 금지**
- `video-llm-engine/app/api/video_llm_analysis.py:26,95-106`은 `VIDEO_LLM_ENABLED`가 켜지면 실제 모델 호출 경로로 진입하되 미구현이면 `"FALLBACK"`, 꺼져 있으면 `"MOCK"`으로 `generationMode`를 명시합니다. `docker-compose.yml:115`에 플래그 배선.
- **실제 모델 연동은 여전히 없습니다.** 응답은 여전히 mock이며, 달라진 것은 "mock임을 스스로 표시하고 실제 호출 자리를 마련한 것"까지입니다.

**Q1 마무리: pip-audit 취약점 실제 패치 + 감사 강제화 (커밋 8d93fd6)**
- `analysis-engine/requirements.txt:35,47`, `video-llm-engine/requirements.txt:27,32`에서 pillow==12.2.0, pytest==9.0.3으로 패치됐고, `verify.yml`의 pip-audit 단계(`verify.yml:64-66`)에서 `continue-on-error`가 제거되어 감사 실패가 CI 실패가 됩니다.

**백업 자동화 + 무결성 검사 (커밋 d24e4b9)**
- `docker-compose.yml:40-73`의 `backup` 서비스가 `BACKUP_INTERVAL_HOURS`(기본 24h) 주기로 스크립트를 반복 실행합니다.
- `scripts/backup-mysql.sh:62-70`은 `gzip -t` 무결성 검사와 최소 크기 검사를 수행하고, 실패 파일은 삭제 후 ERROR 로그를 남깁니다.

**관측성 스택 신설 (커밋 b5dfa72, ca016f3, 13f6a34, 2425452, 7ca6b16, a5ed86e)**
- 관리 포트 분리: `application.yaml:31-37` — `management.server.port: 8081`, `health,prometheus` 노출. nginx `/actuator/health` 프록시도 8081(`infra/nginx/nginx.conf:40-41`).
- 도메인 메트릭 5종: `AnalysisCommandService.java:188`(started), `:399`(completed), `:268,408,419`(failed reason별), `:437`(cancelled), `:442-448`(duration 타이머, outcome별 stop 5곳).
- 구조화 로깅: `logback-spring.xml:22-33`(local 평문), `:35-61`(dev/prod LogstashEncoder JSON 콘솔+파일 롤링). `RequestIdFilter.java:33-52`(X-Request-Id 수용/생성, finally 정리), `AsyncConfig.java:41-53`(TaskDecorator로 MDC 워커 전파), `AnalysisCommandService.java:235,242`(jobId MDC put/finally remove).
- Prometheus: `infra/prometheus/prometheus.yml:18-22`(backend:8081 스크레이핑), `infra/prometheus/alerts.yml:8,17`(BackendDown, AnalysisJobFailureRateHigh).
- Alertmanager: `docker-compose.monitoring.yml:49-63`(v0.33.0, 127.0.0.1:9093), `prometheus.yml:12-15`(alerting 연결), SMTP 비밀번호는 `.gitignore:95`로 커밋 차단(`.example`만 커밋).
- Grafana: `docker-compose.monitoring.yml:28-47`(13.0.3, 127.0.0.1:3000), `infra/grafana/provisioning/dashboards/json/analysis-overview.json:2-3`(uid 고정, "분석 서비스 개요", 패널 4개) 자동 프로비저닝.

**CI/공급망 보강 (커밋 8c161df, 11c1ad2)**
- `verify.yml:90-101`의 `compose-validate` job이 base/prod/monitoring/전체 오버레이 4개 조합의 `docker compose config`를 검증합니다.
- `.github/dependabot.yml`에 gradle/npm/pip×2/docker×4/github-actions **9개 생태계**가 등록됐습니다.

**nginx 보안 헤더 (커밋 fc5dae0, 59be339)**
- `infra/nginx/nginx.conf:25-29` — HSTS, X-Content-Type-Options, X-Frame-Options, Referrer-Policy, CSP(`connect-src 'self'` 정정 포함).

### 5.2 서비스화 진행률 수치화 갱신

| # | 기준 | 07-04 | **07-05** | 변경 근거 |
|---|---|---:|---:|---|
| 1 | 빌드/CI 정상 동작 | 90% | **93%** | compose-validate job 4조합(`verify.yml:90-101`), pip-audit 강제화(continue-on-error 제거), dependabot 9개 생태계. 감점: 이번 세션에서 CI 실제 실행 결과 미확인, 커밋별 빌드 검증 없음 |
| 2 | 4개 실행/배포 단위 분리 | 95% | **95%** | 변화 없음 (모니터링 오버레이는 선택 실행 단위로 추가) |
| 3 | local/dev/prod 분리 + 환경변수 | 90% | **92%** | VITE_API_BASE_URL prod 버그 수정(`docker-compose.prod.yml:36`), GRAFANA_ADMIN_PASSWORD/SMTP 비밀번호 파일 분리. 감점: TLS 실발급·HTTPS 접속 검증 여전히 미완 |
| 4 | 운영 DB + 마이그레이션 | 82% | **88%** | 백업 자동 스케줄링(`docker-compose.yml:40-73`) + gzip -t 무결성 검사(`backup-mysql.sh:62-70`). 감점: **복구 리허설 미완**, 원격 보관/암호화 없음 |
| 5 | 비동기 job 구조 | 78% | **80%** | 메트릭/MDC로 파이프라인 관측 가능해짐. 구조 자체는 그대로(프로세스 내부 스레드풀, 분산 큐 아님) |
| 6 | 프론트-백 API 계약 일치 | 90% | **90%** | 변화 없음 (ApiContractTest 유지) |
| 7 | 인증/권한/소유권/파일 보호 | 83% | **87%** | JWT 30분 단축, 세션 만료 UX, 탈퇴 UI, ffprobe 길이 제한, nginx 보안 헤더+CSP. 감점: JWT localStorage 유지(S1), `./gradlew test` 전체 통과는 환경 제약으로 미확인 |
| 8 | Video LLM mock 대체 | 15% | **20%** | generationMode(MOCK/FALLBACK) 벤더중립 골격과 플래그 배선만 추가. **실제 모델 연동은 여전히 0건** — 응답은 계속 mock |
| 9 | OpenAI 정책 | 80% | **80%** | 변화 없음. 사용자/월별 예산 한도 여전히 없음 |
| 10 | 테스트/로그/모니터링/백업/정리 | 72% | **88%** | 07-04의 최대 감점("metrics/alerting 전무")이 정반대로: 도메인 메트릭 5종 + JSON 구조화 로그/MDC + Prometheus 알림 2개 + Alertmanager + Grafana 대시보드 + 백업 자동화/무결성까지 전부 존재. 감점: 백업 **복구 리허설 미완**, **E2E 검증 미완**(이번 세션 환경에 Docker 데몬이 없어 compose 기동 기반 검증 불가), SMTP 실제 발송 미검증, gradle 테스트 실행 미확인 |

**종합: 약 81% (10개 기준 단순 평균, 07-04 약 78% → +3%p)**

해석: 이번 상승분은 대부분 기준 10(관측성)에서 나왔고, 치명적이었던 VITE_API_BASE_URL 버그가 잡히며 "배포하면 바로 죽는" 유형의 결함이 하나 줄었습니다. 남은 19%는 여전히 Video LLM 실연동(최대 단일 갭), 실행 검증(E2E/테스트/복구 리허설), 분산 큐/공유 스토리지에 몰려 있습니다.

### 5.3 권장 처리 순서 갱신 (2026-07-05)

1. **E2E 실행 검증**: Docker 데몬이 있는 환경에서 compose 기동 → 업로드→분석→결과 조회 전체 흐름 + `./gradlew test` 전체 통과 확인. 지금까지의 여러 Unit이 정적 검토만 거친 상태라 가장 시급합니다. (VITE_API_BASE_URL 버그처럼 "정적으로는 안 보이는" 결함을 잡는 유일한 수단)
2. **백업 복구 리허설**: 백업 생성·무결성 검사는 자동화됐으나, 실제 복원(restore) 절차는 한 번도 실행된 적이 없습니다.
3. **Video LLM 실제 모델 연동**: 골격(`generationMode`, `VIDEO_LLM_ENABLED`)은 준비됨. 벤더 선택이 제품 의사결정으로 남아 있습니다.
4. **Alertmanager SMTP 실값 설정 + 테스트 알림 발송 확인**: 설정은 완료됐으나 실제 발송은 미검증(`infra/alertmanager/alertmanager.yml`은 전부 플레이스홀더).
5. **S1 JWT localStorage → HttpOnly 쿠키 전환 검토**: 만료 단축(30분)으로 완화됐지만 근본 구조는 유지.
6. **분산 큐/공유 스토리지**: 다중 인스턴스 확장 시점에 착수(현재 단일 호스트 운영 전제로는 후순위).

---

## 6. 업데이트: 2026-07-06 현재 상태

2026-07-05 업데이트 이후 인증 계층, JWT secret 운영 안전장치, 프론트 테스트 정리, OpenAI 비용 거버넌스가 추가로 반영됐습니다. 아래 내용은 현재 코드 기준으로 다시 파일을 열어 확인한 결과이며, 기존 1~5번 섹션은 당시 스냅샷으로 그대로 보존합니다.

### 6.1 신규 반영 항목 판정

**회원가입 API rate limiting 추가 — 신규 발견 항목 해결**
- **판정: 해결**
- `AuthController.java:56-67`은 `/api/auth/signup` 진입 직후 `resolveClientIp()` 결과를 기준으로 `userRateLimiter.tryConsume("signup", clientIp)`를 먼저 수행합니다. 즉 중복 이메일 확인이나 비밀번호 해시 전에 IP 기준 제한이 적용됩니다.
- `AuthController.java:146-153`은 `X-Forwarded-For`의 첫 번째 값을 우선 사용하고, 없으면 `request.getRemoteAddr()`를 사용합니다.
- `UserRateLimiter.java:80-88`에는 `"signup"` bucket case가 존재하고, `application.yaml:115-117`, `.env.example:68-69`에 기본값(capacity 5, refill 10분)이 배선되어 있습니다.
- `SignupRateLimitIntegrationTest.java:40-49`는 서로 다른 이메일 3회 회원가입 중 3번째가 429로 차단되는지 검증합니다.

**로그인 IP 기준 보조 rate limiting — S4 한계 부분 해소**
- **판정: 부분해결**
- `AuthController.java:95-114`는 로그인 시 IP 기준 `"login-ip"` 제한을 먼저 확인하고, 그 다음 기존 이메일 기준 `"login"` 제한을 적용합니다.
- `UserRateLimiter.java:84-86`은 `"login"`과 `"login-ip"`를 별도 bucket으로 처리합니다. 설정은 `application.yaml:109-114`, `.env.example:64-67`에 분리되어 있습니다.
- `LoginIpRateLimitIntegrationTest.java:40-52`는 서로 다른 이메일 3개로 로그인해도 같은 IP에서 3번째 요청이 429가 되는지 검증합니다.
- 의미: 2026-07-04/05 섹션에서 남은 한계로 적었던 "이메일 기준 제한이라 IP 기준 분산 공격은 별도 과제"는 **부분적으로 해소**됐습니다. 다만 IP 공유 환경의 오탐과 프록시 신뢰 경계는 운영 네트워크 구성에 계속 의존합니다.

**회원가입 비밀번호 복잡도 정책 + DTO 분리**
- **판정: 해결**
- `AuthController.java:169-177`의 `AuthRequest`는 로그인용으로 남아 있고 비밀번호는 길이(8~72자)만 검증합니다.
- `AuthController.java:180-193`의 `SignupRequest`는 회원가입용으로 분리되어 `@Pattern`으로 영문자와 숫자를 각각 1자 이상 요구합니다.
- 이 분리는 기존 가입자나 향후 정책 변경 시 "로그인 요청까지 새 복잡도 규칙으로 막히는" 문제를 피하기 위한 설계입니다.
- `PasswordComplexityIntegrationTest.java:35-56`은 숫자 없는 비밀번호/문자 없는 비밀번호는 400, `password123`은 201로 검증합니다.

**JWT 기본 secret fail-fast 검증 + docker-compose 배선**
- **판정: 해결**
- `JwtSecretStartupValidator.java:10-12`는 `dev`, `prod` 프로파일에서만 동작합니다.
- `JwtSecretStartupValidator.java:14-17`은 코드 기본값과 `.env.example` placeholder 값을 모두 차단 목록에 넣고, `:27-35`에서 해당 값이면 기동을 실패시킵니다.
- `application.yaml:49-52`는 `SECURITY_JWT_SECRET`, `SECURITY_JWT_EXPIRATION_MINUTES`를 설정으로 노출하고, `docker-compose.yml:142-145`는 이 값을 backend 컨테이너 환경변수로 전달합니다.
- 의미: 기준 3에서 반복 감점 사유였던 "JWT secret 로컬 기본값 잔존"은 dev/prod 기동 fail-fast로 해소됐습니다. local 프로파일은 개발 편의를 위해 제외됩니다.

**회원가입 비밀번호 안내 문구 + 프론트 테스트 cleanup**
- **판정: 해결**
- `SignupPage.jsx:18-23`은 안내 문구 스타일을 정의하고, `SignupPage.jsx:94-109`는 비밀번호 입력란 바로 아래에 "영문자와 숫자..." 안내 문구를 표시합니다.
- `SignupPage.test.jsx:42-63`은 안내 문구가 비밀번호 필드 쪽에 있고 이메일 필드 쪽에는 없음을 검증합니다.
- `frontend/src/test/setup.js:1-7`은 `@testing-library/react`의 `cleanup()`을 전역 `afterEach`에 등록해 렌더링 테스트의 DOM 누적 위험을 줄였습니다.

**OpenAI 월간 호출 상한 — 기준 9 감점 사유 해결**
- **판정: 해결**
- `RateLimitProperties.java:5-13`은 `openaiMonthly` 설정 필드를 포함합니다.
- `application.yaml:118-120`, `.env.example:43-44`는 `OPENAI_MONTHLY_RATE_LIMIT_CAPACITY` 기본 1000, refill 44640분(31일)을 제공합니다.
- `OpenAiClient.java:51-59`는 실제 API 사용 가능 조건일 때 `"openai-monthly"` bucket을 먼저 소비하고, 한도 초과 시 OpenAI 호출 없이 `MOCK` + `"monthly OpenAI budget exceeded"`로 폴백합니다.
- `OpenAiClientBudgetTest.java:23-45`는 한도 초과 시 `RestClient`와 상호작용이 없음을 검증하고, `:48-69`는 OpenAI 비활성화 상태에서는 budget bucket도 소비하지 않음을 검증합니다.
- 의미: 기준 9에서 반복 감점 사유였던 "사용자/월별 예산 한도 없음" 중 **월별 상한**은 해결됐습니다. 사용자별 상한은 아직 별도 정책으로 남아 있습니다.

**OpenAI 월간 사용량/한도 관측성**
- **판정: 해결**
- `UserRateLimiter.java:56-68`은 카운트를 증가시키지 않고 현재 bucket count를 조회하는 `getCurrentCount()`를 제공합니다.
- `OpenAiUsageMetrics.java:21-35`는 `openai.monthly.usage`와 `openai.monthly.budget.capacity` gauge를 등록합니다.
- `infra/prometheus/alerts.yml:1-4`는 Alertmanager 연동 상태에 맞게 stale 주석을 수정했고, `:29-36`은 사용률 90% 초과가 5분 지속되면 `OpenAiMonthlyBudgetNearExhaustion` 경고를 firing합니다.
- `analysis-overview.json:142-173`은 `openai_monthly_usage / clamp_min(openai_monthly_budget_capacity, 1) * 100`을 표시하는 Grafana stat 패널을 추가합니다.

### 6.2 서비스화 진행률 수치화 갱신

| # | 기준 | 07-05 | 07-06 | 변경 근거 |
|---|---|---:|---:|---|
| 1 | 빌드/CI 정상 동작 | 93% | 93% | 이번 변경과 직접 관련 없음. 07-05 값 유지 |
| 2 | 4개 실행/배포 단위 분리 | 95% | 95% | 이번 변경과 직접 관련 없음. 07-05 값 유지 |
| 3 | local/dev/prod 분리 + 환경변수 | 92% | **95%** | `JwtSecretStartupValidator.java:10-35`와 `docker-compose.yml:142-145`로 dev/prod에서 공개 placeholder JWT secret 사용이 기동 실패로 전환됨 |
| 4 | 운영 DB + 마이그레이션 | 88% | 88% | 이번 변경과 직접 관련 없음. 07-05 값 유지 |
| 5 | 비동기 job 구조 | 80% | 80% | 이번 변경과 직접 관련 없음. 07-05 값 유지 |
| 6 | 프론트-백 API 계약 일치 | 90% | 90% | 이번 변경과 직접 관련 없음. 07-05 값 유지 |
| 7 | 인증/권한/소유권/파일 보호 | 87% | **90%** | signup IP rate limit(`AuthController.java:56-67`), login-ip 보조 제한(`AuthController.java:95-114`), SignupRequest 복잡도 검증(`AuthController.java:180-193`), 프론트 안내 문구(`SignupPage.jsx:94-109`) 반영 |
| 8 | Video LLM mock 대체 | 20% | 20% | 이번 변경과 직접 관련 없음. 실제 모델 연동은 여전히 미완 |
| 9 | OpenAI 정책 | 80% | **92%** | `openai-monthly` bucket(`UserRateLimiter.java:80-88`), 월간 한도 설정(`application.yaml:118-120`), 한도 초과 mock 폴백(`OpenAiClient.java:51-59`), usage/capacity gauge 및 알림/대시보드 추가 |
| 10 | 테스트/로그/모니터링/백업/정리 | 88% | **90%** | OpenAI 월간 gauge(`OpenAiUsageMetrics.java:21-35`), Prometheus 알림(`alerts.yml:29-36`), Grafana 패널(`analysis-overview.json:142-173`), RTL 전역 cleanup(`setup.js:1-7`) 추가 |

**종합: 약 83% (07-05 약 81% → +2%p, 10개 기준 단순 평균)**

해석: 이번 상승분은 기준 3(JWT secret 운영 안전장치), 기준 7(인증 API 방어), 기준 9(OpenAI 비용 정책), 기준 10(관측성/테스트 위생)에 집중됩니다. 특히 이전 섹션에서 반복 감점되던 JWT secret 기본값과 OpenAI 월간 예산 부재는 실제 코드/설정/테스트로 해결됐습니다.

### 6.3 권장 처리 순서 갱신 (2026-07-06)

1. **E2E 실행 검증**: compose 전체 기동 후 회원가입→로그인→업로드→분석→결과 조회→소유권 검증 흐름을 실제로 다시 확인해야 합니다.
2. **백업 복구 리허설**: 백업 생성/무결성 검사는 있지만, 실제 MySQL 복원 절차와 실패 시 운영 대응은 아직 검증되지 않았습니다.
3. **Video LLM 실제 모델 연동**: `generationMode` 골격은 있지만 실제 모델/외부 벤더 호출은 여전히 남은 최대 기능 갭입니다.
4. **Alertmanager SMTP 실값 설정 + 테스트 알림 발송 확인**: Alertmanager 연동과 규칙은 있으나 실제 SMTP credential/수신 테스트는 별도입니다.
5. **S1 JWT localStorage → HttpOnly 쿠키 전환 검토**: 만료 시간 단축과 로그아웃 블랙리스트는 완화책이며, 토큰 저장 위치 자체는 아직 localStorage입니다.
6. **README OpenAI 비용 정책 갱신**: `README.md:1004-1010`의 "OpenAI 호출 및 비용 제어 정책"은 timeout/fallback/usage 로그만 설명하고, 새 월간 예산 상한과 Prometheus/Grafana 관측 항목을 아직 언급하지 않습니다.
7. **분산 큐/공유 스토리지**: 단일 호스트/단일 backend 전제에서는 유지 가능하지만, 다중 인스턴스 확장 시 job 분배와 파일 공유 구조가 여전히 병목입니다.

---

## 7. 업데이트: 2026-07-06 저녁 현재 상태

2026-07-06 오전 업데이트 이후 프론트 랜딩 페이지 개편, Docker 포트 충돌 회피, Apple Silicon(aarch64) Docker 호환성 수정, 그리고 최초의 실제 compose 기반 E2E 실행 검증이 추가로 완료됐습니다. 아래 내용은 현재 파일을 다시 열어 확인한 코드 근거와 2026-07-06 저녁 실제 실행 결과를 기준으로 합니다. 기존 1~6번 섹션은 당시 스냅샷으로 보존합니다.

### 7.1 신규 반영 항목 판정

재확인: 6.3에 있던 "README OpenAI 비용 정책 갱신" 항목은 `README.md:1014`에서 이미 커밋 `b337860`으로 해결되어 있었음을 재확인했습니다(7.3에서 제거).

**랜딩 페이지 개편 + 시스템 상태 페이지 분리 (커밋 862075f)**
- **판정: 해결(UX 개선, 10개 기준 진행률에는 직접 반영하지 않음)**
- `HomePage.jsx:32-63`은 "이용 방법" 섹션을 추가해 영상 업로드 → 자동 분석 진행 → 결과 확인 → AI 피드백 확인 흐름을 설명합니다.
- `HomePage.jsx:66-94`는 실제 결과 상세 화면에서 제공하는 자세·제스처, 시선·얼굴, 표정·감정, 음성·발화 지표만 묶어 "분석 항목 상세 소개"를 제공합니다.
- 기존 상태 대시보드는 `StatusPage.jsx:16-35`의 `healthCheck()`/`engineHealthCheck()` 호출 로직과 `StatusPage.jsx:111-158`의 엔진 카드 UI로 분리됐습니다.
- `/status`는 `AppRoutes.jsx:19-27`의 ProtectedRoute + MainLayout 하위에 등록되어 로그인 사용자만 접근할 수 있고, `MainLayout.jsx:53-60`에 "시스템 상태" 네비게이션 링크가 추가됐습니다.
- 의미: 서비스화 10개 기준을 직접 올리는 기능은 아니지만, 로그인 후 홈 화면이 상태 대시보드 중심에서 실제 사용 흐름/분석 항목 소개 중심으로 바뀌어 프론트 콘텐츠 품질이 개선됐습니다.

**analysis-engine/video-llm-engine 호스트 포트 env var화 (커밋 94a3cc8)**
- **판정: 해결**
- `docker-compose.yml:99`는 analysis-engine 호스트 포트를 `ANALYSIS_ENGINE_PORT`로, `docker-compose.yml:118`은 video-llm-engine 호스트 포트를 `VIDEO_LLM_ENGINE_PORT`로 설정 가능하게 바꿨습니다.
- `.env.example:20-22`는 두 포트 변수를 문서화하고, 로컬 포트 충돌 시 값을 바꿔 우회할 수 있음을 명시합니다.
- 컨테이너 내부 포트와 backend의 서비스 간 통신 URL은 바꾸지 않았습니다. 즉 Docker 네트워크 내부 통신은 `analysis-engine:8001`, `video-llm-engine:8002` 구조를 유지하면서, 호스트 노출 포트만 환경변수로 조정할 수 있게 됐습니다.
- 의미: 로컬에서 8001/8002를 다른 프로세스나 기존 Docker 프록시가 점유해도 무관한 프로세스를 종료하지 않고 `.env`로 우회할 수 있습니다.

**analysis-engine aarch64 Docker 호환성 수정 (커밋 91535b3)**
- **판정: 해결**
- `analysis-engine/Dockerfile:1`은 base image를 `python:3.12-slim`으로 사용합니다.
- `analysis-engine/requirements.txt:5`는 `audioop-lts`를 Python 3.13 이상에서만 설치하도록 제한합니다.
- `analysis-engine/requirements.txt:27-37`은 Python 3.12 경로에서 aarch64 wheel이 있는 `mediapipe==0.10.18`, `numpy==1.26.4`, `opencv-*==4.10.0.84`를 사용하고, Python 3.13 이상 경로는 기존 최신 계열을 유지합니다.
- `analysis-engine/requirements.txt:54-62`도 Python 3.12/3.13 경로에 맞춰 `scipy`, `standard-*` 패키지를 분리합니다.
- 의미: CI의 amd64/Python 3.13 경로를 보존하면서, Apple Silicon Docker Desktop(aarch64)에서 analysis-engine 이미지가 실제로 빌드되는 경로가 생겼습니다.

**E2E 실행 검증**
- **판정: 해결**
- 2026-07-06 저녁 `docker compose up --build -d`로 mysql, redis, analysis-engine, video-llm-engine, backend, frontend, backup 스택을 실제 기동했습니다.
- `docker compose ps` 기준 backend와 mysql은 healthy였고, analysis-engine/video-llm-engine/frontend/redis/backup도 Up 상태였습니다. 최종 포트는 mysql `127.0.0.1:3308->3306`, analysis-engine `127.0.0.1:8001->8001`, video-llm-engine `127.0.0.1:8002->8002`로 확인됐습니다.
- 실제 HTTP 흐름은 signup `201` → login `200`(accessToken 발급) → upload `200`(`jobId=20260706121849-866b653b`) → run `200` → status polling `COMPLETED` → progress `100%` → result `200`까지 완료됐습니다.
- 결과 요약은 `totalScore=51`, `postureScore=100`, `speechScore=72`, `gestureScore=69`, `visualAnalysis.generationMode=MOCK`, `feedback.generationMode=MOCK`였습니다.
- 두 번째 계정으로 첫 번째 계정의 result/status/delete를 시도했을 때 모두 `403 ANALYSIS_JOB_ACCESS_DENIED`가 반환되어 소유권 검증도 실제 HTTP로 확인됐습니다.
- backend 컨테이너 내부 `/actuator/prometheus`에서 `analysis_job_completed_total{application="presentation-coaching-backend"} 1.0`과 `analysis_job_duration_seconds_count{...,outcome="completed"} 1`이 확인되어, 실제 완료 job이 메트릭에 반영됐습니다.
- analysis-engine 로그에는 모델 프리로딩 완료(`whisper=base, pose=loaded, face=loaded`)와 9단계 기본 분석 완료, 총점 51점이 기록됐습니다.
- 한계: 이번 E2E는 `OPENAI_ENABLED=false`, `useOpenAi=false`, `VIDEO_LLM_ENABLED=false` 조건에서 실행됐습니다. 따라서 OpenAI 실제 API 호출 경로와 Video LLM 실제 모델 경로는 여전히 검증되지 않았습니다.

### 7.2 서비스화 진행률 수치화 갱신

| # | 기준 | 07-06(오전) | 07-06(저녁) | 변경 근거 |
|---|---|---:|---:|---|
| 1 | 빌드/CI 정상 동작 | 93% | 93% | 이번 변경과 직접 관련 없음. CI 자체 추가 변경은 없음 |
| 2 | 4개 실행/배포 단위 분리 | 95% | **97%** | analysis-engine Docker가 aarch64 환경에서도 실제 빌드되고, 전체 compose 스택 기동까지 확인됨. 근거: `analysis-engine/Dockerfile:1`, `requirements.txt:27-37`, 실제 `docker compose up --build -d` 성공 |
| 3 | local/dev/prod 분리 + 환경변수 | 95% | 95% | 이번 변경과 직접 관련 없음. 다만 엔진 호스트 포트 env var화는 로컬 운용성 개선으로 기록 |
| 4 | 운영 DB + 마이그레이션 | 88% | 88% | 이번 변경과 직접 관련 없음. 백업 복구 리허설은 여전히 미완 |
| 5 | 비동기 job 구조 | 80% | **84%** | 업로드→run→status/progress polling→COMPLETED까지 실제 비동기 job 흐름이 처음으로 compose 환경에서 검증됨. 구조는 여전히 프로세스 내부 스레드풀 기반이라 분산 큐 감점은 유지 |
| 6 | 프론트-백 API 계약 일치 | 90% | 90% | 이번 변경과 직접 관련 없음. 정적 계약 테스트 외 변경 없음 |
| 7 | 인증/권한/소유권/파일 보호 | 90% | **92%** | 두 번째 사용자로 첫 번째 사용자의 result/status/delete 접근 시 모두 `403 ANALYSIS_JOB_ACCESS_DENIED`가 실제 HTTP로 확인됨 |
| 8 | Video LLM mock 대체 | 20% | 20% | 이번 E2E에서도 `generationMode=MOCK`으로 확인됨. 실제 모델 연동은 여전히 미완 |
| 9 | OpenAI 정책 | 92% | 92% | 이번 E2E는 `useOpenAi=false`라 실제 OpenAI 경로 검증과 직접 관련 없음 |
| 10 | 테스트/로그/모니터링/백업/정리 | 90% | **94%** | 실제 E2E 완료, backend 구조화 JSON 로그의 `jobId`/`requestId` 확인, `/actuator/prometheus`의 `analysis_job_completed_total=1.0` 확인으로 정적 관측성 구성이 실제 런타임에서 동작함을 검증 |

**종합: 약 85% (07-06 오전 약 83% → +2%p, 10개 기준 단순 평균)**

해석: 이번 상승분은 "기능 추가"보다 "실제 실행 검증"에서 나왔습니다. 2026-07-05/06 오전까지 가장 큰 확인 한계였던 compose 기반 E2E가 처음으로 완료되면서, 기준 2/5/7/10의 신뢰도가 올라갔습니다. 반면 Video LLM 실제 모델, OpenAI 실제 API, 백업 복구 리허설, 분산 큐/공유 스토리지는 여전히 남아 있어 점수를 크게 부풀리지는 않았습니다.

### 7.3 권장 처리 순서 갱신 (2026-07-06 저녁)

1. **백업 복구 리허설**: 백업 생성/무결성 검사는 있지만, 실제 MySQL 복원 절차와 실패 시 운영 대응은 아직 검증되지 않았습니다.
2. **Video LLM 실제 모델 연동**: 이번 E2E에서도 `generationMode=MOCK`으로 확인됐습니다. 실제 모델/외부 벤더 호출은 여전히 최대 기능 갭입니다.
3. **Alertmanager SMTP 실값 설정 + 테스트 알림 발송 확인**: Alertmanager 연동과 규칙은 있으나 실제 SMTP credential/수신 테스트는 별도입니다.
4. **S1 JWT localStorage → HttpOnly 쿠키 전환 검토**: 만료 시간 단축과 로그아웃 블랙리스트는 완화책이며, 토큰 저장 위치 자체는 아직 localStorage입니다.
5. **분산 큐/공유 스토리지**: 단일 호스트/단일 backend 전제에서는 유지 가능하지만, 다중 인스턴스 확장 시 job 분배와 파일 공유 구조가 여전히 병목입니다.
6. **프론트 폴더/컴포넌트 구조 재편**: 이번 Unit은 HomePage 콘텐츠와 StatusPage 분리까지만 수행했습니다. pages/components 구조 정리와 공통 섹션 컴포넌트 추출은 별도 과제로 남아 있습니다.

완료 처리: 6.3의 1번이었던 **E2E 실행 검증**은 2026-07-06 저녁 실제 compose 기반 실행으로 해결됐습니다.
