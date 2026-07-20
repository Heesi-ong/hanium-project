# 부하 테스트 (k6)

동시 업로드/분석이 몰릴 때 큐 상한·백프레셔·워커 처리량이 설계대로 동작하는지 수치로 확인하기 위한 k6 시나리오입니다.

## 무엇을 검증하나

`upload-analyze.js`는 실제 사용자 흐름(로그인 → 영상 업로드 → 분석 실행 → 상태 폴링)을 여러 가상 사용자(VU)로 동시에 실행합니다. 핵심 관측 지표:

- `http_req_failed` — 5xx 서버 오류 비율(0에 가까워야 정상). **429(백프레셔)는 실패로 치지 않습니다.**
- `upload_rejected_429` / `run_rejected_429` — 업로드/실행이 큐 상한(`ANALYSIS_QUEUE_MAX_GLOBAL_QUEUED`=100, `ANALYSIS_QUEUE_MAX_QUEUED_PER_USER`=3)에 걸려 방어된 횟수. 부하를 올렸을 때 이 값이 늘고 5xx는 늘지 않아야 "정상적으로 막고 있다"는 뜻입니다.
- `analysis_completed` / `analysis_failed` / `analysis_poll_timeout` — 분석 종료 결과 분포.
- `analysis_time_to_complete_seconds` — 업로드 실행부터 COMPLETED까지 걸린 시간 분포(워커 처리량 판단 근거).

## 전제

