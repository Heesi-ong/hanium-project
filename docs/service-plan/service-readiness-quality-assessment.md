# 서비스화 최종 품질 수준 평가

작성일: 2026-07-02
평가 기준: 실제 코드 정독 결과(정적 분석)에 근거한 평가입니다. 부하 테스트, 침투 테스트, 실제 운영 트래픽으로 검증한 결과가 아니라는 점을 먼저 밝힙니다. "이렇게 하면 될 것이다"가 아니라, 현재 확인된 사실과 남은 계획을 기준으로 한 예상 수준입니다.

이 문서는 두 가지를 구분해서 평가합니다.

1. **지금 이 순간 코드 상태**의 품질 수준
2. **1차 갭 분석에서 제안한 Phase 0~6 + 이번 추가 조사 항목까지 전부 완료했다고 가정했을 때** 도달 가능한 품질 수준

---

## 1. 지금 이 순간의 품질 수준

**한 줄 요약: 로컬 시연(데모)용 MVP. 실사용자에게 그대로 열면 안 되는 상태.**

| 평가 축 | 등급 | 근거 |
|---|---|---|
| 기능 완성도 | 중 | 업로드→분석→결과 조회 흐름은 실제로 동작함. 삭제 API는 프론트-백엔드 불일치로 깨져 있음. |
| 보안 | 하 | 로그인/권한 개념 자체가 없음. jobId만 알면 누구나 남의 결과 조회 가능. 분석 엔진에 인증 없이 임의 파일 경로 접근 가능. |
| 안정성/신뢰성 | 하 | 긴 영상 처리 시 타임아웃 없이 요청 스레드가 그대로 블로킹됨. AI 모델을 매 요청 새로 로딩해 리소스 낭비. |
| 확장성 | 하 | 서버 1대만 가능한 구조(로컬 파일 저장 + 인메모리 DB). 동시 처리량 제한 없음. |
| 운영관리(로그/모니터링/백업) | 하 | 구조화된 로그, 모니터링, 백업, 임시파일 정리 정책 모두 없음. CI는 실행조차 안 됨. |
| 코드 품질/유지보수성 | 중 | 백엔드는 계층 구조가 잘 잡혀 있음. analysis-engine은 2000줄 파일 하나에 로직이 몰려 있어 유지보수 어려움. |
| 비용 관리(OpenAI 등) | 중하 | MOCK/REAL/FALLBACK 전환 로직은 있으나 사용량 기록, 중복 호출 방지가 없어 비용 폭주 위험. |

이 상태로는 **한이음 발표/시연 목적에는 충분**하지만, 불특정 다수에게 서비스 링크를 공유하는 순간 보안·안정성 문제가 실제 사고로 이어질 수 있는 수준입니다.

---

## 2. 제안된 계획을 모두 완료했을 때 도달 가능한 수준

Phase 0~6(1차 보고서) + 추가 조사 A/B 항목(2차 보고서)을 전부 반영했다고 가정하면, 다음과 같은 수준까지는 현실적으로 도달 가능하다고 판단합니다.

**도달 가능 수준: "제한된 규모(동시 사용자 수십~1~2백 명 수준)의 클로즈드 베타 서비스"**

근거:

- 인증/권한/소유권 검증이 들어가면 "남의 결과를 본다"는 심각한 문제는 해결됩니다.
- 비동기 큐 구조 + 모델 프리로딩 + 타임아웃 설정이 들어가면 긴 영상 처리로 서버가 멈추는 문제는 크게 줄어듭니다.
- 실제 DB(MySQL) + 마이그레이션 + Docker 배포 구성이 들어가면 "로컬에서만 돌아간다"는 한계는 벗어납니다.
- OpenAI 사용량 기록과 중복 호출 방지가 들어가면 비용이 통제 범위 안에 들어옵니다.

**다만 여기까지 완료해도 아래 수준에는 미치지 못합니다** (별도의 큰 작업이 더 필요합니다):

