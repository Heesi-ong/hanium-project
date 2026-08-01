# 관리자 대시보드·운영 모니터링 분리 고도화 계획

- 작성일: 2026-08-01
- 범위: `frontend`, `backend`, Prometheus, Grafana, Alertmanager의 현재 구현
- 상태: 핵심 분리·고도화 구현 완료, 외부 운영 연동 검증 대기

## 1. 결론

현재 프로젝트에는 서로 목적이 다른 세 화면이 존재한다.

1. `/admin`: 애플리케이션 관리자용 사용자·데이터·실패 작업 조치 화면
2. Grafana(`127.0.0.1:3000`): 운영자용 기술 지표·추세·경보 대응 화면
3. `/status`: 로그인 사용자가 서비스 가용성을 확인하는 상태 화면

세 화면을 하나로 합치지 않는다. 관리자 화면에는 사용자 식별 정보와 상태 변경·삭제·재큐잉 같은 **업무 조치**만 두고, Grafana에는 서비스·인프라·큐·외부 연동의 **기술 관측**만 둔다. `/status`는 내부 주소, 인증 상태, 정책명 등 운영 세부를 제거한 **최소 상태 안내**로 둔다.

가장 먼저 해결해야 할 문제는 다음 세 가지다.

- 공개 허용된 `/api/health/engines`가 내부 엔진 Base URL과 authenticated readiness 세부를 반환한다.
- 백엔드에는 스토리지 삭제 및 비밀번호 재설정 이메일 DEAD_LETTER 조회·재큐잉 API가 있지만 관리자 프론트에는 없다.
- Prometheus가 cAdvisor를 수집하지만 이를 보여주는 대시보드·알림이 없고, 엔진·DB·Redis·MinIO 및 HTTP SLI도 운영 화면에서 충분히 관측되지 않는다.

## 2. 확인한 현재 상태

### 2.1 화면과 접근 경계

| 화면 | 현재 접근 | 현재 역할 | 근거 |
|---|---|---|---|
| `/status` | `ProtectedRoute` | backend, 두 엔진, 비밀번호 재설정 상태 | `frontend/src/routes/AppRoutes.jsx:52-60`, `frontend/src/pages/StatusPage.jsx` |
| `/admin` | `AdminRoute` | 통계, 사용자 관리, 분석 DLQ | `frontend/src/routes/AppRoutes.jsx:62-66`, `frontend/src/pages/AdminDashboardPage.jsx` |
| `/admin/users/:userId` | `AdminRoute` | 사용자별 분석 결과 조회·삭제 | `frontend/src/pages/AdminUserDetailPage.jsx` |
| `/admin/audit-logs` | `AdminRoute` | 관리자 조치 감사로그 | `frontend/src/pages/AdminAuditLogPage.jsx` |
| Grafana | localhost 바인딩 및 Grafana 계정 | 분석·큐·호스트 대시보드 | `docker-compose.monitoring.yml`, `infra/grafana/provisioning` |

백엔드의 `/api/admin/**`는 `ROLE_ADMIN`으로 보호된다. 반면 `/api/health`와 `/api/health/**`는 인증 없이 허용된다(`SecurityConfig.java:80,93-94`). 프론트 라우트 보호만으로 API 공개를 막을 수는 없다.

### 2.2 관리자 기능

현재 관리자 UI가 제공하는 기능은 다음과 같다.

- 전체 사용자·관리자·전체 분석·완료 분석 수
- 사용자 목록, 사용자별 결과 조회
- 사용자 정지·활성화·강제 탈퇴
- 결과 삭제
- 분석 작업 DEAD_LETTER 목록과 단건 재큐잉
- 감사로그 조회

백엔드는 여기에 더해 다음 API를 이미 제공하지만 `frontend/src/api/adminApi.js`와 화면에서 사용하지 않는다.

- `GET /api/admin/storage-deletion-tasks/dead-letter`
- `POST /api/admin/storage-deletion-tasks/{taskId}/requeue`
- `GET /api/admin/password-reset-email-tasks/dead-letter`
- `POST /api/admin/password-reset-email-tasks/{taskId}/requeue`

따라서 스토리지 삭제 실패와 비밀번호 재설정 이메일 실패는 경보가 발생해도 관리자가 애플리케이션 UI에서 복구할 수 없는 프론트/백엔드 계약 공백이다.

