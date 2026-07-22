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
| `USE_VIDEO_LLM` | `false` | true면 분석 실행 요청에 `useVideoLlm: true`를 보냄. `VIDEO_LLM_ENABLED=true`인 환경에서는 실제 유료 API 호출로 이어지므로 기본값은 안전하게 false |
| `VUS` | `3` | 동시 사용자 수 |
| `RAMP_UP` / `HOLD` | `30s` / `1m` | 증가/유지 구간 |
| `MAX_POLL_SECONDS` | `180` | 분석 완료 대기 상한 |
| `GRACEFUL_STOP_SECONDS` | `MAX_POLL_SECONDS + 30` | k6 시나리오 종료 시 진행 중인 반복을 얼마나 기다려줄지. 이 값이 `MAX_POLL_SECONDS`보다 짧으면 HOLD 막바지에 시작된 반복은 완료 여부와 무관하게 강제 종료되어 "0 complete"처럼 보일 수 있다 |

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
- **2026-07-21 수정**: `upload-analyze.js`가 `useVideoLlm: true`를 고정으로 보내던 문제를 고쳤다. 이제 기본값은 `false`이고, `USE_VIDEO_LLM=true`로 명시적으로 켜야만 실제 Video LLM 경로(REAL 모드 활성 환경에서는 실제 유료 NVIDIA 호출)를 탄다. `VIDEO_LLM_ENABLED=true` + 실제 API 키가 설정된 환경에서 부하 테스트를 돌려도 기본값으로는 더 이상 의도치 않은 과금이 발생하지 않는다.
- **2026-07-21 수정**: `ensureLoggedIn()`이 매 반복(iteration)마다 다시 로그인을 시도하던 문제를 고쳤다. VUS를 조금만 올려도(예: 4) 몇 초 만에 로그인 rate limit이 소진되어 이후 반복 대부분이 로그인 단계에서 429로 막히고("login 200" 체크 실패율 97%대) 실제로는 분석 파이프라인 부하가 거의 발생하지 않는 문제가 실측으로 확인됐다(07-20 노트의 "수동으로 Redis rate-limit 키 삭제" 워크어라운드가 필요했던 근본 원인이 바로 이것). VU별로 최초 1회만 로그인하고 이후 반복은 재사용하도록 수정했다 — k6는 VU 안에서 모듈 스코프 변수와 쿠키 자(jar)를 반복 간에 유지하므로 안전하다.
- **2026-07-21 수정**: 시나리오의 `gracefulStop`이 30초로 고정돼 있어, `MAX_POLL_SECONDS`(기본 180초)보다 훨씬 짧았다. HOLD 구간 막바지에 시작된 반복은 분석 완료를 끝까지 기다려보지도 못하고 강제 종료되어 "0 complete, N interrupted"로 나오는(실제로는 서버가 정상 처리 중이었을 수 있는) 착시가 있었다. `gracefulStop`을 `MAX_POLL_SECONDS + 30초`로 자동 계산하도록 바꾸고, 필요하면 `GRACEFUL_STOP_SECONDS`로 직접 override할 수 있게 했다.
- **2026-07-21 첫 정식 부하 실행 기록**: 로컬 docker-compose(`backend` API 전용 + `analysis-worker` 1개, 둘 다 기본 `ANALYSIS_EXECUTOR_CORE_POOL_SIZE=2`/`MAX_POOL_SIZE=4`, `analysis-engine` Whisper/Pose/Face 풀 각 2)에서 `VUS=4 RAMP_UP=10s HOLD=90s MAX_POLL_SECONDS=150`로 실행. `http_req_failed=0.00%`, `analysis_completed`는 반복 수(초기 3건 smoke + 이번 실행) 기준으로 정상 완료했고 `analysis_time_to_complete_seconds`는 약 72~76초(mock 분석 엔진이지만 실제 ffmpeg/faster-whisper/MediaPipe로 처리, useVideoLlm/useOpenAi 모두 false). VU 4는 `ANALYSIS_EXECUTOR_MAX_POOL_SIZE=4`와 정확히 같아 큐잉 없이 바로 실행되는 경계값이었다 — VUS를 그 이상으로 올려 큐잉·429·p95 지연이 실제로 늘어나는 지점을 찾는 것은 다음 회차 과제로 남는다.
- **2026-07-21 VUS 5+ 시도에서 발견한 별개의 차단 요인 → 해결**: 위 세 가지를 고친 뒤에도 VUS를 5~10으로 올리면 로그인(이메일 기준)·가입(클라이언트 IP 기준) rate limit 용량이 각각 기본 5/10분이라, k6가 전부 localhost 한 IP에서 도네이션하는 이 실행 방식으로는 분석 파이프라인에 도달하기도 전에 인증 계층에서 막혔다. **해결**: `docker-compose.yml`에 `LOGIN_RATE_LIMIT_CAPACITY`/`LOGIN_IP_RATE_LIMIT_CAPACITY`/`SIGNUP_RATE_LIMIT_CAPACITY` env 배선을 추가했다(다른 rate limit 카테고리는 이미 노출돼 있었는데 이 셋만 빠져 있었다). 이제 `LOGIN_RATE_LIMIT_CAPACITY=5000 LOGIN_IP_RATE_LIMIT_CAPACITY=5000 SIGNUP_RATE_LIMIT_CAPACITY=5000 docker compose up -d --wait backend`처럼 테스트 세션 동안만 올려서 부하 테스트를 진행하고, 끝나면 컨테이너를 원래 값으로 되돌리면 된다(운영값 자체를 바꾸는 게 아니라 테스트 세션 한정 오버라이드).
- **2026-07-21 두 번째 실제 버그: `gracefulRampDown` 누락** — VUS=8로 처음 돌렸을 때 8건 전부가 "interrupted"로 나왔다(서버 DB 확인 결과 실제로는 정상 처리 중이었음). 원인은 시나리오 마지막 stage가 target 0으로 낮추는 ramp-down인데, `gracefulStop`만 늘리고 `gracefulRampDown`은 기본값(30s)으로 남겨둔 것 — ramp-down 단계의 진행 중인 반복은 `gracefulStop`이 아니라 `gracefulRampDown`이 지배한다. 두 값을 동일하게 맞춰 해결했다(`upload-analyze.js`).
- **2026-07-21 VUS=4/8/16 정식 부하 측정 결과** (backend API 전용 1 + analysis-worker 1, 둘 다 `ANALYSIS_EXECUTOR_CORE_POOL_SIZE=2`/`MAX_POOL_SIZE=4`, analysis-engine Whisper/Pose/Face 풀 각 2, `cpus: 2.0`/`memory: 4g` 제한, useVideoLlm/useOpenAi 모두 false):

  | VUS | 완료 | 실패 | 평균 완료 시간 | 비고 |
  |---|---|---|---|---|
  | 4 | 8/8 | 0 | ~74s | 정상. VU 수가 `ANALYSIS_EXECUTOR_MAX_POOL_SIZE`와 같아 큐잉 없이 즉시 실행 |
  | 8 | 8/8 (1차) | 0 (1차) / 5 (2차) | ~106s (2차 성공분) | 완료 시간이 VUS=4의 약 1.4배로 증가(큐잉). 2차 실행에서 analysis-engine 서킷브레이커가 열려 5건이 한꺼번에 실패 |
  | 16 | 6/16 | 10 | ~169s (성공분) | 완료 시간이 VUS=4의 약 2.3배. 처음 6건은 성공, 이후 10건이 전부 같은 시각에 한꺼번에 실패(서킷브레이커 열림 구간과 정확히 일치) |

  **헤드라인 발견 — analysis-engine 서킷브레이커가 부하 아래에서 실제로 열리며, 열리는 순간부터 큐에 있던 다수의 정상 요청이 한꺼번에 실패로 전환된다.** `AnalysisEngineClient`의 서킷브레이커(`sliding-window-size=10`, `failure-rate-threshold=50%`, `wait-duration-in-open-state=30s`)가 열리면 `CallNotPermittedException`으로 즉시(수 ms 안에) 실패 처리되므로, 큐에 쌓여 있던 작업들이 실제 analysis-engine 호출 시도조차 못 해보고 한 덩어리로 `FAILED` 처리된다(VUS=16에서 마지막 10건이 정확히 같은 타임스탬프에 함께 실패한 것으로 확인). **다만 이 회차에서는 서킷을 처음 연 "진짜 원인"(최초 실패 몇 건)의 정확한 예외를 로그에서 찾지 못했다** — `AnalysisEngineClient.analyze()`의 catch 블록이 원본 예외를 `기본 분석 엔진 호출에 실패했습니다: <메시지>` 형태로 로그에 남기는 코드 경로가 있는데도 analysis-worker 로그 전체에서 이 문자열이 단 한 번도 발견되지 않았다(있는 건 서킷-오픈 상태의 `CallNotPermittedException` 메시지뿐). analysis-engine 자체 접근 로그에는 이 세션 전체에서 200 20건, 401 2건만 있었는데, 이 401 2건은 `analysis-worker`의 컨테이너 IP(172.18.0.8)가 아니라 도커 게이트웨이 IP(172.18.0.1, `analysis-engine` 포트 8001이 `127.0.0.1`에 호스트로 퍼블리시돼 있어 호스트에서 직접 호출 가능함)에서 왔고 요청 볼륨상으로도 서킷을 열기에 부족해, 실제 부하 테스트 트래픽과는 무관한 별개의 산발적 호출로 보인다. 즉 **서킷을 최초로 연 진짜 원인은 여전히 미확인 상태**이며, 다음 조사에는 (a) `analyze()`의 catch 블록에 원본 예외의 클래스명을 남기거나, (b) DEBUG 레벨로 재현하거나, (c) resilience4j 이벤트 리스너(상태 전이 로그)를 추가하는 방법을 권장한다.
  - **완료 시간이 VUS에 비례해 뚜렷하게 늘어난다**는 점은 명확히 확인됐다 — VUS 4→8→16에서 74s→106s→169s로, `analysis-worker`의 `MAX_POOL_SIZE=4`를 넘는 큐잉이 정상적으로 지연을 유발하고 있다(이건 의도된 백프레셔 동작).
  - VUS=16에서 업로드 자체가 2건 실패(`upload 2xx` 체크 실패)했으나 backend 로그에서 명확한 원인(예: `FILE_UPLOAD_FAILED`, `INSUFFICIENT_STORAGE_SPACE`)을 찾지 못했다. 규모가 작아(18건 중 2건) 이번 회차에서는 더 파고들지 않았다.
  - 테스트에 사용한 계정(4+8+16=28개)은 전부 회원탈퇴 API로 정리했고, 테스트를 위해 올린 rate limit 오버라이드는 컨테이너를 원래 값으로 되돌려 세션 종료 시 정상화했다.
  - **다음 우선순위**: 서킷브레이커를 최초로 여는 근본 원인을 확정하는 것이 가장 가치 있는 다음 조사다. 근본 원인 없이 임시방편(서킷브레이커 임계치를 완화하거나 analysis-engine 모델 풀/CPU 제한을 늘리는 것)만 적용하면 증상만 가릴 위험이 있다.