- **대규모 트래픽 대응(수천 명 동시 사용)**: 서버를 여러 대로 늘리려면 파일 저장을 공유 스토리지로 옮기고, 영상 처리 자체를 별도 워커 클러스터로 분리해야 합니다. 이는 Phase 계획에 아직 없는 별도 과제입니다.
- **엔터프라이즈급 보안/컴플라이언스**: 침투 테스트, 정기 취약점 점검, 개인정보 처리방침 법적 검토, 접근 로그 감사 체계 등은 이번 계획 범위 밖입니다.
- **완전 자동화된 운영(SRE 수준)**: 장애 자동 감지·알림, 무중단 배포, 롤백 자동화 등은 포함되어 있지 않습니다.
- **모델 품질 보증**: video-llm-engine을 실제 외부 API로 바꾸는 것까지는 계획에 있지만, 그 결과의 정확도를 정량적으로 검증하는 절차(예: 사람이 채점한 결과와 비교)는 별도로 필요합니다.

---

## 3. 결론

- 지금 상태: **데모/시연 수준.**
- 계획을 다 실행한 뒤: **소규모 실사용자에게 열어도 되는 베타 서비스 수준.**
- 그 이상(대규모 트래픽, 엔터프라이즈 보안, 완전 자동 운영)은 **이번 계획 범위 밖의 별도 프로젝트**로 봐야 합니다.

이 평가는 코드를 읽고 판단한 예상치이며, 각 Phase를 실제로 구현하고 테스트를 돌려본 뒤에 다시 검증이 필요합니다. 특히 비동기 처리 전환(Phase 2)과 인증 도입(Phase 3)은 실제로 구현한 뒤 부하 테스트/시나리오 테스트로 재확인할 것을 권장합니다.

---

## 4. 업데이트: 2026-07-03 현재 상태

2026-07-02 평가 이후 여러 서비스화 Unit이 반영되어, 당시의 "데모/시연 수준" 평가는 더 이상 현재 코드 상태를 정확히 설명하지 않습니다. 아래 표는 2026-07-03 기준 실제 코드 재확인 결과입니다.

