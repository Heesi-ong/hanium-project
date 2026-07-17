# 서비스화 갭 분석 및 단계별 실행 계획 (2026-07-16)

작성일: 2026-07-16
작성 방식: 코드/설정/CI/git 로그를 직접 읽어 근거를 확인한 뒤 작성했습니다. **아직 어떤 코드도 수정하지 않았습니다.** 이 문서는 승인 전 계획 단계 산출물입니다.
용어는 처음 나올 때 괄호로 풀어서 적었습니다.

> **실행 상태 갱신(2026-07-17):** 아래 본문은 2026-07-16 최초 분석 시점의 스냅샷입니다.
> 이후 P0~P4 구현과 검증이 진행됐으므로 현재 판정은 다음 표와 각 운영 문서를 우선합니다.

| 항목 | 2026-07-17 현재 상태 |
| --- | --- |
| P0 홈 화면 대비 | 수정 완료. 프론트 전체 lint/test/build 및 Playwright 데스크톱·모바일 E2E 통과 |
| P1 Video LLM | 활성화 결정 문서와 MOCK/FALLBACK 경고 UI 완료. 실제 NVIDIA 활성화는 비용·개인정보 결정 대기 |
| P2 MinIO | prod 쓰기 필수/MinIO 우선 읽기 적용, 실제 MinIO 백필 통합 테스트와 API+2워커 E2E 완료. staging 기존 데이터 백필만 남음 |
| P3 백업 암호화 | AES-256 암호화/복호화와 실제 MySQL 백업→복구 리허설 완료 |
| P4 관측성/부하 | ffprobe 실패·Video LLM 폴백 지표/알림과 k6 시나리오 완료. mock 서버 부하 검증 및 전체 백엔드 테스트 통과 |
| 추가 고도화 | analysis-engine 모델 풀 설정 fail-fast, Compose 배선, 정상 종료 정리와 lifecycle 테스트 보강 |

## 0. 이 문서의 위치 (기존 문서와의 관계)

이 저장소에는 이미 `docs/service-plan/additional-gap-analysis.md`라는 **롤링(rolling, 날짜별로 계속 덧붙여 갱신하는) 갭 문서**가 07-02부터 07-16까지 잘 유지되고 있습니다. 그 문서는 A(보안)/B(안정성) 항목을 시점별로 추적합니다.

이 문서는 그 롤링 문서를 **대체하지 않고**, 2026-07-16 시점에 제가 코드를 다시 독립적으로 검증해서 (1) 목표 수준(L1~L10) 대비 현재 달성도를 한 장으로 정리하고, (2) 롤링 문서가 아직 반영하지 못한 새 발견을 추가하고, (3) 승인받아 바로 착수할 수 있는 **단계별 실행 계획**을 제시하는 것이 목적입니다.

핵심 결론부터: **이 프로젝트는 "로컬 시연 MVP"를 훨씬 넘어선, 대부분의 서비스화 기준을 이미 충족한 상태입니다.** 남은 것은 소수의 "결정 대기" 항목과, 오늘 QA에서 새로 나온 사용자 눈에 보이는 버그 1건입니다.

---

## 1. 확인한 현재 상태 — 목표 수준(L1~L10) 달성도

CLAUDE.md가 정의한 서비스 가능 수준 10개 기준을 코드 근거와 함께 점검했습니다.

