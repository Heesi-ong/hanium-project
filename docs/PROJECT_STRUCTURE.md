# 프로젝트 구조 문서

## 문서 범위와 확인 기준

- 프로젝트 루트: `/Users/ai/Desktop/hanium project`
- 확인일: 2026-06-14
- 분석 제외 대상: `node_modules`, `.git`, `.venv`, `venv`, `__pycache__`, `dist`, `build`, `.next`, `coverage`, 업로드·결과·프레임 데이터, 로그와 캐시, `.runtime` 실행 데이터
- 이 문서는 현재 파일에서 확인한 내용을 기준으로 작성했다.
- 실행 환경이나 외부 프로그램 상태가 필요한 내용은 코드와 문서에서 확인 가능한 범위만 설명한다.
- 현재 프로젝트는 **발표 영상 분석, 규칙 기반 발표 코칭, 선택형 Ollama AI 코칭, 발표 문맥 채팅**을 제공하는 서비스다.

---

## 1. 프로젝트 개요

이 프로젝트의 서비스명은 프론트엔드에서 **SpeakInsight**로 표시된다. 사용자가 발표 목적과 발표 영상을 업로드하면 백엔드가 영상을 비동기로 분석하고, 결과 화면에서 점수·측정값·피드백·타임라인·연습 계획을 제공한다. 사용자가 요청하면 로컬 Ollama 모델이 기존 분석 근거를 해석하여 AI 발표 코칭을 생성하고, 분석 결과를 문맥으로 연결한 채팅도 제공한다.

### 핵심 기능

1. 회원가입, 로그인, 세션, 계정 관리
2. 발표 목적·청중·핵심 메시지 입력
3. 발표 영상 업로드 및 비동기 분석 작업 생성
4. 자세, 얼굴 방향, 제스처, 발표 속도, 침묵, 필러 단어, 음량 분석
5. 규칙 기반 영역별 점수, 종합 점수, 피드백 생성
6. 시간대별 자세·얼굴·시선 타임라인 분석
7. 발표 목적별 연습 계획과 예상 질문 생성
8. 동일 연습 시리즈의 성장 추이 비교
9. 분석 결과 기반 Ollama AI 발표 코칭과 채팅
10. 관리자용 서비스 상태와 실패 작업 관리

### 영역별 역할

| 영역 | 기술 | 역할 |
|---|---|---|
| 프론트엔드 | React 19, React Router, Vite | 페이지 라우팅, 업로드, 분석 진행률, 결과·성장·채팅·계정·관리자 화면 |
| 백엔드 | FastAPI, Python | 인증, API, 분석 작업 큐, 분석 서비스 조합, 결과·대화 관리 |
| 영상·음성 분석 | MediaPipe, OpenCV, ffmpeg, Whisper | 프레임, 자세, 얼굴 방향, 제스처, 음성 인식, 속도, 침묵, 음량 분석 |
| 데이터베이스 | MySQL, PyMySQL | 사용자, 세션, 분석 작업, 대화, 메시지, 모델, 사용량 저장 |
| 파일 저장 | 로컬 파일 시스템 | 원본 영상, 임시 프레임, 상세 분석 결과, 연습 컨텍스트, AI 코칭 JSON 저장 |
| AI 코칭 | Ollama `qwen3:4b`, 로컬 RAG | 규칙 기반 점수를 변경하지 않고 근거 기반 코칭과 발표 문맥 채팅 생성 |
| 운영·검증 | Bash, Ruff, Coverage, Vitest, Playwright, GitHub Actions | 실행, 마이그레이션, 백업, 단위·통합·E2E 검증 |

### 중요한 설계 원칙

- 기본 발표 점수는 `score_calculator.py`의 결정적 규칙으로 계산한다.
- 자세·얼굴 감지 데이터가 부족하면 `0점`이 아니라 `측정 불가`로 처리한다.
- 측정 불가 점수는 종합 점수 가중치에서 제외한다.
- AI 코칭은 시스템 점수를 수정하지 않고 해석과 연습 행동을 추가한다.
- AI 코칭 실패가 기본 분석 결과와 규칙 기반 코칭 조회를 막지 않는다.
- 분석 작업, 결과, 대화는 로그인 사용자 소유권을 확인한다.

---

## 2. 전체 디렉토리 구조

생성물·데이터·캐시를 제외한 핵심 구조는 다음과 같다.

```text
hanium project/
├── .github/
│   └── workflows/
│       └── verify.yml
├── Back/
│   ├── app/
│   │   ├── middleware/
│   │   │   ├── request_id.py
│   │   │   └── upload_limit.py
│   │   ├── repositories/
│   │   │   └── analysis_job_repository.py
│   │   ├── routers/
│   │   │   ├── admin.py
│   │   │   ├── analyze.py
│   │   │   ├── auth.py
│   │   │   ├── chat.py
│   │   │   └── practice.py
│   │   ├── schemas/
│   │   │   └── auth.py
│   │   ├── services/
│   │   │   ├── ai_coaching.py
│   │   │   ├── analysis_jobs.py
│   │   │   ├── audio_analyzer.py
│   │   │   ├── auth_service.py
│   │   │   ├── database.py
│   │   │   ├── face_analyzer.py
│   │   │   ├── feedback_generator.py
│   │   │   ├── filler_analyzer.py
│   │   │   ├── frame_extractor.py
│   │   │   ├── gesture_analyzer.py
│   │   │   ├── knowledge_retriever.py
│   │   │   ├── ollama_service.py
│   │   │   ├── pose_analyzer.py
│   │   │   ├── practice_coaching.py
│   │   │   ├── practice_contexts.py
│   │   │   ├── result_saver.py
│   │   │   ├── score_calculator.py
│   │   │   ├── timeline_analyzer.py
│   │   │   ├── video_info.py
│   │   │   └── volume_analyzer.py
│   │   ├── workers/
│   │   │   └── analysis_worker.py
│   │   ├── config.py
│   │   └── main.py
│   ├── knowledge/
│   │   ├── README.md
│   │   └── 01_scoring_criteria.md ... 20_safety_and_privacy.md
│   ├── migrations/
│   │   └── 001_auth_chat_schema.sql ... 008_conversation_analysis_result.sql
│   ├── storage/
│   │   ├── uploads/
│   │   ├── frames/
│   │   ├── results/
│   │   ├── practice_contexts/
│   │   ├── ai_coaching/
│   │   └── models/
│   │       ├── face_landmarker.task
│   │       └── pose_landmarker.task
│   ├── tests/
│   ├── .env.example
│   ├── main.py
│   ├── requirements.txt
│   └── requirements-dev.txt
├── Front/
│   ├── e2e/
│   │   └── service-flow.spec.js
│   ├── src/
│   │   ├── api/
│   │   ├── app/
│   │   ├── components/
│   │   ├── features/
│   │   │   ├── analysis/
│   │   │   ├── chat/
│   │   │   ├── practice/
│   │   │   └── upload/
│   │   ├── hooks/
│   │   ├── pages/
│   │   ├── styles/
│   │   ├── test/
│   │   ├── App.jsx
│   │   └── main.jsx
│   ├── .env.example
│   ├── package.json
│   ├── playwright.config.js
│   ├── vite.config.js
│   └── vitest.config.js
├── docs/
│   ├── PRACTICE_COACHING_OPERATIONS.md
│   ├── PRODUCTION_CHECKLIST.md
│   ├── SERVICE_HARDENING_REPORT.md
│   ├── TEST_CRITERIA.md
│   └── PROJECT_STRUCTURE.md
├── scripts/
│   ├── backend-*.sh
│   ├── frontend-*.sh
│   ├── mysql-*.sh
│   ├── db-*.sh
│   ├── migrate.py
│   └── verify*.sh
├── .gitignore
├── pyproject.toml
└── README.md
```

