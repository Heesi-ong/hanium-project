# Project Structure

작성일: 2026-07-05
기준 커밋/브랜치: main (5a7f7f9)

## 1. 전체 개요

- 이 프로젝트는 발표 분석 서비스다.
- 현재 핵심 서비스는 `backend/`, `frontend/`, `analysis-engine/`, `video-llm-engine/` 네 개다.
- 과거 `Back/`, `Front/` 기준 문서나 기억은 현재 구조와 다를 수 있으므로 사용하지 않는다.

## 2. 루트 디렉터리

- `backend/`: Spring Boot API 서버. 인증, 업로드, 분석 job, 결과 저장/조회, OpenAI 피드백 orchestration 담당.
- `frontend/`: Vite/React 클라이언트.
- `analysis-engine/`: FastAPI 기반 정량 분석 엔진. 음성/자세/표정 등 기본 분석 담당.
- `video-llm-engine/`: FastAPI 기반 Video LLM 경계 서비스. 현재는 mock 응답 중심.
- `docs/`: 설계/운영/서비스화 문서 (`analysis-criteria/`, `api/`, `architecture/`, `database/`, `llm/`, `presentation/`, `service-plan/`).
- `infra/`: Docker, nginx, MySQL, env, 모니터링(Prometheus/Alertmanager/Grafana) 등 배포 보조 설정 (`docker/`, `env/`, `mysql/`, `nginx/`, `prometheus/`, `alertmanager/`, `grafana/`). `infra/nginx/Dockerfile`은 운영 TLS 부트스트랩에 필요한 `openssl` 포함 nginx 이미지를 만든다.
- `scripts/`: 운영/백업 스크립트 (현재 `backup-mysql.sh`).
- `storage/`: 업로드, 결과, 로그, temp, 백업, 모델 등 런타임 데이터. 소스 구조가 아니라 운영 데이터로 취급.
- `Project/`: 기획/발표/회의 자료. 애플리케이션 런타임 코드는 아님.
- 루트의 `docker-compose.yml`(기본), `docker-compose.prod.yml`(운영 오버레이), `docker-compose.monitoring.yml`(모니터링 오버레이, opt-in), `.env.example`, `.github/workflows/verify.yml`(CI), `.github/dependabot.yml`(의존성 자동 업데이트)도 실행/배포 기준 파일이다.

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
- 분석 job 생성/실행/재시도/취소
- analysis-engine, video-llm-engine 호출
- OpenAI 피드백 생성/재사용
- 최종 결과 병합/조회/삭제
- rate limit, watchdog, storage cleanup
- Actuator/Prometheus 메트릭 노출과 구조화(JSON) 로깅
- 인증 API rate limiting(회원가입/로그인 이메일·IP 기준)과 JWT secret 운영 안전장치

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
- API client 위치: `frontend/src/api/` (`apiClient.js`, `authApi.js`, `analysisApi.js`, `errorUtils.js`)
- 인증 토큰 저장/전송 방식: `frontend/src/context/` + `apiClient.js`의 헤더 주입 확인
- 결과 상세/목록/업로드/로그인 화면 연결: `pages/` ↔ `routes/AppRoutes.jsx`

### 빌드/실행

- `frontend/package.json`
- `frontend/vite.config.js`
- 배포: `frontend/Dockerfile`, `frontend/nginx.conf`

## 5. Analysis Engine: `analysis-engine/`

### 진입점

- `analysis-engine/app/main.py`
- 내부 구조: `app/api/`(라우터), `app/services/`(분석 로직), `app/core/`(설정·보안), `app/schemas/`, `app/utils/`

### 책임

- 업로드된 영상 경로를 받아 기본 발표 분석 수행
- backend가 호출하는 내부 API 제공
- `X-Internal-Api-Key` 기반 내부 인증 적용 (`app/core/security.py`)

### 테스트

- `analysis-engine/tests/` (`test_basic_analysis_scoring.py`, `test_security.py`), 설정은 `pytest.ini`

## 6. Video LLM Engine: `video-llm-engine/`

### 진입점

- `video-llm-engine/app/main.py`
- 내부 구조: `app/api/`, `app/services/`, `app/model/`, `app/prompts/`, `app/core/`, `app/schemas/`, `app/utils/`

### 현재 상태

- backend가 기대하는 Video LLM 응답 스키마를 제공한다.
- 현재 실제 모델 분석이 아니라 mock 응답이다.
- 실제 모델 도입 후보는 `docs/service-plan/video-llm-model-options.md` 참고.
- 테스트: `video-llm-engine/tests/` (`test_video_llm_analysis.py`, `test_security.py`)

## 7. 런타임 데이터와 주의사항

- `storage/uploads`: 업로드 파일
- `storage/results`: 분석 결과 JSON
- `storage/temp`: 임시 파일
- `storage/logs`: 로그
- `storage/backups`: DB/데이터 백업 (`scripts/backup-mysql.sh` 참고)
- `storage/models`: 모델 파일
- `.runtime/`, `.venv/`, `node_modules/`, `dist/`, `build/`는 소스 구조 설명 대상이 아니다.

## 8. 문서 갱신 원칙

- 구조가 바뀌면 이 문서를 먼저 갱신한다.
- stale 가능성이 높은 서비스화 판단은 `docs/service-plan/` 문서와 실제 코드를 함께 확인한다.
- 코드 위치를 적을 때는 파일명만 쓰지 말고 실제 진입점과 호출 흐름을 같이 적는다.
