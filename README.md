# AI Presentation Coach

Spring Boot 백엔드, React 프론트엔드, Python 분석 엔진, Python Video LLM 엔진을 분리해 구성한 발표 영상 분석 프로젝트입니다.

사용자는 발표 영상을 업로드하고, 백엔드는 업로드된 영상을 기준으로 기본 분석 엔진과 Video LLM 엔진을 호출합니다. 이후 분석 결과를 축약하고 OpenAI 피드백 생성 단계까지 거쳐 최종 결과를 JSON으로 저장합니다.

---

## 1. 프로젝트 구조

```text
hanium project/
├── backend/
│   ├── src/main/java/com/hanium/presentation/
│   ├── src/main/resources/
│   ├── build.gradle
│   └── settings.gradle
│
├── frontend/
│   ├── src/
│   │   ├── api/
│   │   ├── assets/
│   │   ├── components/
│   │   ├── layouts/
│   │   ├── pages/
│   │   ├── routes/
│   │   ├── App.css
│   │   ├── App.jsx
│   │   ├── index.css
│   │   └── main.jsx
│   ├── package.json
│   └── vite.config.js
│
├── analysis-engine/
│   ├── app/
│   │   ├── api/
│   │   └── main.py
│   └── requirements.txt
│
├── video-llm-engine/
│   ├── app/
│   │   ├── api/
│   │   └── main.py
│   └── requirements.txt
│
├── storage/
│   ├── uploads/
│   ├── results/
│   └── temp/
│
├── .gitignore
└── README.md
```

## 2. 서버 구성

```text
frontend           : http://localhost:5173
backend            : http://localhost:8080
analysis-engine    : http://localhost:8001
video-llm-engine   : http://localhost:8002
```

## 3. 실행 순서

전체 기능을 정상적으로 사용하려면 아래 순서로 실행합니다.

1. analysis-engine 실행
2. video-llm-engine 실행
3. backend 실행
4. frontend 실행

## 4. analysis-engine 실행

```bash
cd ~/Desktop/hanium\ project/analysis-engine

python3 -m venv .venv
source .venv/bin/activate

pip install -r requirements.txt

uvicorn app.main:app --reload --port 8001
```

정상 확인:

```bash
curl http://localhost:8001/health
```

예상 응답:

```json
{
  "status": "ok",
  "service": "analysis-engine"
}
```

## 5. video-llm-engine 실행

```bash
cd ~/Desktop/hanium\ project/video-llm-engine

python3 -m venv .venv
source .venv/bin/activate

pip install -r requirements.txt

uvicorn app.main:app --reload --port 8002
```

정상 확인:

```bash
curl http://localhost:8002/health
```

예상 응답:

```json
{
  "status": "ok",
  "service": "video-llm-engine"
}
```

## 6. backend 실행

```bash
cd ~/Desktop/hanium\ project/backend

./gradlew bootRun
```

정상 확인:

```bash
curl http://localhost:8080/api/health
```

예상 응답:

```json
{
  "success": true,
  "message": "백엔드 서버가 정상적으로 실행 중입니다.",
  "data": {
    "service": "backend",
    "status": "ok"
  },
  "timestamp": "..."
}
```

외부 엔진 연결 확인:

```bash
curl http://localhost:8080/api/health/engines
```

## 7. frontend 실행

```bash
cd ~/Desktop/hanium\ project/frontend

npm install
npm run dev
```

브라우저 접속:

```text
http://localhost:5173
```

## 8. 주요 기능

### 8.1 홈 화면

경로:

```text
/
```

기능:

- 백엔드 상태 확인
- analysis-engine 상태 확인
- video-llm-engine 상태 확인
- 업로드 페이지 이동
- 결과 목록 페이지 이동

### 8.2 영상 업로드

경로:

```text
/upload
```

기능:

- 영상 파일 선택
- 영상 업로드
- Video LLM 사용 여부 선택
- OpenAI 피드백 사용 여부 선택
- 분석 실행
- 분석 상태 자동 확인
- 분석 완료 시 결과 상세 페이지로 자동 이동

지원 확장자:

- .mp4
- .mov
- .avi
- .mkv

최대 파일 크기:

```text
500MB
```

### 8.3 결과 목록

경로:

```text
/results
```

기능:

- 전체 분석 결과 목록 조회
- 업로드 완료 / 진행 중 / 완료 / 실패 필터링
- jobId 또는 파일명 검색
- 진행 중 작업 자동 새로고침
- 상세 결과 이동
- 분석 결과 삭제

### 8.4 결과 상세

경로:

```text
/results/{jobId}
```

기능:

- 점수 요약 조회
- 기본 분석 결과 조회
- Video LLM 분석 결과 조회
- 피드백 조회
- 연습 계획 조회
- 타임라인 피드백 조회
- 분석 실패 시 재시도
- 결과 삭제

## 9. 백엔드 주요 API

### 9.1 Health Check

```http
GET /api/health
GET /api/health/engines
```

### 9.2 영상 업로드

```http
POST /api/analysis/upload
```

Form Data:

```text
file: 영상 파일
```

### 9.3 분석 실행

```http
POST /api/analysis/{jobId}/run
```

Request Body:

```json
{
  "useVideoLlm": true,
  "useOpenAi": true
}
```

### 9.4 분석 재시도

```http
POST /api/analysis/{jobId}/retry
```

Request Body:

```json
{
  "useVideoLlm": true,
  "useOpenAi": true
}
```

### 9.5 분석 상태 조회

```http
GET /api/analysis/{jobId}/status
```

### 9.6 결과 목록 조회

```http
GET /api/results
```

### 9.7 결과 상세 조회

```http
GET /api/results/{jobId}
```

### 9.8 결과 삭제

```http
DELETE /api/results/{jobId}
```

## 10. 분석 상태 흐름

```text
UPLOADED
→ BASIC_ANALYZING
→ VIDEO_LLM_ANALYZING
→ COMPACTING
→ OPENAI_GENERATING
→ MERGING_RESULT
→ COMPLETED
```

실패 시:

```text
FAILED
```

## 11. 결과 저장 구조

분석 실행 후 아래 파일들이 생성됩니다.

```text
storage/results/{jobId}/basic-analysis.json
storage/results/{jobId}/video-llm-raw.json
storage/results/{jobId}/video-llm-compact.json
storage/results/{jobId}/openai-feedback.json
storage/results/{jobId}/final-result.json
```

업로드 영상은 아래 경로에 저장됩니다.

```text
storage/uploads/{jobId}/
```

## 12. 전체 테스트 순서

### 12.1 모든 서버 실행

터미널 1:

```bash
cd ~/Desktop/hanium\ project/analysis-engine
source .venv/bin/activate
uvicorn app.main:app --reload --port 8001
```

터미널 2:

```bash
cd ~/Desktop/hanium\ project/video-llm-engine
source .venv/bin/activate
uvicorn app.main:app --reload --port 8002
```

터미널 3:

```bash
cd ~/Desktop/hanium\ project/backend
./gradlew bootRun
```

터미널 4:

```bash
cd ~/Desktop/hanium\ project/frontend
npm run dev
```

### 12.2 브라우저 테스트

1. http://localhost:5173 접속
2. 서버 및 엔진 상태 확인
3. /upload 이동
4. 영상 파일 선택
5. 영상 업로드
6. 분석 실행
7. 분석 완료 후 상세 페이지 자동 이동 확인
8. /results 이동
9. 결과 목록 확인
10. 상세 보기 확인
11. 삭제 기능 확인

## 13. Git에 올리지 않는 항목

아래 항목은 .gitignore에 의해 Git에 포함하지 않습니다.

```gitignore
.env
*.env
node_modules/
frontend/node_modules/
frontend/dist/
backend/build/
backend/.gradle/
analysis-engine/.venv/
video-llm-engine/.venv/
storage/uploads/*
storage/results/*
storage/temp/*
storage/logs/*
.runtime/
Project/
.DS_Store
```

단, 아래 .gitkeep 파일은 디렉토리 유지를 위해 Git에 포함합니다.

```text
storage/uploads/.gitkeep
storage/results/.gitkeep
storage/temp/.gitkeep
storage/logs/.gitkeep
```

## 14. 현재 구현 범위

현재 구현된 범위:

- Spring Boot 백엔드 기본 API
- 영상 업로드
- 분석 작업 상태 관리
- 외부 Python 엔진 Mock 연동
- Video LLM Mock 분석
- OpenAI Mock 피드백
- 결과 JSON 저장
- 결과 목록 조회
- 결과 상세 조회
- 결과 삭제
- 실패 결과 재시도
- React 프론트 기본 화면
- 업로드 화면
- 결과 목록 화면
- 결과 상세 화면
- 엔진 상태 대시보드
- 자동 상태 폴링
- CORS 설정
- README 실행 가이드

현재 실제 분석으로 반영된 범위:



```text

analysis-engine에서 실제 영상 파일 읽기

OpenCV 기반 영상 메타데이터 추출

- 영상 길이
- FPS
- 전체 프레임 수
- 해상도
- 파일 크기

OpenCV 기반 샘플 프레임 추출

- storage/temp/{jobId}/frames/ 경로에 프레임 저장
- 최대 20개 프레임 추출
- 프레임별 timestamp 저장

ffmpeg 기반 오디오 추출

- imageio-ffmpeg 사용
- 영상에서 audio.wav 추출
- storage/temp/{jobId}/audio/audio.wav 저장
- 16000Hz
- mono channel
- pcm_s16le wav 형식으로 변환

faster-whisper 기반 STT 분석

- audio.wav를 텍스트로 변환
- 한국어 음성 인식
- transcript 생성
- segment 단위 발화 구간 생성
- segment별 시작 시간, 종료 시간, 발화 길이, 텍스트 저장
- STT 성공 여부 저장
- 감지 언어와 언어 확률 저장
- STT 실패 시 길이 기반 추정 분석으로 fallback

STT 기반 음성 분석

- STT transcript 기준 단어 수 계산
- STT segment 기준 실제 발화 시간 계산
- segment 사이 공백 기반 침묵 구간 계산
- 1초 이상 공백을 침묵 구간으로 판정
- WPM 계산
- 말하기 속도 점수 계산
- 침묵 횟수 계산
- 침묵 시간 계산
- 침묵 비율 계산
- 침묵 점수 계산
- 음성 점수 계산

STT 기반 필러 분석

- transcript에서 한국어 필러 표현 탐지
- 감지 필러 목록 저장
- 필러별 등장 횟수 저장
- 전체 필러 수 계산
- 전체 단어 수 대비 필러 비율 계산
- 필러 점수 계산
- STT 실패 시 길이 기반 추정값으로 fallback

MediaPipe Tasks PoseLandmarker 기반 자세 분석

- Legacy mp.solutions.pose 미사용
- MediaPipe Tasks API 사용
- pose_landmarker_lite.task 모델 사용
- storage/models/mediapipe/ 경로에 모델 파일 저장
- 포즈 검출률 계산
- 검출 프레임 수 계산
- 좌우 어깨 landmark 추출
- 좌우 팔꿈치 landmark 추출
- 좌우 손목 landmark 추출
- 어깨 높이 차이 계산
- 어깨 균형 점수 계산
- 자세 점수 계산
- 프레임별 자세 분석 결과 저장

MediaPipe Tasks PoseLandmarker 기반 제스처 분석

- Legacy mp.solutions.pose 미사용
- PoseLandmarker 결과의 어깨, 팔꿈치, 손목 landmark 활용
- 양손 손목 visibility 계산
- 손목 위치와 팔 움직임 기반 제스처 감지
- 제스처 감지 프레임 수 계산
- 제스처 비율 계산
- 손 검출률 계산
- 연속 프레임 간 손목 이동량 계산
- 평균 손목 이동량 계산
- 제스처 다양성 점수 계산
- 손 검출 점수 계산
- 손목 움직임 점수 계산
- 제스처 점수 계산
- 프레임별 제스처 분석 결과 저장

MediaPipe Tasks FaceLandmarker 기반 얼굴/시선 분석

- Legacy mp.solutions.face_mesh 미사용
- MediaPipe Tasks API 사용
- face_landmarker.task 모델 사용
- storage/models/mediapipe/ 경로에 모델 파일 저장
- 얼굴 검출률 계산
- 검출 프레임 수 계산
- 눈 중심 좌표 계산
- 코끝 위치 계산
- 코 오프셋 계산
- 시선 방향 추정
- 시선 점수 계산
- 아이컨택 수준 계산
- 입 벌림 정도 계산
- 눈 뜸 정도 계산
- 프레임별 얼굴/시선 분석 결과 저장

MediaPipe Tasks FaceLandmarker 기반 표정/감정 분석

- Legacy Face Mesh 미사용
- FaceLandmarker landmark 기반 표정 특징 계산
- 입 landmark 기반 입 벌림 정도 계산
- 눈 landmark 기반 눈 뜸 정도 계산
- 시선 안정성 점수와 표정 특징 결합
- 프레임별 표정 상태 추정
- neutral, engaged, speaking, low_energy, unknown 상태 분류
- 표정 상태별 프레임 수 집계
- 주요 표정 상태 계산
- 표현력 점수 계산
- 표정 다양성 점수 계산
- 표정/감정 점수 계산
- 프레임별 표정/감정 분석 결과 저장

Spring Boot 결과 병합 반영

- analysis-engine의 pose 결과 수신
- analysis-engine의 gesture 결과 수신
- analysis-engine의 face 결과 수신
- analysis-engine의 emotion 결과 수신
- basicAnalysis.pose에 자세 분석 결과 병합
- basicAnalysis.gesture에 제스처 분석 결과 병합
- basicAnalysis.face에 얼굴/시선 분석 결과 병합
- basicAnalysis.emotion에 표정/감정 분석 결과 병합
- scoreSummary.postureScore 추가
- scoreSummary.gazeScore 추가
- scoreSummary.speechScore 추가
- scoreSummary.gestureScore 추가
- scoreSummary.emotionScore 추가
- 최종 totalScore에 실제 분석 점수 반영
- compact 분석 데이터에 실제 분석 결과 포함
- pipeline 정보에 분석 단계 표시

프론트 상세 화면 실제 분석 결과 표시

- 영상 및 프레임 정보 카드
- 음성 분석 카드
- STT 변환 결과 카드
- Transcript 표시
- STT Segment 테이블 표시
- 필러 분석 카드
- 감지된 필러 표현 테이블 표시
- 자세 분석 카드
- 프레임별 자세 분석 테이블
- 제스처 분석 카드
- 프레임별 제스처 분석 테이블
- 얼굴/시선 분석 카드
- 프레임별 얼굴/시선 분석 테이블
- 표정/감정 분석 카드
- 표정 상태 집계 테이블
- 프레임별 표정/감정 분석 테이블

```

