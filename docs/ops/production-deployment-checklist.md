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

## 2. 실제 도메인/공개 URL로 바꿔야 하는 값

| 환경변수 | 기본값 | 운영에서 해야 할 일 |
| --- | --- | --- |
| `DOMAIN` | `example.com` | nginx/certbot TLS 구성이 사용할 실제 도메인으로 변경. `docker-compose.prod.yml`이 이 값을 필수로 요구함 |
| `MINIO_PUBLIC_ENDPOINT` | `http://localhost:9000` | 브라우저가 영상 스트리밍 리다이렉트를 따라갈 때 접속하는 주소. 실제 공개 도메인/포트로 변경하지 않으면 운영 사용자는 영상을 재생할 수 없음 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | 실제 프론트엔드 배포 도메인으로 변경하지 않으면 브라우저가 API 호출을 CORS로 차단함 |
| `VITE_API_BASE_URL` | `http://localhost:8080` | `docker-compose.prod.yml`은 nginx 동일 origin `/api` 프록시를 쓰도록 이미 빈 문자열로 오버라이드하므로, `docker-compose.prod.yml`을 함께 쓰면 별도 조치 불필요. 프론트를 독립 배포한다면 직접 확인 |

TLS 인증서 발급 자체(Let's Encrypt/certbot)는 로컬 환경에서는 검증할 수 없고, 실제 도메인을 가진
서버에 처음 배포할 때 `docker-compose.prod.yml`의 `certbot` 서비스로 처음 발급받게 된다.

## 3. 기본값이 비어 있어 기능이 꺼진 채로 배포되는 값

| 환경변수 | 기본값 | 비어 있을 때 동작 | 채워야 하는 시점 |
| --- | --- | --- | --- |
| `SMTP_HOST` | (빈 값) | 비밀번호 재설정 이메일을 실제로 보내지 않고 dev에서는 로그로만 남김, prod에서는 링크를 로그에도 남기지 않음 | 비밀번호 재설정 기능을 실제로 쓰게 할 시점 |
| `ADMIN_EMAILS` | (빈 값) | 관리자 대시보드(`/api/admin/**`)에 접근 가능한 계정이 없음 | 관리자 대시보드를 실제로 쓰게 할 시점 |
| `VIDEO_LLM_ENABLED` | `false` | NVIDIA 실연동 대신 mock 응답만 생성 | `README.md` "5.1 Video LLM 실제 모델 활성화"의 결정 체크리스트를 먼저 확인한 뒤 |
| `OPENAI_ENABLED` | `false` | OpenAI 피드백 생성 대신 폴백 처리 | OpenAI 키를 확보하고 비용 정책을 확인한 뒤 |

## 4. 배포 전 별도로 실행해서 확인해야 하는 것

- MySQL 백업/복구가 실제로 동작하는지: `docs/ops/backup-restore-runbook.md`의 리허설 절차를 운영
  DB 인스턴스 기준으로 최소 1회 재현한다.
- MinIO 백필이 필요한 기존 로컬 파일이 있다면: `docs/ops/minio-migration-rehearsal.md`를 참고해
  `STORAGE_BACKFILL_ENABLED=true`로 1회 실행한다(신규 배포라 기존 로컬 파일이 없다면 불필요).
- 스케줄러 분산 락의 Redis fail-open 정책: `docs/ops/scheduler-distributed-lock.md`를 운영팀이
  숙지했는지 확인한다.

## 5. 이 문서에서 다루지 않는 것

- 코드 레벨 결함/버그는 이 문서의 범위가 아니다. `docs/service-plan/additional-gap-analysis.md`의
  최신 업데이트 절을 참고한다.
- 이 체크리스트는 위 항목이 "존재한다"는 것만 정리한 것이고, 각 값을 실제로 얼마로 정할지는
  배포 담당자의 운영 판단이 필요하다.
