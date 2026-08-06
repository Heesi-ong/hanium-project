# 운영 배포 전 체크리스트

이 문서는 로컬/개발 docker-compose 환경에서 실제 운영 서버로 처음 배포하기 전에 확인해야 할
값과 결정 사항을 모은 것이다. 개별 항목의 자세한 배경은 각 항목이 가리키는 파일/문서를 참고한다.

## 1. 반드시 바꿔야 하는 기본 시크릿 (`.env`)

아래 값은 모두 `.env.example`에 개발용 placeholder(`changeme`, `change-me-to-...`)로 들어 있다.
운영 배포 전 전부 강력한 무작위 값으로 교체해야 한다. `SECURITY_JWT_SECRET`은 기본값을 그대로
두면 백엔드가 fail-fast로 기동을 거부하도록 코드에 이미 안전장치가 있지만(`docker-compose.yml`),
나머지 값들은 그대로 둬도 애플리케이션이 정상 기동은 되므로 별도로 직접 확인해야 한다.

| 환경변수 | 기본값(placeholder) | 비고 |
| --- | --- | --- |
| `DB_PASSWORD` | `changeme` | MySQL 앱 계정 비밀번호 |
| `DB_ROOT_PASSWORD` | `changeme` | MySQL root 비밀번호 |
| `REDIS_PASSWORD` | `changeme` | Redis 인증 비밀번호 |
| `MINIO_ROOT_PASSWORD` | `changeme12345` | MinIO 관리자 비밀번호 |
| `GRAFANA_ADMIN_PASSWORD` | `changeme` | Grafana 관리자 비밀번호 |
| `SECURITY_JWT_SECRET` | `change-me-to-a-strong-random-secret-in-production` | JWT 서명 키. 기본값 유지 시 백엔드가 기동을 거부함(fail-fast) |
| `BACKUP_ENCRYPTION_PASSPHRASE` | (빈 값) | 원격 반출되는 MySQL 백업 암호화 키. 백업 파일과 분리된 비밀 관리자에 보관 |

Redis 장애 시 JWT 폐기 원장의 DB fallback이 장시간 대기하지 않도록
`REDIS_CONNECT_TIMEOUT_MS`와 `REDIS_COMMAND_TIMEOUT_MS`는 기본 2000ms로 설정된다.
두 값은 0보다 커야 하며 잘못된 값이면 backend가 기동 단계에서 실패한다. 운영 네트워크
지연을 반영해 조정하더라도 JWT 인증 요청의 허용 지연과 함께 검토한다.

`docker-compose.prod.yml`을 함께 쓰는 운영 배포에서는 `SPRING_PROFILES_ACTIVE=prod`가 강제되며,
DB/Redis/JWT/MinIO/백업 암호화 시크릿은 비어 있으면 compose 설정 단계에서 바로 실패한다.
또한 `BACKUP_ENCRYPTION_REQUIRED=true`와 `BACKUP_REMOTE_REQUIRED=true`가 강제되므로 평문 MySQL 덤프가 생성되지 않고, MinIO 원격 반출 실패도 백업 프로세스 실패로 처리된다.

## 2. 실제 도메인/공개 URL로 바꿔야 하는 값

| 환경변수 | 기본값 | 운영에서 해야 할 일 |
| --- | --- | --- |
| `DOMAIN` | `example.com` | nginx/certbot TLS 구성이 사용할 실제 도메인으로 변경. `docker-compose.prod.yml`이 이 값을 필수로 요구함 |
| `CERTBOT_EMAIL` | (없음) | Let's Encrypt 약관/만료 알림에 사용할 실제 이메일. `docker-compose.prod.yml`이 필수로 요구함 |
| `MINIO_PUBLIC_ENDPOINT` | `http://localhost:9000` | 브라우저가 영상 스트리밍 리다이렉트를 따라갈 때 접속하는 주소. 실제 공개 도메인/포트로 변경하지 않으면 운영 사용자는 영상을 재생할 수 없음 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | 실제 프론트엔드 배포 도메인으로 변경하지 않으면 브라우저가 API 호출을 CORS로 차단함 |
| `VITE_API_BASE_URL` | `http://localhost:8080` | `docker-compose.prod.yml`은 nginx 동일 origin `/api` 프록시를 쓰도록 이미 빈 문자열로 오버라이드하므로, `docker-compose.prod.yml`을 함께 쓰면 별도 조치 불필요. 프론트를 독립 배포한다면 직접 확인 |
| `SECURITY_JWT_COOKIE_SECURE` | `false` | `application-prod.yml`과 `docker-compose.prod.yml`이 `true`로 기본 적용해 인증 쿠키가 HTTPS에서만 전송되도록 함 |
| `SECURITY_API_DOCS_PUBLIC_ENABLED` | `true` | `application-prod.yml`과 `docker-compose.prod.yml`이 `false`로 기본 차단해 `/v3/api-docs`와 `/swagger-ui/**` 공개 노출을 막음 |

