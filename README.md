# AI Presentation Coach

Spring Boot 백엔드, React 프론트엔드, Python 분석 엔진, Python Video LLM 엔진을 분리해 구성한 발표 영상 분석 프로젝트입니다.

사용자는 발표 영상을 업로드하고, 백엔드는 업로드된 영상을 기준으로 기본 분석 엔진과 Video LLM 엔진을 호출합니다. 이후 분석 결과를 축약하고 OpenAI 피드백 생성 단계까지 거쳐 최종 결과를 JSON으로 저장합니다.

기본 분석 엔진(analysis-engine)은 OpenCV로 1초 간격 샘플 프레임과 오디오를 추출하고 MediaPipe Pose Landmarker로 자세·제스처를 검출합니다. 이때 단계별 처리 로그(`analysisTrace`)와, 샘플 프레임 위에 스켈레톤을 그린 오버레이 이미지를 함께 만들어, 결과 상세 화면에서 "어떻게 분석했는지"를 보여줍니다.

> **현재 범위:** 이 저장소는 학생 프로젝트의 로컬/통제된 테스트 시연용입니다. 공개 도메인이나 production 호스트에 배포하지 않으며, GHCR 릴리스·상시 모니터링·원격 백업·상용 SMTP·결제/요금제는 현재 완료 기준에 포함하지 않습니다. 기존 운영 설정과 문서는 학습 이력용 참고 자료입니다.

현재 완성 기준은 로컬에서 MySQL/Redis/MinIO와 네 애플리케이션 서비스를 실행하고, 업로드→비동기 분석→진행 상태→결과 조회 흐름을 재현하는 것입니다. 인증·소유권·파일 보호·외부 AI 전송 고지는 테스트용이라도 유지합니다.

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
│   ├── requirements-base.txt        (mock/external-api, 기본)
│   ├── requirements-real-model.txt  (local-model 전용, torch/transformers 등)
│   └── requirements-test.txt        (pytest 실행용, requirements-base.txt 포함)
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
redis (선택)        : localhost:6379
```

Redis는 분석 진행률(%)을 잠깐 보여주기 위한 캐시 용도로만 사용합니다. 실행하지 않아도
분석 자체는 정상 동작하며, 이 경우 진행률 화면은 저장된 상태 기준으로 대략적인 값만
보여줍니다.

기본 분석 구간(`BASIC_ANALYZING`) 동안에는 analysis-engine이 공유 `/storage` 볼륨의
`storage/temp/{jobId}/progress.json`에 현재 세부 단계를 기록하고, 백엔드가 이를 읽어
진행률 응답에 `basicAnalysisStep`(현재 단계 번호/전체 단계 수/라벨)을 채워줍니다. 이
파일은 분석이 끝나면 temp 디렉터리와 함께 삭제됩니다.

## 3. 실행 순서

전체 기능을 정상적으로 사용하려면 아래 순서로 실행합니다.

1. (선택) Redis 실행
2. analysis-engine 실행
3. video-llm-engine 실행
4. backend 실행
5. frontend 실행

analysis-engine, video-llm-engine, backend는 같은 내부 공유 키를 사용해야 합니다.
로컬 개발에서는 아래 예시처럼 같은 값을 세 터미널에 모두 설정하세요.

```bash
export INTERNAL_ENGINE_API_KEY=local-dev-shared-key
```

## 3-1. Redis 실행 (선택)

```bash
# 도커를 사용하는 경우 (분석 진행률만 잠깐 저장하는 용도라 영속화 옵션은 꺼둡니다)
docker run -d --name hanium-redis -p 6379:6379 --restart unless-stopped \
    redis:7-alpine redis-server --save "" --appendonly no
# 중지: docker stop hanium-redis && docker rm hanium-redis

# 로컬에 설치한 경우 (예: macOS)
brew install redis
brew services start redis
```

## 4. analysis-engine 실행

```bash
cd ~/Desktop/hanium\ project/analysis-engine

python3 -m venv .venv
source .venv/bin/activate

pip install -r requirements.txt

