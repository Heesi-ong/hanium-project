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
| (아직 없음) | | | | | |

- **커밋 SHA**: `git rev-parse --short=7 HEAD` 기준 7자리.
- **이미지 태그**: `sha-<커밋 SHA>` (GHCR `ghcr.io/ehtkddn123-cloud/hanium-<service>:sha-xxxxxxx`).
- **staging-smoke 통과 시각**: `release.yml`의 `staging-smoke` job이 성공한 시각(UTC).
- **production 배포 시각**: 실제로 그 태그를 production에 올린 시각. staging-smoke만 통과하고
  아직 배포하지 않은 태그는 이 칸을 비워 둔다.
- **비고**: rollback으로 재배포한 경우 "rollback from sha-yyyyyyy" 형태로 표시한다.

## Rollback 절차 요약

전체 절차는 `docs/service-plan/release-pipeline-design-2026-08-03.md` 4.5절을 따른다. 요약:

1. 이 표에서 직전 성공 SHA를 찾는다.
2. 현재 SHA와 직전 SHA 사이에 추가된 Flyway migration이 있는지 확인한다(`backend/src/main/resources/db/migration`).
3. 파괴적 변경(컬럼/테이블 삭제, NOT NULL 강제 추가 등)이 없으면 이미지만 이전 태그로 되돌린다.
4. 파괴적 변경이 있으면 `docs/ops/backup-restore-runbook.md`로 DB까지 함께 복구해야 한다 — 코드만
   되돌리는 것으로는 부족하다.
5. rollback도 이 표에 새 행으로 기록한다.
