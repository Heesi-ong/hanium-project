# Release 기준선 설계 (1단계)

- 작성일: 2026-08-03
- 기준: `docs/service-plan/project-review-and-direction-2026-08-03.md` P1-01의 후속 설계 문서
- 범위: 이미지 registry·태깅, build→push→(staging 검증)→production 승격, rollback
- 원칙: 이 문서는 **설계만** 다룬다. 워크플로/Compose 파일은 아직 수정하지 않았다.

## 1. 결론

지금은 registry push, staging, production 호스트가 전부 없다. 애플리케이션은 로컬 Docker Compose로만 실행됐고, 배포는 아직 한 번도 일어난 적이 없다. 따라서 1단계는 "이미 있는 배포를 개선"하는 게 아니라 **처음부터 재현 가능한 배포 기준선을 만드는 것**이다.

핵심 결정은 두 가지로 나뉜다.

1. **지금 바로 설계·구현 가능한 부분**: GHCR(GitHub Container Registry)에 커밋 SHA로 이미지를 push하고, 그 이미지를 CI에서 그대로 pull해 smoke test하는 "artifact 파이프라인". 호스트가 어디든 상관없이 먼저 만들 수 있다.
2. **호스트가 정해져야 채울 수 있는 부분**: production에 실제로 그 이미지를 올리는 승격 단계(SSH 배포 또는 self-hosted runner). 지금은 프로덕션 호스트가 없으므로 이 부분은 메커니즘만 설계하고, 호스트 선정 후 다음 PR에서 채운다.

Kubernetes는 도입하지 않는다. 현재 Compose 구조를 유지하며 이미지 태깅과 승격 절차만 고친다.

## 2. 확인한 현재 상태

- `.github/workflows/verify.yml`의 `docker-build` job이 backend/frontend/analysis-engine/video-llm-engine/backup/nginx 6개 이미지를 빌드하지만 전부 `docker buildx build --load`로 **러너 로컬에만 적재**하고 어디에도 push하지 않는다.
- registry 관련 설정(`docker/login-action` 등)이 워크플로에 없다.
- `docker-compose.yml`의 backend/analysis-worker는 `image: presentation-coaching-backend:latest`를 공유한다. `latest`는 재현 불가능한 태그다(같은 이름이 다른 시점에 다른 바이너리를 가리킬 수 있음).
- 배포 스크립트, deploy 워크플로, 별도 staging 정의가 저장소 어디에도 없다.
- **실제 production 호스트가 아직 없다.** 지금까지의 모든 검증은 개발자 로컬 머신의 Docker Compose에서 이뤄졌다(이 세션에서 반복 확인한 `docker ps`, live Prometheus/Grafana 검증 전부 로컬 실행 기준).
- GitHub 저장소는 `ehtkddn123-cloud/hanium-project` (public/private 여부와 무관하게 GHCR은 같은 계정의 `GITHUB_TOKEN`으로 push 가능).

## 3. 설계 범위

**이번 설계에 포함**

- 이미지 registry 선택과 태깅 규칙
- push 파이프라인 구조 (어느 워크플로에서, 어떤 트리거로)
- "staging 검증"을 호스트 없이 CI로 구현하는 방법
- production 승격 메커니즘의 설계(구현은 호스트 결정 후)
- rollback 절차와 migration 호환성 정책
- 호스트 선정을 위해 사용자가 결정해야 할 옵션 정리

**이번 설계에 포함하지 않음**

- 실제 워크플로/Compose 파일 수정 (다음 단계에서 승인 후 구현)
- production 호스트 자체의 provisioning(어느 클라우드/리전/사양을 쓸지는 별도 결정)
- Kubernetes, 멀티 리전, 오토스케일링

## 4. 설계 상세

### 4.1 이미지 레지스트리와 태깅

- **레지스트리**: GitHub Container Registry(`ghcr.io`)를 쓴다. 이미 GitHub Actions를 쓰고 있어 `GITHUB_TOKEN`만으로 별도 계정·시크릿 없이 push 가능하고, 저장소 접근 권한과 패키지 접근 권한을 그대로 재사용할 수 있다.
- **이미지 이름**: `ghcr.io/ehtkddn123-cloud/hanium-<service>` (backend, frontend, analysis-engine, video-llm-engine, backup, nginx 6개). backend와 analysis-worker는 지금처럼 같은 이미지를 공유한다.
- **태그 규칙**:
  - `sha-<7자리 커밋 SHA>`: 불변 태그. production은 항상 이 형태의 태그만 참조한다.
  - `main`: `main` 브랜치의 최신 통과 빌드를 가리키는 이동 태그. 로컬 개발 편의용이며 production 배포에는 쓰지 않는다.
  - `latest`는 만들지 않는다. 지금 문제의 원인이 "누구나 latest를 쓸 수 있어서 어떤 바이너리인지 불명확한 것"이므로, 태그 자체를 없애 실수로 쓸 수 없게 한다.