---

## 3. 주요 폴더 설명

### `Back/`

- 역할: FastAPI 백엔드, 분석 로직, 모델 파일, DB 마이그레이션, 테스트를 포함한다.
- 포함된 주요 파일: `app/main.py`, `app/config.py`, `requirements.txt`, `.env.example`
- 담당 기능: HTTP API, 인증, 영상 분석, 작업 큐, 결과 저장, Ollama 연동, 운영 상태 확인

### `Back/app/routers/`

- 역할: 기능별 API 엔드포인트를 정의한다.
- 포함된 주요 파일: `analyze.py`, `practice.py`, `auth.py`, `chat.py`, `admin.py`
- 담당 기능: 요청 검증, 사용자 인증·소유권 확인, 서비스 호출, HTTP 응답 반환

### `Back/app/services/`

- 역할: 프로젝트의 핵심 업무 로직과 외부 시스템 연동을 구현한다.
- 포함된 주요 파일:
  - 분석: `audio_analyzer.py`, `pose_analyzer.py`, `face_analyzer.py`, `gesture_analyzer.py`, `volume_analyzer.py`, `timeline_analyzer.py`
  - 점수·피드백: `score_calculator.py`, `feedback_generator.py`, `practice_coaching.py`, `ai_coaching.py`
  - 저장·운영: `analysis_jobs.py`, `result_saver.py`, `database.py`, `file_cleaner.py`, `readiness.py`
  - AI·RAG: `ollama_service.py`, `knowledge_retriever.py`, `conversation_contexts.py`
- 담당 기능: 라우터에서 분리된 분석, 저장, 점수, 코칭, 운영 로직

### `Back/app/workers/`

- 역할: MySQL 작업 큐에서 대기 작업을 가져와 발표 영상을 분석한다.
- 포함된 주요 파일: `analysis_worker.py`
- 담당 기능: 분석 단계 진행, 취소 확인, 결과 저장, 실패 처리, 임시 파일 정리, 유지보수 작업

### `Back/app/middleware/`

- 역할: 모든 요청에 공통 적용되는 HTTP 처리 로직이다.
- 포함된 주요 파일: `request_id.py`, `upload_limit.py`
- 담당 기능: 요청 ID 부여, multipart 처리 전 업로드 크기 제한

### `Back/app/repositories/`

- 역할: 특정 DB 조회를 서비스 로직에서 분리한다.
- 포함된 주요 파일: `analysis_job_repository.py`
- 담당 기능: 현재는 처리 중인 분석 작업 ID 조회

### `Back/app/schemas/`

- 역할: API 요청 데이터의 형식과 검증 규칙을 정의한다.
- 포함된 주요 파일: `auth.py`
- 담당 기능: 회원가입, 로그인, 프로필, 비밀번호, 계정 삭제 요청 검증

### `Back/knowledge/`

- 역할: AI 발표 코칭과 발표 문맥 채팅에서 검색하는 로컬 RAG 지식 문서를 보관한다.
- 포함된 주요 파일: 점수, 속도, 필러, 침묵, 음량, 자세, 제스처, 표정, 시선, 연습법, 안전·개인정보 문서
- 담당 기능: 발표 목적과 측정 가능한 개선 항목에 맞는 코칭 참고 지식 제공
- 주의: 이 문서는 코칭 지침이며 시스템 점수나 측정 근거를 변경하지 않는다.

### `Back/migrations/`

- 역할: MySQL 스키마를 순서대로 생성·변경한다.
- 포함된 주요 파일: `001_auth_chat_schema.sql`부터 `008_conversation_analysis_result.sql`
- 담당 기능: 사용자·세션·채팅·분석 작업 테이블과 인덱스 관리

### `Back/storage/models/`

- 역할: MediaPipe 분석 모델 파일을 보관한다.
- 포함된 주요 파일: `face_landmarker.task`, `pose_landmarker.task`
- 담당 기능: 얼굴 랜드마크와 자세 랜드마크 추론

### `Back/tests/`

- 역할: 백엔드 단위·서비스·접근 제어 테스트를 보관한다.
- 포함된 주요 파일: 분석 작업자, 점수, AI 코칭, 인증, 접근 권한, 저장소, 운영 상태 테스트
- 담당 기능: 분석 규칙, 실패 격리, 사용자 소유권, 저장·정리 동작 검증

### `Front/`

- 역할: React/Vite 기반 사용자 웹 애플리케이션이다.
- 포함된 주요 파일: `src/main.jsx`, `src/App.jsx`, `package.json`, `vite.config.js`
- 담당 기능: 사용자 화면, API 호출, 업로드 진행률, 분석 결과 시각화, AI 채팅

### `Front/src/api/`

- 역할: 백엔드 API 호출을 기능별로 묶는다.
- 포함된 주요 파일: `apiClient.js`, `accountApi.js`, `analyzeApi.js`, `analysisJobsApi.js`, `analysisResultsApi.js`, `practiceApi.js`, `chatApi.js`, `adminApi.js`
- 담당 기능: 쿠키 포함 요청, 오류 메시지 정규화, 기능별 엔드포인트 호출

### `Front/src/app/`

- 역할: 전역 레이아웃과 라우팅을 정의한다.
- 포함된 주요 파일: `AppLayout.jsx`, `AppRoutes.jsx`
- 담당 기능: 내비게이션, 보호 라우트, 관리자 라우트, 페이지 연결

### `Front/src/pages/`

- 역할: URL별 완성 화면을 구성한다.
- 포함된 주요 파일: `HomePage.jsx`, `LoginPage.jsx`, `UploadPage.jsx`, `ResultListPage.jsx`, `ResultDetailPage.jsx`, `GrowthPage.jsx`, `ChatPage.jsx`, `AccountPage.jsx`, `AdminPage.jsx`
- 담당 기능: 사용자가 실제로 이용하는 각 페이지 제공

### `Front/src/features/`

- 역할: 페이지에서 재사용되는 도메인 기능을 묶는다.
- 포함된 주요 파일:
  - `analysis/`: 결과 카드, 포매터, 분석 상태, 결과 조회 훅
  - `upload/`: 분석 업로드 훅, 발표 목적 입력 폼
  - `practice/`: 규칙 기반 연습 코칭, AI 코칭 표시
  - `chat/`: 대화 패널, 사이드바, 발표 문맥 대화 상태
- 담당 기능: 페이지 복잡도 감소와 기능별 상태·UI 재사용

### `Front/src/components/`

- 역할: 여러 화면에서 사용하는 공통 UI와 보호 로직을 제공한다.
- 포함된 주요 파일: `ProtectedRoute.jsx`, `StateMessage.jsx`, `ActionDialog.jsx`, `ErrorBoundary.jsx`
- 담당 기능: 접근 제어, 로딩·오류 상태, 확인 대화상자, 렌더링 오류 처리

### `Front/e2e/`

- 역할: 실제 브라우저 서비스 흐름 테스트를 보관한다.
- 포함된 주요 파일: `service-flow.spec.js`
- 담당 기능: 회원가입부터 영상 분석, 코칭, 계정 삭제까지의 E2E 검증

### `docs/`

- 역할: 운영, 테스트, 보안 강화, 연습 코칭, 구조 문서를 보관한다.
- 포함된 주요 파일: `TEST_CRITERIA.md`, `PRODUCTION_CHECKLIST.md`, `PRACTICE_COACHING_OPERATIONS.md`, `SERVICE_HARDENING_REPORT.md`
- 담당 기능: 코드 외의 실행·검증·운영 계약 제공

### `scripts/`