첫 기동 때 nginx는 인증서 파일이 없으면 1일짜리 임시 self-signed 인증서를 만들어 먼저 뜬다.
이후 `certbot` 서비스가 webroot 방식으로 실제 인증서를 발급한다. 최초 발급 직후에는 nginx가
아직 임시 인증서를 잡고 있을 수 있으므로 `docker compose -f docker-compose.yml -f docker-compose.prod.yml restart nginx`
로 한 번 재시작해 실제 인증서를 물린다.

## 3. 기본값이 비어 있어 기능이 꺼진 채로 배포되는 값

| 환경변수 | 기본값 | 비어 있을 때 동작 | 채워야 하는 시점 |
| --- | --- | --- | --- |
| `SMTP_HOST` | (빈 값) | 비밀번호 재설정 이메일을 실제로 보내지 않고 dev에서는 로그로만 남김, prod에서는 링크를 로그에도 남기지 않음 | 비밀번호 재설정 기능을 실제로 쓰게 할 시점 |
| `ADMIN_EMAILS` | (빈 값) | 관리자 대시보드(`/api/admin/**`)에 접근 가능한 계정이 없음 | 관리자 대시보드를 실제로 쓰게 할 시점 |
| `VIDEO_LLM_POLICY` | `DISABLED` | NVIDIA 실연동 대신 명시적 MOCK 응답만 생성 | `README.md` "5.1 Video LLM 실제 모델 활성화"에서 STRICT/DEGRADED 실패 정책을 결정한 뒤 |
| `VIDEO_LLM_ENABLED` | `false` | 정책 값이 비었을 때만 DISABLED로 해석되는 하위 호환 스위치 | 기존 배포 마이그레이션 시 정책 값과 의미를 맞출 때 |
| `OPENAI_ENABLED` | `false` | OpenAI 피드백 생성 대신 폴백 처리 | OpenAI 키를 확보하고 비용 정책을 확인한 뒤 |

### 3.1 관리자(ADMIN) 부여·회수 절차 (2026-08-03 P0 수정)

과거에는 `ADMIN_EMAILS`에 등록된 이메일로 공개 회원가입/로그인만 해도 이메일 소유 확인 없이
즉시 ADMIN이 부여됐다(실제 재현 확인된 취약점). 관리자 이메일을 아는 누구나 그 주소로 먼저
가입하면 관리자 계정을 선점할 수 있었다. 지금은 공개 `POST /api/auth/signup`,
`POST /api/auth/login` 어느 경로로도 ADMIN이 생성되지 않는다(항상 USER로만 생성/유지).

**부여 절차 (out-of-band, 공개 HTTP 밖에서만 가능)**

1. 관리자로 만들 사람이 먼저 일반 사용자로 회원가입한다(USER로 생성됨).
2. 운영자가 `.env`(또는 배포 환경변수)의 `ADMIN_EMAILS`에 그 이메일을 추가한다(쉼표로 여러 개
   구분 가능).
3. backend(및 analysis-worker, 역할 분리 배포라면 두 서비스 모두)를 재기동한다.
4. 기동 시 `AdminRoleSyncRunner`가 `ADMIN_EMAILS`와 **이미 존재하는** 사용자의 role을
   동기화한다. 아직 가입 전인 이메일은 아무 효과가 없다(다음 재기동 때 그 사람이 가입해
   있으면 그때 승격된다) — 이 러너는 새 사용자를 만들지 않는다.
5. 로그: 승격/강등이 실제로 일어나면 `ADMIN_ROLE_SYNC_PROMOTED`/`ADMIN_ROLE_SYNC_DEMOTED`가
   backend 로그에 남는다.

**회수 절차**: `ADMIN_EMAILS`에서 이메일을 빼고 재기동하면 해당 사용자는 다음 기동 시 USER로
자동 강등된다(대칭 동작). 즉시 로그인 세션까지 끊어야 하면 관리자 화면에서 별도로 강제
탈퇴/정지 조치를 병행한다.