| 평가 영역 | 2026-07-03 현재 등급 | 근거 및 남은 한계 |
| --- | --- | --- |
| 기능 완성도 | 중상 | 업로드, 분석 실행, 결과 조회 흐름에 로그인/소유권/재시도/취소/진행률/워치도그가 붙었습니다(`AuthController.java:38-83`, `AnalysisCommandService.java:155-174`, `StuckAnalysisJobWatchdogService.java:52-83`). 다만 `video-llm-engine/app/api/video_llm_analysis.py:25-35`는 여전히 `mock-video-llm` 응답이라 실제 Video LLM 품질 검증은 미완료입니다. |
| 보안 | 중상 | `/api/**` 인증과 JWT가 적용됐고(`SecurityConfig.java:62-71`, `SecurityConfig.java:106-116`), 결과/분석 job 소유권 검증이 들어갔습니다(`ResultQueryService.java:120-126`, `AnalysisController.java:72-150`). 내부 엔진도 공유 키 인증을 사용합니다(`analysis-engine/app/core/security.py:9-29`, `video-llm-engine/app/core/security.py:9-29`). 업로드 매직바이트 검증도 추가됐습니다(`VideoSignatureValidator.java:7-17`). 단, 운영 JWT secret 기본값 제거 강제, 네트워크 레벨 포트 차단, 악성 파일 심층 검사, 침투 테스트는 남아 있습니다. |
| 안정성/신뢰성 | 중 | 외부 HTTP 호출 타임아웃이 생겼고(`RestClientConfig.java:17-21`), 비동기 실행은 내부 스레드풀로 제한됩니다(`AsyncConfig.java:16-29`). 재시도 횟수 제한, 취소, 낙관적 락, 멈춘 작업 워치도그도 있습니다(`AnalysisJob.java:16-20`, `AnalysisJob.java:35-39`, `AnalysisJob.java:143-188`, `StuckAnalysisJobWatchdogService.java:24-83`). 단, 작업 큐는 분산 큐가 아니라 backend 프로세스 내부 스레드풀이라 서버 재시작/다중 인스턴스 상황의 완전한 작업 재배치는 보장하지 않습니다. |
| 확장성 | 중하 | DB는 dev/prod에서 MySQL + Flyway로 전환됐습니다(`application-dev.yml:1-17`, `application-prod.yml:1-20`, `db/migration/V1__init_schema.sql:5-29`). 그러나 업로드/결과/temp 파일은 여전히 로컬 디스크 경로입니다(`application.yaml:59-68`, `FilePathGenerator.java:17-40`, `VideoFileCommandService.java:40-45`). S3/MinIO/NFS 같은 공유 스토리지와 분산 큐가 없어 수평 확장은 아직 제한적입니다. |
| 운영관리(로그/모니터링/백업) | 중 | backend 구조화 rolling file 로그가 추가됐고(`logback-spring.xml:3-28`), `/actuator/health`만 공개됩니다(`application.yaml:26-33`, `SecurityConfig.java:64-67`). 스토리지 정리 스케줄러와 MySQL 백업 스크립트도 있습니다(`StorageCleanupService.java:41-77`, `scripts/backup-mysql.sh:30-86`). 다만 백업 스크립트는 실제 MySQL 대상 검증이 아니라 fake `mysqldump` 대체 검증만 수행된 상태이며, metrics/alerting/Prometheus/원격 백업/복구 리허설은 없습니다. |
| 코드 품질/유지보수성 | 중상 | backend는 계층 분리, DTO, 테스트, Flyway가 보강됐고, 결과 목록 페이지네이션/N+1도 해결됐습니다(`ResultController.java:44-58`, `ResultQueryService.java:56-85`). pytest가 두 Python 엔진에 들어갔고 CI에서 실행됩니다(`.github/workflows/verify.yml:42-67`). 다만 `analysis-engine/app/api/basic_analysis.py`는 여전히 모델 생성과 채점/분석 로직이 큰 파일에 몰려 있고, 모델 생성도 요청 경로에 남아 있습니다(`basic_analysis.py:531-533`, `basic_analysis.py:621-635`, `basic_analysis.py:965-981`). |
| 비용 관리(OpenAI 등) | 중상 | OpenAI 토큰 사용량 로그가 생겼고(`OpenAiClient.java:107-130`), 재시도 시 기존 `REAL` 응답을 재사용합니다(`ResultCommandService.java:100-126`, `AnalysisCommandService.java:322-333`). 분석 재시도 횟수도 설정화됐습니다(`application.yaml:52-57`). 단, 사용자별 월간 비용 한도, 예산 알림, 관리자 대시보드는 없습니다. |

### 4.1 A/B 항목별 최신 판정 요약

| 항목 | 최신 판정 | 코드 근거 |
| --- | --- | --- |
| A1. 분석 엔진 직접 호출 인증 없음 | 해결 | `analysis-engine/app/core/security.py:9-29`, `video-llm-engine/app/core/security.py:9-29`, `AnalysisEngineClient.java:53-58`, `VideoLlmEngineClient.java:53-58` |
| A2. 업로드 파일 내용 검증 없음 | 부분해결 | `VideoFileCommandService.java:72-82`, `VideoFileCommandService.java:96-104`, `VideoSignatureValidator.java:7-17` |
| A3. 서비스 간 HTTP 타임아웃 없음 | 해결 | `RestClientConfig.java:17-21`, `RestClientConfig.java:31-39` |
| A4. 입력 검증 미흡 | 부분해결 | `AuthController.java:38-40`, `AuthController.java:90-98`, `AnalysisController.java:19-27`, `ResultController.java:61-80` |
| A5. Rate limiting 없음 | 해결 | `UserRateLimitFilter.java:22-27`, `UserRateLimitFilter.java:64-79`, `UserRateLimiter.java:32-49`, `application.yaml:74-80` |
| A6. CORS 하드코딩 | 해결 | `application.yaml:70-72`, `CorsConfig.java:11-14`, `CorsConfig.java:30-40` |
| A7. 비밀값 커밋 위험 | 부분해결 | `application.yaml:35-50`, `application-dev.yml:4-8`, `application-prod.yml:1-10`, 단 `SecurityConfig.java:80-85`에 로컬 JWT secret 기본값 존재 |
| B1. 모델 매 요청 재로딩 | 미해결 | `basic_analysis.py:531-533`, `basic_analysis.py:621-635`, `basic_analysis.py:965-981` |
| B2. 동시 처리량 제한 없음 | 부분해결 | `AsyncConfig.java:16-29`, `AnalysisCommandService.java:196-210`; 단 분산 큐 아님 |
| B3. 호출 타임아웃 없음 | 해결 | `RestClientConfig.java:17-21`, `RestClientConfig.java:31-39` |
| B4. 로컬 파일 저장 + 인메모리 DB | 부분해결 | MySQL/Flyway: `application-dev.yml:1-17`, `application-prod.yml:1-20`, `db/migration/V1__init_schema.sql:5-29`; 로컬 파일 저장 유지: `application.yaml:59-68`, `FilePathGenerator.java:17-40` |
| B5. 페이지네이션 없음/N+1 | 해결 | `ResultController.java:44-58`, `ResultController.java:103-110`, `ResultQueryService.java:56-85` |
| B6. 동시 재실행 락 없음 | 해결 | `AnalysisJob.java:16-20`, `AnalysisCommandService.java:183-194` |
| B7. ErrorBoundary 없음 | 해결 | `frontend/src/components/common/ErrorBoundary.jsx:8-45`, `frontend/src/App.jsx:5-10` |