- 역할: 로컬 서비스 실행, DB 관리, 검증을 자동화한다.
- 포함된 주요 파일: `mysql-start.sh`, `backend-start.sh`, `frontend-start.sh`, `migrate.py`, `verify.sh`, `verify-service.sh`, `verify-browser-e2e.sh`
- 담당 기능: 반복 가능한 개발·운영 작업 제공

### `.github/workflows/`

- 역할: GitHub Actions CI를 정의한다.
- 포함된 주요 파일: `verify.yml`
- 담당 기능: Python/프론트엔드 검사와 MySQL 마이그레이션 통합 검증

---

## 4. 주요 파일 설명

### `README.md`

- 역할: 서비스 기능, 실행 순서, 데이터 저장, 검사, 운영 흐름을 설명하는 기본 문서
- 주요 기능: MySQL·Ollama·백엔드·프론트엔드 실행 방법과 분석·채팅·코칭 구조 안내
- 연결 관계: 신규 개발자와 운영자가 가장 먼저 확인해야 하는 문서

### `Back/app/main.py`

- 역할: 실제 FastAPI 애플리케이션 진입점
- 주요 기능:
  - 보안 헤더, 요청 ID, 업로드 제한, 교차 출처 쿠키 요청 방어, CORS 설정
  - 분석, 인증, 채팅, 관리자, 연습 라우터 등록
  - 시작 시 중단 작업·채팅 복구와 분석 워커 실행
  - `/`, `/health`, `/readiness` 제공
- 연결 관계: `uvicorn app.main:app`으로 실행하며 모든 백엔드 기능을 조합한다.

### `Back/main.py`

- 역할: `Back/app/main.py`의 `app` 객체를 다시 내보내는 호환용 진입 파일
- 주요 기능: 패키지·실행 위치 차이를 고려한 import
- 연결 관계: 실제 권장 실행 진입점은 README에 명시된 `app.main:app`이다.

### `Back/app/config.py`

- 역할: 백엔드 경로와 환경변수를 한곳에서 읽는다.
- 주요 기능: DB, 세션, Ollama, 분석 워커, 보존 기간, 업로드 제한, 영상 제한, 디스크 기준 설정
- 연결 관계: 라우터, 서비스, 작업자 전체가 이 설정을 사용한다.

### `Back/app/routers/analyze.py`

- 역할: 영상 분석 작업과 결과 API를 제공한다.
- 주요 기능: 영상 검증·업로드, 작업 생성·취소·재시도, 결과·섹션·타임라인·보고서·성장 조회, 결과 삭제
- 연결 관계: `analysis_jobs.py`, `result_saver.py`, `practice_coaching.py`, 파일 정리 서비스와 연결된다.

### `Back/app/routers/practice.py`

- 역할: 발표 목적, 연습 컨텍스트, 규칙 기반 코칭, AI 코칭 API를 제공한다.
- 주요 기능: 목적·시리즈 조회, 컨텍스트 저장, 연습 계획·성장 조회, AI 코칭 생성·재생성
- 연결 관계: 분석 결과, 연습 컨텍스트 JSON, `ai_coaching.py`, `practice_coaching.py`를 연결한다.

### `Back/app/routers/auth.py`

- 역할: 사용자 인증과 계정 관리 API를 제공한다.
- 주요 기능: 회원가입, 로그인, 로그아웃, 내 정보, 저장 용량, 데이터 내보내기, 프로필·비밀번호 변경, 전체 로그아웃, 계정 삭제
- 연결 관계: MySQL 사용자·세션 테이블과 분석·코칭·대화 데이터 수명주기를 연결한다.

### `Back/app/routers/chat.py`

- 역할: Ollama 채팅 대화와 메시지 API를 제공한다.
- 주요 기능: 모델 조회, 대화 생성·조회·수정·보관·복원·삭제, 메시지 조회, 사용량 조회, 채팅 실행
- 연결 관계: 분석 결과 ID가 있으면 백엔드가 발표 분석 문맥과 AI 코칭을 시스템 프롬프트에 결합한다.

### `Back/app/workers/analysis_worker.py`

- 역할: 분석 작업 큐를 처리하는 핵심 실행 파이프라인
- 주요 기능:
  1. 영상 정보 검사
  2. 프레임 추출
  3. 자세 분석
  4. 얼굴 방향 분석
  5. 타임라인 분석
  6. 음성·필러 분석
  7. 제스처·음량 분석
  8. 점수·피드백 계산
  9. JSON 결과 저장과 DB 요약 갱신
  10. 취소·실패·임시 파일·보존 기간 정리
- 연결 관계: 거의 모든 분석 서비스와 `analysis_jobs` DB 작업을 조합한다.

### `Back/app/services/score_calculator.py`

- 역할: 측정값을 규칙 기반 점수와 종합 점수로 계산한다.
- 주요 기능:
  - 자세 균형, 얼굴 방향, 속도, 침묵, 필러, 제스처, 음량 점수 계산
  - 시각 데이터 최소 3프레임·30% 감지율 기준 적용
  - 측정 불가 점수 제외 후 사용 가능한 가중치 재계산
  - 분석 신뢰도와 점수 가용성 생성
- 연결 관계: 분석 작업자가 호출하며 결과 상세, 코칭, 성장 비교의 기준값이 된다.

### `Back/app/services/ai_coaching.py`

- 역할: 결과별 구조화 AI 발표 코칭을 생성·검증·저장한다.
- 주요 기능:
  - 분석 결과와 발표 목적을 구조화 입력으로 변환
  - 허용 가능한 실제 분석 근거 목록 생성
  - RAG 지식 검색
  - Ollama JSON 응답 검증
  - 최대 3개 우선 개선사항 제한
  - 잘못된 응답·모델 장애 시 규칙 기반 대체 코칭 저장
  - 발표 결과 기반 채팅 시스템 프롬프트 생성
- 연결 관계: `ollama_service.py`, `knowledge_retriever.py`, `practice_coaching.py`, `Back/ai_coaching` 데이터와 연결된다.

### `Back/app/services/practice_coaching.py`

- 역할: 발표 목적별 규칙 기반 연습 계획을 만든다.
- 주요 기능: 우선 개선 항목 3개, 행동·연습 과제, 예상 질문, 시간 적합성, 내용 구조 보조 분석, 동일 시리즈 비교
- 연결 관계: 연습 API, AI 코칭 입력, 발표 문맥 채팅에서 사용한다.

### `Back/app/services/analysis_jobs.py`

- 역할: MySQL `analysis_jobs` 작업 큐와 사용자별 분석 이력을 관리한다.
- 주요 기능: 작업 예약·선점·진행률·완료·실패·취소·재시도·복구·목록·성장·정리
- 연결 관계: 업로드 라우터, 작업자, 관리자 API, 운영 상태가 사용한다.

### `Back/app/services/result_saver.py`

- 역할: 상세 분석 결과 JSON을 원자적으로 저장·조회·삭제한다.
- 주요 기능: 임시 파일 작성 후 `os.replace`, 결과 로드, 결과 파일 삭제
- 연결 관계: DB에는 핵심 요약을 저장하고 상세 결과는 이 파일 저장소에서 관리한다.

### `Back/app/services/database.py`

- 역할: MySQL 연결과 트랜잭션을 관리한다.
- 주요 기능: 연결 풀, 트랜잭션 컨텍스트, 채팅 동시성 제어용 advisory lock, DB ping
- 연결 관계: 인증, 채팅, 분석 작업, 운영 상태 서비스가 사용한다.

### `Back/app/services/knowledge_retriever.py`

