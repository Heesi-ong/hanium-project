# Project Structure

작성일: 2026-07-05 (2026-08-13 갱신: 학생 프로젝트 로컬/테스트 범위 반영)
기준 커밋/브랜치: main (296207d)

## 1. 전체 개요

- 이 프로젝트는 학생 프로젝트의 로컬/통제된 테스트 시연용 발표 분석 시스템이다.
- 현재 핵심 서비스는 `backend/`, `frontend/`, `analysis-engine/`, `video-llm-engine/` 네 개다.
- 공개 온라인 배포, GHCR 릴리스, production 호스트, 상시 운영 관제와 원격 백업은 현재 범위가 아니다.
- 과거 `Back/`, `Front/` 기준 문서나 기억은 현재 구조와 다를 수 있으므로 사용하지 않는다.

## 2. 루트 디렉터리

- `backend/`: Spring Boot API 서버. 인증, 업로드, 분석 job, 결과 저장/조회, OpenAI 피드백 orchestration 담당.
- `frontend/`: Vite/React 클라이언트.
- `analysis-engine/`: FastAPI 기반 정량 분석 엔진. 음성/자세/표정 등 기본 분석 담당.
- `video-llm-engine/`: FastAPI 기반 Video LLM 경계 서비스. DISABLED mock, DEGRADED fallback,
  STRICT 실패 및 NVIDIA hosted API 실제 호출을 정책으로 분리한다.
- `docs/`: 분석 기준, API, 아키텍처, DB, LLM, 발표 및 이전 운영 설계 문서.
- `infra/`: Docker/MySQL 로컬 실행 보조 설정과 nginx/TLS/모니터링 학습 자산. production 관련 자산은 현재 실행 기준이 아니다.
- `scripts/`: 로컬 실행·검증 스크립트와 선택적 백업 학습 스크립트.
- `storage/`: 업로드, 결과, 로그, temp, 백업, 모델 등 로컬 런타임 데이터.
- `Project/`: 기획/발표/회의 자료. 애플리케이션 런타임 코드는 아님.
- 현재 실행 기준은 `docker-compose.yml`, `.env.example`, `.github/workflows/verify.yml`이다. `docker-compose.prod.yml`, `docker-compose.release.yml`, `docker-compose.monitoring.yml`은 학습/선택 자료로 분리한다. 이전 GHCR workflow는 `docs/archive/operations/release.workflow.yml`로 이동해 실행 경로에서 제거했다.

## 3. Backend: `backend/`

### 진입점

- `backend/src/main/java/com/hanium/presentation/BackendApplication.java`
- 설정: `backend/src/main/resources/application.yaml`
- 프로필 설정:
  - `application-local.yml`
  - `application-dev.yml`
  - `application-prod.yml`
- DB 마이그레이션: `backend/src/main/resources/db/migration/`
- 로그 설정: `backend/src/main/resources/logback-spring.xml`
- LLM 프롬프트: `backend/src/main/resources/prompts/`

### 주요 책임

- JWT 인증/인가
- 영상 업로드와 파일 저장
- 분석 job 생성/실행/재시도/취소와 보존형 Video LLM 재분석 child 접수
- analysis-engine, video-llm-engine 호출
- OpenAI 피드백 생성/재사용
- 최종 결과 병합/조회/삭제
- rate limit, watchdog, storage cleanup
- Actuator/Prometheus 메트릭 노출과 구조화(JSON) 로깅
- 인증 API rate limiting(회원가입/로그인 이메일·IP 기준)과 JWT secret 운영 안전장치
- Video LLM 사용자 일간 1회와 전역 월간 가중 permit을 Redis Lua 한 번으로 함께 예약해
  어느 한도라도 부족할 때 두 카운터 모두 소비하지 않는 비용 예산 경계

### 분석 명령 경계