### 2.3 운영 모니터링

현재 Prometheus 수집 대상은 다음 네 종류다.

- backend Actuator
- analysis-worker Actuator(DNS service discovery)
- node-exporter
- cAdvisor

Grafana에는 3개 대시보드, 총 17개 패널이 프로비저닝되어 있다.

- 분석 서비스 개요: backend up, 분석 작업 비율·실패 사유·평균 소요 시간, OpenAI/Video LLM 월간 사용률
- 분석 큐/타임아웃: QUEUED/RUNNING, 429, timeout, 스토리지 삭제·비밀번호 이메일 DEAD_LETTER
- 호스트 리소스: 디스크, CPU load, 메모리, MySQL 백업 상태·경과 시간

Prometheus 알림에는 backend/worker/exporter down, 분석 실패율, 엔진 readiness 실패, 비용, fallback, DLQ, 결과 데이터 이상, ffprobe, 백업, 호스트 리소스가 포함되어 있다. 다만 대시보드와 알림은 동일 범위를 완전히 덮지 않는다.

## 3. 발견한 문제

### P0. 공개 상태 API의 내부 정보 노출

`/api/health/engines`는 인증 없이 호출할 수 있는데 다음 값을 반환한다.

- analysis-engine과 video-llm-engine의 내부 Base URL
- reachable, ready, authenticated
- Video LLM mode, policy, realModelReady
- readiness 실패 이유와 내부 응답

이는 사용자 상태 페이지에 불필요한 운영 토폴로지와 모델 정책을 공개한다. 또한 요청할 때마다 backend가 엔진 readiness를 동기 호출하므로 상태 페이지 트래픽이 내부 엔진 호출과 readiness 메트릭에 영향을 준다.

**개선 방향**

- 공개 liveness인 `/api/health`는 `service/status`처럼 최소 응답만 유지한다.
- 사용자용 `/api/status`를 별도 DTO로 만들고 `AVAILABLE/DEGRADED/UNAVAILABLE`, 갱신 시각, 사용자 영향 설명만 반환한다.
- 상세 엔진 점검 API는 `/api/admin/diagnostics/...`로 이동하거나 제거한다. 운영자는 Grafana를 사용하며, 유지할 경우에만 ADMIN 인가를 적용한다.
- 프론트 `/status`에서 Base URL, authenticated, mode, policy, realModelReady, 내부 reason을 렌더링하지 않는다.

### P1. 관리자 복구 기능의 프론트 계약 누락

분석 DLQ만 UI에 연결되어 있고 스토리지 삭제·비밀번호 재설정 이메일 DLQ는 백엔드에만 존재한다. Alertmanager 메시지는 이미 "관리자 대시보드에서 재큐잉"을 지시하므로 현재 경보 대응 절차와 실제 UI가 불일치한다.

**개선 방향**

- 관리자 화면에 `복구 작업` 전용 페이지를 신설한다.
- 분석, 스토리지 삭제, 비밀번호 재설정 이메일을 탭 또는 필터로 구분한다.
- 각 항목에 실패 사유, 시도 횟수, 발생/만료 시각, 대상 식별자, 재큐잉 조치를 제공한다.
- 이메일은 현재 DTO의 마스킹된 주소만 표시하고 원문 주소를 추가 노출하지 않는다.
- 재큐잉 후 목록·요약 카운트를 서버에서 다시 조회하며 감사로그까지 연결한다.

### P1. 감사로그 UI가 실제 감사 이벤트를 완전히 표현하지 못함

백엔드는 분석 DLQ, 스토리지 삭제, 비밀번호 이메일 재큐잉을 감사 이벤트로 기록한다. 그러나 `AdminAuditLogPage.jsx`의 한글 라벨은 사용자 상태 변경·강제 탈퇴·결과 삭제만 정의하고 target type도 USER와 ANALYSIS_JOB만 정의한다. 새 이벤트는 enum 원문으로 보이거나 문맥이 부족하다.

**개선 방향**

- 모든 `AdminAuditAction`과 `AdminAuditTargetType`에 화면 라벨을 제공한다.
- 기간, 관리자, 액션, 대상 ID 필터를 서버 페이지네이션과 함께 제공한다.
- 파괴적 조치와 수동 재큐잉에는 관리자 사유 입력을 받아 감사 상세에 저장한다.
- request/correlation ID를 감사 상세 또는 구조화 로그와 연결해 운영 추적성을 확보한다.

