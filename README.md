# Presentation Coaching + Local AI Chat

기존 영상 발표 분석 기능과 로그인 기반 로컬 Ollama 채팅을 함께 제공한다.

## 실행 순서

1. 프로젝트 전용 MySQL을 실행한다.
2. Ollama를 실행하고 `.env`의 `OLLAMA_MODEL`을 설치한다.
3. 백엔드를 실행한다.
4. 프론트엔드를 실행한다.

```bash
./scripts/mysql-start.sh

ollama serve
ollama pull qwen3:4b

cd Back
../.venv/bin/pip install -r requirements.txt
../.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000

cd ../Front
npm install
npm run dev
```

프로젝트 전용 MySQL 데이터는 `.runtime/mysql-data`에 있으며 Git에서 제외된다.
MySQL을 종료할 때는 `./scripts/mysql-stop.sh`를 사용한다. 데이터베이스를 새로
만드는 환경에서는 `Back/migrations` 아래 SQL 파일을 번호 순서대로 적용해야 한다.
백엔드를 백그라운드 사용자 서비스로 실행하거나 종료하려면 각각
`./scripts/backend-start.sh`, `./scripts/backend-stop.sh`를 사용한다.
프론트엔드를 백그라운드에서 실행하거나 종료하려면 각각
`./scripts/frontend-start.sh`, `./scripts/frontend-stop.sh`를 사용한다.

백엔드는 `Back/.env`를 읽는다. 로컬 Ollama는 API 키를 사용하지 않으며
`OLLAMA_API_KEY`가 비어 있어도 정상 동작한다. 개발 중 Vite는 `/analyze`와
`/api`를 `http://127.0.0.1:8000`으로 프록시한다.

## 채팅 API

로그인 성공 시 발급되는 HttpOnly `session_token` 쿠키가 필요하다.

```http
POST /api/conversations/1/chat
Content-Type: application/json

{"content":"로그인 기능을 쉽게 설명해줘"}
```

```json
{
  "userMessage": {"id": 1, "role": "user", "content": "로그인 기능을 쉽게 설명해줘"},
  "assistantMessage": {"id": 2, "role": "assistant", "content": "로그인은 사용자가 본인임을 확인하는 과정입니다."},
  "model": {"provider": "ollama", "modelKey": "qwen3:4b"},
  "usage": {"inputTokens": 100, "outputTokens": 30, "totalTokens": 130, "estimatedCost": 0}
}
```

질문과 답변은 `messages`, 대화 정보는 `conversations`, 토큰 사용량과 비용은
`gpt_usage`에 저장된다. 로컬 모델의 `estimated_cost`는 항상 0이다.
Ollama 호출 실패 시 사용자 메시지는 삭제하지 않고 `metadata.chatStatus=failed`로 남긴다.
서버가 비정상 종료되어 `pending` 상태가 오래 유지된 메시지는 다음 시작 시
`CHAT_PENDING_TIMEOUT_MINUTES`를 기준으로 실패 상태로 복구한다.

## Ollama 연결 오류

```bash
curl http://127.0.0.1:11434/api/tags
ollama list
ollama pull qwen3:4b
```

- 503: Ollama 미실행, 설정 모델 미설치, 잘못된 응답을 확인한다.
- 504: `OLLAMA_TIMEOUT_SECONDS`와 시스템 자원을 확인한다.
- `.env`의 `OLLAMA_BASE_URL`, `OLLAMA_MODEL`과 `gpt_models` 등록값을 맞춘다.

## MySQL Workbench 확인 SQL

```sql
USE gpt_conversation_app;

SELECT id, email, display_name, last_login_at FROM users ORDER BY id DESC;
SELECT id, user_id, title, updated_at FROM conversations ORDER BY id DESC;
SELECT conversation_id, sequence_number, role, content, metadata
FROM messages ORDER BY conversation_id DESC, sequence_number;
SELECT user_id, conversation_id, input_tokens, output_tokens, total_tokens, estimated_cost
FROM gpt_usage ORDER BY id DESC;
```

Workbench에 연결이 보이지 않으면 홈 화면의 `+`에서 Host `127.0.0.1`,
Port `3307`, 사용자 계정을 직접 등록한다. MySQL 서버가 실행 중이어야 한다.

## 기존 분석 기능