## 2026-07-22 서킷브레이커 근본 원인 확정 및 수정

동일한 VUS=16 파동을 다시 실행해 **6건 성공·10건 즉시 실패**를 재현했다. analysis-engine은
차단 전 요청을 모두 200으로 완료했고 최초 HTTP 실패는 없었다. 실제 원인은 Resilience4j의
기본 `slow-call-duration-threshold=60s`였다. 로컬 제한 환경의 정상 분석 완료 시간이
89~252초이므로, 성공한 첫 5건도 전부 slow call로 집계돼 기본 slow-call rate 100%에 도달하면서
회로가 열린 것이다. 즉 failure-rate가 아니라 **정상 장기 요청을 장애로 오판한 slow-call 설정**이
연쇄 실패의 원인이었다.

수정 후 분석 엔진 slow-call 기준은 5분(HTTP read timeout 기본 10분보다 낮음)으로 명시했고,
두 엔진의 회로 설정 전체를 Compose 환경변수로 배선했다. worker에는 최초 호출 예외 타입/스택과
회로 `from`/`to`, failure-rate, slow-call-rate를 남기는 로그도 추가해 다음 장애에서 원인을
바로 구분할 수 있다.
- 업로드 응답의 jobId 필드명은 `jobId`(없으면 `id`)를 시도합니다. 백엔드 응답 스키마가 다르면 `unwrap()` 부분을 조정하세요.
- 분석 상태 필드는 `status`(없으면 `state`)를 봅니다. 마찬가지로 실제 응답에 맞춰 조정할 수 있습니다.