### P1. 운영 모니터링의 서비스·자원 사각지대

- cAdvisor를 스크레이핑하지만 컨테이너 CPU·메모리·재시작·OOM 대시보드와 알림이 없다.
- analysis-engine과 video-llm-engine 자체를 직접 스크레이핑하지 않는다. 현재 엔진 readiness 실패 메트릭은 상태 API가 호출될 때 backend가 기록한 결과라서 지속적인 가용성 관측이 아니다.
- MySQL, Redis, MinIO의 직접 가용성·용량·지연 지표가 없다. backend Hikari/JVM/HTTP 기본 메트릭도 대시보드와 핵심 알림에 사용하지 않는다.
- API 요청률, 5xx 비율, p95/p99 지연, JVM heap/GC/thread, Hikari pool 포화, 컨테이너 재시작 같은 운영 기본 SLI가 없다.
- Alertmanager의 firing/pending 상태를 Grafana 첫 화면에서 한눈에 볼 수 없다.
- 구조화 파일 로그는 있으나 중앙 로그 탐색 및 메트릭에서 로그로 이동하는 경로가 없다.

**개선 방향**

- `서비스 운영 개요` 대시보드를 첫 화면으로 신설한다: 서비스 up, 현재 firing 알림, HTTP 5xx, p95 지연, 큐 백로그, worker 수, DB pool, 호스트/컨테이너 포화, 비용 상태.
- 기존 세 대시보드는 상세 drill-down으로 유지한다.
- cAdvisor 지표로 서비스별 CPU·메모리·재시작/OOM 패널과 알림을 추가한다.
- Python 엔진에 `/metrics`를 추가하거나 blackbox exporter로 `/health`와 `/ready`를 주기 점검한다. 실제 모델 readiness는 비용·부하를 고려해 별도 저주기 probe로 둔다.
- JVM/HTTP/Hikari는 이미 Actuator가 노출하는 지표를 우선 활용한다. MySQL/Redis/MinIO exporter는 직접 지표가 필요한 범위를 확인한 뒤 최소 구성으로 추가한다.
- Grafana alert list 또는 Prometheus `ALERTS` 메트릭 패널과 runbook 링크를 제공한다.
- 중앙 로그는 Loki/Promtail 도입 비용과 보존 정책을 별도 승인한 뒤 추가한다. 초기 단계에서는 Grafana 패널에서 jobId/correlationId와 로그 경로를 안내한다.

### P1. 관리자 대시보드의 실패 격리 부족

초기 로딩이 통계·사용자·분석 DLQ를 하나의 `Promise.all`로 묶는다. 세 요청 중 하나만 실패해도 성공한 영역까지 화면에 반영하지 못하며, 어느 영역이 실패했는지 구분하기 어렵다.

**개선 방향**

- 개요, 사용자, 복구 큐를 별도 라우트와 독립 로딩/오류 경계로 분리한다.
- 개요 카드도 개별 실패 시 나머지 카드는 유지하고 재시도 버튼을 제공한다.
- 조치 성공 후 낙관적 삭제만 하지 말고 관련 목록과 집계값을 다시 동기화한다.

### P2. 관리자 정보구조와 탐색 기능 부족

현재 한 페이지에 통계, 사용자 테이블, 분석 DLQ가 이어지고 목록은 `더 보기`만 제공한다. 사용자·감사로그가 늘어나면 검색과 운영 추적이 어렵다.

**개선 방향**

- `/admin`: 업무 개요 및 조치 필요 건수
- `/admin/users`: 이메일·상태·권한·가입일 검색/필터/정렬과 페이지네이션
- `/admin/recovery`: 3종 DEAD_LETTER 조회·재큐잉
- `/admin/audit-logs`: 기간·관리자·액션·대상 필터
- `/admin/users/:userId`: 사용자 정보와 결과 관리

개요 수치는 누적 전체 수보다 현재 조치 필요 상태를 우선한다. 예: 정지 사용자, QUEUED/RUNNING/FAILED/DEAD_LETTER, 데이터 이상 결과, 대기 중 삭제·이메일 작업. 시간 추세·latency·자원 사용은 Grafana에만 둔다.

### P2. 운영 접근과 보존 정책의 명시 부족