### 4.2 갱신된 결론

현재 상태는 **단순 시연용 데모를 넘어, 제한된 인원의 클로즈드 베타를 조심스럽게 운영해볼 수 있는 수준**에 가까워졌습니다. 특히 인증/소유권, 내부 엔진 API 키, rate limiting, 타임아웃, MySQL/Flyway, 구조화 로깅, Actuator health, 재시도/취소/워치도그, OpenAI 비용 로그/재사용, 페이지네이션, pytest/CI가 들어간 점은 2026-07-02 스냅샷 대비 큰 진전입니다.

하지만 아직 **정식 공개 서비스 또는 수평 확장 가능한 운영 서비스 수준은 아닙니다.** 가장 큰 이유는 다음과 같습니다.

1. `video-llm-engine`은 아직 mock 응답입니다. 실제 Video LLM 연동과 품질 검증이 남아 있으며, 현재 최대 잔여 과제입니다.
2. 비동기 처리는 분산 큐가 아니라 backend 내부 `ThreadPoolTaskExecutor`입니다. 여러 backend 인스턴스가 job을 나눠 갖거나 장애 시 다른 인스턴스가 이어받는 구조가 아닙니다.
3. 업로드/결과 파일은 로컬 디스크에 저장됩니다. DB는 MySQL로 이동했지만 파일 저장은 여전히 수평 확장의 병목입니다.
4. `StorageCleanupService`, `StuckAnalysisJobWatchdogService` 같은 `@Scheduled` 작업에는 분산 락이 없습니다. 다중 인스턴스 배포 시 같은 정리/워치도그 작업이 중복 실행될 수 있습니다.
5. MySQL 백업 스크립트는 존재하지만 실제 MySQL 인스턴스 대상 백업/복구 리허설은 아직 완료되지 않았습니다. 현재 검증 한계는 fake `mysqldump` 기반입니다.
6. analysis-engine은 모델을 요청 경로에서 생성하고 있어 성능/메모리 사용량 리스크가 큽니다.

따라서 다음 단계 우선순위는 **실제 Video LLM 구현**, **analysis-engine 모델 프리로딩/싱글톤화**, **공유 파일 스토리지 도입**, **분산 큐/분산 락 검토**, **실제 백업·복구 리허설과 모니터링/알림 체계 도입**으로 보는 것이 타당합니다.

---

## 5. 업데이트: 2026-07-04 현재 상태

2026-07-03 업데이트 이후 인증 운영, 데이터 삭제, 배포 보안, graceful shutdown, 컨테이너 리소스 제한, 보존 정책, CI 보안감사, API 계약 검증이 추가됐습니다. 아래 표는 새로 해결된 S/O/Q 항목까지 반영한 최신 평가입니다.

### 5.1 갱신된 평가 표

