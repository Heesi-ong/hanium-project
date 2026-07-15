# 스케줄러 분산 락 운영 노트

## 무엇을 위한 것인가

`backend`를 2대 이상 인스턴스로 띄우면, `@Scheduled`로 도는 다음 두 작업이 모든 인스턴스에서
동시에 실행됩니다:

- `StorageCleanupService` — 오래된 temp 파일과 DB에 없는 고아 업로드/결과 디렉토리 정리
- `StuckAnalysisJobWatchdogService` — 오래 멈춰 있는 분석 작업을 FAILED로 전환

`SchedulerDistributedLock`(`backend/src/main/java/com/hanium/presentation/global/config/SchedulerDistributedLock.java`)은
Redis `SETNX` 기반 락으로 이 두 스케줄러가 한 번에 하나의 인스턴스에서만 실행되도록 합니다.

## Fail-open 정책

Redis 호출 자체가 실패하면(연결 끊김, 타임아웃, Redis 다운 등) `tryLock()`은 예외를 삼키고
`true`를 반환합니다 — 락을 못 걸어도 스케줄러를 그냥 실행시킨다는 뜻입니다. 이때 최초 1회
`WARN` 로그(`Redis 스케줄러 분산 락 사용 실패 - 락 없이 스케줄러를 실행합니다`)를 남기고,
같은 상태가 반복되는 동안은 로그를 스팸하지 않습니다. Redis가 복구되면 `INFO` 로그
(`Redis 스케줄러 분산 락 연결이 복구되었습니다.`)를 한 번 남깁니다.

**의도한 트레이드오프**: 정리 작업/워치도그가 Redis 장애 때문에 완전히 멈추는 것보다, 인스턴스가
1대뿐인 대부분의 운영 상황에서는 계속 도는 편이 안전하다고 판단했습니다. 다만 인스턴스를 2대
이상으로 늘려 운영할 경우, Redis 장애 구간에서는 이 두 스케줄러가 여러 인스턴스에서 중복
실행될 수 있습니다.

## 실제로 문제가 되는 경우

- `StorageCleanupService`가 중복 실행되면 같은 정리 작업을 두 번 하는 정도라 영향이 작습니다
  (idempotent에 가까움).
- `StuckAnalysisJobWatchdogService`가 중복 실행되는 것 자체는 같은 job을 두 인스턴스가
  동시에 FAILED 처리하려는 것이라 영향이 제한적이지만, `AnalysisJob`에 걸린 낙관적 락
  (`@Version`)이 있어 실제 DB 갱신은 하나만 성공합니다.

## 모니터링

Redis 장애로 인한 fail-open 상태는 현재 로그로만 확인 가능합니다(Prometheus 메트릭 없음).
운영 중 Redis 장애가 의심되면 backend 로그에서 `Redis 스케줄러 분산 락 사용 실패` 문자열을
검색하세요.

## 남은 개선 과제

- fail-open 발생 여부를 Prometheus 메트릭으로 노출하고 Grafana/Alertmanager 알림에 연결하는
  것을 고려할 수 있습니다 (현재는 로그 검색으로만 확인 가능).
- 인스턴스를 2대 이상으로 늘려 운영하기 전에는, 이 문서의 트레이드오프를 인지하고 있어야 합니다.