- `application/analysis/AnalysisCommandService.java`: 업로드, 접수, 비동기 파이프라인 orchestration
- `application/analysis/AnalysisJobValidator.java`: 소유권과 실행/재시도 가능 상태 검증
- `application/analysis/AnalysisDispatchAdmissionPolicy.java`: 전역·사용자별 DB 대기열과 로컬 executor 접수 한도
- `application/analysis/AnalysisRetryPolicy.java`: 재시도 요청에서 미지정된 Video LLM/OpenAI 옵션 보존
- `application/analysis/AnalysisPipelineTerminationHandler.java`: 실행 중 timeout 우선 판정과 취소·종료 후처리
- `application/analysis/AnalysisPipelineOutcomeHandler.java`: 파이프라인 최종 완료·실패 상태, 진행률, 결과와 메트릭 후처리
- `application/analysis/AnalysisPipelineStageReporter.java`: 단계별 DB 상태와 Redis 진행률 전이 순서·메시지
- `application/analysis/AnalysisBasicStage.java`: 원본 영상 다운로드 URL 해석과 정량 분석 엔진 요청
- `application/analysis/AnalysisResultPersistenceStage.java`: compact/final 및 실패·취소 종료 결과 저장
- `application/analysis/AnalysisOpenAiFeedbackStage.java`: 기존 REAL 피드백 재사용, 신규 호출, 명시적 생략과 저장
- `application/analysis/AnalysisVideoLlmStage.java`: 활성화·원자 예산 예약·영상 길이 정책 판정과 Video LLM 호출, 재분석 REAL 강제

접수 정책, 재시도 옵션 정책, timeout/cancel 종료 경계, 기본 분석, OpenAI 피드백과 Video LLM
실행 경계, 결과 저장, 단계별 상태·진행률 전이, 최종 완료·실패 후처리는 서비스
orchestration과 분리해 독립 단위 테스트로 경계값과 판정·후처리 순서를 고정한다.
`AnalysisCommandService`에는 단계 실행 순서와 timeout/cancel 체크포인트 orchestration이 남아 있다.

### 주요 패키지 (`com.hanium.presentation` 하위)

- `presentation/`: controller, request/response DTO
- `application/`: use case/service
- `domain/`: entity, repository, enum
- `infrastructure/`: 외부 클라이언트, 파일 저장, 구현체
- `global/`: config(Security/Async/RateLimit 등 설정), exception, filter(요청 단위 rate limit 서블릿 필터), logging(요청 상관관계 ID/MDC), properties(`@ConfigurationProperties` 바인딩 클래스), response(공통 API 응답 포맷)
- `common/`: 공통 유틸/공용 코드

## 4. Frontend: `frontend/`

### 진입점

- `frontend/src/main.jsx`
- `frontend/src/App.jsx`

### 확인해야 할 연결

- 라우터/페이지 구조: `frontend/src/routes/AppRoutes.jsx`, `ProtectedRoute.jsx`, `frontend/src/pages/` (Home, Login, Signup, Upload, ResultList, ResultDetail)
- API client 위치: `frontend/src/api/` (`apiClient.js`, `authApi.js`, `analysisApi.js`, `adminApi.js`, `coachApi.js`, `onboardingApi.js`, `errorUtils.js`)
- 인증 토큰 저장/전송 방식: `frontend/src/context/` + `apiClient.js`의 헤더 주입 확인
- 결과 상세/목록/업로드/로그인 화면 연결: `pages/` ↔ `routes/AppRoutes.jsx`

### 빌드/실행

- `frontend/package.json`
- `frontend/vite.config.js`
- 로컬 컨테이너 빌드: `frontend/Dockerfile`, `frontend/nginx.conf`

## 5. Analysis Engine: `analysis-engine/`

### 진입점

- `analysis-engine/app/main.py`
- 내부 구조: `app/api/`(`basic_analysis.py` — 분석 orchestration과 실패 응답 조립.
  `readiness.py`), `app/services/`(`media_io.py` — 영상 경로 검증·다운로드,
  프레임/오디오 추출, MediaPipe 이미지 준비, 임시 파일 정리. `speech_to_text.py` —
  Whisper 모델 풀 호출, 세그먼트 변환, timeout과 STT 성공·실패 응답. `scoring.py` —
  최종 가중합, 신뢰도 penalty, 최종 점수 clamp와 공통 평균 계산. `pose_analysis.py` —
  Pose Landmarker 호출, landmark 변환, 자세·어깨 균형·제스처 정량 분석.
  `face_analysis.py` — Face Landmarker 호출, 시선·눈맞춤·표정 상태와 점수 분석.
  `audio_analysis.py` — 말하기 속도·침묵·필러·WAV 음량 안정성과 음성 점수 분석),
  `app/core/`(logging_config.py, model_registry.py, security.py). `schemas/utils` 같은
  추가 하위 패키지는 아직 존재하지 않는다.

### 책임

- 업로드된 영상 경로를 받아 기본 발표 분석 수행
- backend가 호출하는 내부 API 제공
- `X-Internal-Api-Key` 기반 내부 인증 적용 (`app/core/security.py`)

### 테스트