- 역할: `Back/knowledge` Markdown 문서에서 관련 코칭 지식을 검색한다.
- 주요 기능: front matter 파싱, 캐시, 발표 목적·지표·질의 토큰 기반 점수화, 관련 문서 제한
- 연결 관계: AI 발표 코칭과 발표 문맥 채팅에 참고 문서를 전달한다.

### `Front/src/App.jsx`

- 역할: 프론트엔드 최상위 애플리케이션
- 주요 기능: 현재 사용자 조회, 인증 로딩 상태, 전역 레이아웃과 라우트 조합
- 연결 관계: `accountApi.js`, `AppLayout.jsx`, `AppRoutes.jsx`를 사용한다.

### `Front/src/app/AppRoutes.jsx`

- 역할: 프론트엔드 URL과 페이지를 연결한다.
- 주요 기능: 공개·로그인 보호·관리자 전용 라우트 구분
- 연결 관계: `ProtectedRoute.jsx`와 각 페이지를 연결한다.

### `Front/src/pages/UploadPage.jsx`

- 역할: 발표 목적 입력과 영상 업로드 화면
- 주요 기능: 파일 선택, 안내, 업로드·분석 상태, 취소·재시도, 완료 후 결과 이동
- 연결 관계: `useAnalysisUpload.js`, `PracticeContextForm.jsx`, 분석·연습 API를 사용한다.

### `Front/src/pages/ResultDetailPage.jsx`

- 역할: 발표 분석의 상세 결과 대시보드
- 주요 기능: 점수, 측정 불가, 속도, 침묵, 필러, 제스처, 음량, 타임라인, 규칙 기반 코칭, AI 코칭, 보고서·채팅 연결
- 연결 관계: 결과 조회 훅, `PracticeCoachingSections.jsx`, `AiCoachingSection.jsx`를 사용한다.

### `Front/src/pages/ChatPage.jsx`

- 역할: 일반 AI 채팅과 발표 결과 기반 코칭 채팅 화면
- 주요 기능: 대화·메시지 페이지네이션, 생성·이름 변경·보관·복원·삭제, 응답 재생성
- 연결 관계: `chatApi.js`, 채팅 기능 컴포넌트, 발표 문맥 세션 훅을 사용한다.

### `scripts/verify.sh`

- 역할: 프로젝트의 기본 전체 검증 명령
- 주요 기능: 셸 문법, Ruff, 백엔드 단위 테스트·커버리지, 프론트 포맷·린트·테스트·빌드
- 연결 관계: 로컬 개발과 CI의 품질 기준이다.

---

## 5. 실행 흐름

### 서비스 시작 흐름

1. `scripts/mysql-start.sh`가 프로젝트 전용 MySQL을 시작한다.
2. `scripts/migrate.py` 또는 초기 설정이 마이그레이션을 순서대로 적용한다.
3. 운영자가 Ollama를 실행하고 설정된 모델을 설치한다.
4. 백엔드는 `Back/.env`를 읽고 `uvicorn app.main:app`으로 시작한다.
5. FastAPI lifespan이 중단된 분석 작업과 오래된 pending 채팅을 복구한다.
6. 분석 워커 스레드와 유지보수 스레드가 시작된다.
7. Vite 프론트엔드가 실행되고 `/analyze`, `/api` 요청을 백엔드로 프록시한다.

### 회원가입·로그인 흐름

1. 사용자가 로그인 화면에서 회원가입 또는 로그인을 요청한다.
2. 프론트엔드가 `/api/auth/register` 또는 `/api/auth/login`을 호출한다.
3. 백엔드가 입력을 검증하고 비밀번호 해시와 사용자 정보를 MySQL에 저장·조회한다.
4. 로그인 성공 시 서버가 `HttpOnly session_token` 쿠키를 발급한다.
5. 이후 보호 API는 쿠키 세션으로 현재 사용자와 소유권을 확인한다.

### 발표 영상 분석 흐름

1. 사용자가 발표 목적, 청중, 목표 시간, 핵심 메시지, 연습 시리즈를 입력한다.
2. 사용자가 영상을 선택하면 프론트가 형식과 크기를 우선 확인한다.
3. 프론트가 `POST /analyze/upload`로 영상을 전송한다.
4. 백엔드가 로그인, 요청 제한, 확장자, 크기, 디스크, 사용자 용량, 동시 작업 수, 영상 정보를 검증한다.
5. 원본 영상을 `Back/storage/uploads`에 저장하고 MySQL `analysis_jobs`에 `QUEUED` 작업을 등록한다.
6. 프론트는 받은 작업 ID로 연습 컨텍스트를 저장하고 작업 상태를 주기적으로 조회한다.
7. 분석 워커가 작업을 선점하고 단계·진행률을 DB에 갱신한다.
8. 영상 정보, 프레임, 자세, 얼굴 방향, 타임라인, 음성, 필러, 제스처, 음량을 분석한다.
9. `score_calculator.py`가 규칙 기반 점수와 분석 신뢰도를 계산한다.
10. `feedback_generator.py`가 기본 피드백을 생성한다.
11. 상세 결과는 `Back/storage/results/{result_id}.json`, 핵심 요약과 상태는 MySQL에 저장한다.
12. 성공한 원본 영상과 임시 프레임을 정리한다.
13. 프론트가 완료 상태를 확인하고 결과 상세 화면으로 이동한다.

### 결과·연습 코칭 흐름

1. 결과 상세 화면이 요약, 섹션, 타임라인을 각각 조회한다.
2. 기본 분석 결과를 먼저 표시한다.
3. 별도 연습 코칭 요청이 발표 목적과 결과를 바탕으로 규칙 기반 계획을 생성한다.
4. 동일 목적·동일 시리즈의 이전 결과가 있으면 성장 변화량을 계산한다.
5. 연습 코칭 요청이 실패해도 기본 결과와 타임라인은 계속 표시한다.

### AI 발표 코칭 흐름

1. 사용자가 결과 상세 화면에서 AI 코칭 생성을 요청한다.
2. 백엔드가 결과 소유권과 완료 상태를 확인한다.
3. 시스템 점수, 측정 가능 여부, 분석 신뢰도, 발화 구간, 규칙 기반 코칭을 구조화한다.
4. 발표 목적과 낮은 측정 가능 점수를 기준으로 RAG 문서를 검색한다.
5. 백엔드가 허용 가능한 실제 분석 근거 목록과 함께 Ollama에 JSON 응답을 요청한다.
6. 응답 구조와 근거가 검증되면 AI 코칭을 저장한다.
7. 응답이 잘못되거나 Ollama가 실패하면 규칙 기반 대체 코칭을 저장한다.
8. AI 코칭 성공 여부와 관계없이 기본 분석 결과는 유지된다.

### 발표 문맥 채팅 흐름

1. 일반 채팅은 분석 문맥 없이 새 대화를 만든다.
2. 결과 화면에서 채팅을 시작하면 프론트는 `analysis_result_id`를 전달한다.
3. 백엔드는 결과 소유권을 확인하고 분석 결과·연습 코칭·AI 코칭·RAG 지식을 시스템 프롬프트로 만든다.
4. 사용자의 질문과 최근 대화 메시지를 Ollama에 전달한다.
5. 사용자·AI 메시지와 토큰 사용량을 MySQL에 저장한다.
6. Ollama 실패 시 사용자 메시지는 실패 상태로 남겨 복구 가능성을 유지한다.

---

## 6. API 구조

모든 보호 API는 별도 표기가 없는 한 `HttpOnly session_token` 쿠키가 필요하다.

### 공통·운영 API