시스템 PATH에 `ffmpeg`가 필요하다. 메인 페이지(`/`)는 공개되어 있지만,
영상 분석(`/upload`, `/results`, `/result/:id`)과 AI 코치(`/chat`)는 로그인 후
사용할 수 있다. 분석 업로드는 즉시 작업 ID를 반환하고 MySQL 기반 작업 큐에서
처리된다. 처리 상태와 단계별 진행률은 `/analyze/job/:id`, 사용자별 이력은
페이지네이션을 지원하는 `/analyze/results`에서 확인한다. 서버 재시작 시 처리
중이던 작업은 대기열로 복구되어 다시 실행된다.

분석 작업은 로그인 사용자별로 격리되며 다른 사용자의 작업 ID를 알아도 조회하거나
삭제할 수 없다. 로그인, 회원가입, 영상 업로드, AI 채팅에는 단일 백엔드 인스턴스
기준의 메모리 요청 제한이 적용된다.

실패 또는 취소 작업의 원본은 기본 24시간 보존되어 재시도할 수 있다. 완료 결과는
성장 추이 화면, Markdown 보고서, AI 코치 상담 문맥으로 활용할 수 있다. 핵심 점수와
지표는 MySQL에 저장되고 상세 타임라인과 분석 데이터는 JSON 파일로 분리된다.
프레임은 작업 ID별 임시 디렉토리에 저장되며 작업 종료 시 삭제된다. 비정상 종료로
남은 프레임 디렉토리는 서버 시작과 주기 정리 시 처리 중 작업을 제외하고 삭제된다.
주기 정리는 분석 처리와 분리된 유지보수 스레드에서 실행된다. `/readiness`에서 DB,
분석 워커, 유지보수 스레드, Ollama 모델, 작업 큐, 디스크 여유 공간을 함께 확인할 수
있다. `MIN_FREE_DISK_MB`보다 디스크 여유 공간이 부족하면 새 업로드를 거부한다.
관리자는 `/api/admin/status`에서 동일한 운영 상태를 인증된 요청으로 조회할 수 있다.
완료 결과 자동 정리는 기본적으로 비활성화되어 있다. 운영 정책을 확정한 뒤
`ANALYSIS_RESULT_RETENTION_DAYS`를 1 이상의 값으로 설정하면 해당 기간이 지난
완료 결과의 DB 레코드와 JSON 파일을 주기적으로 함께 삭제한다.

## 사용자 및 대화 관리

- `/account`: 표시 이름 변경, 비밀번호 변경, 전체 로그아웃, 계정 탈퇴
- `/growth`: 완료된 발표 분석의 점수와 주요 지표 변화 비교
- AI 코치: 대화 이름 변경, 보관, 삭제, 응답 복사, 마지막 응답 재생성
- Ollama에는 전체 대화 대신 최근 `CHAT_HISTORY_MESSAGES`개 메시지만 전달한다.

## 검사

프로젝트 루트에서 실행한다.

```bash
./scripts/verify.sh
```

개발 의존성은 `.venv/bin/pip install -r Back/requirements-dev.txt`로 설치한다.
MySQL, Ollama, 백엔드, 프론트엔드를 실행한 상태의 읽기 전용 통합 검증은
`./scripts/verify-service.sh`로 수행한다.
실제 회원가입, 영상 업로드, 분석 결과, Ollama 코칭, 계정 삭제까지의 브라우저
E2E는 실행 중인 로컬 서비스와 Playwright Chromium을 사용해
`./scripts/verify-browser-e2e.sh`로 수행한다. 테스트용 영상과 계정은 실행 중
생성되며 완료 또는 실패 후 정리된다.

세부 테스트 기준은 `docs/TEST_CRITERIA.md`, 운영 적용 전 확인사항은
`docs/PRODUCTION_CHECKLIST.md`에서 확인한다.

## 데이터베이스 백업 및 마이그레이션

스키마 변경 전 `./scripts/db-backup.sh`로 SQL 백업을 생성한다. 백업은
`.runtime/backups`에 저장되며 Git에서 제외된다. 마이그레이션은
`.venv/bin/python scripts/migrate.py`로 적용하며 적용 이력은
`schema_migrations`에 기록된다. 같은 명령을 다시 실행해도 적용된 변경은 반복하지
않는다. 복원은 운영 서비스를 중지하고 아래처럼 명시적인 확인값과 함께 실행한다.

```bash
CONFIRM_RESTORE=gpt_conversation_app ./scripts/db-restore.sh .runtime/backups/<backup.sql>
```