- **Compose 참조 방식**: 새 오버레이 `docker-compose.release.yml`을 추가한다. 이 파일은 `build:` 대신 `image: ghcr.io/ehtkddn123-cloud/hanium-backend:${IMAGE_TAG:?IMAGE_TAG must be set}` 형태로 서비스를 재정의한다. `IMAGE_TAG`를 비워두면 compose 단계에서 바로 실패하므로, "태그를 안 정했는데 배포됨" 상황 자체가 나지 않는다. 기존 `docker-compose.yml`(로컬 개발용 `build:` 기반)은 그대로 둔다.

### 4.2 빌드·푸시 파이프라인

- 새 워크플로 `.github/workflows/release.yml`을 만든다. `verify.yml`과 분리하는 이유: `verify.yml`은 "품질 게이트"(PR마다 계속 도는 테스트/빌드)이고, `release.yml`은 "이 커밋을 배포 가능한 산출물로 만드는 것"이라 책임이 다르다.
- 트리거: `main` 브랜치에 push되고 `verify.yml`이 성공했을 때만 실행한다(`workflow_run` 트리거, `conclusion == 'success'` 조건). PR 단계에서는 push하지 않는다 — 아직 리뷰/머지되지 않은 이미지가 registry에 쌓이는 것을 막는다.
- job 구성:
  1. `build-and-push`: 6개 이미지를 매트릭스로 빌드하고 `sha-<short-sha>`와 `main` 두 태그로 push한다. `verify.yml`의 `docker-build` job과 같은 GHA 캐시 scope를 재사용해 중복 빌드 시간을 줄인다.
  2. `staging-smoke`: push된 이미지를 **다시 빌드하지 않고 pull**해서 `docker-compose.release.yml + docker-compose.yml`(env 오버레이) 조합으로 기동한 뒤, 기존 `backend-boot-smoke`/`frontend-e2e-full-stack`이 하던 것과 동일한 확인(health, migration, 로그인, 업로드→큐→결과)을 GitHub Actions 러너 위에서 수행한다. 이게 이번 설계의 "staging"이다 — 상시 떠 있는 서버가 아니라 매 릴리스마다 뜨고 사라지는 ephemeral 환경이며, **production에 실제로 올라갈 그 이미지(digest)를 그대로 검증**한다는 점이 지금 CI와의 핵심 차이다(지금은 `docker-build`와 `backend-boot-smoke`가 각자 따로 다시 빌드해서, 이론상 두 잡의 산출물이 100% 동일하다는 보장이 없다).
  3. `staging-smoke`가 통과한 커밋의 SHA만 "배포 가능"으로 간주한다. GitHub Deployments API(또는 간단히 `docs/ops/release-log.md`에 표로 기록)에 `sha-<short-sha>`, 통과 시각, 커밋 메시지를 남긴다.

### 4.3 production 승격 (호스트 결정 후 구현)

호스트가 없어 지금은 메커니즘만 설계한다. 호스트가 정해지면 아래 중 하나를 골라 `deploy` job을 추가한다.

- **옵션 A — SSH 배포 (권장)**: GitHub Environment `production`을 만들고 필수 리뷰어(본인)를 지정한다. `workflow_dispatch`로 배포할 `sha-<short-sha>`를 입력받아, SSH로 호스트에 접속해 `IMAGE_TAG=sha-xxxxxxx docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.release.yml pull && ... up -d`를 실행한다. SSH 개인키는 GitHub Environment 시크릿으로만 저장한다.
  - 장점: 호스트에 아무것도 설치할 필요 없음, 표준적인 패턴.
  - 단점: SSH 개인키를 GitHub에 저장해야 함(Environment 보호 규칙으로 완화 가능).
- **옵션 B — self-hosted runner**: 호스트에 GitHub Actions self-hosted runner를 설치해, `deploy` job이 그 runner에서 직접 실행되게 한다.
  - 장점: SSH 키를 GitHub에 저장할 필요가 없음.
  - 단점: 호스트에 runner 프로세스를 상시 설치·관리해야 하고, 그 자체가 공격 표면이 됨(GitHub 쪽의 워크플로 코드가 곧 호스트에서 실행 권한을 가짐).