- **dev/staging 환경에서만** 실행하세요. 운영 DB나 실제 비용이 발생하는 설정에서 무겁게 돌리지 마세요.
- 기본값은 `useOpenAi: false`로 두어 OpenAI 비용을 피합니다. Video LLM은 `VIDEO_LLM_ENABLED=false`(기본, mock)이면 비용이 없습니다.
- [k6](https://k6.io/docs/get-started/installation/) 설치 필요.

## 실행

```bash
# 1) 최초 1회: 테스트 계정 준비 (또는 AUTO_SIGNUP=true 사용)
#    비밀번호는 복잡도 정책을 통과해야 합니다(대/소문자+숫자+특수문자).

# 2) 가벼운 smoke (기본: VU 3, 약 1분 45초)
BASE_URL=http://localhost:8080 \
LOAD_EMAIL=loadtest@example.com LOAD_PASSWORD='LoadTest!2026aB' \
k6 run scripts/load-test/upload-analyze.js

# 3) VU마다 고유 계정 자동 생성
AUTO_SIGNUP=true k6 run scripts/load-test/upload-analyze.js

# 4) 부하를 키우고 싶을 때 (예: VU 20, 5분 유지)
VUS=20 HOLD=5m k6 run scripts/load-test/upload-analyze.js
```

### 조정 가능한 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8080` | 백엔드 주소 |
| `AUTO_SIGNUP` | `false` | true면 VU마다 고유 계정 생성 |
| `LOAD_EMAIL` / `LOAD_PASSWORD` | `loadtest@example.com` / `LoadTest!2026aB` | 고정 계정 |
| `VIDEO_PATH` | `../../sample-demo.mp4` | 업로드할 샘플 영상 |
| `VUS` | `3` | 동시 사용자 수 |
| `RAMP_UP` / `HOLD` | `30s` / `1m` | 증가/유지 구간 |
| `MAX_POLL_SECONDS` | `180` | 분석 완료 대기 상한 |

## 함께 볼 대시보드

부하를 주는 동안 Grafana/Prometheus에서 다음을 함께 관찰하면 병목을 특정할 수 있습니다.

- `analysis_job_started_total` / `analysis_job_completed_total` / `analysis_job_failed_total` — 처리량과 실패율
- `analysis_job_rejected_total{reason=...}` — 큐 상한 방어 발동
- `video_llm_generation_total{mode=...}` — REAL/FALLBACK/MOCK 비율 (Video LLM을 켠 경우 폴백률 확인)
- `video_duration_probe_result_total{outcome=...}` — ffprobe fail-open 발생 여부
- 호스트 CPU/메모리(`node_*`), 컨테이너별(`container_*`, cAdvisor)

## 결과를 설정에 반영하는 방법

backend worker와 analysis-engine은 서로 다른 두 단계의 동시성 상한을 갖습니다.

| 단계 | 환경변수 | 의미 |
|---|---|---|
| backend worker | `ANALYSIS_EXECUTOR_CORE_POOL_SIZE`, `ANALYSIS_EXECUTOR_MAX_POOL_SIZE` | worker 한 인스턴스가 동시에 실행하는 분석 파이프라인 수 |
| backend worker | `ANALYSIS_EXECUTOR_QUEUE_CAPACITY` | 해당 worker 프로세스 내부 대기열. DB의 전역·사용자별 큐 상한과 별개 |
| analysis-engine | `ANALYSIS_ENGINE_WHISPER_POOL_SIZE` | 동시 STT 추론 인스턴스 수 |
| analysis-engine | `ANALYSIS_ENGINE_POSE_POOL_SIZE` | 동시 자세 추론 인스턴스 수 |
| analysis-engine | `ANALYSIS_ENGINE_FACE_POOL_SIZE` | 동시 얼굴·시선 추론 인스턴스 수 |

- analysis-engine 풀 크기는 모두 1 이상의 정수여야 하며, 잘못된 값이면 엔진이 시작 단계에서 실패합니다.
- worker 수와 `ANALYSIS_EXECUTOR_MAX_POOL_SIZE`의 곱이 엔진 풀 크기보다 크면, 남는 파이프라인은 해당 모델 단계에서 기다립니다. 이는 정상적인 백프레셔지만 p95 완료 시간이 급증하는 지점은 기록해야 합니다.
- 모델 풀을 늘리면 처리량뿐 아니라 메모리 사용량도 증가합니다. CPU가 이미 포화됐거나 컨테이너 메모리가 한계에 가까우면 풀을 늘리지 말고 worker/큐 상한을 낮춥니다.
- 한 번에 한 변수만 바꾸고 동일 영상·VU·시간으로 다시 측정해야 전후 차이를 설명할 수 있습니다.

최소한 아래 값을 실행 기록에 남깁니다: 실행 일시/커밋, 서버 CPU·메모리, worker 수, 위 6개 동시성 변수, VU·유지 시간, 완료/실패/429 수, 완료 시간 p50·p95·p99, 최대 컨테이너 메모리.

## 한계 / 주의

- 2026-07-17에 공식 k6 런타임으로 파싱 검증하고, 모의 API가 분석 실행 요청에 429를 반환하는 네이티브 smoke를 수행했습니다. 429 응답 8건이 `run_rejected_429`에는 기록되면서 `http_req_failed=0.00%`로 유지되는 것을 확인했습니다.
- 2026-07-20에 실제 dev docker-compose 스택(`BASE_URL=http://backend:8080`, 컨테이너 네트워크 내부)의 실제 backend/worker를 대상으로 처음 실행했습니다. 이 과정에서 `analysis_time_to_complete_seconds` 트렌드 지표가 `Trend(name, true)`의 `isTime` 플래그 때문에 실제 초 단위 값(예: 81초)이 ms로 오표기(예: "81.45ms")되는 버그를 발견해 수정했습니다(`upload-analyze.js`, `isTime` 인자 제거). 동일 IP에서 반복 자동 가입 시 signup/login rate limit이 정상적으로 차단하는 것도 함께 확인했습니다(테스트 진행을 위해 해당 IP의 Redis rate-limit 키를 수동 삭제). 다만 이 실행은 스모크 성격(정식 VU/HOLD 수치, p50/p95/p99, 컨테이너 메모리 등 정량 기록은 아직 남기지 않음) — "실제 backend/worker에서 스크립트 자체가 정상 동작한다"는 것만 확정됐고, 적정 VU/큐 상한 값 산정을 위한 정식 부하 리포트는 여전히 남은 과제입니다.
- **2026-07-20 이후 주의**: `upload-analyze.js:135`는 `useVideoLlm: true`를 고정으로 보냅니다. `VIDEO_LLM_ENABLED=true` + 실제 NVIDIA 키가 설정된 환경(현재 로컬 dev가 이 상태)에서 VU를 올려 돌리면 **실제 유료 NVIDIA API 호출이 동시다발적으로 발생**합니다(일일/월간 예산 가드가 있어 무한 과금은 아니지만, 정식 부하 테스트 전에는 `useVideoLlm: false`로 바꾸거나 `VIDEO_LLM_ENABLED=false`인 별도 환경에서 실행하는 것을 권장합니다).
- 업로드 응답의 jobId 필드명은 `jobId`(없으면 `id`)를 시도합니다. 백엔드 응답 스키마가 다르면 `unwrap()` 부분을 조정하세요.
- 분석 상태 필드는 `status`(없으면 `state`)를 봅니다. 마찬가지로 실제 응답에 맞춰 조정할 수 있습니다.
