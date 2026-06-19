# 분석 워커 분리 준비

현재 정책은 로컬 단일 서버이므로 FastAPI 프로세스 내부 워커를 계속 사용한다.

향후 별도 프로세스로 분리할 때는 백엔드에 `DISABLE_BACKGROUND_SERVICES=true`를 설정하고,
별도 프로세스에서 다음 명령을 실행한다.

```bash
.venv/bin/python scripts/analysis-worker-run.py
```

두 방식을 동시에 활성화하지 않는다. 작업 선점은 MySQL 트랜잭션을 사용하지만, 유지보수
작업과 heartbeat 상태가 중복 실행될 수 있다. 분리 전에는 재시작 복구, 취소, 중복 선점,
유지보수 작업을 운영 환경과 동일한 설정으로 검증해야 한다.
