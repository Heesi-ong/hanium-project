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
| `VIDEO_LLM_ENABLED` | `false` | NVIDIA 실연동 대신 mock 응답만 생성 | `README.md` "5.1 Video LLM 실제 모델 활성화"의 결정 체크리스트를 먼저 확인한 뒤 |
| `OPENAI_ENABLED` | `false` | OpenAI 피드백 생성 대신 폴백 처리 | OpenAI 키를 확보하고 비용 정책을 확인한 뒤 |

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
- `analysis-engine`과 `video-llm-engine`이 `healthy`가 된 뒤에만 backend와 analysis-worker가
  시작되는지 `docker compose ps`로 확인한다. analysis-engine 최초 기동은 Whisper/MediaPipe
  모델 다운로드와 풀 프리로드 때문에 시간이 걸릴 수 있으며, 10분의 healthcheck 준비 시간을
  넘기면 `docker compose logs analysis-engine`에서 다운로드·메모리·풀 크기 설정 오류를 확인한다.
- Redis도 비밀번호 인증을 포함한 healthcheck가 `healthy`가 된 뒤 backend와 analysis-worker가
  시작되어야 한다. `unhealthy`이면 `REDIS_PASSWORD`가 Redis 컨테이너와 두 Spring 서비스에
  동일하게 전달됐는지 먼저 확인한다.
- 운영 nginx는 backend가 rate limit용으로 신뢰하는 `X-Forwarded-For`를 `$remote_addr`로
  덮어쓴다. 별도 로드밸런서/CDN을 nginx 앞에 둘 경우 nginx real-ip 설정을 먼저 확정하지 않으면
  모든 사용자가 로드밸런서 IP로 집계될 수 있다.
- `minio-init`이 종료 코드 0으로 끝나 사용자 파일 버킷과 백업 버킷을 만든 뒤에만 backup,
  backend, analysis-worker가 시작되어야 한다. 세 서비스가 대기 상태라면
  `docker compose logs minio-init`에서 MinIO 계정·버킷 이름·네트워크 오류를 확인한다.
- 전체 기동 후 `GET /api/health/engines`에서 두 엔진의 `health`뿐 아니라 내부 API 키를 사용하는
  authenticated readiness 경로도 성공하는지 확인한다. 단순 `/health` 성공만으로 분석 API 인증까지
  검증됐다고 판단하면 안 된다.
- `VIDEO_LLM_ENABLED=true`로 배포했다면 `videoLlmEngine.readiness.response.mode=REAL`,
  `realModelReady=true`인지 확인한다. `health`가 `up`이어도 readiness가 `ready=false` 또는
  `mode=FALLBACK`이면 API 키, timeout, 영상 크기 상한, NVIDIA base URL 설정을 먼저 수정한다.
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
