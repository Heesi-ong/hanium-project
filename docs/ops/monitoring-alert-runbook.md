# 운영 모니터링 알림 대응 Runbook

이 문서는 Grafana `서비스 운영 개요`와 Prometheus/Alertmanager 알림에서 장애 원인을 좁히는 순서를 정의한다. 기술 상태 확인은 Grafana에서 수행하고, 사용자·결과·DEAD_LETTER 재큐잉 같은 업무 조치는 애플리케이션 `/admin`에서 수행한다.

## 공통 확인 순서

1. Grafana `서비스 운영 개요`에서 firing 알림, backend/worker, dependency probe 상태를 확인한다.
2. 동일 시간대의 HTTP 5xx·p95, 분석 큐, JVM heap, Hikari pool, 컨테이너 CPU·메모리를 확인한다.
3. Prometheus `Targets`에서 해당 job의 scrape 오류와 마지막 성공 시각을 확인한다.
4. 대상 컨테이너의 health와 구조화 로그를 requestId/jobId 기준으로 확인한다.
5. 원인을 제거한 뒤 알림이 `resolved`로 바뀌는지 확인한다.
6. DEAD_LETTER가 남았다면 `/admin/recovery`에서 대상과 실패 사유를 검토한 뒤 재큐잉한다.

## MonitoringComponentDown

- 영향: 해당 exporter 또는 UI가 담당하는 관측 영역이 보이지 않을 수 있다.
- 확인: `docker compose ... ps`, Prometheus Targets, 대상 컨테이너 로그.
- 주의: Alertmanager 자체가 중단되면 이메일 전송이 불가능하므로 Prometheus Alerts 화면을 직접 확인한다.

## ServiceDependencyProbeFailed

- `analysis-engine`, `video-llm-engine`: 컨테이너 health와 `/health` 응답을 확인한다. 실제 모델 readiness는 backend의 readiness metric과 엔진 로그로 추가 확인한다.
- `mysql`, `redis`: TCP probe는 포트 연결만 증명한다. Hikari pool, JWT revocation Redis failure, 애플리케이션 오류를 함께 확인한다.
- `minio`: `/minio/health/live`와 용량, backend/worker의 object storage 오류를 확인한다.
- 연결 복구 후 `/admin/recovery`에 분석·삭제·이메일 DEAD_LETTER가 남았는지 확인한다.

## BackendHttp5xxRateHigh

- Grafana에서 5xx 발생 시작 시각과 요청률을 비교한다.
- backend JSON 로그에서 status 5xx와 같은 requestId를 찾는다.
- DB/Redis/MinIO/엔진 probe, Hikari pool, JVM heap을 함께 확인한다.
- 단일 사용자 데이터 문제라면 관리자 사용자 상세에서 결과 상태를 확인하되 기술 지표를 관리자 화면에 복제하지 않는다.

## BackendHttpLatencyP95High

- URI별 요청률과 p95를 확인한다.
- Hikari 포화, JVM GC/heap, 컨테이너 CPU·메모리, 외부 엔진 latency를 순서대로 확인한다.
- 긴 분석 자체는 worker에서 실행되므로 API p95가 긴 영상 처리 시간과 같아서는 안 된다.

## BackendJvmHeapUsageHigh

- heap 추세와 GC 빈도를 확인한다.
- 컨테이너 memory limit 및 OOM/restart 흔적을 확인한다.
- 대용량 결과 JSON, 업로드 buffering, 캐시·컬렉션 누수를 우선 조사한다.

## BackendDatabasePoolSaturated

- Hikari active/max와 HTTP p95·5xx를 함께 확인한다.
- MySQL probe, slow query, 장기 transaction, connection leak를 확인한다.
- 단순 pool 크기 상향 전에 DB의 실제 동시 처리 여력과 쿼리 병목을 확인한다.

## AnalysisQueueNearCapacity

- worker up, running 수, 완료/실패율, 평균 분석 시간을 확인한다.
- 엔진 probe와 외부 모델 fallback/timeout을 확인한다.
- worker 확장은 DB claim 안전성과 호스트 CPU·메모리 여유를 확인한 뒤 수행한다.
- 이미 재시도를 소진한 항목은 `/admin/recovery`에서 별도로 처리한다.

## DEAD_LETTER 알림

- Grafana/Alertmanager: 건수·발생 시각과 기반 장애 여부를 확인한다.
- `/admin/recovery`: 실패 사유, 시도 횟수, 대상 ID와 만료 시각을 확인한다.
- 원인이 제거되기 전에 반복 재큐잉하지 않는다.
- 재큐잉 조치가 `/admin/audit-logs`에 기록됐는지 확인한다.

## 백업 알림

- `docs/ops/backup-restore-runbook.md`를 따른다.
- 백업 성공 표시는 복구 가능성을 증명하지 않으므로 정기 복구 리허설 결과까지 확인한다.