| 평가 영역 | 2026-07-04 현재 등급 | 근거 및 남은 한계 |
| --- | --- | --- |
| 기능 완성도 | 중상 | 회원가입/로그인에 더해 로그아웃과 회원탈퇴 API가 생겼습니다(`AuthController.java:110-120`, `UserController.java:23-33`). 분석 취소/재시도/워치도그/원본 영상 보존 정책도 존재합니다(`OriginalVideoRetentionService.java:52-63`). 단 `video-llm-engine/app/api/video_llm_analysis.py:25-79`는 여전히 mock 응답입니다. |
| 보안 | 중상 | JWT 블랙리스트 로그아웃(`JwtBlacklist.java:27-52`), 로그인 rate limit(`AuthController.java:89-95`), 회원탈퇴(`UserWithdrawalService.java:40-57`), 내부 포트 loopback 제한과 Redis 비밀번호(`docker-compose.yml:30-50`, `docker-compose.yml:114-116`), nginx TLS 스캐폴딩(`infra/nginx/nginx.conf:14-31`)이 추가됐습니다. 단 JWT localStorage 저장(`AuthContext.jsx:8-39`)과 실제 TLS 발급 검증은 남아 있습니다. |
| 안정성/신뢰성 | 중상 | `server.shutdown: graceful`과 executor shutdown wait가 추가됐고(`application.yaml:1-13`, `AsyncConfig.java:21-30`), backend Compose 종료 유예도 설정됐습니다(`docker-compose.yml:87-92`). 멈춘 작업 워치도그와 스케줄러 분산 락도 있습니다(`StuckAnalysisJobWatchdogService.java:60-87`, `SchedulerDistributedLock.java:25-36`). 단 분산 큐가 아니라 내부 스레드풀 구조입니다(`AnalysisCommandService.java:196-210`). |
| 확장성 | 중 | 컨테이너별 CPU/메모리 제한이 추가됐습니다(`docker-compose.yml:20-24`, `docker-compose.yml:56-60`, `docker-compose.yml:73-78`, `docker-compose.yml:93-97`). analysis-engine 모델 프리로딩도 적용됐습니다(`analysis-engine/app/main.py:16-27`, `model_registry.py:43-47`). 그러나 파일은 여전히 로컬 경로 기반이고(`FilePathGenerator.java:17-39`), 공유 스토리지/분산 큐가 없습니다. |
| 운영관리(로그/모니터링/백업) | 중상 | 원본 영상 보존 기간 정리(`OriginalVideoRetentionService.java:85-99`), MySQL 백업 스크립트(`scripts/backup-mysql.sh:60-86`), Actuator health, rolling log, 리소스 제한이 있습니다. 단 백업 복구 리허설, metrics/alerting, 원격 백업은 아직 없습니다. |
| 코드 품질/유지보수성 | 중상 | OpenAPI와 API 계약 테스트가 추가됐습니다(`build.gradle:21-31`, `ApiContractTest.java:47-73`). CI는 npm audit, pip-audit, Docker build matrix, dependabot을 포함합니다(`.github/workflows/verify.yml:38-89`, `.github/dependabot.yml:1-26`). 단 Python audit는 현재 취약점 때문에 실패 허용이고, O3 전체 테스트 통과는 아직 확인되지 않았습니다. |
| 비용 관리(OpenAI 등) | 중상 | 기존 OpenAI 토큰 로그/REAL 응답 재사용/재시도 제한 정책은 유지됩니다. 추가로 로그인/분석 rate limit과 컨테이너 리소스 제한으로 폭주 비용/자원 사용을 일부 통제합니다(`UserRateLimiter.java:36-72`, `docker-compose.yml:56-78`). 단 사용자별 월간 예산/관리자 비용 대시보드는 없습니다. |

### 5.2 S/O/Q 항목 최신 반영