| 메서드 | 경로 | 목적 | 관련 파일 | 반환 개요 |
|---|---|---|---|---|
| GET | `/` | 백엔드 기본 상태 | `Back/app/main.py` | 실행 메시지 |
| GET | `/health` | 프로세스와 DB 생존 확인 | `Back/app/main.py` | 상태, DB 연결 |
| GET | `/readiness` | DB·워커·Ollama·큐·디스크 준비 상태 | `main.py`, `readiness.py` | 준비 상태와 세부 검사 |
| GET | `/api/admin/status` | 관리자 운영 상태 조회 | `routers/admin.py` | readiness 기반 상태 |
| GET | `/api/admin/analysis-jobs` | 실패·정체 작업 조회 | `routers/admin.py` | 문제 작업 목록 |
| POST | `/api/admin/analysis-jobs/{job_id}/retry` | 관리자 작업 재시도 | `routers/admin.py` | 재등록 작업 |

### 인증·계정 API

| 메서드 | 경로 | 목적 | 관련 파일 | 반환 개요 |
|---|---|---|---|---|
| POST | `/api/auth/register` | 회원가입과 세션 발급 | `routers/auth.py` | 사용자 정보 |
| POST | `/api/auth/login` | 로그인과 세션 발급 | `routers/auth.py` | 사용자 정보 |
| POST | `/api/auth/logout` | 현재 세션 로그아웃 | `routers/auth.py` | 204 |
| GET | `/api/auth/me` | 현재 사용자 조회 | `routers/auth.py` | 사용자 정보 |
| GET | `/api/auth/storage` | 사용자 저장 용량 조회 | `routers/auth.py` | 사용량·가용량·활성 작업 |
| GET | `/api/auth/export` | 사용자 데이터 내보내기 | `routers/auth.py` | 프로필·대화·분석·코칭 데이터 |
| PUT | `/api/auth/profile` | 표시 이름 변경 | `routers/auth.py` | 변경된 사용자 |
| PUT | `/api/auth/password` | 비밀번호 변경 | `routers/auth.py` | 204 |
| POST | `/api/auth/logout-all` | 모든 세션 삭제 | `routers/auth.py` | 204 |
| DELETE | `/api/auth/account` | 계정과 연결 데이터 삭제 | `routers/auth.py` | 204 |

### 분석 API

| 메서드 | 경로 | 목적 | 관련 파일 | 반환 개요 |
|---|---|---|---|---|
| GET | `/analyze/` | 분석 API 확인 | `routers/analyze.py` | 준비 메시지 |
| POST | `/analyze/upload` | 영상 저장과 분석 작업 생성 | `routers/analyze.py` | `QUEUED` 작업 ID |
| GET | `/analyze/job/{result_id}` | 작업 상태·단계·진행률 조회 | `routers/analyze.py` | 작업 정보 |
| POST | `/analyze/job/{result_id}/cancel` | 대기·처리 작업 취소 요청 | `routers/analyze.py` | 갱신된 작업 |
| POST | `/analyze/job/{result_id}/retry` | 실패·취소 작업 재시도 | `routers/analyze.py` | 재등록 작업 |
| GET | `/analyze/results` | 사용자별 분석 이력 조회 | `routers/analyze.py` | 페이지네이션 결과 |
| GET | `/analyze/growth` | 완료 분석 성장 데이터 조회 | `routers/analyze.py` | 성장 배열 |
| GET | `/analyze/result/{result_id}` | 전체 상세 JSON 조회 | `routers/analyze.py` | 상세 분석 결과 |
| GET | `/analyze/result/{result_id}/summary` | 요약 조회 | `routers/analyze.py` | 점수·요약 |
| GET | `/analyze/result/{result_id}/sections` | 화면별 분석 섹션 조회 | `routers/analyze.py` | 점수·발화·필러·제스처·음량 |
| GET | `/analyze/result/{result_id}/timeline` | 전체 타임라인 조회 | `routers/analyze.py` | 타임라인 |
| GET | `/analyze/result/{result_id}/timeline/chart` | 차트용 타임라인 조회 | `routers/analyze.py` | 시간별 차트 데이터 |
| GET | `/analyze/result/{result_id}/report.md` | Markdown 보고서 다운로드 | `routers/analyze.py` | Markdown 파일 |
| DELETE | `/analyze/result/{result_id}` | 결과와 연결 데이터 삭제 | `routers/analyze.py` | 삭제 결과 |

### 연습·AI 코칭 API

| 메서드 | 경로 | 목적 | 관련 파일 | 반환 개요 |
|---|---|---|---|---|
| GET | `/analyze/practice/purposes` | 발표 목적과 기준 조회 | `routers/practice.py` | 목적 배열 |
| GET | `/analyze/practice/series` | 사용자 연습 시리즈 조회 | `routers/practice.py` | 시리즈 배열 |
| PUT | `/analyze/practice/{result_id}` | 발표 목적·연습 컨텍스트 저장 | `routers/practice.py` | 저장된 컨텍스트 |
| GET | `/analyze/practice/{result_id}` | 규칙 기반 연습 코칭 조회 | `routers/practice.py` | 연습 계획 |
| GET | `/analyze/practice/growth/all` | 시리즈 기준 성장 추이 조회 | `routers/practice.py` | 보강된 성장 데이터 |
| GET | `/analyze/practice/{result_id}/ai-coaching` | 저장된 AI 코칭 조회 | `routers/practice.py` | AI 코칭 또는 미생성 상태 |
| POST | `/analyze/practice/{result_id}/ai-coaching` | AI 코칭 생성 또는 캐시 반환 | `routers/practice.py` | AI·대체 코칭 |
| POST | `/analyze/practice/{result_id}/ai-coaching/regenerate` | AI 코칭 재생성 | `routers/practice.py` | 새 AI·대체 코칭 |

### 채팅 API

| 메서드 | 경로 | 목적 | 관련 파일 | 반환 개요 |
|---|---|---|---|---|
| GET | `/api/models` | 활성 채팅 모델 조회 | `routers/chat.py` | 모델 배열 |
| GET | `/api/conversations` | 대화 목록 조회 | `routers/chat.py` | 대화, 총수, 다음 커서 |
| POST | `/api/conversations` | 일반·발표 문맥 대화 생성 | `routers/chat.py` | 대화 정보 |
| PATCH | `/api/conversations/{id}` | 대화 이름 변경 | `routers/chat.py` | 변경된 대화 |
| POST | `/api/conversations/{id}/archive` | 대화 보관 | `routers/chat.py` | 204 |
| POST | `/api/conversations/{id}/restore` | 보관 대화 복원 | `routers/chat.py` | 204 |
| DELETE | `/api/conversations/{id}` | 대화 삭제 | `routers/chat.py` | 204 |
| GET | `/api/conversations/{id}/messages` | 메시지 목록 조회 | `routers/chat.py` | 메시지, 총수, 다음 커서 |
| POST | `/api/conversations/{id}/chat` | Ollama 채팅 실행 | `routers/chat.py` | 사용자·AI 메시지, 모델, 사용량 |
| GET | `/api/usage/summary` | 사용자 채팅 사용량 조회 | `routers/chat.py` | 요청·토큰·비용 합계 |

---

## 7. 데이터 저장 구조

### MySQL 저장

| 테이블 | 주요 역할 | 주요 필드 |
|---|---|---|
| `users` | 사용자 계정 | 이메일, 비밀번호 해시, 표시 이름, 역할, 상태 |
| `user_sessions` | 로그인 세션 | 사용자 ID, 토큰 해시, 만료·최근 사용 시각 |
| `gpt_models` | 사용 가능한 AI 모델 | provider, model key, 표시 이름, 활성 여부 |
| `conversations` | 채팅 대화 | 사용자, 모델, 분석 결과 ID, 제목, 시스템 프롬프트, 보관 시각 |
| `messages` | 채팅 메시지 | 대화, 역할, 내용, 메타데이터, 순서 |
| `gpt_usage` | 모델 사용량 | 요청 ID, 입출력 토큰, 총 토큰, 추정 비용 |
| `analysis_jobs` | 분석 작업 큐와 핵심 요약 | 사용자, 상태, 단계, 진행률, 파일명, 점수, 지표, 결과 경로 |
| `schema_migrations` | 적용된 마이그레이션 | 파일명, 체크섬, 적용 시각 |

