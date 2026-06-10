# 서비스 안정화 완료 보고서

기준일: 2026-06-11

## 실행 구조

- 프론트엔드: React/Vite, `127.0.0.1:5173`
- 백엔드: FastAPI, `127.0.0.1:8000`
- 데이터베이스: MySQL, `127.0.0.1:3307`
- AI: 로컬 Ollama `qwen3:4b`
- 분석 실행: MySQL 작업 큐와 백엔드 분석 워커
- 유지보수: 분석 워커와 분리된 만료 파일·고아 프레임 정리 스레드

## 적용 내용

- `/health`와 분리된 `/readiness`에서 DB, 큐, 워커 heartbeat, 유지보수 스레드,
  Ollama 모델, 디스크 여유 공간을 확인한다.
- 관리자 전용 `/api/admin/status`와 `/admin` 운영 상태 화면을 제공한다.
- MySQL 연결 풀과 연결·읽기·쓰기 timeout을 적용했다.
- Ollama 요청 중에는 런타임 풀 연결을 반환하고, 대화 직렬화용 MySQL advisory
  lock만 전용 연결에서 유지한다.
- 중단 분석과 pending 채팅 복구, 분석·채팅 idempotency, 원자적 결과 저장,
  손상 결과 처리, 삭제 실패 시 DB 참조 보존과 재시도를 적용했다.
- 업로드 크기, 사용자 저장 용량, 동시 분석 수, 디스크 여유 공간을 검증한다.
- 분석 결과, 대화, 메시지 목록에 cursor 페이지네이션을 적용했다.
- 결과에 분석 알고리즘 버전과 Ollama 모델 정보를 저장한다.
- 사용자 데이터 내보내기, 저장 공간 확인, 계정 삭제, 보관 대화 복원을 제공한다.
- React Error Boundary, API 요청 취소, 비활성 탭 polling 억제, 접근 가능한 진행률과
  확인 모달을 적용했다.

## 검증 결과

- `./scripts/verify.sh`: 백엔드 45개 테스트, 프론트엔드 9개 테스트, Ruff,
  Prettier, ESLint, 프로덕션 빌드 통과
- 백엔드 커버리지: 46%, CI 최소 기준 45%
- `./scripts/verify-service.sh`: 실제 MySQL, 워커, Ollama, 디스크 readiness 통과
- `./scripts/verify-browser-e2e.sh`: 실제 회원가입, 영상 업로드·분석 완료, 상세 결과,
  Ollama 코칭, 계정 삭제 통과
- 최신 SQL 백업의 `Dump completed` 확인
- 최신 백업을 임시 DB에 복원하고 사용자·대화·분석·마이그레이션 수 검증 후 임시 DB 삭제
- 마이그레이션 두 번 재실행 결과 모두 `Applied migrations: none`
- 주요 cursor 조회 쿼리의 `EXPLAIN`에서 기존 복합 인덱스 사용 확인
- 기존 사용자 1명 보존, E2E 임시 데이터 정리 후 분석·대화·pending 요청 0건 확인
- Ollama `qwen3:4b`, `gemma3:1b` 모델 보존 확인

## 운영 배포 전 잔여 위험

- 현재 분석 워커와 요청 제한은 단일 백엔드 인스턴스 기준이다. 다중 인스턴스 운영
  전 외부 작업 큐와 Redis 기반 rate limit이 필요하다.
- 브라우저의 응답 중단은 HTTP 대기를 취소하지만 이미 시작된 Ollama 생성 자체를
  즉시 중단하지는 않는다.
- GitHub Actions 워크플로 파일과 로컬 동등 검증은 통과했지만, 원격 저장소에서의
  실제 Actions 실행은 push 후 별도로 확인해야 한다.
- 현재 커버리지 기준은 45%다. 운영 전 인증, 채팅, 분석 서비스의 실패 경로 테스트를
  추가하며 기준을 단계적으로 높이는 것이 권장된다.