Grafana/Prometheus/Alertmanager는 localhost에만 바인딩되어 외부 공개를 막고 있으나, 원격 운영 접근 방법(VPN/SSH tunnel), Grafana 계정 수명주기, 데이터 보존 기간, 백업 여부가 화면 운영 절차와 함께 정리되어 있지 않다. Grafana 기본 관리자 비밀번호 fallback도 운영 배포 체크에서 반드시 차단해야 한다.

**개선 방향**

- 애플리케이션 ADMIN 계정과 Grafana 운영자 계정을 별도 권한으로 유지한다.
- Grafana는 VPN/SSH tunnel 또는 사내 SSO 뒤에서만 접근하도록 한다.
- Prometheus/Grafana 보존 기간, 볼륨 백업 여부, 알림 수신자 점검 주기를 runbook에 명시한다.
- prod에서는 `GRAFANA_ADMIN_PASSWORD=changeme`와 미설정을 기동 전에 차단한다.

## 4. 목표 책임 분리

| 기능 | 관리자 페이지 | 운영 모니터링 | 사용자 상태 페이지 |
|---|:---:|:---:|:---:|
| 사용자 검색·정지·활성화·탈퇴 | O | X | X |
| 사용자 결과 조회·삭제 | O | X | X |
| DEAD_LETTER 상세·재큐잉 | O | 건수·경보·추세만 | X |
| 감사로그와 조치 사유 | O | X | X |
| 서비스/worker/exporter up | X | O | 종합 상태만 |
| HTTP 요청률·5xx·p95/p99 | X | O | X |
| JVM·DB pool·컨테이너·호스트 자원 | X | O | X |
| DB·Redis·MinIO·엔진 기술 상태 | X | O | 사용자 영향만 |
| 비용·fallback·모델 호출 추세 | X | O | X |
| firing 알림과 runbook | X | O | X |
| 내부 Base URL·인증 상태·정책명 | X | 필요 최소한만 | X |
| 장애 시 사용자 안내 | X | 원인·기술 영향 | O |

관리자 페이지에서 Grafana 링크를 제공할 수는 있지만 Grafana 패널을 복제하지 않는다. Grafana 알림에서 관리자 복구 화면의 정확한 경로를 runbook 링크로 제공할 수는 있지만 Grafana에서 업무 조치를 실행하지 않는다.

## 5. 단계별 실행 계획

### 1단계 — 상태 API 보안 경계 정리(P0)

수정 예상 범위:

- `backend/.../HealthController.java`
- 상태 DTO 및 controller/security 테스트
- `frontend/src/pages/StatusPage.jsx`와 테스트
- 필요 시 `frontend/src/api/analysisApi.js`

완료 기준:

- 비인증 상태 응답에 내부 URL·인증 여부·모델 정책·내부 오류가 없다.
- 사용자 상태 화면은 최소 상태와 사용자 영향만 표시한다.
- 상세 진단은 ADMIN 전용이거나 Grafana로 대체된다.

### 2단계 — 관리자 정보구조 분리 및 백엔드 기존 복구 API 연결(P1)

수정 예상 범위:

- 관리자 라우트와 내비게이션
- `frontend/src/api/adminApi.js`
- 관리자 개요/사용자/복구 화면과 테스트
- 기존 `AdminDashboardPage.jsx` 축소 또는 분해

완료 기준:

- 분석·스토리지 삭제·비밀번호 이메일 3종 DEAD_LETTER를 조회하고 단건 재큐잉할 수 있다.
- 각 영역의 로딩·실패·재시도가 독립적이다.
- 모바일에서 표가 화면 전체를 밀어내지 않고 필요한 컬럼을 확인할 수 있다.
- 백엔드 기존 API 계약 테스트와 프론트 UI 테스트가 통과한다.

### 3단계 — 관리자 검색·필터·감사 추적 강화(P1/P2)

수정 예상 범위:

- 관리자 조회 API의 서버 검색/필터/정렬 파라미터
- repository query와 pagination 테스트
- 관리자 조치 요청 DTO의 사유 필드
- 감사로그 라벨·필터 UI와 테스트

완료 기준:

- 사용자와 감사로그를 전체 로딩 없이 서버에서 검색·필터링한다.
- 모든 관리자 조치 유형이 한글 라벨로 표시된다.
- 파괴적 조치와 재큐잉에 관리자·시각·대상·사유가 남는다.