| # | 목표 기준 | 판정 | 근거 (직접 확인) |
|---|---|---|---|
| L1 | 빌드/CI가 현재 구조에 맞게 동작 | ✅ 충족 | `.github/workflows/verify.yml`에 backend(gradle test), frontend(lint+test+build+audit), python-engines matrix(analysis-engine·video-llm-engine, pip-audit+pytest), docker-build matrix(4서비스+Trivy 스캔), **backend-boot-smoke**(실제 MySQL/Redis/MinIO 띄우고 dev 프로필 부팅 헬스체크), compose-validate까지 6개 job. |
| L2 | 4개 서비스가 명확한 실행/배포 단위로 분리 | ✅ 충족 | `backend`·`frontend`·`analysis-engine`·`video-llm-engine` 각각 Dockerfile 보유. docker-compose에 `analysis-worker`를 별도 컨테이너로 두고 `--scale analysis-worker=N` 수평 확장 가능. |
| L3 | local/dev/prod 분리 + 비밀값 환경변수 주입 | ✅ 충족 | `application-local.yml`/`application-dev.yml`/`application-prod.yml` 분리. 비밀값은 `${OPENAI_API_KEY:}` 등 환경변수 참조. `.env.example`(약 200줄)이 모든 키를 문서화. prod DB는 기본값 없이 주입 강제. |
| L4 | 운영 DB + 마이그레이션 체계 | ✅ 충족 | H2가 아니라 MySQL + Flyway. `db/migration/`에 V1~V14 마이그레이션 존재. backend-boot-smoke가 `ddl-auto: validate`로 엔티티↔스키마 정합성까지 CI에서 검증. |
| L5 | 긴 영상 분석을 비동기 큐/워커/상태조회로 처리 | 🟡 대체로 충족 | 업로드→`QUEUED` 저장 후 `QueuedAnalysisJobPoller`가 DB에서 원자적 claim(가로채기)해 워커에서 실행. 진행률/취소/타임아웃/재시도/워치독(멈춘 작업 강제 종료) 모두 구현. **단, 진짜 메시지 브로커(RabbitMQ/SQS/Redis Streams 등: 재시도·데드레터·우선순위를 브로커가 보장)는 아니고 "DB 폴링 기반 claim 큐"입니다.** 현재 규모에는 충분하나 구조적 한계는 남아 있습니다. |
| L6 | 프론트-백엔드 API 계약 일치 | ✅ 충족 | `ApiContractTest.java`가 프론트 호출 경로와 백엔드 라우트 계약을 자동 검증. springdoc/OpenAPI 연동. |
| L7 | 인증/권한/소유권/업로드 파일 보호 | ✅ 충족 | JWT를 httpOnly+secure+SameSite 쿠키로 발급(localStorage 저장 안 함). `/api/**` 인증 요구, 결과 조회/삭제 소유권 검증, `/api/admin/**`은 `hasRole("ADMIN")`. 엔진 호출은 `X-Internal-Api-Key` 공유 키(fail-closed). 영상은 presigned URL(서명된 임시 URL)로만 접근. |
| L8 | Video LLM mock을 실제/외부 모델로 대체 | 🟡 코드 완성·비활성 | `video-llm-engine`에 NVIDIA NIM(`nemotron-3-nano-omni`) 실호출 코드 완성(에셋 업로드/202 폴링/JSON 정규화/타임아웃/실패 시 mock 폴백). **단 기본값이 `VIDEO_LLM_ENABLED=false`, `VIDEO_LLM_BACKEND=mock`이라 실제로는 아직 꺼져 있음.** 켜는 것은 "결정"의 문제. |
| L9 | OpenAI 설정/timeout/fallback/비용정책 명확 | ✅ 충족 | `openai.enabled`(기본 false), `timeout-ms`(15s), 월 예산 가드(`openai-monthly.capacity` 기본 1000회), 서킷브레이커, 재시도 시 기존 REAL 응답 재사용. Video LLM도 별도 월 예산(500회 보수적)·서킷브레이커·timeout(120s). |
| L10 | 테스트/로그/모니터링/백업/정리 정책 | ✅ 충족 | backend 71 + frontend 33 + python 8 테스트 파일. logback rolling 파일 로그. Prometheus/node-exporter/cAdvisor/Grafana/Alertmanager 5종 + 알림 규칙 + 대시보드 JSON. MySQL 백업(로컬+MinIO 원격 반출)·복구 스크립트·실제 복구 리허설 로그. temp/고아 파일·원본 영상 보존 정리 스케줄러(분산 락 포함). |

**요약: 10개 중 8개 완전 충족, 2개(L5·L8)는 "구조적 한계" 또는 "활성화 결정"만 남은 상태.** 서비스화 관점에서 매우 성숙합니다.

---

## 2. 발견한 문제 (남은 갭) — 우선순위 순

### P0. [신규·Critical] 홈 화면 주요 제목이 사실상 안 보임 (사용자 눈에 보이는 버그)