| 항목 | 최신 판정 | 코드 근거 |
| --- | --- | --- |
| S2. 로그아웃/토큰 무효화 | 해결 | `AuthController.java:110-120`, `JwtBlacklist.java:27-52`, `SecurityConfig.java:183-187` |
| S3. 회원탈퇴/데이터 일괄 삭제 | 해결 | `UserController.java:23-33`, `UserWithdrawalService.java:40-80` |
| S4. 로그인 rate limit | 해결 | `AuthController.java:89-95`, `UserRateLimiter.java:36-72`, `application.yaml:92-94` |
| S5. compose 포트 노출/Redis 비밀번호 | 해결 | `docker-compose.yml:30-50`, `docker-compose.yml:64-83`, `docker-compose.yml:114-116`, `application.yaml:20-24` |
| S6. nginx/TLS 스캐폴딩 | 부분해결 | `infra/nginx/nginx.conf:1-31`, `docker-compose.prod.yml:5-34`; 실제 인증서 발급/HTTPS 접속 검증은 남음 |
| O1. graceful shutdown | 해결 | `application.yaml:1-13`, `AsyncConfig.java:21-30`, `docker-compose.yml:87-92` |
| O2. 컨테이너 리소스 제한 | 해결(실기동 검증 미완) | `docker-compose.yml:20-24`, `docker-compose.yml:43-47`, `docker-compose.yml:56-60`, `docker-compose.yml:73-78`, `docker-compose.yml:93-97`, `docker-compose.yml:147-151` |
| O3. 업로드 용량/저장 공간 검증 | 구현됨(테스트 미검증) | `ErrorCode.java:13-14`, `GlobalExceptionHandler.java:63-70`, `VideoFileCommandService.java:91-106`, `VideoFileCommandServiceTest.java:70-92`; 영상 길이 제한은 미구현 |
| O4. 완료 job 원본 영상 보존 기간 | 해결 | `OriginalVideoRetentionService.java:52-63`, `OriginalVideoRetentionService.java:85-99`, `OriginalVideoRetentionServiceTest.java:69-99` |
| Q1. CI 보안/공급망 검사 | 해결(취약점 보고 단계 포함) | `.github/workflows/verify.yml:38-89`, `.github/dependabot.yml:1-26` |
| Q2. API 계약 자동 검증 | 해결 | `build.gradle:21-31`, `SecurityConfig.java:62-68`, `ApiContractTest.java:47-73` |

### 5.3 갱신된 결론

2026-07-04 현재 상태는 **제한된 인원의 클로즈드 베타에 필요한 운영 안전장치가 상당 부분 들어간 상태**입니다. 2026-07-03 대비 가장 큰 변화는 인증 운영(S2/S4), 개인정보 삭제(S3), 배포 보안(S5/S6), 종료 안정성(O1), 리소스 제한(O2), 보존 정책(O4), CI/계약 자동화(Q1/Q2)가 코드와 설정으로 반영됐다는 점입니다.

다만 **정식 공개 서비스 수준으로 보기에는 아직 핵심 잔여 리스크가 남아 있습니다.**

1. `video-llm-engine`은 아직 mock 응답입니다(`video_llm_analysis.py:25-79`). 서비스 품질을 좌우하는 가장 큰 미해결 과제입니다.
2. 토큰은 여전히 localStorage에 저장됩니다(`AuthContext.jsx:8-39`, `apiClient.js:12-36`). XSS 방어와 저장 전략 검토가 필요합니다.
3. 분석 작업은 분산 큐가 아니라 backend 내부 스레드풀 기반입니다(`AnalysisCommandService.java:196-210`, `AsyncConfig.java:21-35`). 다중 인스턴스에서 작업 재분배를 보장하지 않습니다.
4. 업로드/결과 파일은 로컬 디스크 기반입니다(`FilePathGenerator.java:17-39`). 공유 스토리지 없이는 수평 확장이 제한됩니다.
5. O3 저장 공간 검증은 구현됐지만 전체 테스트 통과가 아직 확인되지 않았고, 영상 재생 시간 제한은 남아 있습니다(`basic_analysis.py:272-308`).
6. MySQL 백업은 스크립트가 있지만 실제 복구 리허설, 원격 보관, 암호화, 알림은 아직 부족합니다(`scripts/backup-mysql.sh:60-86`).

따라서 다음 단계는 **Video LLM 실제화**, **JWT 저장 전략 개선**, **O3 테스트 검증과 영상 길이 제한**, **공유 스토리지/분산 큐 검토**, **백업 복구 리허설 및 metrics/alerting 도입** 순서가 타당합니다.