export INTERNAL_ENGINE_API_KEY=local-dev-shared-key
export LOG_DIR=../storage/logs
uvicorn app.main:app --reload --port 8001
```

`INTERNAL_ENGINE_API_KEY`가 비어 있으면 analysis-engine의 `/api/**` 분석 요청은 401로 거부됩니다.
이 값은 backend 실행 터미널의 `INTERNAL_ENGINE_API_KEY`와 반드시 같아야 합니다.
`LOG_DIR`을 지정하면 analysis-engine 로그가 콘솔과 함께 `${LOG_DIR}/analysis-engine.log`에도 기록됩니다.

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

# 현재는 mock 응답만 반환하므로 requirements-base.txt(가벼움)만으로 충분합니다.
# 실제 로컬 모델(torch/transformers)을 구동하려면 requirements-real-model.txt를 대신 설치하세요.
pip install -r requirements-base.txt

export INTERNAL_ENGINE_API_KEY=local-dev-shared-key
export LOG_DIR=../storage/logs
uvicorn app.main:app --reload --port 8002
```

`INTERNAL_ENGINE_API_KEY`가 비어 있으면 video-llm-engine의 `/api/**` 분석 요청은 401로 거부됩니다.
이 값은 backend 실행 터미널의 `INTERNAL_ENGINE_API_KEY`와 반드시 같아야 합니다.
`LOG_DIR`을 지정하면 video-llm-engine 로그가 콘솔과 함께 `${LOG_DIR}/video-llm-engine.log`에도 기록됩니다.

`docker compose build` 시에는 `VIDEO_LLM_BACKEND`(mock/external-api/local-model) 빌드 인자로
같은 선택을 할 수 있습니다. 자세한 내용은 `.env.example`과 `video-llm-engine/Dockerfile`을 참고하세요.

### 5.1 Video LLM 실제 모델 활성화

기본 정책은 실제 외부 모델을 호출하지 않는 `VIDEO_LLM_POLICY=DISABLED`입니다. 기존
`VIDEO_LLM_ENABLED`는 이전 배포 호환용이며, 정책 값이 비어 있을 때만
`true=DEGRADED`, `false=DISABLED`로 해석됩니다. 새 배포에서는 아래 정책을 명시해야 합니다.

| 환경변수 | 위치 | 의미 |
| --- | --- | --- |
| `VIDEO_LLM_POLICY` | 루트 `.env` 또는 video-llm-engine 실행 환경 | `STRICT`: 실제 호출 실패 시 502로 작업 실패, `DEGRADED`: 실패 시 명시적 FALLBACK 샘플 결과, `DISABLED`: 실제 호출 없이 MOCK 결과. 운영 정확성을 우선하면 `STRICT`를 권장합니다. |
| `VIDEO_LLM_ENABLED` | 같은 위치 | 이전 배포 호환용입니다. `VIDEO_LLM_POLICY`가 비어 있을 때만 정책을 유도합니다. |
| `NVIDIA_API_KEY` | video-llm-engine 실행 환경 | NVIDIA API Catalog(build.nvidia.com)에서 발급받은 `nvapi-` 키입니다. 정책이 STRICT/DEGRADED인데 비어 있으면 엔진이 기동 단계에서 실패합니다. |
| `NVIDIA_VIDEO_LLM_MODEL` | video-llm-engine 실행 환경 | 기본값은 `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning`입니다. API Catalog의 모델 ID가 바뀐 경우에만 덮어씁니다. |
| `NVIDIA_API_BASE_URL` | video-llm-engine 실행 환경 | 기본값은 `https://integrate.api.nvidia.com/v1`입니다. |
| `NVIDIA_ASSET_API_BASE_URL` | video-llm-engine 실행 환경 | 180KB 초과 영상을 업로드하는 NVCF Asset API base URL입니다. 기본값은 `https://api.nvcf.nvidia.com/v2/nvcf`입니다. |
| `NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS` | video-llm-engine 실행 환경 | 외부 영상 분석 호출 timeout입니다. 기본값은 `120`초입니다. |
| `VIDEO_LLM_MAX_VIDEO_SIZE_MB` | video-llm-engine 실행 환경 | 다운로드·로컬 파일·NVIDIA Asset 업로드에 허용할 단일 영상 최대 크기입니다. backend 업로드 상한과 같은 `500`MB가 기본입니다. |
| `VIDEO_LLM_CHUNK_DURATION_SECONDS` | backend와 video-llm-engine 공통 | 긴 영상을 나누는 구간 길이이자 월간 예산 예약 단위 계산 기준입니다. 두 서비스에 반드시 같은 값을 주입해야 하며 기본값은 `100`초입니다. |
| `VIDEO_LLM_MONTHLY_RATE_LIMIT_CAPACITY` | backend 실행 환경 또는 루트 `.env` | NVIDIA 실제 세그먼트 호출 월간 예산 가드입니다. 기본값은 `500`회입니다. 정확한 NVIDIA 무료 한도가 아직 확인되지 않아 보수적으로 둔 추정값입니다. |
| `VIDEO_LLM_MONTHLY_RATE_LIMIT_REFILL_MINUTES` | backend 실행 환경 또는 루트 `.env` | 월간 카운터 refill 주기입니다. 기본값은 `44640`분입니다. |

로컬에서 직접 실행할 때는 video-llm-engine 터미널에 다음처럼 지정합니다.

```bash
cd ~/Desktop/hanium\ project/video-llm-engine

export INTERNAL_ENGINE_API_KEY=local-dev-shared-key
export VIDEO_LLM_POLICY=STRICT
export VIDEO_LLM_ENABLED=true
export NVIDIA_API_KEY=nvapi-...
export NVIDIA_VIDEO_LLM_MODEL=nvidia/nemotron-3-nano-omni-30b-a3b-reasoning
export NVIDIA_API_BASE_URL=https://integrate.api.nvidia.com/v1
export NVIDIA_ASSET_API_BASE_URL=https://api.nvcf.nvidia.com/v2/nvcf
export NVIDIA_VIDEO_LLM_TIMEOUT_SECONDS=120
export VIDEO_LLM_CHUNK_DURATION_SECONDS=100
uvicorn app.main:app --reload --port 8002
```

backend의 월간 호출 한도는 backend 터미널 또는 루트 `.env`에서 설정합니다.

```bash
export VIDEO_LLM_MONTHLY_RATE_LIMIT_CAPACITY=500
export VIDEO_LLM_MONTHLY_RATE_LIMIT_REFILL_MINUTES=44640
```

로컬 Compose에서 실제 모델 호출을 선택적으로 테스트하려면 루트 `.env`에
`VIDEO_LLM_POLICY=STRICT`(또는 fallback 테스트용 `DEGRADED`), `VIDEO_LLM_ENABLED=true`,
`NVIDIA_API_KEY`, 필요한 `NVIDIA_*` override, `VIDEO_LLM_MONTHLY_RATE_LIMIT_*` 값을 채웁니다.
키는 저장소에 커밋하지 마세요. 테스트 전에 외부 AI 전송 동의를 확인하고,
`GET /api/internal/readiness`의 `ready=true`, `mode=REAL`, `realModelReady=true`를 확인하세요.
이 상세 응답을 공개 프론트나
외부 네트워크에 노출하면 안 됩니다. `health`가 `up`이어도 readiness가 `ready=false`이면 실제
모델 설정은 아직 완료되지 않은 상태입니다.

비용과 한도 측면에서는 backend가 실제 Video LLM 호출 전에
`ceil(durationSec / VIDEO_LLM_CHUNK_DURATION_SECONDS)`만큼 월간 카운터를 원자적으로
예약합니다. 영상 길이를 다시 확인하지 못하면 업로드 최대 길이(기본 30분)를 기준으로
18회를 보수적으로 예약합니다. 일일 사용자 한도는 NVIDIA 세그먼트 수가 아니라 분석 작업
건수 기준을 유지합니다.
`VIDEO_LLM_MONTHLY_RATE_LIMIT_CAPACITY`를 초과하면 NVIDIA 호출을 보내지 않고 Video LLM 분석을
생략하며, 결과 화면에는 `SKIPPED` 상태와 생략 사유가 표시됩니다. 이 동작은 서비스 장애를 막는
안전장치이지, NVIDIA의 실제 무료 rate limit이 500회라는 뜻은 아닙니다.

알려진 품질/운영 한계는 `docs/service-plan/video-llm-model-options.md`에 계속 갱신합니다.
2026-07-13 기준으로 120초/1.2MB 샘플까지 성공했지만 NVIDIA 무료 API의 정확한 분당/일당
rate limit과 최대 영상 길이/용량은 공식 수치로 확인되지 않았습니다. 또한 60초 이상 영상에서는
3구간 프롬프트 적용 후 시간 구간화가 개선됐지만, 일부 라벨은 실제 시각 변화보다 프롬프트 구조를
따른 추정일 가능성이 있어 품질 검수 없이 최종 사용자 판단 근거로 과신하면 안 됩니다.

#### 선택적 실제 Video LLM 테스트 전 체크리스트

현재 기본값은 `DISABLED`이며, 실제 활성화 전에 실패 시 전체 작업을 실패시킬지(`STRICT`),
샘플 대체 결과를 허용할지(`DEGRADED`)를 제품 정책으로 결정해야 합니다. 코드는 timeout
(120초 기본)·정책별 실패 처리·월간 비용 상한(`VIDEO_LLM_MONTHLY_RATE_LIMIT_CAPACITY`,
기본 500회)·초과 시 `SKIPPED` 표시까지 갖추고 있습니다.

1. **NVIDIA 무료 엔드포인트의 정확한 rate limit이 아직 공식 문서로 확인되지 않았습니다.**
   `docs/service-plan/video-llm-model-options.md`의 라이브 테스트는 4초 영상 10회 연속 호출까지만
   429 없이 성공한 것을 확인했을 뿐, 실제 분당/일당 한도나 유료 전환 조건은 모릅니다. 테스트
   횟수를 보수적으로 제한하고, 필요하면 추가 rate limit 스트레스 테스트(예:
   1시간 동안 100회 호출)를 먼저 진행하는 것을 권장합니다.
2. **영상 길이 상한(`VIDEO_MAX_DURATION_MINUTES`, 기본 30분)이 NVIDIA 실측 성공 범위(120초)보다
   훨씬 큽니다.** 120초를 넘는 영상에서 NVIDIA 호출이 성공하는지, 실패해 mock으로 계속
   처리되는지는 확인되지 않았습니다. 긴 영상으로 실제 모델을 테스트할 경우, 실제 업로드
   허용 길이 상한과 NVIDIA 검증 범위를 맞추거나(예: Video LLM 전용 별도 길이 제한 도입), 긴
   영상은 처음부터 mock/SKIPPED로 처리되는 것을 감수할지 결정해야 합니다.
3. **시간 구간화 품질 한계가 사용자에게 어떻게 노출되는지 확인이 필요합니다.** 3구간 프롬프트
   적용 후에도 일부 라벨은 실제 시각 변화보다 프롬프트 구조를 따른 추정일 가능성이 있습니다
   (`docs/service-plan/video-llm-model-options.md` 205~207번 줄 표 참고). 결과 화면에 이 한계를
   그대로 안내할지, 추가 품질 검수 후 켤지 결정해야 합니다.
4. **월간 비용 상한(`VIDEO_LLM_MONTHLY_RATE_LIMIT_CAPACITY`, 기본 500회)이 실제 예상 트래픽
   대비 적정한지 재검토가 필요합니다.** 이 값은 NVIDIA의 실제 무료 한도를 아는 상태에서 정한
   것이 아니라, OpenAI 기본값보다 보수적으로 잡은 추정값입니다. 카운터 자체는 긴 영상의
   예상 세그먼트 호출 수만큼 예약하도록 보완됐습니다.

실제 영상을 NVIDIA로 전송하기 전에 `frontend/src/pages/PrivacyPage.jsx`의
"외부 AI 호출" 안내를 사용자에게 보여주고 명시적 동의를 확인해야 합니다.

### 5.2 비밀번호 재설정 이메일 발송 활성화

비밀번호 재설정은 `POST /api/auth/password-reset/request`로 이메일을 요청하고,
`/reset-password?token=...` 화면에서 새 비밀번호를 확정하는 흐름입니다. 토큰 원문은 DB에 저장하지
않고 SHA-256 해시만 저장하며, 기본 만료 시간은 30분입니다. 요청 API는 이메일 존재 여부와 무관하게
항상 같은 성공 메시지를 반환해 계정 존재 여부가 노출되지 않도록 합니다.

| 환경변수 | 의미 |
| --- | --- |
| `SMTP_HOST` | 선택적 SMTP 테스트 호스트입니다. 비어 있으면 로컬 로그 폴백을 사용합니다. |
| `SMTP_PORT` | SMTP 포트입니다. 기본값은 `587`입니다. |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | SMTP 인증 계정입니다. |
| `SMTP_AUTH` | SMTP auth 사용 여부입니다. 기본값은 `true`입니다. |
| `SMTP_STARTTLS_ENABLE` | STARTTLS 사용 여부입니다. 기본값은 `true`입니다. |
| `MAIL_FROM_ADDRESS` | 발신자 주소입니다. 기본값은 `no-reply@example.com`입니다. |
| `PASSWORD_RESET_URL_BASE` | 메일이나 로그에 들어갈 프론트 재설정 URL입니다. 로컬 기본값은 `http://localhost:5173/reset-password`입니다. |
| `PASSWORD_RESET_TOKEN_EXPIRATION_MINUTES` | 재설정 토큰 만료 시간입니다. 기본값은 `30`분입니다. |
| `PASSWORD_RESET_RATE_LIMIT_CAPACITY` / `PASSWORD_RESET_RATE_LIMIT_REFILL_MINUTES` | 재설정 요청 rate limit입니다. 기본값은 10분당 5회입니다. |

로컬에서는 `SMTP_HOST`를 비워 두고 backend 로그의
`PASSWORD_RESET_DEV_FALLBACK 개발용 폴백` 링크로 흐름을 검증합니다. 실제 SMTP 발송은
현재 프로젝트 완료 기준이 아닙니다.

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

export INTERNAL_ENGINE_API_KEY=local-dev-shared-key
./gradlew bootRun
```

backend는 `application.yaml`의 `external.analysis-engine.api-key`,
`external.video-llm-engine.api-key` 값을 `${INTERNAL_ENGINE_API_KEY:}`에서 읽습니다.
analysis-engine/video-llm-engine 터미널과 같은 값을 설정해야 분석 요청이 401로 막히지 않습니다.

정상 확인:

```bash
curl http://localhost:8080/api/health
curl http://localhost:8081/actuator/health
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

로그인 사용자의 기능 가용성 확인:

```bash
curl --cookie "access_token=<JWT>" http://localhost:8080/api/status
```

`/api/status`는 사용자 영향만 `AVAILABLE`/`DEGRADED`/`UNAVAILABLE`로 반환하며 내부 엔진 URL, 인증 상태, 모델 정책과 원본 오류는 노출하지 않습니다. 엔진·데이터 계층의 기술 상태는 로컬 로그와 아래 개발용 메트릭으로 확인합니다.

### 6.1 로컬 개발용 메트릭 엔드포인트

Actuator와 Prometheus 메트릭은 메인 API 포트(기본 8080)가 아니라 관리 포트(기본 8081)에서만 노출됩니다. 로컬에서 `./gradlew bootRun`으로 실행하면 `http://localhost:8081/actuator/health`, `http://localhost:8081/actuator/prometheus`로 확인할 수 있습니다.

`docker-compose.yml`에서는 관리 포트 8081을 호스트 `ports`에 등록하지 않습니다. 같은 Docker 네트워크 내부의 nginx 또는 향후 Prometheus 컨테이너에서만 `http://backend:8081/actuator/prometheus`로 스크레이핑하도록 구성합니다.

JVM/HTTP 기본 메트릭 외에 분석 작업과 외부 연동 상태를 확인하는 커스텀 메트릭이 노출됩니다.

| 메트릭 | 종류 | 태그 | 의미 |
|---|---|---|---|
| `analysis.job.started` | Counter | `trigger` = `run` \| `retry` | 분석 실행/재시도가 접수된 횟수 |
| `analysis.job.completed` | Counter | 없음 | 분석 파이프라인이 성공적으로 완료된 횟수 |
| `analysis.job.failed` | Counter | `reason` = `upload-not-found` \| `business` \| `unexpected` | 분석이 실패로 끝난 횟수(사유별) |
| `analysis.job.cancelled` | Counter | 없음 | 사용자 취소 요청으로 중단된 횟수 |
| `analysis.job.duration` | Timer | `outcome` = `completed` \| `failed` \| `cancelled` | 분석 파이프라인 소요 시간(종료 결과별) |
| `engine.readiness.check` | Counter | `engine` = `analysis` \| `video_llm`, `outcome` = `ready` \| `not_ready` \| `unauthenticated` \| `unreachable` | 인증된 `/api/status` 조회에서 backend가 엔진 readiness를 확인한 결과 |
| `result.data_issue` | Counter | `source` = `list` \| `detail`, `issue` | 결과 조회 중 감지된 누락 영상/누락 결과 파일/placeholder 결과 건수 |
| `video_llm.generation` | Counter | `mode` = `REAL` \| `FALLBACK` \| `MOCK` \| `UNKNOWN` | Video LLM 결과가 실제 모델/폴백/mock 중 어떤 경로로 생성됐는지 |
| `video_duration_probe.result` | Counter | `outcome`, `reason` | ffprobe 영상 길이 확인 성공/실패 및 fail-open 사유 |

기본값은 ffprobe 실패 시 업로드를 허용합니다. 엄격한 테스트가 필요하면 `VIDEO_DURATION_PROBE_REQUIRED=true`로 설정해 재생 시간 확인 실패를 업로드 실패로 처리하세요.

메트릭 수집 구성은 현재 필수 실행 범위가 아닌 학습/선택 자료입니다. 필요할 때만 모니터링 오버레이로 Prometheus 컨테이너를 함께 띄웁니다. (기본 `docker compose up`에는 포함되지 않습니다.)

```bash
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up -d prometheus
```

Prometheus UI는 `http://127.0.0.1:9090`에서 확인할 수 있습니다. Alertmanager, Grafana,
Blackbox Exporter와 기존 경보 규칙은 이전 운영 관제 설계를 보존한 학습 자산이며,
현재 CI와 프로젝트 완료 기준에서는 검증하지 않습니다. 로컬 분석 진행 확인은
아래 로그 감시 스크립트를 기본 도구로 사용합니다.

### 6.2 로그

로그 형식은 Spring 프로필에 따라 달라집니다.

- **local**: 사람이 읽기 쉬운 평문 로그를 콘솔에만 출력합니다.
- **dev/prod**: JSON 구조화 로그를 콘솔과 파일(`storage/logs/backend.log`, `STORAGE_LOG_PATH`로 변경 가능)에 함께 남깁니다. 파일은 일자별로 롤링되며 파일당 최대 100MB, 보관 기간 30일, 전체 보관 용량 1GB로 제한됩니다. docker-compose에서는 `./storage:/storage` 볼륨이 이미 마운트되어 있어 호스트의 `storage/logs/`에서 바로 확인할 수 있습니다.

JSON 로그에는 두 가지 추적용 필드가 자동으로 포함됩니다.

- `requestId`: HTTP 요청 단위 상관관계 ID. 클라이언트가 `X-Request-Id` 헤더를 보내면 그 값을 쓰고, 없으면 서버가 생성해 응답 헤더로 돌려줍니다.
- `jobId`: 분석 작업 단위 ID. 백그라운드 분석 파이프라인의 모든 로그에 붙습니다.

특정 요청이나 분석 작업의 로그만 골라 보려면:

```bash
# 특정 분석 작업의 로그만 추적
grep '"jobId":"<jobId>"' storage/logs/backend.log | jq .

# 특정 요청의 로그만 추적
grep '"requestId":"<requestId>"' storage/logs/backend.log | jq .
```

분석 중인 단계만 여러 서비스에서 모아 사람이 읽기 쉬운 한 줄 형식으로 실시간 확인하려면
루트 디렉터리에서 다음 스크립트를 실행합니다.

```bash
# 특정 업로드 응답에서 받은 jobId만 실시간 추적
./scripts/watch-analysis-logs.sh 20260812051828-03f8a97d

# 최근 10분 동안 시작된 모든 분석 작업을 함께 추적
./scripts/watch-analysis-logs.sh --all --since 10m

# 과거 500줄만 조회하고 종료
./scripts/watch-analysis-logs.sh --job 20260812051828-03f8a97d --tail 500 --no-follow
```

스크립트는 `backend`, `analysis-worker`, `analysis-engine`, `video-llm-engine`의 Compose 로그를
합치고 `jobId`를 기준으로 필터링합니다. 원본 JSON/평문 로그를 변경하지 않고 화면 표시만
`[시간] [서비스] [jobId] [단계] 메시지`로 정규화하며, TTY에서는 단계별 색상을 사용합니다.
`NO_COLOR=1` 또는 `--no-color`로 색상을 끌 수 있습니다. `--no-follow` 과거 조회는 서비스가
달라도 Docker 기록 시각을 기준으로 정렬하며, 실시간 추적은 버퍼링 없이 도착 즉시 표시합니다.

```text
[05:18:29] [BACKEND  ] [20260812051828-03f8a97d] [QUEUE         ] 실행을 접수(QUEUED)했습니다.
[05:18:31] [BASIC    ] [20260812051828-03f8a97d] [BASIC 4/9     ] 4/9 음성을 텍스트로 변환(STT)하는 중...
[05:19:00] [WORKER   ] [20260812051828-03f8a97d] [VIDEO_LLM 40% ] Video LLM 분석 요청을 전송합니다.
[05:19:04] [WORKER   ] [20260812051828-03f8a97d] [DONE 100%     ] 분석 파이프라인이 완료되었습니다.
```

표시 시각은 각 애플리케이션 로그에 기록된 시각을 사용합니다. 운영 서버의 컨테이너 로그가
UTC라면 표시 시각도 UTC이며, 원본 전체 로그와 장기 보관 정책은 기존
`storage/logs/backend.log` 및 서비스별 로그 설정을 그대로 따릅니다. 스크립트는 로그를
읽기만 하며 분석 상태, DB, Redis 진행률을 변경하지 않습니다.

### 6.3 구조적 로그 키워드

backend는 grep으로 상태를 빠르게 확인할 수 있도록 주요 이벤트를 대문자 키워드로 남깁니다.
`grep KEYWORD storage/logs/backend.log`(dev/prod) 또는 콘솔에서 바로 검색하세요.

| 키워드 | 시점 | 확인할 수 있는 것 |
|---|---|---|
| `EXTERNAL_AI_MODE` | 기동 시 1회 | 이 인스턴스가 피드백/코치 LLM을 실제 호출하는가 mock인가 (`feedback=real\|mock(provider=openai\|nvidia)`) |
| `FEEDBACK_LLM_MODE` / `COACH_LLM_MODE` | 피드백/코치 응답 생성 시 | `mode=MOCK` 또는 `mode=FALLBACK`과 사유. 실제 호출 성공은 아래 `*_USAGE`로 확인 |
| `FEEDBACK_LLM_USAGE` / `COACH_LLM_USAGE` / `OPENAI_USAGE` | 실제 LLM 호출 성공 시 | provider, model, 토큰 수. 이 로그가 있으면 그 job은 실제 호출을 했다는 뜻 (`OPENAI_USAGE`는 명명 비대칭 — feedback openai provider 경로) |
| `FEEDBACK_LLM_FALLBACK_TO_MOCK` / `COACH_LLM_FALLBACK_TO_MOCK` | 실제 호출 시도 실패 → mock 전환 | 실패 사유(HTTP 오류, 타임아웃, 필드 누락 등) |
| `FEEDBACK_LLM_RETRY_AFTER_NETWORK_ERROR` | 첫 외부 호출이 콜드 DNS/TLS로 실패 | 1회 재시도 발생 |
| `OPENAI_REUSE` | 재시도 시 이전 실제 응답 재사용 | 실제 호출 없이 기존 성공 결과 재사용 |
| `STAGE_TRANSITION jobId= step= percent=` | 파이프라인 단계 전이마다 | 현재 어느 단계인지(BASIC_ANALYSIS → VIDEO_LLM_ANALYSIS → COMPACT_ANALYSIS → OPENAI_FEEDBACK → RESULT_MERGE → COMPLETED/FAILED) |
| `STARTUP_RECOVERY` | 기동 시, 유실된 실행 중 작업이 있을 때 | 이전 프로세스가 죽으며 남긴 RUNNING 작업을 몇 건 실패 처리했는지 |
| `OBJECT_STORAGE_*` / `MINIO_*` | MinIO 미러링/프리사인 URL/백필 실패 | 로컬 파일은 정상인데 오브젝트 스토리지 반영이 실패한 경우 |
| `ADMIN_ROLE_SYNC_*` | 기동 시 `ADMIN_EMAILS` 목록과 DB 권한 동기화 | 승격/강등된 계정 |

Video LLM 결과가 실제 모델/폴백/mock 중 무엇이었는지는 job 완료 로그
`상태 즉시 반영: COMPLETED (videoLlmGenerationMode=...)`와 집계 메트릭
`video_llm.generation{mode}`(§6.1)로 확인합니다.

## 7. frontend 실행

```bash
cd ~/Desktop/hanium\ project/frontend

npm install
echo "VITE_API_BASE_URL=http://localhost:8080" > .env
npm run dev
```

`.env`(git에 커밋되지 않음)로 `VITE_API_BASE_URL`을 지정하지 않으면 빈 문자열이 기본값이 되어,
브라우저가 API 요청을 backend(8080)가 아니라 frontend 자기 자신(5173)으로 보냅니다. 이 경우
정적 페이지는 정상적으로 보이지만 로그인을 포함한 모든 API 호출이 조용히 실패합니다(`docker
compose`로 실행할 때는 `VITE_API_BASE_URL`이 빌드 인자로 주입되므로 이 문제가 없습니다. 7.1 참고).

브라우저 접속:

```text
http://localhost:5173
```

### 7.1 frontend 컨테이너의 API 호출 경로

frontend가 `/api`를 호출하는 현재 기준은 로컬 구성입니다.

- **`docker-compose.yml`(기본)**: 빌드 시 `VITE_API_BASE_URL=http://localhost:8080`을 주입해,
  브라우저가 backend를 **직접** 호출합니다. frontend 컨테이너 자신의 nginx는 API 요청을
  받지 않습니다.

frontend 컨테이너만 따로 실행할 때 `/api` 요청을 자체 nginx가 받게 하려면
`frontend/nginx.conf.template`의
`BACKEND_API_UPSTREAM` 환경변수로 켜는 선택적 `/api` 프록시가 있습니다.

- 기본값(비어 있음): `/api/**` 요청은 (index.html을 대신 돌려주지 않고) 502/500으로
  명확히 실패합니다. 기본 `docker-compose.yml`은 이 프록시가 필요 없습니다.
- `BACKEND_API_UPSTREAM=backend:8080`처럼 backend와 같은 네트워크의 주소를 지정하면,
  frontend 컨테이너가 자체적으로 `/api`를 그 주소로 프록시합니다. 기본
  `docker-compose.yml`은 브라우저가 backend를 직접 호출하므로 이 값을 설정하지 않습니다.

## 8. 주요 기능

### 8.0 회원가입 / 로그인

경로:

```text
/signup
/login
```

기능:

- 이메일/비밀번호 회원가입
- 로그인 후 JWT 토큰 저장
- 로그인한 사용자만 홈, 업로드, 결과 목록, 결과 상세 화면 접근 가능
- 본인이 업로드한 분석 작업과 결과만 조회/삭제 가능

영상 업로드와 결과 조회를 테스트하기 전에 반드시 `/signup`에서 회원가입한 뒤 `/login`에서 로그인하세요.

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

허용된 `/api/auth/**`와 정확히 일치하는 `/api/health`를 제외한 `/api/**` 요청은 인증이 필요합니다.
인증은 로그인 응답의 `Set-Cookie: access_token=...` HttpOnly 쿠키를 사용합니다.
브라우저 프론트엔드는 `withCredentials` 요청으로 세션을 복구하며, `curl` 같은 수동
클라이언트는 로그인 응답을 쿠키 파일에 저장한 뒤 후속 요청에 같은 쿠키를 전달해야 합니다.
공개 로그인 API는 JavaScript나 응답 본문에 access token을 노출하지 않습니다.

### 9.1 Health Check

```http
GET /api/health
```

인증 없이 호출할 수 있습니다. 기능별 사용자 영향은 인증 후 `GET /api/status`로 확인하며,
내부 엔진 상세 진단은 이 API에 포함되지 않습니다.

### 9.2 인증

```http
POST /api/auth/signup
POST /api/auth/login
```

Request Body:

```json
{
  "email": "user@example.com",
  "password": "password123"
}
```

로그인 성공 시 응답의 `data.user`에 사용자 정보가 반환되고 `access_token` HttpOnly 쿠키가
설정됩니다. 응답 본문에는 `accessToken` 또는 `tokenType`이 포함되지 않으며, 프론트엔드는
토큰을 `localStorage`에 저장하지 않고 쿠키 기반 세션만 사용합니다.

```bash
# 수동 호출 예시: 로그인 쿠키를 저장한 뒤 같은 쿠키 파일을 사용합니다.
curl -c cookies.txt -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"password123"}' \
  http://localhost:8080/api/auth/login
curl -b cookies.txt http://localhost:8080/api/auth/me
```

- 인증 쿠키는 `HttpOnly`, `SameSite=Lax`, `Path=/`로 설정됩니다. localhost HTTP 테스트에서는 `SECURITY_JWT_COOKIE_SECURE=false`를 사용합니다.
- 쿠키가 첨부된 `POST`/`PUT`/`PATCH`/`DELETE` 요청은 `CORS_ALLOWED_ORIGINS`의 정확한 Origin 또는 브라우저의 `Sec-Fetch-Site: same-origin`을 요구합니다. 브라우저 Origin 누락과 `same-site` 교차 origin은 403(`AUTH_ORIGIN_FORBIDDEN`)으로 거부됩니다. 명시적 Bearer 인증과 Fetch Metadata가 없는 비브라우저 클라이언트는 이 쿠키 CSRF 경계를 적용받지 않습니다.
- `GET /api/auth/me`는 세션 확인용 API입니다. 인증된 세션은 사용자 정보를, 익명·만료·무효 세션은 오류 대신 `200`과 `data: null`을 반환합니다. 다른 보호 API는 인증이 없으면 계속 401을 반환합니다.
- 회원가입은 클라이언트 IP 기준으로, 로그인은 이메일과 IP 기준을 모두 적용해 rate limit이 걸립니다. localhost 직접 접속에서는 `SECURITY_CLIENT_IP_TRUST_FORWARDED_HEADERS=false`를 유지하세요.
- Swagger UI와 OpenAPI JSON은 로컬 API 계약 확인을 위해 기본 공개합니다.
- 회원가입 비밀번호는 8~72자이면서 영문자와 숫자를 각각 1자 이상 포함해야 합니다(400 응답으로 거부됩니다). 이 복잡도 규칙은 회원가입에만 적용되며, 로그인 요청의 비밀번호 필드에는 적용되지 않습니다.

### 9.3 영상 업로드

```http
POST /api/analysis/upload
```

인증 필요. 브라우저는 로그인 쿠키를 자동 전송하고, 수동 호출은 로그인 때 저장한 쿠키를 전달합니다.

Form Data:

```text
file: 영상 파일
```

### 9.4 분석 실행

```http
POST /api/analysis/{jobId}/run
```

인증 필요. 브라우저는 로그인 쿠키를 자동 전송하고, 수동 호출은 로그인 때 저장한 쿠키를 전달합니다.

Request Body:

```json
{
  "useVideoLlm": true,
  "useOpenAi": true
}
```

### 9.5 분석 재시도

```http
POST /api/analysis/{jobId}/retry
```

인증 필요. 브라우저는 로그인 쿠키를 자동 전송하고, 수동 호출은 로그인 때 저장한 쿠키를 전달합니다.

Request Body:

```json
{
  "useVideoLlm": true,
  "useOpenAi": true
}
```

### 9.6 실제 Video LLM 재분석

`DEGRADED` 정책에서 `FALLBACK`으로 완료된 본인 소유 결과만, 기존 결과를 덮어쓰지 않고
새 child job으로 재분석합니다.

```http
POST /api/analysis/{sourceJobId}/video-llm-reanalysis
Idempotency-Key: {16~128자 고유 요청 키}
Content-Type: application/json
```

```json
{
  "useOpenAi": true
}
```

최초 접수는 202, 같은 `Idempotency-Key` replay는 같은 child를 200으로 반환합니다.
활성 재분석이 이미 있으면 409, 원본 영상이 만료됐으면 410, Video LLM 한도 초과는 429입니다.
이 경로는 전역 정책이 `DEGRADED`여도 `REAL` 응답만 성공으로 인정하며 FALLBACK/MOCK/SKIPPED는
작업 실패로 기록합니다.

프론트 결과 상세는 저장된 mode가 `FALLBACK`인 완료된 기본 분석에만 재분석 버튼을
표시합니다. 비용·사용 한도 재소비를 확인한 뒤 새 child 상세로 이동하며, 결과 파일이 아직
없는 QUEUED/RUNNING 단계부터 기존 상태 polling을 이어갑니다. 원본과 재분석 상세에는 서로
이동할 수 있는 lineage 링크가 표시됩니다.

### 9.7 분석 상태 조회

```http
GET /api/analysis/{jobId}/status
```

인증 필요. 브라우저는 로그인 쿠키를 자동 전송하고, 수동 호출은 로그인 때 저장한 쿠키를 전달합니다.

### 9.8 결과 목록 조회

```http
GET /api/results
GET /api/results?page=0&size=50
```

인증 필요. 브라우저는 로그인 쿠키를 자동 전송하고, 수동 호출은 로그인 때 저장한 쿠키를 전달합니다.

Query Parameters:

```text
page: 0부터 시작하는 페이지 번호 (기본값 0)
size: 페이지 크기 (기본값 50, 최대 100)
```

### 9.9 결과 상세 조회

```http
GET /api/results/{jobId}
```

인증 필요. 브라우저는 로그인 쿠키를 자동 전송하고, 수동 호출은 로그인 때 저장한 쿠키를 전달합니다.

### 9.10 결과 삭제

```http
DELETE /api/results/{jobId}
```

인증 필요. 브라우저는 로그인 쿠키를 자동 전송하고, 수동 호출은 로그인 때 저장한 쿠키를 전달합니다.

`results/{jobId}/frames/` 아래의 오버레이 프레임도 함께 정리됩니다(로컬 파일 즉시 삭제, MinIO 프리픽스는 삭제 outbox worker가 처리).

### 9.11 분석 진행률 조회

```http
GET /api/analysis/{jobId}/progress
```

인증 필요. Redis 진행률 캐시가 있으면 `percent`/`message`/`step`을, 없으면 저장된 상태 기준의 대략치를 반환합니다. 상태가 `BASIC_ANALYZING`이고 analysis-engine이 `progress.json`을 남겼다면 응답에 아래가 추가됩니다.

```json
{
  "percent": 22,
  "message": "자세(포즈)와 제스처를 분석하는 중...",
  "basicAnalysisStep": {
    "stepNo": 5,
    "totalSteps": 9,
    "stepKey": "pose_gesture",
    "label": "자세(포즈)와 제스처를 분석하는 중..."
  }
}
```

### 9.12 분석 프레임 오버레이 조회

```http
GET /api/results/{jobId}/frames/{fileName}
```

인증 필요(결과 소유자만). `fileName`은 `frame_001.jpg` 형식만 허용합니다. 샘플 프레임 위에 MediaPipe가 검출한 어깨·팔꿈치·손목 골격과 어깨 균형선을 그린 JPEG(가로 640px)를 반환합니다. 프레임 목록과 메타데이터는 결과 상세(`GET /api/results/{jobId}`)의 `result.basicAnalysis.frameGallery` 배열에 있습니다.

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
storage/results/{jobId}/frames/frame_001.jpg ...   # 스켈레톤 오버레이 프레임(최대 20장)
```

`basic-analysis.json`의 `analysisTrace`는 엔진 단계별 처리 로그, `frameGallery`는 저장된 오버레이 프레임의 메타데이터입니다. 프레임 이미지 원본(base64)은 저장 JSON에 포함하지 않고 `frames/` 아래 파일로만 둡니다.

업로드 영상은 아래 경로에 저장됩니다.

```text
storage/uploads/{jobId}/
```

## 12. 전체 테스트 순서

### 12.0 엔진 단위 테스트

analysis-engine의 정량 분석 순수 함수 테스트는 Python 3.13 환경에서 아래처럼 실행할 수 있습니다.

```bash
cd ~/Desktop/hanium\ project/analysis-engine
pip install -r requirements.txt
pytest
```

video-llm-engine의 mock 엔드포인트 테스트는 requirements-test.txt(=requirements-base.txt + pytest + HTTPX2)만
설치하면 되며, torch/transformers 등 무거운 real-model 의존성은 필요 없습니다.

```bash
cd ~/Desktop/hanium\ project/video-llm-engine
pip install -r requirements-test.txt
pytest
```

두 Python 엔진의 `pytest`는 statement/branch coverage를 함께 측정하며 총 coverage가 70% 미만이면
실패합니다. HTML/XML 결과는 각 엔진의 `htmlcov/`, `coverage.xml`에 생성됩니다. 프론트엔드는
`npm run test:coverage`로 statements/branches/functions/lines 70% 하락을 차단합니다.

backend 전체 테스트는 Docker가 있으면 Testcontainers로 MySQL 8.4 Flyway migration과 Redis 7
인증/TTL도 실제 검증합니다. Docker Engine 29 호환 기본 API는 1.44이며, 다른 환경은
`DOCKER_API_VERSION`으로 덮어쓸 수 있습니다. 테스트 결과로 Jacoco와 backend OpenAPI가 각각
`backend/build/reports/jacoco/test/`, `backend/build/contracts/backend-openapi.json`에 생성됩니다.

### 12.1 모든 서버 실행

터미널 1:

```bash
cd ~/Desktop/hanium\ project/analysis-engine
source .venv/bin/activate
export INTERNAL_ENGINE_API_KEY=local-dev-shared-key
export LOG_DIR=../storage/logs
uvicorn app.main:app --reload --port 8001
```

터미널 2:

```bash
cd ~/Desktop/hanium\ project/video-llm-engine
source .venv/bin/activate
export INTERNAL_ENGINE_API_KEY=local-dev-shared-key
export LOG_DIR=../storage/logs
uvicorn app.main:app --reload --port 8002
```

터미널 3:

```bash
cd ~/Desktop/hanium\ project/backend
export INTERNAL_ENGINE_API_KEY=local-dev-shared-key
./gradlew bootRun
```

터미널 4:

```bash
cd ~/Desktop/hanium\ project/frontend
echo "VITE_API_BASE_URL=http://localhost:8080" > .env
npm run dev
```

`.env` 없이 실행하면 `VITE_API_BASE_URL`이 빈 문자열로 기본 적용되어 브라우저가 API 요청을
backend(8080)가 아니라 frontend 자기 자신(5173)으로 보냅니다. 홈 화면은 정상적으로 보이지만
로그인을 포함한 모든 API 호출이 조용히 실패하니, 아래 12.2 브라우저 테스트를 진행하기 전에
반드시 이 `.env`를 만들어야 합니다.

### 12.2 브라우저 테스트

1. http://localhost:5173 접속
2. /signup 이동 후 이메일/비밀번호로 회원가입
3. /login 이동 후 로그인
4. 서버 및 엔진 상태 확인
5. /upload 이동
6. 영상 파일 선택
7. “업로드하고 분석 시작” 클릭
8. 분석 진행 중 새로고침 후 상태·진행률 복구 확인
9. 분석 완료 후 상세 페이지 자동 이동 확인
10. /results 이동
11. 결과 목록 확인
12. 상세 보기 확인
13. 삭제 기능 확인

### 12.3 E2E 자동화 테스트(Playwright)

위 12.2의 수동 확인 항목 중 로그인·라우팅·명암비·콘솔 오류 부분은
`frontend/e2e/*.spec.js`로 자동화되어 있습니다.

- `public-flow.spec.js`: 로그인 불필요한 공개 페이지(홈/요금제/로그인/회원가입/약관 등)의
  라우팅과 렌더링, 그리고 2026-07-16 P0 명암비 회귀(제목이 배경과 구분 안 되는 버그) 같은
  유형을 잡습니다. 프론트 dev 서버만 있으면 되고 백엔드는 필요 없습니다. CI의 `frontend`
  job에서 매 push/PR마다 자동 실행됩니다.
- `auth-api.spec.js`, `protected-pages.spec.js`: 실제 회원가입→로그인→쿠키 인증으로
  `/onboarding`, `/upload`, `/results`, `/account`, `/status`가 로그인으로 튕기지 않고
  정상 렌더링되는지 확인합니다. 실제 backend/DB가 필요하고, **앱과 /api가 같은 출처**여야
  로그인 쿠키가 페이지 탐색 요청에 붙습니다(`localhost`와 `127.0.0.1`은 쿠키 스코프상
  다른 호스트로 취급되니 반드시 `localhost`를 쓰세요). CI의 `frontend-e2e-full-stack`
  job에서 실제 docker compose 스택(MySQL/Redis/MinIO/backend/frontend, `--no-deps`라
  analysis-engine/video-llm-engine은 제외) 앞에서 자동 실행됩니다.
- `analysis-pipeline.spec.js`: 실제 `sample-demo.mp4`를 화면의 단일 CTA로 업로드·접수하고,
  분석 중 브라우저를 새로고침해 상태와 진행률이 복구되는지 확인한 뒤 실제 worker와
  analysis-engine의 완료 결과·영상 접근 토큰까지 검증합니다. 완료 결과의 총점은
  `frontend/e2e/fixtures/sample-demo-golden-v1.json` 기준과 허용 편차도 비교합니다. 외부 Video
  LLM/OpenAI는 비활성화하며 테스트 계정은 종료 시 삭제합니다.

로컬에서 직접 돌리려면:

```bash
# 공개 페이지만(백엔드 불필요, 프론트가 자체 dev 서버를 띄웁니다)
cd ~/Desktop/hanium\ project/frontend
npm run test:e2e -- e2e/public-flow.spec.js

# 로그인 필요 흐름까지(백엔드/DB가 이미 http://localhost:8080, 프론트가 http://localhost:5173에 떠 있어야 함)
E2E_FULL_STACK=true BASE_URL=http://localhost:5173 API_BASE_URL=http://localhost:8080 \
  npm run test:e2e -- e2e/auth-api.spec.js e2e/protected-pages.spec.js

# 실제 분석 파이프라인(전체 스택 기동 시 아래 provider 값을 명시해 로컬 .env 누수를 차단하세요)
OPENAI_ENABLED=false FEEDBACK_LLM_PROVIDER=openai NVIDIA_API_KEY= \
VIDEO_LLM_POLICY=DISABLED VIDEO_LLM_ENABLED=false docker compose up -d --wait

E2E_ANALYSIS_PIPELINE=true BASE_URL=http://localhost:5173 \
API_BASE_URL=http://localhost:8080 E2E_ANALYSIS_MAX_WAIT_MS=900000 \
  npm run test:e2e -- e2e/analysis-pipeline.spec.js
```

`OPENAI_ENABLED=false`만 지정해도 로컬 `.env`가 `FEEDBACK_LLM_PROVIDER=nvidia`와 NVIDIA
키를 제공하면 피드백 단계가 실제 외부 provider를 사용할 수 있습니다. 비용 없는 E2E에서는
provider를 `openai`로 고정하고 OpenAI/NVIDIA 키를 모두 비워야 합니다.

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
storage/backups/*
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

## 14. MySQL 백업

MySQL 데이터는 `mysql-data` Docker 볼륨에 저장되지만, 볼륨 손상이나 실수 삭제에 대비해 `mysqldump` 백업을 별도로 남길 수 있습니다.

기본 `docker compose up`은 백업을 실행하지 않습니다. 백업/복구 학습이 필요한 경우에만
`docker compose --profile ops up -d backup`으로 선택적으로 실행하세요. 실제 영상이나 이메일이
포함된 데이터를 백업했다면 테스트 후 `storage/backups`의 사본도 별도로 삭제하세요.

수동 실행 예시:

```bash
cd ~/Desktop/hanium\ project

DB_HOST=127.0.0.1 \
DB_PORT=3306 \
DB_NAME=hanium_dev \
DB_USERNAME=hanium \
DB_PASSWORD=실제비밀번호 \
BACKUP_DIR=./storage/backups \
BACKUP_RETENTION_DAYS=14 \
./scripts/backup-mysql.sh
```

`docker-compose.yml`은 `127.0.0.1:${DB_PORT:-3306}:3306`으로 MySQL 포트를 로컬 호스트에만 노출하므로, compose로 MySQL을 띄운 뒤 같은 호스트에서 위 스크립트를 실행할 수 있습니다. `BACKUP_RETENTION_DAYS`보다 오래된 같은 DB의 백업 파일은 자동 삭제됩니다. 실행 로그는 stdout과 `storage/logs/backup.log`에 함께 남습니다.

복구 예시:

```bash
DB_HOST=127.0.0.1 \
DB_PORT=3306 \
DB_NAME=hanium_dev \
DB_USERNAME=hanium \
DB_PASSWORD=실제비밀번호 \
./scripts/restore-mysql.sh storage/backups/hanium_dev_YYYYMMDD_HHMMSS.sql.gz
```

`restore-mysql.sh`는 로컬 호스트에 `mysql` CLI가 설치되어 있고 compose MySQL 포트가 `127.0.0.1`로 열려 있는 방식을 전제로 합니다. 대상 DB에 기존 테이블이 있으면 실수로 덮어쓰지 않도록 기본적으로 중단하며, 테스트 DB처럼 덮어써도 되는 대상임을 확인한 경우에만 `--force`를 붙여 실행합니다. 실행 로그는 `RESTORE_LOG_PATH`가 있으면 그 경로에, 없으면 `BACKUP_LOG_PATH` 또는 `storage/logs/restore.log`에 남습니다.

## 14-1. MinIO 오브젝트 스토리지 백필

Phase B1/B2에서 MinIO 이중 쓰기가 도입되기 전에 로컬 디스크(`storage/uploads`, `storage/results`)에만 저장된 기존 파일은 MinIO에 사본이 없습니다. `ObjectStorageBackfillRunner`는 이런 과거 파일만 MinIO로 채워 넣는 1회성 배치이며, `storage.backfill.enabled=true`(환경변수 `STORAGE_BACKFILL_ENABLED=true`)로 명시적으로 켰을 때만 동작하고 끝나면 프로세스가 자동 종료됩니다.

```bash
docker compose run --rm -e STORAGE_BACKFILL_ENABLED=true backend
```

이미 MinIO에 존재하는 오브젝트는 건너뛰므로(idempotent) 중간에 실패해도 다시 실행하면 이어서 진행됩니다. 개별 파일 업로드 실패는 `storage/logs`의 애플리케이션 로그에 `OBJECT_STORAGE_BACKFILL_FILE_FAILED`로 남고 나머지 파일 백필은 계속됩니다. 완료 시 `OBJECT_STORAGE_BACKFILL_DONE` 로그에 uploads/results 각각의 scanned/uploaded/skipped/failed 건수가 남습니다. 평소 `docker compose up`으로 기동할 때는 `STORAGE_BACKFILL_ENABLED`가 기본값 `false`이므로 이 러너는 절대 동작하지 않습니다.

## 15. 의존성 업데이트 자동화

Dependabot은 매주 backend(Gradle), frontend(npm), analysis-engine/video-llm-engine(pip), 4개 Dockerfile, GitHub Actions 의존성을 확인해 업데이트 PR을 자동으로 생성합니다. 이는 CI를 즉시 실패시키는 게이트가 아니라 PR 생성 방식이므로, 실제 병합 여부는 변경 내용과 CI 결과를 사람이 검토해 결정해야 합니다.

CI의 `docker-build` job은 핵심 애플리케이션 이미지(`backend`, `frontend`, `analysis-engine`, `video-llm-engine`)를 빌드한 뒤 Trivy로 컨테이너 OS 패키지 취약점을 스캔합니다. 현재는 `CRITICAL,HIGH` 결과를 로그에 표로 남기는 보고 전용 단계이며, `exit-code: 0`이라 취약점 발견만으로 빌드를 실패시키지 않습니다.

### 15.1 비활성 Release 자료

`docs/archive/operations/release.workflow.yml`과 `docker-compose.release.yml`은 이전에 설계한 GHCR build→push→staging smoke 흐름의 학습 이력용 자료입니다. 릴리스 YAML을 `.github/workflows`에서 제거했으므로 GitHub Actions가 실행하지 않습니다. 이 프로젝트는 이미지를 GHCR에 push하거나 production으로 승격하지 않습니다.

## 16. 현재 구현 범위

현재 구현된 범위:

- Spring Boot 백엔드 기본 API
- 이메일/비밀번호 회원가입 및 로그인
- JWT 기반 API 인증
- 영상 업로드
- 업로드 영상 매직바이트 검증
- 분석 작업 상태 관리
- 분석 작업/결과 소유권 검증
- 외부 Python 엔진 연동(analysis-engine은 OpenCV/MediaPipe/faster-whisper 기반 실제 분석,
  아래 "현재 실제 분석으로 반영된 범위" 참고)
- 내부 엔진 API 키 인증
- Video LLM 분석(기본은 `VIDEO_LLM_POLICY=DISABLED`, STRICT/DEGRADED 정책으로 NVIDIA hosted 모델 실제 연동 가능 — 5.1절 참고)
- OpenAI 피드백 생성(기본은 안전한 mock 폴백, `OPENAI_ENABLED=true`로 실제 API 연동 가능 — "OpenAI 호출 및 비용 제어 정책" 절 참고)
- 결과 JSON 저장
- 결과 목록 조회 및 페이지네이션
- 결과 상세 조회
- 결과 삭제
- 실패 결과 재시도
- 사용자별 요청 제한(rate limiting)
- React 프론트 기본 화면
- 로그인 / 회원가입 화면
- 업로드 화면
- 결과 목록 화면
- 결과 상세 화면
- 엔진 상태 대시보드
- 자동 상태 폴링
- CORS 설정
- README 실행 가이드
- 온보딩 화면 및 비밀번호 재설정(이메일 발송)
- AI 코치 대화(완료된 분석 결과 기반 채팅, 사용자별 일일 한도)
- 관리자 대시보드/사용자 관리/감사 로그
- 관리자 정지·강제 탈퇴·결과 삭제·수동 재큐잉은 대상과 영향을 확인한 뒤 필수 사유와 선택적 인시던트/문의 참조 ID를 입력해야 합니다. 서버가 부여한 `X-Request-Id`와 함께 감사로그에 구조화해 저장하며, 탈퇴 사용자 이메일·토큰·원본 오류 전문은 감사 `detail`에 복사하지 않습니다.
- MySQL + Flyway 마이그레이션, MinIO 오브젝트 스토리지, Redis 기반 rate limiting
- Docker Compose 기반 로컬 전체 실행(운영·모니터링·백업 구성은 학습/선택 자료)

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

MediaPipe Tasks FaceLandmarker 기반 얼굴/시선 분석 (내부 원본 진단용, 사용자 점수 제외)

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

MediaPipe Tasks FaceLandmarker 기반 표정/감정 분석 (내부 원본 진단용, 사용자 점수 제외)

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

```

현재 점수 계산에 반영된 항목 (`weighted-v2`):

```text

totalScore = postureScore * (5 / 12)
           + speechScore * (5 / 12)
           + gestureScore * (1 / 6)

postureScore
- MediaPipe Tasks PoseLandmarker 기반 자세 점수 (Pose Landmark Angle Analysis)

speechScore
- faster-whisper STT 기반 음성 점수 (Speech Rate Analysis)

gestureScore
- MediaPipe Tasks PoseLandmarker 기반 제스처 점수 (Motion Tracking)

시선·표정 분석 원본 데이터는 기존 결과 호환과 내부 진단을 위해 유지하지만,
총점·사용자 점수 계약·AI 피드백 입력·사용자 화면에는 반영하지 않습니다.

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

### OpenAI 호출 및 비용 제어 정책

- `openai.enabled=false`이거나 `OPENAI_API_KEY`가 비어 있으면 실제 API를 호출하지 않고 내부 Mock 피드백으로 동작합니다.
- 실제 API 호출은 `openai.timeout-ms`(`OPENAI_TIMEOUT_MS`, 기본 15000ms)를 요청 read timeout으로 사용합니다.
- timeout, HTTP 에러, 빈 응답, completed가 아닌 응답, 응답 텍스트 누락, JSON 파싱 실패가 발생하면 별도 재시도 없이 즉시 fallback Mock 피드백으로 전환합니다.
- 실제 OpenAI API 응답에 usage 정보가 있으면 backend 로그 파일에 `OPENAI_USAGE` 접두어로 `jobId`, `model`, `inputTokens`, `outputTokens`, `totalTokens`가 남습니다. usage가 없을 때도 `usage=none` 로그가 남습니다.
- 실패한 분석 작업 재시도는 `analysis.retry.max-count`(`ANALYSIS_RETRY_MAX_COUNT`, 기본 3회)까지만 허용됩니다. 초과 시 `/api/analysis/{jobId}/retry`는 `ANALYSIS_RETRY_LIMIT_EXCEEDED` 409 응답을 반환합니다.
- 이번 달 실제 API 호출 횟수가 `rate-limit.openai-monthly.capacity`(`OPENAI_MONTHLY_RATE_LIMIT_CAPACITY`, 기본 1000회)를 초과하면 API를 호출하지 않고 즉시 Mock 피드백으로 전환됩니다(호출 자체가 발생하지 않으므로 실패가 아닙니다). 현재 사용량은 `openai.monthly.usage`, 설정된 한도는 `openai.monthly.budget.capacity` Prometheus 메트릭으로 확인할 수 있고, 사용률이 90%를 5분 이상 초과하면 `OpenAiMonthlyBudgetNearExhaustion` 알림이 발동합니다.

## 결과 목록 생성 상태 표시 및 필터

결과 목록 화면에서 각 분석 결과의 OpenAI 피드백 생성 방식과 Video LLM 시각 분석 생성 방식을 함께 확인할 수 있도록 개선했습니다.

기존에는 결과 상세 화면에 들어가야 AI 피드백이 Mock인지, 실제 OpenAI 응답인지 확인할 수 있었습니다. 이제 목록 카드에서도 OpenAI 생성 방식 배지와 Video LLM 생성 방식 배지가 별도로 표시됩니다.

OpenAI 생성 방식 필터는 OpenAI 피드백의 `generationMode`를 기본으로 보되, 기존 결과 파일처럼 값이 `UNKNOWN`/`-`/빈 값이면 `pipeline.openAiGenerationMode`를 사용합니다. 검색 입력은 파일명/jobId뿐 아니라 OpenAI 메타데이터(`feedback.*`, `pipeline.openAiGenerationMode/openAiModel/openAiFallbackReason`)와 Video LLM 메타데이터(`visualAnalysis.model.*`, `pipeline.videoLlmGenerationMode`, `pipeline.videoLlmAnalysis`)까지 포함합니다.

목록 API(`GET /api/results`)의 각 content 항목은 상세 분석 전체를 싣지 않고, 목록 표시와 검색에 필요한 최소 메타데이터만 포함합니다. 상세 API(`GET /api/results/{jobId}`)도 같은 기준으로 `feedback`, `visualAnalysis.model`, `pipeline`을 정규화해 목록과 상세 화면의 생성 방식 표시가 어긋나지 않도록 합니다. 단, 상세 API의 `visualAnalysis.observations` 같은 세부 관찰 데이터는 보존됩니다. 완료된 작업의 결과 파일이 없거나 비어 있으면 상세 API도 500으로 끊지 않고 `dataIssue=RESULT_DATA_UNAVAILABLE`과 기본 결과 구조를 반환합니다.

- `feedback.generationMode/model/realApiUsed/fallbackReason/overall`
- `visualAnalysis.model.name/version/generationMode`
- `pipeline.videoLlmAnalysis/videoLlmGenerationMode/openAiGenerationMode/openAiModel/openAiRealApiUsed/openAiFallbackReason`
- `visualAnalysis.observations` 같은 세부 관찰 배열은 payload 크기를 줄이기 위해 목록 응답에 포함하지 않습니다.

### OpenAI 피드백 생성 상태

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

### Video LLM 시각 분석 생성 상태

```text

REAL

- 실제 Video LLM 호출에 성공하여 업로드 영상 기반 시각 분석을 생성한 결과

FALLBACK

- 실제 Video LLM 호출을 시도했지만 실패하여 샘플 시각 분석으로 대체된 결과

MOCK

- 실제 Video LLM을 호출하지 않고 샘플 시각 분석으로 생성된 결과

SKIPPED

- 월간 호출 한도 등 정책상 Video LLM 호출을 생략한 결과

UNKNOWN

- 기존 결과 파일에 Video LLM 생성 메타데이터가 없는 경우

```

## AI 코치 대화 제약

AI 코치 대화(`POST /api/results/{jobId}/coach/messages`)는 완료된 분석 작업에서만 사용할 수 있습니다. 또한 OpenAI 호출에 필요한 `compact-analysis.json` 요약 데이터가 있어야 하며, 이 파일이 없거나 비어 있으면 대화 이력 생성, 일일 한도 차감, OpenAI 호출을 수행하지 않고 400 응답으로 중단합니다. 이 경우 결과 상세의 손상 안내를 확인한 뒤 재분석이 필요할 수 있습니다.

### 아직 로컬 테스트·품질 개선이 필요한 범위:

- 선택적 테스트 키를 사용한 Video LLM/OpenAI 실제 호출과 mock/fallback 결과 구분 검증
- 실제 학생 영상을 사용할 경우 명시적 동의, 외부 AI 전송 고지, 테스트 후 데이터 삭제 확인
- 발표 내용 의미 분석 품질 보정
- 점수 기준 보정은 추후 진행 예정

## 16. 기본 실행 체크리스트

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