현재 점수 계산에 반영된 항목:

```text

totalScore = postureScore * 0.20
           + gazeScore * 0.20
           + speechScore * 0.30
           + gestureScore * 0.15
           + emotionScore * 0.15

postureScore
- MediaPipe Tasks PoseLandmarker 기반 자세 점수

gazeScore
- MediaPipe Tasks FaceLandmarker 기반 시선 점수

speechScore
- faster-whisper STT 기반 음성 점수

gestureScore
- MediaPipe Tasks PoseLandmarker 기반 제스처 점수

emotionScore
- MediaPipe Tasks FaceLandmarker 기반 표정/감정 점수

```

현재 자동 저장되는 런타임 파일:

```text

storage/temp/{jobId}/frames/

storage/temp/{jobId}/audio/audio.wav

storage/models/mediapipe/pose_landmarker_lite.task

storage/models/mediapipe/face_landmarker.task

storage/results/{jobId}/

```

## 프론트 결과 상세 화면 컴포넌트 분리

기존 `ResultDetailPage.jsx`에 집중되어 있던 결과 상세 화면 코드를 역할별 컴포넌트로 분리했습니다.

분리 목적:

```text

ResultDetailPage.jsx의 비대화 방지

결과 상세 화면 유지보수성 개선

분석 영역별 컴포넌트 독립 관리

차트, 요약, 상세 테이블 영역 분리

추후 화면 수정 시 영향 범위 최소화

```

## OpenAI 피드백 생성 상태 구조

OpenAI 피드백 결과에 생성 방식을 구분하는 상태 필드를 추가했습니다.

## 결과 목록 AI 피드백 생성 상태 표시 및 필터

결과 목록 화면에서 각 분석 결과의 AI 피드백 생성 방식을 바로 확인할 수 있도록 개선했습니다.

기존에는 결과 상세 화면에 들어가야 AI 피드백이 Mock인지, 실제 OpenAI 응답인지 확인할 수 있었습니다.

현재는 결과 목록 카드에서도 `MOCK`, `REAL`, `FALLBACK`, `UNKNOWN`, `FAILED` 상태를 확인할 수 있습니다.

### 표시되는 생성 상태

```text

MOCK

- OpenAI API를 호출하지 않고 내부 Mock 로직으로 생성된 피드백

REAL

- 실제 OpenAI API 호출에 성공하여 생성된 피드백

FALLBACK

- 실제 OpenAI API 호출을 시도했지만 실패하여 Mock 피드백으로 대체된 결과

UNKNOWN

- 기존 결과 파일에 generationMode 필드가 없는 경우

FAILED

- 분석 실패 또는 피드백 생성 실패 상태

```


### 아직 실제 구현이 필요한 범위:

- 실제 Video LLM 모델 연결
- 실제 OpenAI API 호출
- 발표 내용 의미 분석
- 사용자 인증
- DB 영속화 운영 설정
- 배포 설정
- 점수 기준 보정은 추후 진행 예정

## 15. 기본 실행 체크리스트

- analysis-engine 8001 실행 여부 확인
- video-llm-engine 8002 실행 여부 확인
- backend 8080 실행 여부 확인
- frontend 5173 실행 여부 확인
- 브라우저에서 홈 화면 엔진 상태 확인
- 업로드 파일 확장자 확인
- 분석 결과 JSON 생성 확인
- 결과 목록 조회 확인
- 결과 상세 조회 확인

---