### 파일 시스템 저장

| 위치 | 데이터 | 보존·정리 방식 |
|---|---|---|
| `Back/storage/uploads/` | 업로드 원본 영상 | 완료 시 삭제, 실패·취소 시 기본 24시간 보존 후 정리 |
| `Back/storage/frames/{job_id}/` | 분석용 임시 프레임 | 작업 종료 시 삭제, 고아 폴더 주기 정리 |
| `Back/storage/results/{result_id}.json` | 상세 분석 결과와 타임라인 | DB 핵심 요약과 함께 관리, 결과 삭제·보존 정책에 따라 정리 |
| `Back/storage/practice_contexts/{result_id}.json` | 발표 목적, 청중, 목표 시간, 핵심 메시지, 시리즈 | 결과 수명주기와 함께 정리 |
| `Back/storage/ai_coaching/{result_id}.json` | 구조화 AI 코칭 또는 규칙 기반 대체 코칭 | 결과 수명주기와 함께 정리 |
| `Back/storage/models/` | MediaPipe 분석 모델 파일 | 정적 모델 파일 (.task) 보관 |
| `Back/knowledge/*.md` | RAG 코칭 지식 | 코드와 함께 버전 관리 |
| `.runtime/mysql-data/` | 로컬 MySQL 데이터 | Git 제외 실행 데이터 |
| `.runtime/backups/` | DB SQL 백업 | 보존 기간 설정에 따라 관리 |

### 상세 분석 결과의 주요 데이터

- 식별·상태: `result_id`, `created_at`, `status`, `original_filename`
- 영상: 길이, 해상도, FPS, 프레임 수
- 시각 분석: 자세 결과, 얼굴 결과, 감지율, 어깨 균형, 얼굴 방향
- 음성 분석: Whisper 텍스트·구간, WPM, 음절/분, 침묵
- 전달 분석: 필러 단어, 손동작 변화, 음량
- 점수: 각 지표 점수, 측정 가능 여부, 종합 점수, 분석 신뢰도
- 결과: 요약 피드백, 세부 피드백, 시간대별 타임라인
- 메타데이터: 분석 알고리즘 버전, Ollama 모델, 처리 시간

### 저장 구조의 특징

- MySQL은 사용자·권한·작업 상태·목록 조회에 필요한 구조화 데이터를 담당한다.
- JSON 파일은 큰 상세 분석 결과와 코칭 데이터를 담당한다.
- DB 작업과 JSON 파일은 함께 수명주기를 관리하지만 하나의 원자적 트랜잭션으로 묶이지는 않는다.

---

## 8. 프론트엔드 구조

### 페이지 구성과 라우팅

| 경로 | 페이지 | 접근 | 역할 |
|---|---|---|---|
| `/` | `HomePage` | 공개 | 서비스 소개와 기능 안내 |
| `/login` | `LoginPage` | 공개 | 로그인·회원가입 |
| `/upload` | `UploadPage` | 로그인 | 발표 목적 입력, 영상 업로드, 분석 진행 |
| `/results` | `ResultListPage` | 로그인 | 검색·필터·정렬·페이지네이션 분석 이력 |
| `/result/:resultId` | `ResultDetailPage` | 로그인 | 분석 결과, 타임라인, 연습·AI 코칭 |
| `/growth` | `GrowthPage` | 로그인 | 동일 연습 시리즈 성장 추이 |
| `/chat` | `ChatPage` | 로그인 | 일반 AI 코치 채팅 |
| `/chat/result/:resultId` | `ChatPage` | 로그인 | 발표 분석 문맥 채팅 |
| `/account` | `AccountPage` | 로그인 | 프로필, 비밀번호, 세션, 데이터, 탈퇴 |
| `/admin` | `AdminPage` | 관리자 | 운영 상태와 문제 작업 재시도 |

### 컴포넌트 구조

- 공통: 보호 라우트, 상태 메시지, 확인 다이얼로그, 오류 경계
- 분석: 결과 카드, 점수 포매터, 단계명, 목록·상세 데이터 훅
- 업로드: 발표 컨텍스트 폼, 업로드·폴링·취소·재시도 훅
- 연습: 규칙 기반 코칭과 AI 코칭 섹션
- 채팅: 대화 사이드바, 채팅 패널, 발표 문맥 대화 관리

### API 호출 흐름

1. `apiClient.js`가 `VITE_API_BASE_URL`과 `credentials: include` 방식의 공통 요청을 처리한다.
2. 기능별 API 파일이 엔드포인트를 감싼다.
3. 페이지 또는 기능 훅이 API 함수를 호출한다.
4. 로딩·성공·실패 상태를 페이지에 표시한다.
5. 개발 환경에서는 Vite가 `/api`와 `/analyze`를 백엔드로 프록시한다.

### 상태 관리 방식

- Redux 같은 전역 상태 라이브러리는 사용하지 않는다.
- 현재 사용자 상태는 `App.jsx`에서 관리한다.
- 페이지·기능별 상태는 React `useState`, `useEffect`, `useMemo`, `useCallback`, 사용자 정의 훅으로 관리한다.
- 발표 문맥 채팅 연결 정보 일부는 브라우저 세션 저장소를 사용한다.
- 서버 데이터는 API 재조회와 폴링으로 동기화한다.

### 오류 격리

- 기본 결과, 타임라인, 연습 코칭, AI 코칭을 별도 요청으로 나누어 일부 요청 실패가 전체 화면을 막지 않도록 한다.
- `ErrorBoundary`가 예상하지 못한 렌더링 오류를 처리한다.
- `apiClient.js`가 다양한 서버 오류 응답을 사용자 메시지로 정규화한다.

---

## 9. 백엔드 구조

### 라우터 구조

- `analyze.py`: 분석 작업과 결과
- `practice.py`: 발표 목적, 연습 컨텍스트, 규칙·AI 코칭
- `auth.py`: 사용자, 세션, 계정
- `chat.py`: 모델, 대화, 메시지, Ollama 채팅
- `admin.py`: 관리자 운영 상태와 문제 작업

### 서비스 계층 구조

- 입력·보안: 인증, 요청 제한, 영상 정보·제약 검증
- 분석: 프레임, 자세, 얼굴, 타임라인, 음성, 필러, 제스처, 음량
- 평가: 점수 계산, 기본 피드백, 연습 코칭
- AI: RAG 검색, Ollama 호출, AI 코칭 검증·대체, 발표 문맥 생성
- 저장: DB 연결, 분석 작업, 상세 JSON, 컨텍스트, 파일 정리
- 운영: readiness, 저장 용량, 마이그레이션, 중단 작업 복구

### 분석 기능 구조

```text
분석 작업
  → 영상 제한 검증
  → 1초 간격 프레임 추출
  → MediaPipe 자세·얼굴 랜드마크
  → 타임라인 점수
  → Whisper 음성 인식·발화 속도·침묵
  → 필러 단어·제스처·음량
  → 규칙 기반 점수·피드백
  → 상세 JSON + DB 요약 저장
```

### 작업 큐와 동시성

- MySQL `analysis_jobs`가 영속 작업 큐 역할을 한다.
- FastAPI 프로세스 안의 워커 스레드가 대기 작업을 선점한다.
- 진행률, 단계, heartbeat, 취소 요청, 재시도 횟수를 DB에 저장한다.
- 서버 재시작 시 중단 작업을 다시 대기열로 복구한다.
- 채팅은 MySQL advisory lock으로 같은 대화의 동시 요청을 제한한다.