### 4단계 — 운영 모니터링 핵심 SLI와 통합 개요(P1)

수정 예상 범위:

- Grafana `service-operations-overview.json` 신설
- 기존 대시보드 보강
- Prometheus 알림 규칙과 rule test
- 필요 시 backend metric tag/recording rule

우선 패널:

1. 현재 firing 알림과 서비스 up
2. HTTP 요청률·5xx·p95/p99
3. 분석 큐 backlog/running/oldest age와 worker 수
4. JVM heap/GC/thread와 Hikari active/pending
5. 서비스별 컨테이너 CPU/메모리/restart/OOM
6. 호스트 디스크와 MySQL 백업
7. OpenAI/Video LLM 사용률·fallback

완료 기준:

- 정상·장애·부분 장애를 한 화면에서 식별한다.
- 각 경보에 담당 대시보드와 runbook 링크가 있다.
- cAdvisor 수집 지표가 실제 패널·알림에서 사용된다.

### 5단계 — 엔진 및 데이터 계층 관측 보강(P1/P2)

수정 예상 범위:

- Python 엔진 `/metrics` 또는 blackbox exporter
- MySQL/Redis/MinIO exporter 검토 및 최소 도입
- readiness probe 주기·timeout·비용 정책
- Alertmanager/Grafana 규칙과 운영 문서

완료 기준:

- backend 상태 페이지 호출과 무관하게 두 엔진의 health/readiness를 지속 관측한다.
- DB pool 포화, Redis 장애, MinIO 용량/실패를 조용히 놓치지 않는다.
- probe가 실제 모델 호출 비용이나 분석 처리량을 과도하게 소모하지 않는다.

### 6단계 — 실제 브라우저·장애 주입 검증(P1)

- 일반 사용자와 관리자 계정으로 `/status` 및 모든 관리자 라우트 확인
- 390px/768px/desktop 반응형 확인 및 `documentScrollWidth` 측정
- 세 종류 DEAD_LETTER 생성 → 경보 → 관리자 조회 → 재큐잉 → 감사로그 → 복구 E2E
- backend/worker/엔진/exporter 중단 시 해당 Grafana 패널·알림 발동 확인
- 5xx/latency/DB pool/queue backlog 임계값의 테스트 트래픽 검증
- Alertmanager 실제 수신과 runbook 링크 확인

## 6. 도입할 때와 하지 않을 때의 차이

| 항목 | 도입 시 | 미도입 시 | 운영 비용 |
|---|---|---|---|
| 관리자 복구 페이지 | 경보 후 업무 복구를 UI에서 감사 가능하게 수행 | API 수동 호출 또는 DB 조작 필요 | 프론트 유지보수, 권한 테스트 |
| 통합 운영 개요 | 장애 위치와 사용자 영향을 수분 내 좁힘 | 여러 대시보드와 로그를 수동 순회 | 패널·임계값 튜닝 |
| 엔진 지속 probe | 상태 페이지 방문과 무관하게 장애 감지 | 사용자 요청 전까지 엔진 장애가 잠복 | probe 부하와 오탐 관리 |
| DB/Redis/MinIO 관측 | 저장·인증·큐 원인을 직접 분리 | backend 오류율만 보고 원인 추정 | exporter 운영·보안 |
| 중앙 로그 | 메트릭에서 요청/job 로그로 빠른 추적 | 각 컨테이너 파일 로그를 직접 탐색 | 저장공간·보존·PII 마스킹 |

초기에는 기존 Actuator와 cAdvisor를 최대한 활용해 새 구성 요소를 줄인다. exporter와 Loki는 실제 사각지대와 보존 비용을 확인한 뒤 별도 단계로 도입한다.

## 7. 검증 기준

각 단계는 다음 증거를 남긴 뒤 완료로 판단한다.

- backend: 변경 관련 단위/통합 테스트와 `./gradlew clean test` 또는 `--rerun-tasks`
- frontend: Vitest, ESLint, production build
- monitoring: Compose config, YAML/JSON parse, `promtool check config`, `promtool check rules`
- runtime: Prometheus targets, 실제 PromQL 결과, Grafana 렌더링, Alertmanager 수신
- browser: ADMIN/USER 권한 분리, 빈 상태·부분 실패·모바일·파괴적 확인 흐름
- E2E: 3종 DEAD_LETTER 경보에서 관리자 복구와 감사로그까지 연결

