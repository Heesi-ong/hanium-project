# Release 기록

`.github/workflows/release.yml`의 `build-and-push` + `staging-smoke`를 통과한 커밋과, 그중 실제로
production에 승격한 커밋을 기록한다. 설계 배경은
`docs/service-plan/release-pipeline-design-2026-08-03.md`를 참고한다.

production 승격(`deploy` job)은 아직 없다(2026-08-03 기준 production 호스트 미정). 호스트가
정해지고 deploy job이 추가되면, 그 job의 마지막 단계에서 이 표에 행을 추가하는 것을 원칙으로
하되, 자동화 전까지는 배포를 실행한 사람이 수동으로 기록한다.

## 기록 형식

| 커밋 SHA | 이미지 태그 | staging-smoke 통과 시각 | production 배포 시각 | 배포자 | 비고 |
| --- | --- | --- | --- | --- | --- |
| eda2777 | sha-eda2777 | 2026-08-03T10:13:36Z | (미배포) | - | release.yml 최초 전체 통과(compute-tag/build-and-push 6개/staging-smoke). production 호스트 미정이라 승격 없음 |

- **커밋 SHA**: `git rev-parse --short=7 HEAD` 기준 7자리.
- **이미지 태그**: `sha-<커밋 SHA>` (GHCR `ghcr.io/ehtkddn123-cloud/hanium-<service>:sha-xxxxxxx`).
- **staging-smoke 통과 시각**: `release.yml`의 `staging-smoke` job이 성공한 시각(UTC).
- **production 배포 시각**: 실제로 그 태그를 production에 올린 시각. staging-smoke만 통과하고
  아직 배포하지 않은 태그는 이 칸을 비워 둔다.
- **비고**: rollback으로 재배포한 경우 "rollback from sha-yyyyyyy" 형태로 표시한다.

## 2026-08-09 로컬 릴리스 사전 검증

아래 결과는 미커밋 작업 트리를 별도 Compose 프로젝트(`hanium-remaining`)로 빌드해 확인한
로컬 증거다. GHCR에 push된 digest의 `staging-smoke` 결과가 아니므로 위 릴리스 기록 표에는
추가하지 않는다.

- `mysql:8.4`의 빈 볼륨에서 backend dev 프로필을 부팅해 Flyway V1→V26 fresh migration과
  Hibernate `ddl-auto: validate`를 통과했다. `flyway_schema_history`에서 V25/V26 `success=1`,
  `users.onboarding_skipped_at`, `admin_audit_logs.reason/request_id/incident_id` 컬럼을 직접 확인했다.
- backend와 analysis-worker는 동일한 현재 backend 이미지로, analysis-engine은 현재 소스로
  빌드했다. MySQL·Redis·MinIO·backend·analysis-worker·analysis-engine이 모두 healthy였다.
- `sample-demo.mp4`를 사용한 `analysis-pipeline.spec.js`가 30.5초에 통과했다. 실제 정량 분석
  job `20260809063240-6b2aca23`이 `COMPLETED`에 도달했고 Video LLM/OpenAI는 의도대로
  비활성화했다. 테스트 종료 후 E2E 계정과 FAILED/DEAD_LETTER 작업 잔여는 각각 0건이었다.
- GitHub 저장소 `ehtkddn123-cloud/hanium-project`의 Repository Variables는 조회 결과 빈 목록이었다.
  따라서 실제 사업자·개인정보 보호책임자 정보 9개를 등록하기 전에는 새 frontend 릴리스가
  의도적으로 실패한다. 실제 값 등록 후 `verify → release → staging-smoke` 실행이 필요하다.
- Spring Boot 3.5.3 기본 Flyway 11.7.2를 같은 메이저의 11.20.3으로 올린 뒤 별도 빈 볼륨의
  MySQL 8.4.10에서 다시 검증했다. backend와 의존 서비스가 모두 healthy였고 관리 헬스는
  `UP`, V1→V26 적용과 V25/V26 `success=1`, 신규 컬럼 조회가 통과했다. 기존의
  `database newer than this version of Flyway` 호환성 경고도 전체 backend 로그에서 0건이었다.
  Redgate 문서가 MySQL 8.4를 개별 verified version으로 명시한 것은 아니므로, 이 결과는
  프로젝트의 실제 migration/스키마 호환성 증거로 한정한다.

## 2026-08-12 브라우저 새로고침 복구 검증

아래 결과도 미커밋 작업 트리의 로컬 증거이며 production 또는 GHCR digest 검증은 아니다.
Docker Hub의 `node:22-alpine` 메타데이터 조회가 시간 초과되어, 직전 검증용 nginx 이미지에
현재 `npm run build`로 생성한 `frontend/dist`를 읽기 전용 마운트해 프런트 코드를 검증했다.