- 1인 운영·100명 규모에서는 옵션 A가 관리 부담이 적어 기본으로 권장하지만, 최종 선택은 호스트 provider가 정해진 뒤 다시 확인한다.
- 배포 후 확인: `docker compose ps`로 전 서비스 healthy, `/api/health` 200, nginx TLS 정상, 로그인 1회 성공을 자동 확인하는 post-deploy smoke를 `deploy` job 마지막 단계에 포함한다. 실패하면 job을 실패 처리하고 자동으로 이전 태그를 재배포하지는 않는다(자동 rollback은 이번 단계 범위 밖 — 아래 4.5).

### 4.4 호스트 선정을 위한 참고 (사용자 결정 필요)

지금 결정할 필요는 없지만, 규모(월 100명, 동시 3~5건 분석)를 감안하면:

- 분석 워크로드(Whisper/MediaPipe, 영상 처리)가 CPU/메모리를 상당히 쓰므로 최소 4 vCPU / 8GB RAM급 인스턴스를 권장한다.
- 저비용 옵션: Oracle Cloud Free Tier(ARM, 무료지만 리전별 재고 이슈 있음), Naver Cloud/NHN(국내 리전, 원화 결제), Vultr/DigitalOcean/Hetzner(해외, 저렴하고 안정적).
- 어느 걸 고르든 이 설계의 registry/태깅/승격 구조는 그대로 재사용 가능하다 — provider 선택이 이 설계를 바꾸지 않는다.

### 4.5 Rollback

- `docs/ops/release-log.md`(신설)에 매 릴리스마다 `sha-<short-sha>`, 배포 시각, 포함된 Flyway migration 최고 버전을 기록한다.
- rollback 절차: 직전 성공 SHA를 골라 4.3의 `deploy` job을 그 태그로 재실행한다. 이미지만 이전 것으로 되돌리는 것이므로 별도 재빌드가 필요 없다(이미 push된 이미지를 재사용).
- **migration 호환성 규칙**: rollback 대상 SHA와 현재 SHA 사이에 새로 추가된 Flyway migration이 있으면, 반드시 다음을 확인한 뒤에만 rollback한다.
  - 새 migration이 컬럼/테이블 삭제나 NOT NULL 강제 추가처럼 **구버전 코드가 읽지 못하는 파괴적 변경**인지 확인한다.
  - 파괴적 변경이 없으면(컬럼 추가, 인덱스 추가 등 하위 호환) 코드만 이전 SHA로 되돌리고 DB는 그대로 둔다.
  - 파괴적 변경이 있으면 코드 rollback만으로는 부족하다 — `docs/ops/backup-restore-runbook.md`의 절차로 백업에서 DB까지 함께 복구해야 한다.
  - 이 판단은 자동화하지 않는다(migration 파일 diff를 사람이 확인). 자동 호환성 검사는 이번 범위 밖.

## 5. 완료 기준

문서 P1-01이 제시한 기준을 그대로 따른다: **동일 digest를 staging(CI ephemeral 환경)에서 검증해 production에 배포하고, 이전 digest로 복구할 수 있다.**

구체적으로는:

- [ ] `main` push 시 6개 이미지가 `sha-<short-sha>` 태그로 GHCR에 push된다.
- [ ] push된 이미지가 재빌드 없이 pull되어 staging smoke(health/migration/로그인/업로드→큐→결과)를 통과한다.
- [ ] production 호스트가 정해진 뒤, 승인된 SHA만 수동 `workflow_dispatch`로 production에 승격된다.
- [ ] 이전 SHA로 재배포하는 rollback이 재빌드 없이 성공한다.
- [ ] migration 호환성 확인 없이 rollback이 실행되지 않도록 runbook에 체크리스트가 있다.

## 6. 다음 실행 순서

1. (사용자 결정) production 호스트 provider와 사양을 정한다 — 4.4 참고.
2. `docker-compose.release.yml` 신설 + `IMAGE_TAG` 필수화.
3. `.github/workflows/release.yml` 신설: `build-and-push` + `staging-smoke`.
4. `docs/ops/release-log.md` 신설, 배포마다 기록하는 절차를 runbook에 추가.
5. 호스트가 정해지면 `deploy` job(옵션 A/B 중 확정한 방식)을 추가하고 실제 1회 배포로 end-to-end 검증한다.
6. rollback을 1회 실제로 리허설한다(현재 SHA → 이전 SHA → 다시 현재 SHA).

## 7. 이번 문서에서 하지 않은 것

- 코드, 워크플로, Compose 파일을 변경하지 않았다.
- GHCR push를 실제로 실행하거나 GitHub 저장소 설정(Environment, 시크릿)을 변경하지 않았다.
- production 호스트를 provisioning하지 않았다(아직 없음을 확인만 했다).