- **문제**: 오늘 QA 리포트(`docs/qa-ui-report-2026-07-16.md`)가 측정한 대로, 홈(`/`)의 히어로 제목과 모든 섹션 제목이 다크 배경에 다크 글자로 렌더링돼 명암비 1.26~1.31(WCAG AA 기준 4.5:1)로 **거의 안 보입니다.** 전 해상도에서 발생.
- **근본 원인 (제가 직접 확인)**: `frontend/src/index.css` 37~41번째 줄에 `h1, h2 { color: var(--text-h) }`가 있고 `--text-h`는 `#2B2420`(어두운 색)입니다. 이 규칙이 **CSS 레이어에 안 들어간(unlayered) 전역 규칙**이라, Tailwind v4의 유틸리티 클래스(`layer(utilities)`에 속함)보다 우선순위가 높아 밝은 글자색 지정을 덮어씁니다. 즉 라이트 테마 잔재 토큰(`--text-h`)과 실제 다크 테마가 충돌한 것입니다. h3에는 이 규칙이 없어 정상입니다.
- **왜 중요한가**: 첫 화면(랜딩)의 제목이 안 보이는 것은 **서비스 첫인상에 직접 타격**입니다. 기능은 멀쩡한데 신뢰도만 깎이는, 비용 대비 손실이 가장 큰 유형입니다.
- **참고**: 현재 미커밋 상태인 `HomePage.jsx` 변경분은 이 버그와 무관한 단순 문구 다듬기(원점수→기본 점수, WPM 용어 풀이)였습니다. 즉 **이 Critical 버그는 아직 안 고쳐진 상태**입니다.

### P1. [결정 대기·High] Video LLM 실연동을 켤지 말지 결정 필요

- **문제**: 실호출 코드는 완성됐지만 기본값이 꺼져 있어(`VIDEO_LLM_ENABLED=false`) 실제 서비스에서는 여전히 mock 관찰 결과가 나갑니다. mock은 입력 영상과 무관한 고정 응답이라, 서비스 품질 관점에서 가장 큰 "실질 미충족" 항목입니다.
- **왜 결정이 필요한가**: 켜는 순간 (1) NVIDIA API 비용/무료 한도, (2) 실패 시 mock 폴백을 사용자에게 어떻게 표기할지(사용자가 "가짜 결과"를 진짜로 오해하면 안 됨), (3) 영상 업로드가 외부(NVIDIA)로 나가는 것에 대한 개인정보 고지가 걸립니다. 기술이 아니라 정책·비용·프라이버시 결정입니다.

### P2. [구조·Medium] 로컬 디스크 fallback 제거 시점 미결정 + MinIO 백필 실환경 리허설 부재

- **문제**: 파일 저장이 MinIO(S3 호환 오브젝트 스토리지)로 이중 쓰기되도록 마이그레이션(Phase A~F)됐지만, MinIO 실패 시 로컬 디스크로 계속 동작하는 **이중 구조**가 안전망으로 남아 있습니다. 이 로컬 fallback이 남아 있는 한, 여러 워커 인스턴스로 수평 확장할 때 "A 인스턴스가 로컬에 쓴 파일을 B가 못 찾는" 문제가 이론상 재발할 수 있습니다.
- **추가**: 기존 로컬 파일을 MinIO로 옮기는 백필 러너(`ObjectStorageBackfillRunner`)는 단위 테스트만 됐고, **실제 MinIO가 떠 있는 환경에서 1회 실행(리허설)한 적이 없습니다.**

### P3. [운영·Medium] 백업 원격 반출은 되지만 암호화가 없음