### 예외 처리 방식

- FastAPI `HTTPException`으로 사용자에게 노출할 상태 코드와 한국어 메시지를 반환한다.
- 분석 작업 내부 오류는 로그에 상세 기록하고 사용자에게는 공용 오류 메시지를 저장한다.
- 실패 또는 취소 작업은 원본을 일정 시간 보존해 재시도를 지원한다.
- AI 코칭 실패는 대체 코칭으로 전환하며 기본 결과와 분리한다.
- 결과 파일이 없거나 DB 점수와 불일치하면 작업 상태·요약을 보정한다.

### 보안과 접근 제어

- 비밀번호는 salt를 포함해 해시로 저장한다.
- 세션 토큰 원문 대신 토큰 해시를 DB에 저장하고 HttpOnly 쿠키를 사용한다.
- 분석, 결과, 대화는 사용자 소유권을 확인한다.
- 관리자 API는 `admin` 역할을 요구한다.
- CORS 허용 출처, 쿠키 기반 교차 출처 변경 요청 차단, 보안 헤더를 적용한다.
- 업로드 형식·크기·영상 길이·해상도·FPS·프레임·디스크·사용자 용량을 제한한다.
- 로그인, 회원가입, 업로드, 채팅에 메모리 기반 요청 제한을 적용한다.

---

## 10. 환경 설정 파일

### `Back/.env.example`

- 역할: 백엔드 환경변수 예시
- 주요 설정:
  - DB 연결·풀·백업·마이그레이션 계정
  - 세션 쿠키와 만료
  - Ollama 주소·모델·타임아웃
  - 분석 워커와 보존 기간
  - 업로드·영상·디스크·사용자 용량 제한
  - 알고리즘 버전과 CORS 출처
- 주의: 실제 비밀번호와 운영 주소는 `Back/.env`에 설정하고 버전 관리에 포함하지 않아야 한다.

### `Front/.env.example`

- 역할: 프론트엔드 API 기준 주소 예시
- 주요 설정: `VITE_API_BASE_URL`
- 기본 사용: Vite 프록시 또는 동일 출처 배포에서는 빈 값 사용

### `Back/requirements.txt`

- 역할: 백엔드 실행 의존성
- 주요 패키지: FastAPI, MediaPipe, Whisper, OpenCV, multipart, dotenv, PyMySQL, cryptography, requests, uvicorn
- 추가 요구사항: 시스템 PATH에 `ffmpeg`가 필요하다.

### `Back/requirements-dev.txt`

- 역할: 백엔드 개발·검증 의존성
- 주요 패키지: Coverage, HTTPX, Ruff

### `Front/package.json`

- 역할: 프론트엔드 의존성과 npm 명령 정의
- 주요 명령: `dev`, `build`, `preview`, `lint`, `format:check`, `test`, `test:e2e`
- 주요 패키지: React, React Router, Vite, Vitest, Playwright, Testing Library

### `pyproject.toml`

- 역할: Ruff 코드 검사 설정
- 주요 설정: Python 3.12 대상, E/F/I 검사, 줄 길이 120

### `Front/vite.config.js`

- 역할: Vite 개발 서버 설정
- 주요 설정: 포트 5173, `/analyze`와 `/api`를 `127.0.0.1:8000`으로 프록시

### `.github/workflows/verify.yml`

- 역할: CI 검증
- 주요 기능:
  - Python 3.12, Node 22 환경
  - Ruff, 백엔드 테스트·커버리지
  - 프론트 포맷·린트·테스트·빌드·audit
  - MySQL 마이그레이션 반복 적용과 큐 상태 확인

### `README.md`

- 역할: 실행, 기능, 데이터, 장애 대응, 테스트, 백업·마이그레이션 안내
- 신규 개발자가 가장 먼저 읽어야 하는 실행 문서다.

### Docker 관련 파일

- `docker-compose.yml`은 현재 확인되지 않는다.
- 로컬 MySQL은 `scripts/mysql-start.sh`와 `.runtime/mysql-data` 방식으로 관리한다.

### 기본 실행 순서

```bash
./scripts/mysql-start.sh
ollama serve
ollama pull qwen3:4b

cd Back
../.venv/bin/pip install -r requirements.txt
../.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000

cd ../Front
npm install
npm run dev
```

---

## 11. 현재 프로젝트의 장점

- 발표 영상 업로드부터 분석, 코칭, 반복 연습, 채팅까지 사용자 흐름이 연결되어 있다.
- 프론트엔드, 라우터, 서비스, 작업자, DB 마이그레이션이 역할별로 구분되어 있다.
- 분석을 비동기 작업 큐로 처리해 HTTP 요청과 긴 분석 작업을 분리했다.
- 작업 상태, 단계, 진행률, 취소, 재시도, 서버 재시작 복구를 지원한다.
- 사용자 소유권 검사를 분석 결과와 채팅에 일관되게 적용한다.
- 측정 불가 항목을 실제 낮은 점수와 구분해 분석 신뢰도를 높였다.
- 결정적 점수와 AI 해석을 분리해 AI 실패나 환각이 기본 점수를 변경하지 않는다.
- AI 응답 근거를 허용된 측정값으로 제한하고 JSON 구조를 검증한다.
- AI 장애 시 규칙 기반 대체 코칭으로 기본 기능을 유지한다.
- 상세 결과는 원자적 파일 교체 방식으로 저장한다.
- 상세 JSON과 DB 요약을 분리해 큰 분석 데이터와 목록 조회 요구를 함께 처리한다.
- 파일, 연습 컨텍스트, AI 코칭, 대화를 결과 삭제·계정 삭제 흐름에 연결했다.
- 운영 상태, 저장 공간, 큐, 워커, Ollama를 readiness에서 점검할 수 있다.
- 단위 테스트, 프론트 테스트, E2E, CI, 운영 체크리스트가 갖춰져 있다.

---

## 12. 개선이 필요한 부분

### 디렉토리 구조

- `Back/app/services/`에 분석, DB, AI, 운영 서비스가 많이 모여 있어 기능 수가 더 늘면 탐색이 어려워질 수 있다.
- 일부 분석 작업 DB 접근은 `services/analysis_jobs.py`, 일부는 `repositories/`에 있어 저장소 계층의 기준이 일관되지 않다.
- `Back/main.py`와 `Back/app/main.py` 두 진입 파일이 있어 실행 진입점을 혼동할 수 있다.

### 파일 분리

- `routers/chat.py`와 `routers/auth.py`가 직접 SQL과 복잡한 수명주기 로직을 많이 포함한다.
- `analysis_worker.py`가 분석 파이프라인과 유지보수·정리 작업을 함께 담당한다.
- `ai_coaching.py`가 입력 구성, 프롬프트, 검증, 저장, 대체 코칭을 모두 담당한다.

### API 설계

- `/api/...`와 `/analyze/...` 두 경로 규칙이 혼합되어 있다.
- API 버전 경로가 없어 향후 호환성 관리가 어렵다.
- 긴 Ollama 채팅 요청은 동기 HTTP 처리이므로 응답 지연과 취소 처리에 한계가 있다.
- 결과 전체·섹션·타임라인 API가 명확히 분리되어 있지만 응답 스키마 문서화가 코드 외부에 충분하지 않다.

### 예외 처리

- 분석 작업은 공용 오류 메시지를 제공해 보안상 안전하지만, 운영자가 오류 원인과 사용자 작업을 연결하려면 구조화된 로그·추적 시스템이 더 필요하다.
- 일부 DB와 파일 시스템 작업은 서로 다른 저장소에 걸쳐 있어 중간 실패 시 보정·정리가 필요하다.
- 외부 프로그램인 ffmpeg, Whisper, MediaPipe, Ollama 장애 유형을 운영 지표로 더 세분화할 수 있다.