- `analysis-engine/tests/` (`test_basic_analysis_scoring.py`, `test_media_io.py`,
  `test_pose_analysis.py`, `test_face_analysis.py`, `test_audio_analysis.py`,
  `test_speech_duration_fallback.py`, `test_whisper_transcribe_timeout.py`, `test_security.py`),
  설정은 `pytest.ini`

## 6. Video LLM Engine: `video-llm-engine/`

### 진입점

- `video-llm-engine/app/main.py`
- 내부 구조: `app/api/`(`video_llm_analysis.py` — prompt·분할 호출·API orchestration,
  `readiness.py`), `app/services/`(`media_io.py` — 허용 경로 검증, presigned URL 다운로드,
  스트리밍 크기 제한과 임시 파일 수명주기. `deadline.py` — 전체 요청 잔여 시간 계산.
  `nvidia_provider.py` — inline/asset 입력, asset 업로드, chat, polling, cleanup HTTP client.
  `nvidia_response.py` — chat content 추출, 모델 JSON 파싱, 관찰 구간·요약 검증과 공개 응답 정규화),
  `app/core/`(logging_config.py, security.py, settings.py). `model/prompts/schemas/utils` 같은
  추가 하위 패키지는 아직 존재하지 않는다.

### 현재 상태

- backend가 기대하는 Video LLM 응답 스키마를 제공한다.
- `VIDEO_LLM_POLICY` 기본값은 `DISABLED`다. `STRICT` 또는 `DEGRADED`와
  `NVIDIA_API_KEY`를 설정하면 NVIDIA hosted API
  (`nvidia/nemotron-3-nano-omni-30b-a3b-reasoning`)를 실제로 호출한다.
  `STRICT`는 실패 시 502로 backend 작업을 실패시키고, `DEGRADED`만 명시적
  `FALLBACK` 샘플 결과를 허용한다. `VIDEO_LLM_ENABLED`는 정책 값이 비었을 때의
  하위 호환 스위치다.
- backend 재분석 요청은 `requireReal=true`를 보내므로 DEGRADED에서도 FALLBACK을 허용하지
  않고, 엔진과 backend가 모두 `generationMode=REAL`인지 검증한다.
- 결과 목록·상세 응답은 `analysisKind`, `sourceJobId`, 저장된
  `videoLlmGenerationMode`를 노출한다. STANDARD 상세는 최신 재분석 job을 함께 알려주며,
  결과 파일이 아직 없는 child도 상태 shell로 조회되어 프론트 polling이 끊기지 않는다.
- 프론트 결과 상세는 STANDARD+COMPLETED+FALLBACK에서만 비용 재소비 확인 후 재분석을
  접수하고 child 상세로 이동한다. 원본과 재분석 상세 사이의 lineage 링크를 제공한다.
- 동시 호출 수 제한(세마포어), 긴 영상 구간 분할(ffmpeg chunking) 후 병합, 구간 길이에 따른
  프롬프트 분기(30초 미만은 3구간 강제 분할 프롬프트 미적용)까지 구현되어 있다. 다만 실제
  NVIDIA 응답 품질은 API 키 없이는 검증하지 못했다.
- 활성화 전 결정 체크리스트/실측 결과는 `docs/service-plan/video-llm-model-options.md` 참고.
- 테스트: `video-llm-engine/tests/` (`test_video_llm_analysis.py`, `test_security.py` 등)

## 7. 런타임 데이터와 주의사항

- `storage/uploads`: 업로드 파일
- `storage/results`: 분석 결과 JSON
- `storage/temp`: 임시 파일
- `storage/logs`: 로그
- `storage/backups`: DB/데이터 백업 (`scripts/backup-mysql.sh` 참고)
- `storage/models`: 모델 파일
- Docker Compose는 `STORAGE_HOST_PATH`(기본 `./storage`)를 위 경로들의 호스트 루트로 사용한다.
  별도 프로젝트명과 빈 DB로 E2E를 실행할 때는 반드시 전용 빈 디렉터리를 지정한다. 기존
  스토리지를 새 DB와 연결하면 고아 정리 스케줄러가 기존 업로드·결과를 삭제할 수 있다.
- `.runtime/`, `.venv/`, `node_modules/`, `dist/`, `build/`는 소스 구조 설명 대상이 아니다.

## 8. 문서 갱신 원칙

- 구조가 바뀌면 이 문서를 먼저 갱신한다.
- stale 가능성이 높은 서비스화/운영 판단은 현재 로컬/테스트 범위와 실제 코드를 함께 확인한다.
- 코드 위치를 적을 때는 파일명만 쓰지 말고 실제 진입점과 호출 흐름을 같이 적는다.