- **문제 (제가 직접 확인)**: `scripts/backup-mysql.sh`는 이미 MinIO 원격 버킷으로 백업 파일을 `mc cp`로 반출합니다(롤링 문서 최신 리스크 #4는 이 부분에서 갱신 필요 — 원격 반출은 **완료**). 다만 백업 파일은 **평문 gzip**이고 `gpg`/`openssl` 등 암호화 단계가 없습니다.
- **왜 중요한가**: DB 덤프에는 사용자 이메일·비밀번호 해시 등 개인정보가 들어 있습니다. 원격 저장소가 유출되면 그대로 노출됩니다.

### P4. [안정성·Low~Medium] 잔여 세부 항목

- **ffprobe fail-open**: `FfprobeVideoDurationProbe.java`는 ffprobe(영상 길이 확인 도구) 실행이 실패/타임아웃하면 영상 길이 제한을 **통과시킵니다**(fail-open). 의도된 트레이드오프지만, ffprobe가 컨테이너에서 상시 실패하면 30분 제한이 사실상 무력화될 수 있어 모니터링 지표화가 필요합니다.
- **분산 큐가 진짜 브로커는 아님(L5)**: DB 폴링 claim 방식이라 우선순위/데드레터(반복 실패 작업 격리)/지수 백오프 재시도를 브로커 수준으로 보장하지 않습니다. 현재 규모엔 충분하나 장기 확장 시 한계.
- **부하/성능 테스트 부재**: 동시 업로드 N건에서 워커 처리량·지연·메모리를 측정한 부하 테스트가 없습니다. 큐 상한값(전역 100, 사용자당 3)이 실제 하드웨어에서 적정한지 근거가 부족합니다.
- **관측성 심화 여지**: health/metrics/대시보드는 있으나, LLM/OpenAI **비용 소진율**과 mock **폴백 발생률**을 별도 알림으로 두면 운영 사고를 조기에 잡을 수 있습니다(폴백이 조용히 늘면 품질 저하를 놓침).
- **개인정보 외부 전송 고지(P1과 연동)**: Video LLM을 켜면 영상이 NVIDIA로 나가므로 개인정보처리방침에 국외 이전/제3자 제공 고지가 필요합니다.

---

## 3. 각 항목 수정 방향 (구체적 how)

**P0 (홈 제목 대비)** — 둘 중 하나. (A) `index.css`의 `h1, h2 { color: var(--text-h) }`를 제거하거나 `@layer base { ... }`로 감싸 Tailwind 유틸리티가 정상적으로 색을 덮어쓰게 함. (B) 라이트 잔재 토큰(`--text/--text-h/--bg`)을 다크 테마 값으로 교체. 함께 QA #2(모바일 `code` 가로 오버플로우 → `code`에 `overflow-wrap: anywhere`)와 #3·#4(주황 CTA·푸터 링크 대비 4.5:1 확보)도 같은 파일에서 처리하면 효율적. **검증**: 수정 후 세 해상도에서 명암비 재측정 + `npm run test`/`build`.

**P1 (Video LLM 활성화)** — 코드 수정이 아니라 결정 문서화 우선. `docs/service-plan/video-llm-model-options.md`와 활성화 체크리스트를 근거로 (1) 비용 상한·월 예산(현재 500회) 확정, (2) 폴백 발생 시 UI에 "임시/샘플 결과" 배지 노출 정책, (3) 개인정보처리방침에 국외 이전 고지 추가를 결정한 뒤, staging에서 `VIDEO_LLM_ENABLED=true`로 실영상 1~2건 E2E 확인. **검증**: 폴백률·비용 로그(`NVIDIA_VIDEO_LLM_USAGE`) 확인.

**P2 (fallback 제거 + 백필 리허설)** — 순서가 중요. 먼저 staging MinIO에서 `STORAGE_BACKFILL_ENABLED=true`로 백필 러너를 1회 실행해 기존 로컬 파일이 전부 올라가는지 확인 → 그 다음에 로컬 fallback을 "쓰기 실패 시 에러"로 바꿀지, 아니면 읽기 fallback만 남길지 결정. **검증**: 백필 후 MinIO 객체 수 vs 로컬 파일 수 대조, 다중 워커(`--scale analysis-worker=2`)에서 업로드→결과 조회 E2E.

**P3 (백업 암호화)** — `backup-mysql.sh`의 gzip 뒤에 `gpg --symmetric`(또는 `openssl enc`) 단계를 추가하고 키는 환경변수/시크릿 매니저로 주입. 복구 스크립트에 복호화 단계 대칭 추가. **검증**: 암호화 백업을 실제로 복호화→복구 리허설 1회.

**P4** — ffprobe 실패를 Prometheus 카운터로 노출하고 알림 임계치 설정. 부하 테스트는 k6/Locust로 동시 업로드 시나리오 1개부터. 비용/폴백률 지표를 Grafana 패널+Alertmanager 규칙으로 추가.

---

## 4. 남은 위험

- **P0를 제외한 나머지는 "지금 당장 서비스가 깨지는" 위험이 아니라 "확장·품질·컴플라이언스" 위험**입니다. 즉 소규모 실서비스 시작은 이미 가능한 수준입니다.
- Video LLM을 끈 채로 출시하면 "영상 관찰(시선/표정/제스처)" 결과가 mock 고정값이라는 점을 사용자가 오해할 위험이 남습니다. 켜기 전까지는 UI에서 해당 섹션을 "베타/샘플"로 명시하는 것이 안전합니다.
- 로컬 fallback이 남아 있는 한 "수평 확장 완전 보장"이라고는 말할 수 없습니다(단일/소수 인스턴스는 문제없음).
- 부하 테스트 근거가 없어 큐 상한·워커 수의 적정값은 아직 추정치입니다.

## 5. 실행한 검증 (이번 회차)

- **읽기 기반 검증**: pwd/git status/git log(최근 30커밋)/디렉토리 트리, CI 워크플로우 전문, docker-compose 존재, Flyway 마이그레이션 개수(14), video-llm 실호출 코드 전문, application.yaml의 OpenAI/LLM/예산/타임아웃 키, `.env.example`, 백업 스크립트의 원격 반출/암호화 유무, index.css의 h1/h2 규칙, 미커밋 HomePage диff, 테스트 파일 개수(backend 71·frontend 33·python 8)를 **직접 열어 확인**했습니다.
- **실행하지 않은 것(솔직히 명시)**: backend Gradle 테스트, frontend `npm test`/`build`, python pytest를 **이번 회차에서 실제로 돌리지는 않았습니다.** 따라서 "테스트가 지금 전부 통과한다"는 보장은 이 문서가 하지 않습니다. 계획 승인 시 각 단계 끝에 해당 검증을 실제 실행하겠습니다.

## 6. 단계별 실행 계획 (승인용)

작게 나눠, 사용자 변경분을 되돌리지 않고, 각 단계 끝에 실제 검증을 붙입니다.

| 단계 | 내용 | 예상 규모 | 끝에 실행할 검증 |
|---|---|---|---|
| **P0** | 홈 제목 대비 Critical 수정 + QA #2~#4 경미 이슈 동반 수정 (`index.css` 중심) | 파일 1~2개, 소 | 세 해상도 명암비 재측정, `npm run lint/test/build` |
| **P1** | Video LLM 활성화 "결정 문서" 작성(비용·폴백 표기·프라이버시) → staging 실영상 E2E | 문서+설정, 중 | 폴백률·비용 로그 확인, E2E 1~2건 |
| **P2** | staging MinIO 백필 리허설 → 로컬 fallback 정책 확정 | 스크립트 실행+소코드, 중 | 객체수 대조, 2워커 E2E |
| **P3** | 백업 암호화(gpg/openssl) + 복구 스크립트 대칭 수정 | 스크립트 2개, 소 | 암호화→복호화→복구 리허설 |
| **P4** | ffprobe 실패 지표화, 비용/폴백률 알림, k6 부하 테스트 1건 | 관측성+테스트, 중 | 알림 발화 테스트, 부하 리포트 |

## 7. 다음 우선순위 (권장)

**먼저 P0(홈 제목 Critical 버그)부터 처리하는 것을 권장합니다.** 비용이 가장 작고(파일 1~2개), 오늘 QA로 근본 원인까지 확정됐으며, 사용자 첫인상에 직접 영향을 주는 유일한 "지금 보이는" 문제이기 때문입니다. 나머지 P1~P4는 대부분 "결정 + 운영 리허설" 성격이라, P0 처리 후 어느 것을 먼저 진행할지 정하면 됩니다.

**승인해 주시면 P0부터 착수하겠습니다.** (착수 전, 어떤 파일을 왜 고칠지 다시 한 번 짧게 설명드린 뒤 진행합니다.)