### 데이터 저장 방식

- MySQL과 JSON 파일 저장이 혼합되어 단일 트랜잭션으로 일관성을 보장하기 어렵다.
- 로컬 파일 시스템은 다중 백엔드 인스턴스나 분산 워커 환경으로 확장할 때 공유 저장소가 필요하다.
- 연습 컨텍스트와 AI 코칭이 JSON 파일이므로 검색·통계·관리자 조회가 제한된다.
- 결과 JSON 스키마 버전과 마이그레이션 전략을 더 명확히 관리할 필요가 있다.

### 프론트엔드 구조

- 서버 상태는 API 재조회와 폴링 중심이며 서버 푸시 방식은 없다.
- 페이지별 데이터 요청이 늘어나면서 결과 상세 페이지의 부분 실패 상태 관리가 복잡해질 수 있다.
- 모바일 내비게이션과 대규모 결과 데이터 렌더링 성능을 지속적으로 검증해야 한다.

### 유지보수성

- `Back/.env.example`의 알고리즘 버전과 `config.py` 기본값처럼 문서·예시·코드 버전이 달라질 가능성이 있다.
- `docs/PRACTICE_COACHING_OPERATIONS.md`의 프롬프트 버전 설명과 실제 코드 버전이 달라질 수 있어 자동 검증이 필요하다.
- 분석 기준, RAG 문서, 알고리즘 버전의 변경 이력을 하나의 릴리스 정책으로 연결할 필요가 있다.

### 확장성

- 분석 작업은 MySQL 큐를 사용하지만 실행은 FastAPI 프로세스 내부 워커 스레드이므로 다중 인스턴스 운영에 제한이 있다.
- 요청 제한은 단일 프로세스 메모리 기반이므로 여러 백엔드 인스턴스에서 일관되지 않다.
- CPU/GPU 사용량이 큰 Whisper·MediaPipe 작업과 API 서버가 같은 프로세스 자원을 공유한다.
- 로컬 Ollama와 로컬 파일 저장은 단일 서버 환경에는 적합하지만 수평 확장에는 별도 설계가 필요하다.

---

## 13. 추천 개선 방향

### 우선순위 높음

- 분석 워커와 유지보수 작업을 FastAPI 프로세스에서 별도 워커 프로세스로 분리한다.
- Redis/Celery, RQ 또는 동등한 영속 작업 처리 방식을 검토하고 작업 선점·재시도 정책을 명시한다.
- `routers/chat.py`, `routers/auth.py`의 직접 SQL과 복잡한 업무 로직을 서비스·저장소 계층으로 분리한다.
- MySQL 요약과 상세 JSON 사이의 일관성 점검·복구 작업을 정기 운영 작업으로 명확히 만든다.
- 운영 환경에서 업로드·결과·코칭 파일을 공유 객체 저장소 또는 공유 파일 저장소로 이전할 수 있는 인터페이스를 만든다.
- 구조화 로그, 요청 ID, 작업 ID, 사용자 ID를 연결한 오류 추적과 운영 메트릭을 추가한다.
- `.env.example`, 운영 문서, 알고리즘·프롬프트 버전 설명이 실제 코드와 일치하는지 CI에서 검사한다.

### 우선순위 중간

- `/api/v1` 등 일관된 API 버전과 경로 규칙을 도입한다.
- 분석 결과 JSON, 연습 컨텍스트, AI 코칭에 명시적 스키마 버전을 추가한다.
- Pydantic 응답 모델과 OpenAPI 설명을 보강해 프론트엔드·백엔드 계약을 명확히 한다.
- 연습 컨텍스트와 AI 코칭 메타데이터를 DB로 이전해 검색·통계·관리 기능을 강화한다.
- 결과 상세 데이터 요청을 전용 데이터 계층 또는 서버 상태 관리 라이브러리로 정리한다.
- Ollama 채팅의 스트리밍 응답과 실제 서버 측 취소를 지원한다.
- 사용자별·기능별 요청 제한을 Redis 등 공유 저장소 기반으로 변경한다.

### 우선순위 낮음

- 서비스 디렉토리를 `analysis`, `coaching`, `storage`, `operations` 하위 패키지로 재구성한다.
- 프론트엔드 결과 차트와 대규모 메시지 목록의 렌더링 최적화를 적용한다.
- 모델·분석 알고리즘별 성능 비교와 회귀 검증용 기준 영상 데이터셋을 구축한다.
- 관리자 화면에 작업 실패 유형, 처리 시간, 저장 공간, 모델 상태 추이 차트를 추가한다.
- Docker 또는 컨테이너 기반 개발 환경을 제공해 MySQL·Ollama·백엔드·프론트 실행 조건을 표준화한다.

---

## 14. 신규 개발자가 보면 좋은 순서

1. `README.md`
   - 서비스 목적, 실행 순서, 데이터 저장과 검증 방법을 먼저 파악한다.
2. `docs/PROJECT_STRUCTURE.md`
   - 전체 폴더와 기능 연결 관계를 이해한다.
3. `Front/src/app/AppRoutes.jsx`
   - 사용자가 이용하는 전체 페이지 흐름을 확인한다.
4. `Front/src/pages/UploadPage.jsx`
   - 분석 시작 시 사용자 입력과 업로드 흐름을 확인한다.
5. `Back/app/main.py`
   - 백엔드 진입점, 미들웨어, 라우터, 워커 시작 구조를 확인한다.
6. `Back/app/routers/analyze.py`
   - 업로드, 작업 상태, 결과 조회·삭제 API를 확인한다.
7. `Back/app/workers/analysis_worker.py`
   - 실제 분석 파이프라인의 처리 순서를 이해한다.
8. `Back/app/services/score_calculator.py`
   - 점수와 측정 불가 처리 기준을 이해한다.
9. `Back/app/services/practice_coaching.py`
   - 규칙 기반 연습 코칭과 발표 목적별 차이를 확인한다.
10. `Back/app/services/ai_coaching.py`
    - 시스템 점수와 AI 해석의 분리, 근거 검증, 대체 코칭을 이해한다.
11. `Back/app/routers/chat.py`
    - 일반 채팅과 발표 문맥 채팅의 저장·Ollama 흐름을 확인한다.
12. `Back/migrations/`
    - 사용자, 분석 작업, 채팅 데이터 구조를 이해한다.
13. `Front/src/pages/ResultDetailPage.jsx`
    - 분석 결과와 코칭이 화면에서 어떻게 합쳐지는지 확인한다.
14. `docs/TEST_CRITERIA.md`
    - 기능 변경 시 반드시 유지해야 할 동작을 확인한다.
15. `scripts/verify.sh`
    - 변경 후 기본 전체 검증 방법을 확인한다.

### 처음 작업하기 전에 확인할 사항

- 백엔드는 `Back` 디렉토리에서 `uvicorn app.main:app`으로 실행한다.
- MySQL 마이그레이션이 모두 적용되어 있어야 한다.
- 영상 분석에는 ffmpeg와 Python 분석 의존성이 필요하다.
- AI 코칭·채팅에는 Ollama와 설정된 모델이 필요하지만, Ollama 장애가 기본 분석 결과를 막아서는 안 된다.
- 기존 결정적 점수 계산과 과거 결과의 알고리즘 버전을 임의로 변경하지 않는다.
- 기능 변경 후에는 최소 `./scripts/verify.sh`를 실행하고, 서비스 흐름 변경이면 통합·E2E 검증도 수행한다.