## 8. 권장 구현 순서

승인 후 **1단계 상태 API 보안 경계 정리**부터 진행한다. 그 다음 백엔드에 이미 구현된 API를 활용해 **2단계 관리자 복구 화면**을 완성한다. 이후 관리자 탐색/감사를 보강하고, 운영 모니터링은 기존 수집 지표를 먼저 시각화한 다음 엔진·데이터 계층 exporter를 추가한다.

이 순서는 정보 노출을 먼저 막고, 이미 존재하는 복구 기능을 가장 낮은 비용으로 UI에 연결한 뒤, 새 인프라 구성 요소의 운영 부담을 단계적으로 늘리는 방식이다.

## 9. 2026-08-01 구현 결과

### 완료한 항목

- 공개 `/api/health`를 최소 liveness 응답으로 제한하고 `/api/health/engines`를 제거했다.
- 인증된 `/api/status`가 내부 URL·인증 상태·모델 정책·원본 오류 없이 사용자 영향만 반환하도록 분리했다.
- 관리자 정보구조를 `/admin`, `/admin/users`, `/admin/recovery`, `/admin/audit-logs`, `/admin/users/:userId`로 분리했다.
- 관리자 개요에서 기술 지표를 제거하고 사용자·분석 누계 및 3종 조치 필요 건수만 제공한다.
- 분석·스토리지 삭제·비밀번호 재설정 이메일 DEAD_LETTER를 독립적으로 조회·재큐잉할 수 있게 연결했다.
- 사용자 이메일·상태·권한과 감사로그 관리자·작업·대상·기간을 서버 페이지네이션 조건으로 검색한다.
- 모든 현재 재큐잉 감사 이벤트와 대상 유형에 한글 라벨을 제공한다.
- Blackbox Exporter를 추가해 두 엔진, MySQL, Redis, MinIO를 상태 페이지와 무관하게 지속 점검한다.
- `서비스 운영 개요` 14개 패널과 HTTP 5xx/p95, JVM heap, Hikari pool, 큐 용량, dependency probe 등 핵심 경보를 추가했다.
- 경보에서 Grafana 진단 후 `/admin/recovery` 업무 조치로 이어지는 runbook을 작성했다.

### 실행한 검증

- backend 전체 테스트를 `--rerun-tasks`로 실행해 통과했다.
- frontend 전체 Vitest 220개, ESLint, production build를 통과했다. 검색·필터 변경 후 관련 backend/frontend 테스트도 다시 통과했다.
- Compose 병합, YAML/JSON parse, Prometheus config, 28개 alert rule과 rule unit test, Blackbox Exporter config를 검증했다.
- 실제 Prometheus target에서 모니터링 구성요소가 모두 up인 것과 중지된 애플리케이션 dependency probe가 실패로 관측되는 것을 확인했다.
- 실제 Grafana API에서 `서비스 운영 개요`가 provisioned 상태이며 14개 패널을 가진 것을 확인했다.
- 최신 Docker 이미지로 backend/frontend를 재생성하고 관리자 권한 브라우저에서 상태·개요·사용자·복구·감사로그 화면과 서버 필터를 확인했다.
- 390px viewport에서 관리자 개요와 복구 화면의 `documentScrollWidth`가 390px임을 확인했다.

### 남은 운영 검증과 의도적 보류

- SMTP 실제 수신, 원격 staging의 경보 전달, 장애 주입에 의한 firing→resolved 전환은 실제 운영 자격증명과 배포 환경에서 확인해야 한다.
- 세 종류 DEAD_LETTER를 실제로 생성해 재큐잉·감사로그까지 잇는 destructive E2E는 운영 데이터와 분리된 전용 테스트 환경에서 수행한다.
- 관리자 조치 사유와 request/correlation ID의 감사로그 영속화는 아직 남아 있다. 현재도 관리자·시각·액션·대상·상세는 기록된다.
- 중앙 로그 탐색(Loki/Promtail)은 저장 비용, 개인정보 마스킹, 보존 기간 합의가 필요해 이번 범위에서는 도입하지 않았다.
- MySQL·Redis probe는 연결 가능성만 확인한다. 기능·성능 문제는 Hikari와 애플리케이션 오류 지표를 함께 사용하며, 세부 exporter는 실제 사각지대가 확인되면 추가한다.