- 실제 Chromium에서 `sample-demo.mp4` 선택 후 단일 “업로드하고 분석 시작” CTA를 실행하고,
  job `20260812004142-69612186`의 분석 도중 페이지를 새로고침했다. 새 페이지의 첫 상태 API
  요청에는 의도적으로 503을 한 번 반환했으며, 이후 자동 polling이 재개되어 결과 상세 화면까지
  이동했다. Playwright 결과는 1건 통과, 테스트 본문 20.1초(전체 20.6초)였다.
- MySQL·Redis·MinIO·backend·독립 analysis-worker·analysis-engine·frontend가 모두 healthy인
  격리 Compose 프로젝트에서 실행했다. Video LLM과 OpenAI는 의도적으로 비활성화했다.
- 회원탈퇴 직후 `users`, `analysis_jobs`, `uploaded_videos`는 모두 0건이었다. 스토리지 삭제
  outbox 2건은 기본 2분 스케줄에서 재시도 없이 `COMPLETED`됐고, MinIO의 업로드·결과 객체와
  로컬 job 디렉터리는 최종 0건이었다. 검증 후 격리 컨테이너·네트워크·볼륨을 제거했다.

## Rollback 체크리스트

전체 설계는 `docs/service-plan/release-pipeline-design-2026-08-03.md` 4.5절을 따른다. 이 표는 그
설계를 실제 사고 중에 순서를 건너뛰지 않고 따라갈 수 있는 체크리스트로 만든 것이다 —
**"migration 호환성을 확인했다"는 항목에 체크하기 전에는 배포 명령을 실행하지 않는다.**

- [ ] **1. 롤백 대상 SHA 확정**: 이 표에서 직전 성공(staging-smoke 통과 + production 배포) SHA를
      찾는다. 두 SHA를 각각 `TARGET_SHA`(되돌아갈 대상), `CURRENT_SHA`(현재 production)로 적어둔다.
- [ ] **2. migration diff 확인**: 아래 명령으로 두 SHA 사이에 추가된 Flyway migration 파일 목록을
      뽑는다. 목록이 비어 있으면 3번을 건너뛰고 바로 4번(코드만 롤백)으로 간다.
      ```bash
      git diff --name-only --diff-filter=A "$TARGET_SHA" "$CURRENT_SHA" -- \
        backend/src/main/resources/db/migration
      ```
- [ ] **3. 각 migration의 파괴적 변경 여부 판정** (목록이 비어 있지 않을 때만): 나열된 각 파일을 열어
      `DROP COLUMN`/`DROP TABLE`/기존 컬럼에 `NOT NULL` 강제 추가/기존 컬럼 타입 축소가 있는지
      사람이 직접 읽고 판정한다(자동화하지 않음 — 설계 문서 4.5절 원칙). 하나라도 있으면
      "파괴적"으로 표시하고 5번으로 간다. 전부 없으면(컬럼 추가, 인덱스 추가 등) 4번으로 간다.
- [ ] **4. 코드만 롤백** (파괴적 변경 없음): DB는 그대로 두고 이미지만 `TARGET_SHA`로 되돌린다.
      ```bash
      IMAGE_TAG=sha-$TARGET_SHA docker compose \
        -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.release.yml \
        up -d
      ```
      배포 후 `docker compose ps` 전 서비스 healthy, `/api/health` 200을 확인하고 8번으로 간다.
- [ ] **5. DB까지 함께 복구해야 함을 인지** (파괴적 변경 있음): 코드만 되돌리는 것으로는 부족하다.
      `docs/ops/backup-restore-runbook.md`의 "복구 (비상시 절차)"를 그대로 따른다.
- [ ] **6. 복구 대상 백업 파일 선정**: `TARGET_SHA` 배포 시점 **이후**, `CURRENT_SHA`의 파괴적
      migration이 적용되기 **이전**에 생성된 백업을 고른다(`ls -t storage/backups/*.sql.gz*`와 이
      표의 "production 배포 시각"을 대조). 그 사이 발생한 사용자 데이터(가입, 분석 결과 등)는
      복구 시 유실됨을 배포자가 인지하고 진행한다.
- [ ] **7. DB 복구 실행 후 코드 롤백**: `backup-restore-runbook.md`의 `restore-mysql.sh`로 DB를
      복구한 뒤, 4번의 이미지 롤백 명령을 실행한다.
- [ ] **8. 배포 후 기록**: 아래 "기록 형식" 표에 새 행을 추가한다. 비고 칸에
      `rollback from sha-<CURRENT_SHA>`(및 DB 복구를 했다면 `+ db restored to <백업 파일명>`)를
      남긴다.