주의: 이 절차는 "누가 먼저 가입해서 이메일을 선점하는 것"을 막지 못한다 — 목표 관리자 이메일로
아직 아무도 가입하지 않았다면, 그 이메일로 먼저 가입하는 사람이 이후 `ADMIN_EMAILS`에 등록될 때
승격 대상이 된다. 운영 전 관리자 이메일로 직접 먼저 가입해 선점해 두는 것을 권장한다.

## 4. 모니터링 알림 필수값

`docker-compose.monitoring.yml`의 Alertmanager는 실제 SMTP 설정 없이는 fail-fast로 종료된다.
모니터링 오버레이를 켜기 전 아래 값을 채우고, `infra/alertmanager/secrets/smtp_password` 파일을
실제 SMTP 비밀번호로 만든다. 이 비밀번호 파일은 `.gitignore`로 커밋되지 않는다.

| 환경변수 | 예시 | 비고 |
| --- | --- | --- |
| `ALERT_SMTP_SMARTHOST` | `smtp.example.com:587` | SMTP 서버와 포트 |
| `ALERT_SMTP_FROM` | `alerts@example.com` | 알림 발신 주소 |
| `ALERT_SMTP_USERNAME` | `alerts@example.com` | SMTP 인증 사용자 |
| `ALERT_EMAIL_TO` | `oncall@example.com` | 알림 수신 주소 |

## 5. 배포 전 별도로 실행해서 확인해야 하는 것

- MySQL healthcheck는 root 비밀번호로 TCP `SELECT 1`까지 실행하므로 `healthy`가 되어야 한다.
  `unhealthy`이면 단순 포트 상태가 아니라 `DB_ROOT_PASSWORD`, 초기 스키마 생성, 데이터 디렉터리
  권한을 `docker compose logs mysql`에서 확인한다.
- V21/V24 SHA-256 해시 컬럼(`revoked_access_tokens.token_hash`,
  `analysis_jobs.reanalysis_idempotency_key_hash`)이 MySQL `CHAR(64)`이며 JPA schema validate와
  일치하는지 확인한다. 빈 MySQL 8.4에 V1부터 전체 적용하는 CI `backend-boot-smoke`를
  생략하지 않는다.
- MySQL 8.4 기동 로그에서 현재 Flyway의 공식 검증 범위가 8.1까지라는 경고가 남는다.
  운영에서는 MySQL을 검증된 버전으로 고정하거나 Flyway 호환 버전을 올린 뒤 빈 DB에
  V1부터 전체 migration과 `ddl-auto: validate`를 다시 통과시킨다.
- 재분석 child가 남은 source 결과 삭제가 HTTP 409로 거부되는지 확인한다. 삭제는 child부터
  수행하며, child 삭제 때는 공유 원본 asset과 upload prefix가 유지되고 마지막 source 삭제
  때만 asset과 upload 삭제 Outbox가 생성되어야 한다.
- MinIO를 의도적으로 중단한 상태에서 삭제 Outbox가 `PENDING`과 오류·다음 재시도 시각을
  보존하는지, MinIO 복구 후 `COMPLETED`로 바뀌고 실제 객체가 사라지는지 확인한다. 운영
  인수 전에는 재시도 소진→`DEAD_LETTER`→관리자 목록 조회→재큐잉→감사로그→실제 삭제까지
  리허설한다. 2026-07-23 격리 MySQL·실제 MinIO 검증에서는 이 전체 흐름이 통과했다.
- `analysis-engine`과 `video-llm-engine`이 `healthy`가 된 뒤에만 backend와 analysis-worker가
  시작되는지 `docker compose ps`로 확인한다. analysis-engine 최초 기동은 Whisper/MediaPipe
  모델 다운로드와 풀 프리로드 때문에 시간이 걸릴 수 있으며, 10분의 healthcheck 준비 시간을
  넘기면 `docker compose logs analysis-engine`에서 다운로드·메모리·풀 크기 설정 오류를 확인한다.
- Redis도 비밀번호 인증을 포함한 healthcheck가 `healthy`가 된 뒤 backend와 analysis-worker가
  시작되어야 한다. `unhealthy`이면 `REDIS_PASSWORD`가 Redis 컨테이너와 두 Spring 서비스에
  동일하게 전달됐는지 먼저 확인한다.
