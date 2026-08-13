# 학생 프로젝트 로컬/테스트 기능 고도화 완료 보고서

- 완료일: 2026-08-13
- 대상: `backend`, `frontend`, `analysis-engine`, `video-llm-engine`, 로컬 Compose, CI 검증 계약
- 완료 범위: 공개 배포 없이 한 대의 개발 PC에서 재현 가능한 업로드·비동기 분석·진행 확인·결과 조회·삭제 흐름

## 1. 최종 판단

현재 프로젝트는 학생 프로젝트의 로컬 시연과 통제된 테스트에 필요한 핵심 기능을 갖췄다.
MySQL, Redis, MinIO와 네 애플리케이션 서비스를 분리 실행할 수 있고, 영상 업로드 뒤 독립
워커가 작업을 선점해 정량 분석을 수행한다. 브라우저는 새로고침 뒤에도 진행 상태를 복구하고,
결과 조회와 보호된 원본 영상 접근까지 이어진다.

이번 완료 판정은 실제 공개 서비스 준비를 뜻하지 않는다. production 호스트, 공개 도메인,
상용 SMTP, 실사용자 개인정보 고지, 상시 관제, 원격 백업, 유료 AI 호출은 사용자가 정한 현재
프로젝트 범위에서 제외했다.

## 2. 완료 기준과 근거

| 기준 | 현재 구현과 확인 결과 |
|---|---|
| 실행 단위 | `backend`, `analysis-worker`, `frontend`, `analysis-engine`, `video-llm-engine`이 Compose에서 분리 실행되고 모두 healthy 상태를 확인했다. |
| 데이터베이스 | MySQL 8.4와 Flyway V1~V26을 사용하며, 빈 DB 기동과 현재 스키마 검증을 통과했다. |
| 비동기 분석 | 업로드/실행 접수와 실제 분석을 분리하고 Redis·DB 기반 대기열, worker claim, 재시도·취소·watchdog 경계를 둔다. |
| 사용자 상태 복구 | 업로드 작업 식별자를 브라우저에 보존하고 새로고침 뒤 status/progress polling을 재개한다. 일시적 503도 자동 재시도한다. |
| 분석 진행 로그 | 네 서비스 로그를 `jobId`로 모아 QUEUE, BASIC 1~9, VIDEO_LLM, COMPACT, OPENAI, MERGE, DONE 순서로 터미널에 표시한다. |
| 인증·소유권 | HttpOnly JWT 쿠키, 사용자별 job/result 접근 검증, 내부 엔진 공유키, 영상 접근 토큰을 유지한다. |
| 외부 AI 구분 | Video LLM은 `DISABLED/DEGRADED/STRICT`, 피드백은 `SKIPPED/MOCK/FALLBACK/REAL` 의미를 구분하며 UI와 저장 결과가 실제 실행 경로를 표시한다. |
| 파일 보호·정리 | 로컬 파일과 MinIO 경계를 두고 회원탈퇴/결과 삭제를 삭제 outbox로 처리한다. 성공 E2E의 업로드·결과 삭제 작업 2건이 `COMPLETED`가 됐다. |
| 계약·회귀 검증 | backend, frontend, 두 Python 엔진의 전체 테스트와 정적 검사, Docker 빌드, 실제 샘플 영상 브라우저 E2E를 통과했다. |
| 로컬 범위 문서 | README, 구조 문서, 환경변수 예시와 Compose 기본값이 공개 운영이 아닌 로컬/테스트 기준으로 정렬됐다. |

## 3. 마지막 검증에서 발견하고 수정한 문제

### 상태 조회 rate limit 불일치

업로드 화면은 분석 중 status를 1.5초마다, progress를 1초마다 조회한다. 기존 status bucket
90회/분은 정상 UI 요청 약 100회/분보다 작아, 약 1분이 걸리는 실제 분석에서 `429`가 발생했다.

- backend 기본값, Compose와 `.env.example`을 120회/분으로 맞췄다.
- E2E가 UI와 별도로 status API를 반복 조회해 사용량을 왜곡하던 중복 polling을 제거했다.
- 수정 후 동일 샘플 영상 분석 E2E가 1분 24초에 완료됐다.

### 과거 분석 로그의 서비스별 묶음 출력

Compose의 비-follow 로그는 컨테이너별로 묶일 수 있어, 표시 시각은 맞아도 분석 단계가 시간순으로
보이지 않았다.

- Docker timestamp를 요청하고 `--no-follow` 모드에서만 RFC3339 시각으로 안정 정렬한다.
- 실시간 follow 모드는 버퍼링 없이 기존 스트리밍을 유지한다.
- 실제 완료 job으로 QUEUE부터 DONE 100%까지 시간순 출력을 확인했다.

## 4. 실행한 최종 검증

- backend: `./gradlew clean test --rerun-tasks` 성공
- frontend: ESLint 성공, Vitest 51개 파일·288개 테스트 성공, Vite production build 성공
- analysis-engine: pytest 169개 성공, Ruff와 compileall 성공
- video-llm-engine: 비-live pytest 199개 성공, NVIDIA live 1개는 키가 없어 제외, Ruff와 compileall 성공
- 분석 로그 뷰어: shell 회귀 테스트 성공, 실제 네 서비스 로그 시간순 출력 확인
- Docker: 변경된 backend/worker 이미지 빌드 성공, MySQL/Redis/MinIO/네 애플리케이션 서비스 healthy 확인
- 실제 브라우저 E2E: 회원가입·로그인 → 업로드 → worker claim → 정량 분석 → 새로고침 복구 →
  일시적 503 재시도 → 결과 조회 → 영상 접근 토큰 → 회원탈퇴·삭제 outbox 완료 성공
- 실제 분석 결과: 기본 분석 1~9단계, 총점 계산, Video LLM `DISABLED`, OpenAI `SKIPPED`,
  최종 `COMPLETED`와 진행률 100% 확인

## 5. 현재 범위에서 제외한 항목

다음은 결함을 숨긴 것이 아니라 사용자가 정한 학생 프로젝트 범위 밖의 작업이다.

- 공개 도메인·TLS·production 배포와 GHCR release
- 실제 사업자/개인정보 보호책임자 정보와 법률 검토
- 상용 SMTP 발송, Alertmanager 실수신, 원격 암호화 백업·복구
- 실제 NVIDIA/OpenAI API 키를 사용한 품질·비용 acceptance
- 결제, 요금제, 다중 지역·고가용성 운영

이 항목이 필요해지는 시점에는 현재 로컬 완료 판정을 그대로 운영 준비 완료로 해석하지 말고,
별도의 서비스화 갭 리뷰와 보안·부하·복구 검증을 다시 수행해야 한다.

## 6. 선택적 후속 개선

현재 사용자 흐름을 막는 필수 잔여 작업은 없다. 아래는 기능 완성 조건이 아닌 장기 유지보수
개선이다.

- 결과의 `feedback`, `pipeline`, 엔진 세부 응답을 단계적으로 typed DTO로 전환
- `AnalysisCommandService`의 실행 순서를 흐리지 않는 범위에서 추가 orchestration 분리 검토
- 실제 외부 provider를 사용할 때만 수동 live acceptance와 비용 상한 검증
- 팀 규모가 커질 때 coverage 하락 기준과 OpenAPI consumer diff 도입