- Redis를 중단한 상태에서 기존 폐기 토큰이 계속 401인지, 신규 로그아웃이 DB
  `revoked_access_tokens` 원장에 기록되는지, 정상 토큰은 DB 확인 후 200인지 검증한다.
  각 요청이 설정한 Redis timeout 안팎에서 끝나야 하며 60초 기본 timeout으로 대기하면 안 된다.
  Redis 복구 후 장애 중 폐기된 토큰을 두 번 조회해 첫 요청이 DB 원장을 Redis에 다시 적재하고
  다음 요청이 `security_jwt_revocation_total{result="redis_hit"}`를 증가시키는지도 확인한다.
- 운영 nginx는 backend가 rate limit용으로 신뢰하는 `X-Forwarded-For`를 `$remote_addr`로
  덮어쓴다. 별도 로드밸런서/CDN을 nginx 앞에 둘 경우 nginx real-ip 설정을 먼저 확정하지 않으면
  모든 사용자가 로드밸런서 IP로 집계될 수 있다.
- `minio-init`이 종료 코드 0으로 끝나 사용자 파일 버킷과 백업 버킷을 만든 뒤에만 backup,
  backend, analysis-worker가 시작되어야 한다. 세 서비스가 대기 상태라면
  `docker compose logs minio-init`에서 MinIO 계정·버킷 이름·네트워크 오류를 확인한다.
- 전체 기동 후 운영 네트워크 안에서 `X-Internal-Api-Key`를 사용해 두 엔진의
  `GET /api/internal/readiness`가 성공하는지 확인한다. 상세 readiness를 공개 상태 API나 외부
  네트워크에 노출하지 않으며, 단순 `/health` 성공만으로 분석 API 인증까지 검증됐다고 판단하면 안 된다.
- 실제 모델 정책으로 배포했다면 Video LLM readiness 응답의 `mode=REAL`,
  `policy=STRICT|DEGRADED`, `realModelReady=true`인지 확인한다. `health`가 `up`이어도
  readiness가 `ready=false`이면 API 키, timeout, 영상 크기 상한, NVIDIA base URL 설정을
  먼저 수정한다.
- `VIDEO_LLM_CHUNK_DURATION_SECONDS`가 backend, analysis-worker,
  video-llm-engine 세 서비스에 같은 값으로 전달되는지 `docker compose config`에서
  확인한다. 값이 어긋나면 월간 예산 예약 단위와 실제 NVIDIA 세그먼트 호출 수가 달라진다.
- MySQL 백업/복구가 실제로 동작하는지: `docs/ops/backup-restore-runbook.md`의 리허설 절차를 운영
  DB 인스턴스 기준으로 최소 1회 재현한다.
- `backup` 서비스는 전용 멀티 아키텍처 이미지를 빌드해 `mysqldump`, `openssl`, `mc`를 포함한다.
  첫 배포에서는 `docker compose logs backup`에서 암호화 완료와 MinIO 원격 반출 완료를 확인하고,
  `mysql_backup_remote_export_last_run_status 1`을 확인한 뒤 버킷에서 내려받은 객체를 별도 복구
  인스턴스에 실제 복원한다. `MysqlBackupRemoteExportFailed` 또는 `MysqlBackupRemoteExportStale`이
  firing이면 로컬 백업 성공만으로 배포 검증을 통과시키지 않는다.
- MinIO 백필이 필요한 기존 로컬 파일이 있다면: `docs/ops/minio-migration-rehearsal.md`를 참고해
  `STORAGE_BACKFILL_ENABLED=true`로 1회 실행한다(신규 배포라 기존 로컬 파일이 없다면 불필요).
- 스케줄러 분산 락의 Redis fail-open 정책: `docs/ops/scheduler-distributed-lock.md`를 운영팀이
  숙지했는지 확인한다.
- 비밀번호 재설정 이메일 실발송: `SMTP_HOST` 등을 설정한 뒤 실제 계정으로 `/forgot-password`에서
  재설정을 요청해 **받은 편지함에 메일이 도착하는지** 1회 확인한다. prod에서 `SMTP_HOST`가 비어
  있으면 기동 로그에 `PASSWORD_RESET_MAIL_NOT_CONFIGURED` 경고가 남으므로, 배포 직후 이 경고가
  없는지도 함께 확인한다. (백엔드는 이메일 미설정으로 기동을 막지는 않는다.)

## 6. 이 문서에서 다루지 않는 것

- 코드 레벨 결함/버그는 이 문서의 범위가 아니다. `docs/service-plan/additional-gap-analysis.md`의
  최신 업데이트 절을 참고한다.
- 이 체크리스트는 위 항목이 "존재한다"는 것만 정리한 것이고, 각 값을 실제로 얼마로 정할지는
  배포 담당자의 운영 판단이 필요하다.
