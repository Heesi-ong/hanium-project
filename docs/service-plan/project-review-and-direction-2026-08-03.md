# 프로젝트 전체 코드·구조·페이지 리뷰 및 개선 방향

- 작성일: 2026-08-03
- 기준 브랜치/커밋: `main` / `5decd10`
- 범위: `backend`, `frontend`, `analysis-engine`, `video-llm-engine`, CI/release, Compose, 운영 문서, 실제 브라우저 화면
- 작업 원칙: 이번 단계에서는 애플리케이션 코드를 수정하지 않고 문제와 개선 순서를 확정한다.

## 1. 최종 판단

프로젝트는 단순 MVP보다 훨씬 앞선 **운영 후보(beta)** 단계다. 네 서비스 경계, MySQL/Flyway, Redis 기반 비동기 작업, MinIO, 인증·소유권, 백업/복구, 모니터링, 관리자 복구 화면, 전체 분석 파이프라인 CI가 이미 있다. 모바일 390px에서도 점검한 주요 페이지에 가로 넘침이 없었고, 회원가입·로그인·온보딩 분기·사용자 상태·관리자 화면도 실제 컨테이너에서 동작했다.

그러나 지금 상태로 공개 출시해서는 안 된다. 가장 큰 이유는 다음 네 가지다.

1. **관리자 이메일을 아는 사람이 먼저 가입하면 즉시 ADMIN이 되는 계정 선점 취약점이 실제로 재현됐다.**
2. 실제 엔진 상태는 “이용 불가”인데 업로드 화면은 Video LLM과 OpenAI를 기본 선택하며, 제품 약속과 런타임 capability가 일치하지 않는다.
3. 온보딩이 “맞춤형 피드백”을 약속하지만 저장한 답변은 분석·코칭 어디에서도 읽지 않는다.
4. 개인정보처리방침과 이용약관에 사업자·책임자 정보 placeholder가 남아 있어 공개 출시 문서가 완성되지 않았다.

따라서 다음 작업은 대형 리팩터링이나 기능 추가가 아니라 **관리자 권한 취약점 차단 → 기능 상태와 UI 일치 → 온보딩 약속 이행/축소 → 법적·운영 launch gate 완료** 순서여야 한다.

**갱신(2026-08-12, 사용자 결정)**: 위 네 가지 이유 중 4번(사업자 정보, P0-02)과 이에 연결된 P1-06/07(production 배포)은 프로젝트 범위에서 제거됐다 — 현재 프로젝트는 실제 공개 서비스 출시 계획이 없는 테스트/데모 단계로 운영하기로 정했기 때문이다. 1~3번(관리자 계정 선점, capability 불일치, 온보딩 미반영)은 이미 이번 세션에서 모두 해결됐다(각 P0-01/P1-01/P1-02 상태 참고). 즉 이 문서 작성 시점의 "지금 상태로 공개 출시해서는 안 된다"는 결론은 여전히 유효하지만, 그건 더 이상 이 프로젝트의 목표가 아니다 — 실제 공개 서비스로 전환하는 결정이 나중에 내려지면 P0-02/P1-06/07을 다시 범위에 넣고 처음부터 진행해야 한다.

## 2. 확인한 현재 상태

### 2.1 서비스 구조

- `backend`: Spring Boot API와 동일 이미지 기반의 독립 `analysis-worker` 실행 단위
- `frontend`: React/Vite 정적 앱, production에서는 nginx same-origin `/api` 프록시 사용
- `analysis-engine`: FastAPI 정량 분석 엔진
- `video-llm-engine`: DISABLED/DEGRADED/STRICT 정책과 외부 모델 호출 경로를 가진 시각 분석 엔진
- 데이터/인프라: MySQL 8.4, Flyway V1~V24, Redis, MinIO, 삭제·메일 outbox, DEAD_LETTER 복구
- 운영: Prometheus, Alertmanager, Grafana, node-exporter, cAdvisor, Blackbox Exporter, 백업/복구 스크립트

서비스 경계와 비동기 처리 방향은 적절하다. 현재 단계에서 Kubernetes나 마이크로서비스 재분해부터 시작할 이유는 없다.

### 2.2 보안과 데이터 경계

이미 구현된 강점은 다음과 같다.

- JWT HttpOnly cookie와 bearer 호환
- 결과·분석 작업의 사용자 소유권 검증
- ADMIN API 인가와 내부 엔진 공유키
- prod 비밀값 환경변수 주입 및 일부 fail-fast 검증
- 원본/결과 파일의 MinIO 저장, 삭제 outbox, 사용자 탈퇴 정리
- 요청 ID, 구조화 로그, rate limit, 관리자 감사로그

다만 관리자 역할 부여 방식은 위 기반을 무력화하는 P0 취약점이다. 쿠키 인증에 CSRF를 비활성화한 상태와 로그인 응답 본문에 JWT를 함께 반환하는 호환 경로도 후속 축소가 필요하다.

### 2.3 CI와 release

- `verify.yml`은 backend/frontend/Python 테스트, Docker 이미지 6종, boot smoke, 인증 UI E2E, 실제 analysis-engine E2E, Compose 계약을 검증한다.
- `release.yml`은 verify 성공 후 GHCR 이미지 6종을 `sha-<7자리>`와 `main` 태그로 push하고, push된 backend/frontend/analysis-engine 이미지를 이용해 smoke를 수행하도록 추가됐다.
- production 호스트와 승인된 deploy/rollback job은 아직 없다.
- release smoke는 nginx, backup, video-llm-engine 이미지를 실제 기동하지 않으며 production same-origin UI 흐름도 검증하지 않는다.
- 7자리 SHA 태그는 관례상 추적 가능하지만 registry가 덮어쓰기를 막는 진짜 불변성은 아니다. production은 full SHA 또는 digest를 배포 기준으로 삼아야 한다.
- SBOM, 이미지 서명, provenance 검증은 아직 없다.

2026-08-03 점검 중 최신 `verify` 실행 `30801945986`은 backend, frontend, Python 엔진, Compose, Docker build, frontend full-stack E2E까지 통과했고 analysis-pipeline E2E가 진행 중이었다. 직전 실행에서는 SMTP 통합 테스트 한 건이 간헐적으로 실패했으나 같은 테스트가 최신 backend job에서는 통과했다. 일회성으로 넘기지 말고 반복 실행으로 flake 여부를 확인해야 한다.

### 2.4 테스트 기준선

애플리케이션 코드는 release/CI 변경 전 기준과 같으며 직전 fresh 검증 결과는 다음과 같다.

- backend: 452 tests, 실패 0, 9 skipped, line coverage 89.5%
- frontend: 220 tests, ESLint/build 통과, line coverage 76.05%, branch 67.95%
- analysis-engine: 144 tests와 Ruff 통과
- video-llm-engine: 184 tests 통과, live test 1개 제외, Ruff 통과
- base/prod/monitoring/prod+monitoring Compose 병합 검증 통과

테스트 양은 강점이지만 coverage 하락 방지 기준, 실제 NVIDIA/OpenAI acceptance, 관리자 브라우저 E2E, 배포된 production 전체 경로 검증은 별도 gate로 남아 있다.

## 3. 우선순위별 문제와 수정 방향

### P0-01. 공개 회원가입으로 관리자 계정을 선점할 수 있다

**근거**

- `AuthController.signup()`은 입력 이메일이 `ADMIN_EMAILS`에 포함되면 이메일 소유 확인 없이 `ADMIN`을 부여한다.
- 로그인 때도 설정 이메일을 기준으로 role을 다시 승격/강등한다.
- `AdminAuthorizationIntegrationTest`도 공개 signup/login으로 설정 이메일이 ADMIN이 되는 동작을 정상 요구사항으로 고정하고 있다.
- 이메일 인증 기능은 코드에 없다.
- 실제 런타임에서 검증용 주소를 `ADMIN_EMAILS`에 넣은 뒤 그 주소로 공개 회원가입하자 응답이 즉시 `admin: true`를 반환했다.

**영향**

관리자 이메일을 알고 있거나 추측한 공격자가 실제 운영자보다 먼저 가입하면 사용자 정지·강제 탈퇴·결과 삭제·DEAD_LETTER 재처리 등 관리자 기능에 접근할 수 있다. 서비스 공개 전 반드시 차단해야 한다.

**수정 방법**

1. 공개 `signup`은 무조건 `USER`만 생성하도록 바꾼다.
2. 로그인 시 `ADMIN_EMAILS`와 role을 동기화하는 로직을 제거한다.
3. 최초 관리자는 migration, 제한된 CLI, 일회성 bootstrap token 등 공개 HTTP 밖에서 생성한다.
4. 이후 관리자는 기존 관리자가 초대하고, 이메일 소유 확인과 재인증을 거친 뒤 승격한다.
5. 역할 변경 이력에 실행자, 사유, 요청 ID를 기록한다.
6. 기존 `AdminAuthorizationIntegrationTest`의 승격 기대를 제거하고 “관리자 이메일 회원가입은 USER”, “설정값만으로 로그인 승격 불가”, “승격 API는 ADMIN만 가능” 통합 테스트로 교체한다.

**완료 기준**: 새 DB에서 공개 API만 사용해 ADMIN을 만들 수 없고, 별도 bootstrap 절차와 회수 절차가 문서화돼야 한다.

**상태(2026-08-09)**: 완료. 공개 signup/login은 항상 `USER`를 유지하고, 이미 가입된 계정만
기동 시 `AdminRoleSyncRunner`가 운영 설정과 동기화한다. 공개 HTTP만으로 ADMIN을 만들 수 없는
통합 테스트와 부여·회수 runbook(`docs/ops/production-deployment-checklist.md`)을 추가했다.

### P0-02. 공개 출시용 약관/개인정보 문서가 미완성이다

**근거**

- `/terms`, `/privacy`에 상호, 대표자, 사업자등록번호, 주소, 전화, 개인정보 보호책임자 placeholder가 실제 화면에 노출된다.
- 문서 자체가 초안임을 나타내는 안내가 있다.

**영향**

사용자가 데이터 처리 주체와 문의 창구를 확인할 수 없다. 영상·음성·외부 AI 전송을 다루는 서비스이므로 단순 문구 문제가 아니라 launch gate다.

**수정 방법**

- 실제 사업/운영 주체와 연락처를 확정한다.
- 수집 항목, 목적, 보유 기간, 국외 이전, 위탁, 백업 보존, 탈퇴 후 삭제 시간을 실제 설정과 대조한다.
- 동의 문서 버전을 DB에 저장하고 개정 동의/이력 조회 절차를 둔다.
- 가입 화면의 약관·개인정보 동의 방식은 국내 개인정보/전자상거래 전문가 검토를 거친다.
- 이 문서는 법률 자문을 대체하지 않으며, 공개 전 전문 검토 완료 증거를 남긴다.

**완료 기준**: 화면의 placeholder가 0개이고, 코드 설정·실제 데이터 흐름·법적 문서가 일치하며 승인자와 버전이 기록돼야 한다.

**상태(2026-08-12, 범위 제거 — 사용자 결정)**: 현재 프로젝트는 실제 공개 서비스 출시 계획이
없는 테스트/데모 단계로 운영하기로 확정되어, 이 항목 자체가 프로젝트 범위에서 제거됐다.
2026-08-06에는 실제 사업자 정보(상호/대표자/사업자등록번호/주소/연락처, 개인정보 보호책임자
정보)가 없어 "계속 보류"로 남겨뒀으나, 사용자가 이후 "테스트 단계에서 불필요하므로 제거"로
결정했다. placeholder와 초안 안내문은 화면에 그대로 유지된다(`frontend/src/pages/PrivacyPage.jsx`,
`TermsPage.jsx`). 값 주입 경로(`VITE_BUSINESS_*`, `VITE_PRIVACY_OFFICER_*` 환경변수,
`frontend/src/constants/businessInfo.js`)는 코드에 남겨뒀으므로, 나중에 실제 공개 서비스로
전환하기로 결정하면 코드 변경 없이 해당 값만 채우고 frontend를 재빌드하면 된다. 그 전환
결정이 내려지기 전까지는 이 프로젝트를 실제 사용자에게 공개 출시해서는 안 된다.

### P0-03. 격리 Compose 검증이 기존 로컬 스토리지를 삭제할 수 있다

**근거**

- `COMPOSE_PROJECT_NAME`은 MySQL·MinIO named volume만 격리하고, 네 실행 서비스는 모두 동일한
  호스트 `./storage`를 `/storage`로 바인드 마운트했다.
- 빈 DB로 뜬 독립 worker는 매시 정각 24시간 초과 디렉터리를 DB에 없는 고아 데이터로 판정해
  재귀 삭제한다.
- 2026-08-12 outcome E2E 검증 중 실제로 기존 `uploads` 140개와 `results` 16개가
  `ORPHAN_CLEANUP` 대상으로 삭제됐고, MinIO 삭제 outbox 156건이 생성됐다.

**영향**

프로젝트명만 바꿔 안전하게 격리됐다고 판단한 로컬·CI 검증이 기존 런타임 업로드와 결과를
삭제할 수 있다. 해당 경로는 Git 비추적 런타임 데이터이므로 삭제 후 소스 관리로 복구할 수 없다.

**수정 방법**

- Compose의 모든 `/storage`와 backup/log/metrics 마운트 원본을 `STORAGE_HOST_PATH` 하나로
  통일한다.
- 별도 DB를 쓰는 E2E는 `mktemp -d`로 만든 전용 빈 디렉터리를 반드시 지정한다.
- 검증 전후 기존 스토리지 목록을 비교하고 새 DB의 `ORPHAN_CLEANUP` 작업이 0건인지 확인한다.

**상태(2026-08-12)**: 재발 방지 완료, 삭제 데이터 복구는 미수행. `docker-compose.yml`,
`.env.example`, `frontend/e2e/README.md`에 격리 경로 계약을 반영했다. 전용
`/private/tmp/hanium-outcome-e2e-storage.XuvU6i`로 전체 스택을 다시 기동한 뒤 실제 Chromium
샘플 영상 E2E가 38.1초에 통과했다. 기존 `storage/uploads`·`storage/results` 목록은 전후
SHA-256과 개수 82개가 동일했고 새 DB의 `ORPHAN_CLEANUP`은 0건이었다. 앞서 삭제된 156개
디렉터리는 임의 복구·덮어쓰기를 시도하지 않았으며, 테스트 산출물인지 사용자 데이터인지
현재 DB만으로 확정할 수 없다.

### P1-01. 엔진 이용 가능 상태와 업로드 옵션이 모순된다

**근거**

- 실제 `/status` 화면은 기본 분석, Video LLM, 비밀번호 재설정을 “이용 불가”로 정확히 표시했다.
- 같은 세션의 `/upload`는 `useVideoLlm=true`, `useOpenAi=true`로 시작하며 두 옵션을 체크한 상태로 보여준다.
- 업로드 페이지는 `/api/status`의 capability를 읽지 않는다.
- prod 설정도 기능 비활성 상태로 정상 기동할 수 있다.

**영향**

사용자는 선택한 기능이 실제로 수행됐다고 오해하거나, fallback/mock 결과를 실제 AI 결과로 받아들일 수 있다. 제품 신뢰와 과금·개인정보 고지에 직접 영향을 준다.

**수정 방법**

- 사용자용 capability API를 `available`, `mode`, `reason`, `estimatedTime`, `privacyNotice` 정도의 안정된 계약으로 제공한다.
- 업로드 진입 시 capability를 조회해 이용 불가 옵션은 해제·비활성화하고 이유를 표시한다.
- required capability가 없는 production은 기동을 거부하거나, 출시 모드에서 해당 기능·문구를 완전히 숨긴다.
- 결과 화면은 REAL/MOCK/FALLBACK을 내부 용어 대신 “실제 분석”, “기본 피드백”, “대체 결과”처럼 사용자 관점으로 설명하되 실제 분석이 아닌 데이터는 명확히 구분한다.
- status와 upload가 같은 응답을 소비하는 contract/E2E를 추가한다.

**완료 기준**: 상태 화면이 이용 불가인 기능을 업로드 화면에서 선택할 수 없고, 결과가 어떤 경로로 생성됐는지 사용자가 오해하지 않는다.

**상태(2026-08-12)**: 완료. 업로드 화면은 인증된 `/api/status` capability를 읽어
`UNAVAILABLE`인 Video LLM/OpenAI 옵션을 자동 해제·비활성화하고 사유를 표시한다. 실제 전체
스택 E2E에서 `VIDEO_LLM_POLICY=DISABLED`인 엔진이 `ready=true`와 MOCK capability를 함께
반환했지만 backend가 최상위 `ready`만 보고 사용자 기능을 `AVAILABLE`로 잘못 노출하는 회귀를
발견했다. `HealthController`가 내부 `realModelReady=true`까지 확인하도록 수정해 DISABLED/MOCK은
`UNAVAILABLE`, 실제 모델 준비 상태만 `AVAILABLE`로 공개한다. 단위 테스트와 실제 Chromium
재검증에서 업로드 옵션이 “Video LLM 미사용”으로 고정되고 외부 provider 호출 없이 분석이
완료되는 것을 확인했다.

### P1-02. 온보딩의 맞춤형 피드백 약속이 구현되지 않았다

**근거**

- 화면은 목적·경험·개선 목표를 저장하면 피드백을 더 맞춤화한다고 안내한다.
- backend는 세 값을 `User`에 저장하지만 getter는 분석·점수·OpenAI prompt·코치 채팅에서 호출되지 않는다.
- 계정 화면에서 해당 값을 다시 보거나 수정할 수 없다.
- “나중에 하기”는 서버 상태를 기록하지 않아 다음 로그인 때 다시 온보딩으로 이동한다.
- 로그인 직후 온보딩 분기는 동작했지만 `ProtectedRoute`는 인증 여부만 확인하므로 URL 직접 접근으로 온보딩을 건너뛸 수 있다.

**영향**

사용자가 시간을 들여 입력한 정보가 결과에 반영되지 않으며 제품 약속이 사실과 다르다. 반복 노출/우회도 일관성을 해친다.

**수정 방법**

- 단기: 맞춤화 구현 전까지 문구를 “향후 추천 설정에 활용”로 낮추거나 온보딩을 제거한다.
- 구현 시: 정량 점수는 그대로 유지하고, 목적·경험·개선 목표는 LLM 피드백 우선순위와 설명 난이도에만 사용한다.
- prompt에 들어가는 사용자 설정을 versioned DTO로 만들고 결과에 적용된 profile version을 기록한다.
- 계정 화면에서 조회·수정·초기화할 수 있게 한다.
- `PENDING/COMPLETED/SKIPPED` 상태를 구분해 건너뛰기 의사를 저장한다.
- 온보딩이 필수라면 route guard로 보호하고, 선택 사항이라면 직접 접근을 허용하되 반복 강제를 없앤다.

**완료 기준**: 고정 fixture에서 설정에 따라 설명 우선순위만 달라지고 정량 점수는 동일하며, 사용자가 설정을 수정/철회할 수 있어야 한다.

### P1-03. 업로드·장시간 분석 UX가 실제 사용에 취약하다

**근거**

- 최대 500MB 업로드를 허용하지만 axios 요청에 `onUploadProgress`가 없어 업로드 진행률이 없다.
- 업로드와 분석 실행이 두 단계로 나뉘어 초기에 비활성 버튼이 많다.
- 분석 상태 polling은 한 번만 실패해도 즉시 중단한다.
- 화면의 진행률은 서버 실측이 아니라 단계 체류 시간을 기반으로 보간한 값인데 “예상”이라는 표시가 없다.
- 일반 사용자 화면에 `jobId`와 내부 `storedFilePath`를 표시한다.

**영향**

느린 네트워크에서 멈춘 것으로 보이고, 일시적 모바일 네트워크 장애가 분석 실패처럼 느껴진다. 내부 경로 노출은 정보 가치가 낮고 지원 부담만 늘린다.

**수정 방법**

- 업로드 진행률, 취소, 재시도와 서버 접수 완료를 별도 단계로 표시한다.
- 기본 흐름은 “영상 선택 → 업로드하고 분석 시작” 한 번으로 합치고 고급 옵션만 펼침 영역으로 둔다.
- polling은 지수 backoff와 3~5회 연속 실패 허용, 페이지 재진입 시 서버 job 복구를 적용한다.
- 보간 값은 “예상 진행률”로 표시하거나 단계형 상태만 보여준다.
- 사용자 화면에서는 파일명, 접수 시각, 현재 단계, 예상 범위만 노출하고 jobId는 복사 가능한 “문의용 ID”로 접는다. 저장 경로는 제거한다.
- 최대 영상 길이, 대략적 처리 시간, 창을 닫아도 계속 진행되는지 명시한다.

**완료 기준**: 느린 업로드·일시적 5xx·새로고침·탭 재진입 E2E에서 사용자가 작업 상태를 잃지 않는다.

**상태(2026-08-12)**: 완료. 업로드 진행률, 연속 5회 polling 실패 허용, 문의용 ID 접힘,
내부 저장 경로 제거를 적용했다. 결과 상세의 일반 재시도는 더 이상 `useVideoLlm/useOpenAi`를
강제로 켜지 않고 DB에 저장된 최초 선택을 보존한다. 본문 없는 `/retry` 통합 테스트에서 두 옵션이
`false`인 작업이 Video LLM/OpenAI를 호출하지 않는 것을 확인했다. 브라우저에는 파일명·경로 대신
소유권이 적용된 상태 API를 다시 조회할 수 있는 `jobId`만 저장하고, `UPLOADED`와 실행 중 상태를
페이지 재진입 시 자동 복구하도록 구현했다. Vitest의 언마운트·StrictMode 재마운트 회귀 테스트와
실제 Chromium 새로고침(인증·상태 API 모의 응답)에서 `BASIC_ANALYZING` 및 진행률 복구를 확인했다.
기본 흐름도 “업로드하고 분석 시작” 단일 CTA로 합치고 옵션은 현재 선택값이 보이는 고급 접힘
영역으로 이동했다. 업로드만 끝난 복구 작업에는 “분석 다시 시작”을 제공하며, `/run` 응답 timeout
후 서버 상태가 실행 중이면 오류를 지우고 상태·진행률 추적을 계속한다. Vitest에서 업로드→실행
순서, 옵션 전달, timeout 접수 복구, 명령 응답 후 진행률 지속을 확인했다. 실제 Chromium과
MySQL·Redis·MinIO·backend·독립 worker·analysis-engine·frontend를 연결한 `sample-demo.mp4`
E2E에서 단일 CTA 업로드 후 새로고침했고, 첫 상태 요청에 503을 한 번 주입한 뒤 자동 polling이
재개돼 결과 상세까지 이동하는 것을 확인했다. 분석 job `20260812004142-69612186`은 20.1초에
`COMPLETED`에 도달했다. 회원탈퇴 후 사용자·job·video DB 행은 0건이었고, 비동기 삭제 outbox
2건은 다음 기본 스케줄에 재시도 없이 `COMPLETED`되어 MinIO 업로드·결과 객체도 0건이 됐다.

### P1-04. 결과 목록이 사용자 목표보다 내부 구현을 앞세운다

**근거**

- 결과가 0개여도 여섯 개 요약 카드, 두 묶음 필터, 추이 차트, 검색·정렬·비교가 모두 보인다.
- `OpenAI Mock/Real/Fallback`, `UNKNOWN/FAILED`, `jobId`, Video LLM 방식이 핵심 필터와 검색어로 노출된다.
- 결과가 없는 사용자의 다음 행동은 하단의 작은 문장뿐이다.

**영향**

첫 사용자는 무엇을 해야 하는지보다 시스템 내부 상태를 먼저 학습해야 한다. 풍부한 기능이 오히려 빈 화면의 복잡도를 높인다.

**수정 방법**

- 결과 0개일 때는 안내, 샘플 결과 보기, 첫 영상 업로드 CTA만 보여준다.
- 1개일 때 비교/추이를 숨기고, 2개 이상일 때 점진적으로 공개한다.
- 목록 기본 정보는 파일명, 날짜, 총점, 개선 포인트, 상태로 제한한다.
- 생성 방식은 상세의 “분석 정보” 접힘 영역으로 이동한다.
- 검색은 파일명·메모 중심으로 바꾸고 문의용 ID 검색은 고급 필터로 둔다.

**완료 기준**: 신규 사용자가 결과 목록에서 한 번의 명확한 CTA로 첫 분석을 시작하고, 내부 용어를 몰라도 결과 의미를 이해한다.

**상태(2026-08-12)**: 완료. 결과가 없으면 분석 기능·필터·차트를 모두 숨기고 “첫 영상
업로드하기”를 유일한 주 CTA로 표시하며, 홈의 예시 지표 링크만 보조 행동으로 남겼다. 결과가
1개일 때는 비교·추이·검색·정렬을 숨기고 2개 이상부터 점진적으로 공개한다. 목록 카드의 기본
정보는 제목(파일명/메모), 상태, 총점, 생성일, 첫 개선 포인트로 제한했다. OpenAI/Video LLM
생성 방식과 Mock/Real/Fallback 통계·필터는 목록에서 제거했고 문의용 jobId 검색만 접힌 고급
검색에 남겼다. 서버 `totalElements`를 사용해 pagination 중 전체 건수를 현재 페이지 길이로
오표시하지 않으며, 점수 미산출 실패 결과는 0점이 아닌 `-`로 표시하고 점수 정렬의 마지막에
둔다. 최초 목록 조회 실패는 실제 빈 계정과 분리해 오류 설명과 “다시 시도”만 표시한다.
Vitest 14건과 실제 Chromium에서 빈 결과, 복수 결과, API 503 상태를 확인했다. 1280px와
390×844 모두 가로 넘침이 없었고, 기본 화면에 OpenAI/Mock/jobId가 노출되지 않았다.

### P1-05. 재방문 사용자를 위한 제품 홈과 계정 기능이 부족하다

**근거**

- 로그인 후에도 홈은 마케팅 랜딩 페이지다. 최근 분석, 진행 중 작업, 다음 추천 행동이 없다.
- 계정 화면은 이메일 표시, 비밀번호 변경, 탈퇴만 제공한다.
- 온보딩 설정 수정, 내 데이터 내보내기, 활성 세션, 이메일 변경, 완료 알림, 문의/오류 신고가 없다.
- 홈 내부 footer와 전역 `MainLayout` footer가 연속으로 렌더링된다.
- 요금제 페이지는 결제/플랜이 없다고 정직하게 안내하지만 정식 “요금제” 메뉴명은 서비스 완성도 기대와 어긋난다.

**수정 방법**

- 로그인 사용자의 `/`를 최근 결과·진행 중 작업·다음 연습을 보여주는 개인 대시보드로 전환하거나 별도 `/dashboard`를 둔다.
- 계정에 분석 설정, 데이터 다운로드/삭제 상태, 세션 관리, 알림, 문의 경로를 단계적으로 추가한다.
- 홈 footer를 한 곳으로 통합한다.
- 결제 출시 전에는 “요금제”를 “베타 이용 안내”로 바꾸고, 샘플 결과·분석 한계·실제 제공 capability를 표시한다.
- 홈의 82/71/128 수치는 실제 고객 성과처럼 보이지 않도록 시각적으로 “예시 결과”를 표시한다.

**완료 기준**: 로그인 사용자는 첫 화면에서 진행/최근 결과/다음 행동을 확인하고, 서비스 문의와 데이터 권리 행사를 계정 화면에서 찾을 수 있다.

**상태(2026-08-12)**: 완료. 비로그인 사용자는 기존 공개 랜딩을 유지하고, 로그인 사용자의 `/`는
마케팅 히어로 대신 전용 개인 대시보드를 첫 화면으로 렌더링한다. 대시보드는 최근 10개 작업을
기준으로 진행 중 건수, 완료 건수, 최근 완료 점수, 최근 작업 3개와 우선 다음 행동을 표시한다.
업로드만 끝난 `UPLOADED` 작업도 재개 필요 건수로 포함한다. 진행 작업이 있으면 현재 단계 확인을
먼저 안내하고, 없으면 가장 최근 완료 결과의 개선 포인트를 다음 연습 과제로 사용한다. 조회
실패는 오류 전용 화면과 “다시 시도”를 제공하며 결과가 없으면
첫 업로드 CTA로 전환한다. 계정 화면에서는 현재 온보딩 값을 확인·수정할 수 있고 비밀번호 변경,
개인정보처리방침의 열람·정정·삭제 요청 절차, 전체 데이터 삭제를 포함한 회원탈퇴 경로를 한
화면에서 찾을 수 있다. 중복 홈 footer 제거, “베타 이용 안내” 명칭, 예시 점수 표시는 기존
수정 상태를 유지한다. Home/Account Vitest 19건과 실제 Chromium에서 정상 대시보드, 진행 작업,
503 후 수동 재시도, 계정 설정을 확인했다. 390×844에서도 다음 행동이 첫 viewport에 표시되고
가로 넘침과 예상치 못한 콘솔 오류가 없었다.

### P1-06. release는 artifact 단계이며 실제 배포·롤백은 미완성이다

**근거**

- GHCR build/push와 제한된 staging smoke는 추가됐다.
- production 호스트, 환경 승인, 실제 deploy, rollback, migration 호환성 자동 판정은 없다.
- smoke에서 video-llm-engine, nginx, backup을 기동하지 않는다.
- 배포 태그가 7자리 SHA이고 digest 고정/서명 검증이 없다.

**수정 방법**

- full commit SHA와 image digest를 release manifest에 기록한다.
- release smoke에서 prod+release overlay, nginx same-origin, backup 도구, 선택한 Video LLM 정책을 실제로 기동한다.
- staging 자동 배포 후 로그인→업로드→worker→결과→삭제, SMTP, 경보를 검증한다.
- production은 환경 승인 뒤 staging에서 검증한 동일 digest만 배포한다.
- Flyway 하위 호환성 확인과 이전 digest rollback runbook을 함께 둔다.
- SBOM 생성, keyless 서명, 배포 시 provenance 검증을 점진적으로 추가한다.

**완료 기준**: 동일 digest가 staging에서 검증돼 production에 배포되고, 새 migration과 호환되는 이전 release로 정해진 RTO 안에 복구된다.

**상태(2026-08-12, 범위 제거 — 사용자 결정)**: 현재 프로젝트는 테스트 단계이며 production
운영으로 갈 계획이 없어, 배포 단계(4.3/4.4 설계 포함) 자체가 프로젝트 범위에서 제거됐다.
2026-08-06에는 production 호스트가 없어 `deploy` job을 "계속 보류"로 남겨뒀으나(존재하지 않는
호스트를 향한 워크플로는 테스트 불가능한 죽은 코드가 된다), 사용자가 이후 "운영 계획 자체가
없으므로 배포 단계를 삭제"로 결정했다. `deploy` job은 애초에 구현된 적이 없으므로 되돌릴
코드는 없다 — 4.3/4.4의 설계 내용은 나중에 실제 운영을 결정할 때 참고용으로 문서에 남겨둔다.
호스트 결정과 무관하게 이미 처리된 하위 항목은 그대로 유효하다: SBOM 생성(CycloneDX,
`docker-build` 잡에 아티팩트 업로드 추가, P2-06 참고), migration 호환성 확인 없이 rollback을
실행하지 못하게 막는 체크리스트(`docs/ops/release-log.md`의 "Rollback 체크리스트"), MySQL 8.1
Flyway 호환 리허설(P2-05 참고, 8.4 유지로 결정됨).

### P1-07. 외부 연동과 운영 복구가 아직 실환경에서 증명되지 않았다

로컬/CI가 대체하지 못하는 항목은 실제 SMTP TLS·반송, NVIDIA REAL, OpenAI 품질·비용, 운영 도메인 TLS, Alertmanager 실제 수신, 원격 암호화 백업 복구, staging MinIO 백필이다.

**수정 방법**

- 고정 샘플 영상으로 NVIDIA/OpenAI 품질, timeout, 비용을 기록한다.
- 실제 수신함에서 비밀번호 메일과 장애/해결 알림을 확인한다.
- 암호화 백업을 새 MySQL 인스턴스에 복구하고 사용자·job·asset 수를 대조한다.
- 분석/스토리지 삭제/메일 DEAD_LETTER를 의도적으로 만들어 경보→관리자 재큐잉→감사로그→복구를 확인한다.

**완료 기준**: 담당자, 실행 시각, 증거 링크, 재검증 만료일이 있는 go-live checklist가 모두 승인된다.

**상태(2026-08-12, 범위 제거 — 사용자 결정)**: P1-06과 같은 이유(production 운영 계획 없음)로
프로젝트 범위에서 제거됐다. 실제 SMTP 수신함, NVIDIA/OpenAI 유료 API 키, 운영 도메인, staging
호스트가 있어야 하는 항목들이라 2026-08-06에 "계속 보류"로 남겨뒀으나, 사용자가 이후 "운영
계획이 없으므로 제거"로 결정했다. 이 중 로컬에서 대체 가능했던 부분(MySQL 백업/복구 리허설,
DEAD_LETTER 재큐잉 흐름)은 이미 이전 세션에서 검증됐다(`docs/ops/backup-restore-runbook.md`).

### P2-01. 브라우저 인증 경계의 불필요한 노출을 줄여야 한다

- HttpOnly cookie를 쓰면서 로그인 응답 JSON에도 `accessToken`을 반환한다. 브라우저는 이를 저장하지 않으므로 일반 웹 로그인에서는 제거하는 편이 안전하다.
- cookie 인증인데 Spring Security CSRF는 비활성화돼 있다. `SameSite=Lax`가 기본 방어를 제공하지만 같은 사이트 하위 도메인과 향후 CORS 변경까지 고려한 명시적 CSRF/Origin 검증이 없다.
- 공개 페이지 진입 때 `/api/auth/me`의 예상된 401이 브라우저 콘솔에 resource error로 남는다.

**개선 방향**: 웹 로그인은 cookie-only 응답으로 바꾸고 bearer가 필요하면 별도 명시적 클라이언트 계약으로 분리한다. 상태 변경 요청은 CSRF token 또는 엄격한 Origin 검증을 추가한다. 익명 세션 확인은 콘솔/E2E 오류 예산을 오염시키지 않도록 처리한다.

**상태(2026-08-12)**: 완료. 로그인 응답은 `data.user`만 반환하고 JWT는 HttpOnly
`access_token` 쿠키로만 전달한다. `CookieOriginProtectionFilter`를 추가해 인증 쿠키가
첨부된 상태 변경 요청은 허용 Origin 또는 `Sec-Fetch-Site: same-origin`만 통과시키며,
브라우저 Origin 누락과 `same-site` 교차 origin은 403으로 차단한다. 명시적 Bearer와
Fetch Metadata가 없는 비브라우저 클라이언트는 기존 계약을 유지한다. 세션 확인 전용
`GET /api/auth/me`는 익명·만료·무효 세션에 `200 + data:null`을 반환하므로 공개 페이지
콘솔의 예상된 401 오류 필터도 제거했다. 백엔드 전체 `clean test`, 프론트 286개 테스트,
lint/build를 통과했고 실제 Chromium 공개 랜딩에서 401/네트워크 콘솔 오류가 없음을
확인했다. 별도 CSRF token이 필요한 cross-site embed는 현재 지원 범위가 아니다.

### P2-02. 핵심 로직이 대형 파일에 집중돼 있다

- `analysis-engine/app/api/basic_analysis.py`: 218줄(media I/O·STT·scoring·pose/gesture·face/gaze/emotion·audio 6단계 분리 후)
- `video-llm-engine/app/api/video_llm_analysis.py`: 632줄(media I/O·deadline·NVIDIA provider·response 3단계 분리 후)
- `backend/.../AnalysisCommandService.java`: 681줄
- `frontend/src/pages/ResultDetailPage.jsx`: 1,074줄
- `frontend/src/pages/ResultListPage.jsx`: 766줄
- `frontend/src/pages/UploadPage.jsx`: 1,011줄

**개선 방향**: 동작 변경 없이 characterization test를 먼저 고정하고, 엔진은 media/provider/scoring/orchestration/schema, backend는 command/dispatch/retry/persistence policy, frontend는 hook/mapper/section 순으로 추출한다. 줄 수보다 외부 I/O와 순수 계산 경계가 독립 테스트 가능한지를 완료 기준으로 삼는다.

**상태(2026-08-12, backend 9단계)**: 진행 중. 기존 `AnalysisJobValidator` 분리에 이어
`AnalysisDispatchAdmissionPolicy`와 `AnalysisRetryPolicy`를 추출했다. 접수 정책은 전역 DB
대기열 한도 → 사용자별 한도 → local dispatch일 때 executor 포화 순서를 유지하며, 한도 바로
아래/동일 경계와 remote worker 모드의 executor 미조회까지 독립 테스트한다. 재시도 정책은
옵션 미지정 시 최초 Video LLM/OpenAI 선택 보존, 부분 override, 명시적 false를 각각 고정한다.
추가로 `AnalysisPipelineTerminationHandler`를 추출해 timeout을 취소보다 먼저 판정하는 계약,
정확한 마감 시각 경계, 실행 중 취소, 정상 통과, 종료 결과 저장 실패 격리를 독립 테스트로
고정했다. 상태·진행률·결과 파일·메트릭·타이머 후처리도 이 경계에서 함께 수행한다. 기존 queue
backpressure/full, DEAD_LETTER requeue, claimed dispatch, timeout/metrics characterization도 유지한다.
3단계에서는 `AnalysisOpenAiFeedbackStage`를 추출해 기존 REAL 응답 재사용, 신규 OpenAI 호출,
명시적 비활성화, 피드백 저장을 한 경계로 묶었다. 비활성화가 레거시 `MOCK/legacy constructor`로
저장되던 계약 오류는 `SKIPPED`와 구체적인 미사용 사유로 수정하고, 최종 결과 pipeline과 프론트
상태 배지도 “OpenAI 피드백 사용 안 함”으로 일치시켰다. 백엔드 전체 `clean test` 514건은 실패
0건이며 9건은 조건부 스킵됐고, 프론트 291개 테스트와 lint/build도 통과했다. 서비스는
1,000줄에서 906줄로 줄었다. 현재 이미지로 격리 Compose를 기동해 실제 Chromium 샘플 영상
E2E도 통과했으며, 업로드→독립 worker→analysis-engine→완료→OpenAI `SKIPPED` 공개 계약→
영상 토큰→회원탈퇴/outbox 정리까지 확인했다. 이 과정에서 로컬 `.env`의 NVIDIA feedback
provider가 비용 없는 E2E에 유입될 수 있던 설정과, 쿠키 Origin 보호 이후 직접 API 정리 요청이
403이 되던 스펙을 함께 수정했다. 3단계까지는 기본 분석/Video LLM 호출, 단계 전이,
compact/final 결과 저장 orchestration이 한 파일에 남아 있었다.

4단계에서는 `AnalysisVideoLlmStage`를 추출해 기능 비활성화, 일·월 사용량 제한, 영상 길이 기반
월간 permit 계산, 길이 측정 실패 시 보수적 permit 계산, provider 요청 생성, 재분석의 REAL 응답
강제를 한 경계로 묶었다. 기존 판정 순서와 skip 사유는 유지하고, 8개 독립 테스트로 비활성화,
일간·월간 거부, 250초 영상의 3 permit, 길이 측정 실패의 최대 18 permit, 재분석 오류 계약을
고정했다. 관련 characterization 24건과 백엔드 전체 `clean test` 522건은 실패 0건이며 9건은
조건부 스킵됐다. `AnalysisCommandService`는 906줄에서 732줄로 줄었다. 현재 backend/frontend
이미지와 MySQL·Redis·MinIO·독립 worker·analysis-engine을 격리 Compose로 기동한 실제 Chromium
샘플 영상 E2E도 통과했다. 로그에서 Video LLM `DISABLED`, OpenAI `SKIPPED`, 최종
`COMPLETED(videoLlmGenerationMode=SKIPPED)`와 회원탈퇴 정리를 확인했으며 외부 provider는
호출하지 않았다. 기본 분석 호출, 단계 전이, compact/final 결과 저장 orchestration은 여전히
한 파일에 있어 P2-02 전체는 미완료다. 일간·월간 quota 예약도 서로 하나의 원자 연산은 아니므로
동시 요청 경계에서 permit을 불필요하게 소모할 가능성이 남아 있다.

5단계에서는 `AnalysisBasicStage`를 추출해 MinIO presigned URL 해석과 analysis-engine 요청을
한 경계로 묶었다. 재분석 child job에서는 분석 correlation용 작업 ID와 원본 다운로드용 영상
자산 ID가 다르다는 계약, URL을 요청에 전달하는 경로, URL이 없을 때 로컬 경로 fallback,
엔진 `BusinessException` 원형 전파를 3개 독립 테스트로 고정했다. 진행률·상태·timeout
checkpoint는 순서 책임이므로 orchestration에 유지했다. capability 회귀 테스트 2건까지 더해
백엔드 전체 `clean test` 527건은 실패·오류 0건이며 9건은 조건부 스킵됐다.
`AnalysisCommandService`는 728줄이다. 현재 이미지로 격리 전체 스택을 기동한 실제 Chromium
E2E는 최초에 DISABLED/MOCK Video LLM을 `AVAILABLE`로 잘못 노출하는 P1 계약 오류를 검출했고,
이를 수정한 동일 재실행은 20.1초에 통과했다. 로그에서 새 `AnalysisBasicStage`의 기본 분석 응답,
Video LLM `DISABLED`, OpenAI `SKIPPED`, 최종 `COMPLETED`를 확인했다. 회원탈퇴 후 사용자·job·
video DB 행 0건, 삭제 outbox `COMPLETED` 2건, MinIO 객체 0건까지 확인했다. 단계 진행과
compact/final 결과 저장 orchestration은 여전히 후속 분리 대상이다.

6단계에서는 `AnalysisResultPersistenceStage`를 추출해 기본/Video LLM 원본과 compact 결과,
최종 병합 결과, 실패·취소 종료 결과 저장을 단일 경계로 모았다. compact 반환값과 두 엔진 응답,
최종 저장의 세 provider 응답, `FAILED`/`CANCELLED` 상태와 취소 사유를 4개 계약으로 고정하고,
종료 결과 저장 장애가 원래 파이프라인 결과를 덮지 않는 격리 테스트를 추가했다. 이전에는 이
저장 장애를 조용히 무시했지만 이제 원래 상태를 유지하면서 jobId·종료 상태·예외를 경고 로그로
남긴다. 상태·진행률과 `lastPercent` 갱신 순서는 기존 실패 보고 계약을 보존하기 위해 orchestration에
유지했다. 백엔드 전체 `clean test` 532건은 실패·오류 0건이며 9건은 조건부 스킵됐고,
`AnalysisCommandService`는 701줄이다. 현재 이미지의 격리 전체 스택에서 실제 Chromium 샘플
영상 E2E는 21.8초에 통과했다. 로그에서 기본 분석 응답→compact 60%→OpenAI `SKIPPED`→최종
병합 90%→`COMPLETED` 순서를 확인했다. 최종 완료와 실패 orchestration 및 단계 전이는 후속
분리 대상이다.

7단계에서는 `AnalysisPipelineOutcomeHandler`를 추출해 정상 완료, 실행 중 실패, executor 접수
전 queue-full 실패, claim 선점 실패의 타이머 종료 계약을 한 경계로 모았다. 상태 저장→메트릭→
진행률 완료 순서와 실패 상태→실패 진행률→안전한 종료 결과 저장 순서를 4개 독립 테스트로
고정했다. `AnalysisCommandService`는 705줄이며, 백엔드 전체 `clean test` 536건은 실패·오류
0건, 조건부 스킵 9건이었다. 현재 이미지로 실제 Chromium 샘플 영상 E2E가 최초 32.9초,
스토리지 격리 보강 후 재검증에서 38.1초에 통과했고 로그에서 새 outcome handler의 100% 완료를
확인했다. 단계별 상태·진행률 전이는 아직 orchestration에 남아 후속 분리 대상이다. 재검증 중
별도 Compose 프로젝트가 기존 호스트 스토리지를 공유하는 P0 데이터 격리 문제를 발견해
`STORAGE_HOST_PATH` 계약으로 수정했으며, 상세 영향과 검증 결과는 P0-03에 기록했다.

8단계에서는 `AnalysisPipelineStageReporter`를 추출해 시작, 기본 분석 10%, Video LLM 40%,
compact 60%, OpenAI 75%, 결과 병합 90%의 상태·진행률·메시지를 한 경계로 모았다. 기본 분석은
QUEUED 선점이 이미 DB를 `BASIC_ANALYZING`으로 원자 전이하므로 DB 상태를 중복 저장하지 않고,
나머지 단계는 원본인 DB 상태를 먼저 저장한 뒤 보조 Redis 진행률을 갱신하는 순서를 6개 독립
테스트로 고정했다. `AnalysisCommandService`는 705줄에서 681줄로 줄었다. 백엔드 전체
`clean test` 542건은 실패·오류 0건이며 조건부 스킵은 9건이었다. 전용 임시
`STORAGE_HOST_PATH`와 빈 DB로 전체 스택을 기동한 실제 Chromium 샘플 영상 E2E는 33.9초에
통과했다. worker 로그에서 새 reporter의 10%→60%→90%와 outcome 100%를 확인했고,
Video LLM `DISABLED`, OpenAI 미사용, 최종 `SKIPPED` 계약도 유지됐다. 회원·job·video DB 행은
0건으로 정리됐고 `ORPHAN_CLEANUP`은 0건, 기존 호스트 스토리지의 전후 SHA-256과 디렉터리
105개도 동일했다. 단계 실행 순서와 timeout/cancel 체크포인트 orchestration은 서비스에 남아 있다.

9단계에서는 Video LLM 사용자 일간 1회와 전역 월간 NVIDIA 예상 호출 N회를 Redis Lua 한 번으로
검사·예약하도록 `UserRateLimiter.reserveVideoLlmBudget`을 추가했다. 이전의 `wouldAllow` 두 번 후
daily→monthly 순차 소비는 두 확인 사이 경쟁에서 monthly가 거절될 때 실제 provider 호출 없이
daily permit만 남기는 문제가 있었다. 새 스크립트는 두 용량을 먼저 확인하고 모두 허용될 때만
두 키를 함께 `INCRBY`하며 각 TTL을 설정한다. Redis 장애 시 로컬 fallback도 map 전체를 짧게
잠가 두 창을 함께 판정·갱신한다. 월간 거절 후 daily 불변, 20개 동시 fallback 요청에서 일간
용량 5건·월간 15 permit 정합성, Redis 결과 코드 매핑과 stage skip/provider 계약을 테스트로
고정했다. 백엔드 전체 `clean test` 545건은 실패·오류 0건이며 조건부 스킵은 9건이었다.

격리 Redis 7에서 동일 Lua를 직접 실행해 정상 예약은 daily=1/monthly=3, 이어진 월간 거절은
두 값 불변, 일간 거절은 새 월간 키 미생성, 두 TTL 설정을 확인했다. 현재 backend와 전체 격리
스택에서는 API로 `useVideoLlm=true`를 명시한 job `20260812033322-d0fec8b6`이 30.125초 영상의
예상 호출 1회를 실제 Redis daily/monthly에 함께 예약하고 외부 호출 없는 `MOCK`으로 완료됐다.
회원탈퇴 후 사용자·job·video 행은 모두 0건이었다. 이어 실행한 비용 없는 Chromium 전체 분석
E2E도 36.2초에 통과했다. 두 E2E 후 `ORPHAN_CLEANUP`은 0건이고 기존 호스트 스토리지의 전후
SHA-256과 디렉터리 119개는 동일했다.

분석 엔진 1단계에서는 `app/services/media_io.py`를 추가해 허용 경로 확인, presigned URL
다운로드, 크기 제한, 프레임·오디오 추출, MediaPipe 이미지 준비와 임시 디렉터리 정리를 API
orchestration에서 분리했다. 기존 응답 키·샘플링·fallback·오디오 오류 계약은 유지했다. 동시에
열리지 않은 영상이나 프레임 읽기 예외에서도 `cv2.VideoCapture.release()`가 실행되도록
`try/finally` 수명주기를 고정했다. `test_media_io.py`의 자원 해제·ffmpeg 실패 계약과 이전
다운로드/SSRF/크기 제한 characterization을 새 경계로 이전했다. `basic_analysis.py`는 2,431줄에서
2,040줄로 줄었고 분석 엔진 전체 테스트 148건, Ruff, compileall이 통과했다.

변경 이미지를 전용 `STORAGE_HOST_PATH`와 빈 DB로 기동한 실제 Chromium 샘플 영상 E2E는
20.5초에 통과했다. job `20260812034635-0ddbd71d`가 새 미디어 서비스 경계를 거쳐 프레임·오디오를
추출하고 기본 분석 47점, Video LLM `DISABLED`, OpenAI `SKIPPED`, 최종 `COMPLETED`로 끝났다.
임시 job 디렉터리는 엔진이 정리했고 회원탈퇴 후 사용자·job·video 행은 0건, 삭제 outbox 2건은
모두 `COMPLETED`였다. 기존 호스트 `storage/uploads`·`storage/results`는 전후 132개 파일과
SHA-256이 동일했다. STT/provider와 순수 scoring/orchestration 경계는 후속 분리 대상이다.

분석 엔진 2단계에서는 `app/services/speech_to_text.py`를 추가해 Whisper 모델 풀 대여,
provider 호출, 세그먼트 정규화, transcript/word count 생성, daemon worker timeout과 성공·실패
응답 구성을 API orchestration에서 분리했다. 오디오 추출 선행 실패와 파일 누락에서는 provider를
호출하지 않고, provider 예외와 timeout은 기존 `faster_whisper` 실패 shape으로 변환하는 계약을
characterization test로 고정했다. 성공 응답도 language probability, 세그먼트 시간 반올림,
segment/word count를 포함한 전체 shape으로 검증한다. `basic_analysis.py`는 2,040줄에서 1,898줄로
줄었고 분석 엔진 전체 테스트 151건, Ruff, compileall이 통과했다.

새 이미지의 전용 `STORAGE_HOST_PATH`·빈 DB·모델 풀 1개 설정에서 실제 Chromium 샘플 영상
E2E는 21.0초에 통과했고 job `20260812035545-a670abc2`가 기본 분석 48점, Video LLM
`DISABLED`, OpenAI `SKIPPED`, 최종 `COMPLETED`로 끝났다. E2E 결과 존재만으로 STT 성공을
추정하지 않기 위해 동일 격리 엔진에 직접 요청한 job `20260812040000-abcdef12`도 확인했다.
30.12초 영상에서 실제 faster-whisper가 `success=true`, 언어 `ko`, 7개 세그먼트, 43단어,
오류 없음으로 응답했다. 두 job 임시 디렉터리는 삭제됐고 회원탈퇴 후 사용자·job·video 행은
0건, 삭제 outbox 2건은 모두 `COMPLETED`였다. 기존 호스트 저장소 132개 파일과 SHA-256도
전후 동일했다. Python thread는 native provider 호출을 강제 종료할 수 없어 무한 hang 시 모델
풀 슬롯을 잃을 수 있는 기존 한계가 남으며, 완전 격리는 별도 process worker 설계가 필요하다.

분석 엔진 3단계에서는 `app/services/scoring.py`를 추가해 문서 기준 최종 가중합(자세 25%,
표정 20%, 시선 20%, 음성 25%, 제스처 10%), 탐지 신뢰도·STT fallback·짧은 영상 penalty,
15점 감점 상한과 최종 0~100 clamp를 순수 계산 경계로 분리했다. 구성요소별 정량 점수 산식은
변경하지 않았다. 검출률 0.5/0.7, 영상 길이 0/10초, 빈 analysis method, fallback, penalty 상한,
음수·100 초과 raw score 경계를 characterization test로 고정했다. `basic_analysis.py`는
1,898줄에서 1,781줄로 줄었고 분석 엔진 전체 테스트 163건, Ruff, compileall이 통과했다.

전용 저장소와 빈 DB로 기동한 실제 Chromium 샘플 영상 E2E는 20.3초에 통과했고 job
`20260812040423-679a59af`가 기본 분석 48점, Video LLM `DISABLED`, OpenAI `SKIPPED`, 최종
`COMPLETED`로 끝났다. 동일 격리 엔진에 직접 요청한 job `20260812041000-fedcba98`에서는
구성요소 자세 100·표정 8·시선 0·음성 80·제스처 69가 `rawScore=53`으로 계산되고, 얼굴
검출률 0에 따른 penalty 5와 사유가 적용돼 `totalScore=48`, `lowConfidence=true`가 되는 수치
계약을 확인했다. 두 job 임시 디렉터리는 삭제됐고 회원·job·video 행 0건, 삭제 outbox
`COMPLETED` 2건, 기존 호스트 저장소 132개 파일과 SHA-256 불변도 확인했다.

분석 엔진 4단계에서는 `app/services/pose_analysis.py`를 추가해 Pose Landmarker 모델 풀 호출,
landmark 변환, 자세·어깨 균형 점수와 손목·팔꿈치 기반 제스처 집계를 API orchestration에서
분리했다. 검출 프레임과 MediaPipe 이미지 누락 프레임이 섞인 경우의 탐지율·자세 응답 shape,
연속 2프레임에서 손 활성도·손목 이동량·제스처 variety/visibility/movement 점수를
characterization test로 고정했다. 포즈와 얼굴 분석이 함께 사용하던 평균 계산은
`app/services/scoring.py`로 옮겼다. `basic_analysis.py`는 1,781줄에서 1,391줄로 줄었고 분석
엔진 전체 테스트 165건, Ruff, compileall이 통과했다.

새 이미지와 전용 저장소·빈 DB·모델 풀 1개 설정의 실제 Chromium 샘플 영상 E2E는 22.0초에
통과했다. job `20260812041414-b7ec0580`은 기본 분석 48점, Video LLM `DISABLED`, OpenAI
`SKIPPED`, 최종 `COMPLETED`로 끝났고 임시 디렉터리도 정리됐다. 동일 격리 엔진에 직접 요청한
job `20260812042000-acde1234`에서는 자세 탐지율 1.0·20/20 프레임·자세 100·어깨 균형 100,
제스처 69·활성 프레임 9/20·손 가시성 0.5·평균 손목 이동량 0.0035를 확인했다. 이는 3단계
검증의 자세 100·제스처 69와 동일하다. 직결 job의 총점은 50점으로 달랐으나 이번 경계 밖의
얼굴·표정 등 모델 출력까지 포함한 값이므로 포즈 분리 회귀 근거로 사용하지 않는다. 회원탈퇴
후 사용자·job·video 행은 0건, 삭제 outbox 2건은 모두 `COMPLETED`였다. 기존 호스트 저장소
132개 파일과 SHA-256도 전후 동일했으며, 검증 전용 Compose 볼륨과 임시 루트는 제거했다.

분석 엔진 5단계에서는 `app/services/face_analysis.py`를 추가해 Face Landmarker 모델 풀 호출,
얼굴 landmark 변환, iris 기반 시선·카메라 응시·눈맞춤 점수와 입·눈 개방도 기반 표정 상태·점수
집계를 API orchestration에서 분리했다. 검출 프레임과 이미지 누락 프레임이 섞인 얼굴 응답,
정면 시선 비율, 표정/unknown 혼합 시 평균·다양성·지배 상태를 characterization test로 고정했다.
추가 리뷰에서 포즈·얼굴 서비스가 `zip(sampled_frames, mp_images)`로 이미지 목록이 짧을 때
뒤쪽 프레임 결과를 조용히 누락하던 경계도 발견했다. 두 서비스 모두 프레임을 기준으로 순회하고
누락 이미지를 미검출로 보존하도록 보강했다. `basic_analysis.py`는 1,391줄에서 839줄로 줄었고
분석 엔진 전체 테스트 167건, Ruff, compileall이 통과했다.

새 이미지와 전용 저장소·빈 DB·각 모델 풀 1개 설정의 실제 Chromium 샘플 영상 E2E는 20.4초에
통과했다. job `20260812042958-aad87f2c`는 기본 분석 48점, Video LLM `DISABLED`, OpenAI
`SKIPPED`, 최종 `COMPLETED`로 끝났고 임시 디렉터리도 정리됐다. 동일 격리 엔진에 직접 요청한
job `20260812133500-acde1234`에서는 얼굴 탐지 0/20, 시선 0, 표정 8, 지배 상태 `unknown`을
확인해 3단계 수치 계약과 동일했다. 직결 job 총점 49는 다른 모델 출력까지 포함하므로 이 분리의
회귀 기준으로 사용하지 않는다. 회원탈퇴 후 사용자·job·video 행은 0건이었고, 삭제 outbox
2건은 약 20초 뒤 모두 `COMPLETED`로 수렴했다. 기존 호스트 저장소 132개 파일과 SHA-256은
전후 동일했고, 검증 전용 Compose 볼륨과 임시 루트는 제거했다.

분석 엔진 6단계에서는 `app/services/audio_analysis.py`를 추가해 STT 성공·영상 길이 fallback
음성 분석, 세그먼트 기반 침묵, 한국어 필러, PCM16 WAV 음량 안정성, 문서 가중치 기반 최종
음성 점수와 빈 응답 구성을 API orchestration에서 분리했다. STT 세그먼트→침묵→필러→최종
음성 점수까지 이어지는 통합 계약을 characterization test로 추가하고 기존 WAV·fallback·경계
테스트의 소유 모듈을 새 서비스로 옮겼다. 추가 리뷰에서는 오디오 추출 자체가 실패했는데도
“오디오는 추출했지만”이라고 표시하던 부정확한 fallback 문구를 발견해, 외부 분기 계약인
`analysisMethod`는 유지하면서 실제 추출 성공 여부에 맞는 설명을 반환하도록 수정했다.
`basic_analysis.py`는 839줄에서 218줄로 줄었고 분석 엔진 전체 테스트 169건, Ruff, compileall이
통과했다.

마지막 보정까지 포함한 새 이미지의 실제 Chromium 샘플 영상 E2E는 19.3초에 통과했다. job
`20260812044151-16a03548`는 기본 분석 48점, Video LLM `DISABLED`, OpenAI `SKIPPED`, 최종
`COMPLETED`로 끝났고 임시 디렉터리도 정리됐다. 동일 최종 이미지의 직결 job
`20260812135500-acde1234`는 faster-whisper `success=true`, 언어 `ko`, 7개 세그먼트, 44단어,
말하기 속도 88 WPM, 속도 점수 60, 침묵 점수 100, 음량 안정성 60, 필러 0개·점수 100,
최종 음성 점수 80을 반환했다. STT 단어 수는 이전 실측 43과 달리 44였지만 음성 점수 80은
동일하므로 provider 출력 변동과 서비스 분리 회귀를 구분한다. 두 차례 E2E 후 회원·job·video
행은 모두 0건이었고 삭제 outbox 4건은 오류·재시도 없이 모두 `COMPLETED`였다. 마지막 2건은
30초 폴링 직후 분 단위 스케줄 경계에서 처리됐다. 기존 호스트 저장소 132개 파일과 SHA-256은
전후 동일했고, 검증 전용 Compose 볼륨과 임시 루트는 제거했다.

Video LLM 엔진 1단계에서는 `app/services/media_io.py`와 `app/services/deadline.py`를 추가해
허용 로컬 경로, presigned URL host:port allowlist, redirect 거부, 스트리밍 다운로드 상한,
부분·완료 임시 파일 수명주기와 전체 요청 잔여 시간 계산을 API 모듈에서 분리했다. API DTO를
서비스가 역참조하지 않도록 필요한 속성만 선언한 Protocol을 사용해 순환 의존을 피했다.
기존 SSRF·로컬 fallback·Content-Length 상한 테스트에 더해 길이 헤더 없이 본문 스트리밍 중
상한을 넘는 경우에도 부분 파일이 삭제되는 계약과 deadline 없음·잔여 시간·만료 계약을
독립 테스트로 추가했다. `video_llm_analysis.py`는 1,336줄에서 1,169줄로 줄었고 Video LLM
전체 테스트 188건, Ruff, compileall이 통과했다. NVIDIA live 테스트 1건은 키가 없어 제외됐다.

변경 이미지를 전용 저장소와 빈 DB의 격리 스택에 기동한 실제 Chromium 샘플 영상 E2E는
33.6초에 통과했다. job `20260812045413-3b47f2c1`은 기본 분석 50점, Video LLM `SKIPPED`,
OpenAI `SKIPPED`, 최종 `COMPLETED`로 끝났고 분석 임시 디렉터리도 정리됐다. 외부 호출이
비활성화된 E2E만으로 새 media 경계를 직접 증명할 수 없으므로, 동일 최종 Video LLM 컨테이너에서
`/storage/uploads/direct/sample-demo.mp4`를 새 서비스로 열어 `video/mp4`, 961,000바이트와
허용 경로를 확인했다. 회원탈퇴 후 사용자·job·video 행은 0건, 삭제 outbox 2건은 모두
`COMPLETED`였다. 기본 분석 총점 변동은 Video LLM이 실행되지 않은 상태의 정량 모델 출력이므로
이 분리의 회귀 지표로 사용하지 않는다. 기존 호스트 저장소 132개 파일과 SHA-256은 전후
동일했고, 검증 전용 Compose 볼륨과 임시 루트는 제거했다.

Video LLM 엔진 2단계에서는 `app/services/nvidia_provider.py`를 추가해 작은 영상의 inline
base64 입력, 큰 영상의 asset 생성·스트리밍 업로드, chat completion POST, 202 비동기 polling,
asset cleanup과 provider 사용 로그를 API 모듈에서 분리했다. API에는 실제 호출 세마포어,
prompt payload 생성, 모델 JSON 파싱·정규화 orchestration만 남겼다. 기존 asset 생성·업로드
실패, 정상 cleanup, cleanup 실패 무시, polling 완료·timeout 계약에 chat 연결 실패 후에도
업로드된 asset을 별도 cleanup client로 삭제하는 characterization test를 추가했다.
`video_llm_analysis.py`는 1,169줄에서 852줄로 줄었고 Video LLM 전체 테스트 189건, Ruff,
compileall이 통과했다. NVIDIA 실제 API live 테스트 1건은 키가 없어 제외됐다.

최종 이미지를 격리 전체 스택에 기동한 실제 Chromium 샘플 영상 E2E는 36.8초에 통과했다.
job `20260812050340-10d4b4bf`는 기본 분석 50점, Video LLM `SKIPPED`, OpenAI `SKIPPED`,
최종 `COMPLETED`로 끝났고 분석 임시 디렉터리도 정리됐다. 외부 NVIDIA 호출 없이도 최종
컨테이너에 새 provider가 반영됐는지 확인하기 위해 작은 MP4 fixture를 inline 입력으로 변환해
`assetId=None`, `contentType=video/mp4`, base64 data URL을 확인했다. asset/chat/poll/cleanup의
실제 HTTP 순서와 오류 정책은 fake client 및 로컬 fault-injection 서버 테스트로 검증했다.
회원탈퇴 후 사용자·job·video 행은 0건이었고 삭제 outbox 2건은 약 25초 후 모두
`COMPLETED`로 수렴했다. 격리 스택 종료 후 컨테이너·네트워크·named volume과 전용 임시
루트를 모두 제거했다. 기존 호스트 `storage/uploads`·`storage/results`는 사전 기준과 동일한
132개 파일이었고 최근 60분 변경 파일도 0개여서 검증 환경이 기존 런타임 데이터를 건드리지
않았음을 확인했다.

Video LLM 엔진 3단계에서는 `app/services/nvidia_response.py`를 추가해 chat completion content
추출, Markdown fence 제거와 모델 JSON 파싱, 네 관찰 category의 시간·문자열·confidence 검증,
영상 길이 상·하한 clamp, global summary 검증과 공개 응답 정규화를 API 모듈에서 분리했다.
provider 전용 추가 필드를 공개 응답에서 제거하고 문자열을 trim하는 전체 응답 shape 계약과,
content part의 `text`가 문자열이 아닐 때 내부 `TypeError`를 노출하지 않고 명시적인 provider
응답 오류로 거부하는 계약을 추가했다. 기존 응답 방어 테스트도 새 서비스 소유로 옮겼다.
`video_llm_analysis.py`는 852줄에서 632줄로 줄었고 집중 테스트 111건, Video LLM 전체
비-live 테스트 191건, Ruff, compileall과 diff 검사가 통과했다. NVIDIA 실제 API live 테스트
1건은 키가 없어 제외됐다.

새 최종 이미지를 전용 저장소와 빈 DB의 격리 전체 스택에 기동한 실제 Chromium 샘플 영상
E2E는 36.6초에 통과했다. job `20260812051828-03f8a97d`는 기본 분석 50점, Video LLM
`SKIPPED`, OpenAI 생략, 최종 `COMPLETED`로 끝났고 분석 임시 디렉터리도 정리됐다. 동일
Video LLM 컨테이너에서 새 response 서비스 파일 존재, fenced JSON 파싱, 공개 REAL 응답
정규화를 직접 확인했다. 회원탈퇴 후 사용자·job·video 행은 0건이었고 삭제 outbox 2건은
모두 `COMPLETED`로 수렴했다. 격리 컨테이너·네트워크·named volume과 임시 루트를 제거했으며,
기존 호스트 `storage/uploads`·`storage/results`는 전후 동일한 132개 파일과 집계 SHA-256
`fb4342df16cba1b7bb0313d42774ffdeed2b6a4845f39ed08f2c9f7f5d120d31`을 유지했다.

### P2-03. 관리자 파괴적 조치의 사유·상관관계가 부족하다

정지, 강제 탈퇴, 결과 삭제, 수동 재큐잉은 감사로그를 남기지만 사용자 입력 사유와 incident/reference ID가 구조화된 필수 필드가 아니다.

**개선 방향**: 파괴적 조치 DTO에 길이 제한 `reason`을 필수로 추가하고 requestId/incidentId를 감사로그 컬럼으로 저장한다. UI 확인창에서도 대상과 영향 범위를 다시 보여주고, 민감정보·토큰·원본 오류 전문은 detail에 저장하지 않는다.

**상태(2026-08-12)**: 완료. 계정 정지·강제 탈퇴·결과 삭제와 분석/스토리지 삭제/비밀번호
메일 DEAD_LETTER 재큐잉 6개 API가 공통 `AdminActionReasonRequest`를 사용한다. 사유는
공백 제거 후 필수·최대 500자, 선택적 incident/reference ID는 공백을 null로 정규화하고 최대
100자로 제한한다. 모든 경로가 공백 사유를 서비스 실행 전에 400으로 거부하는 통합 테스트를
추가했다. `RequestIdFilter`의 `X-Request-Id`를 동일 트랜잭션의 감사로그에 저장하고 조회 UI에서
사유·requestId·incidentId를 각각 확인할 수 있다. 관리자 확인 다이얼로그는 대상과 영향을
명시하고 필수 사유 및 선택 참조 ID를 입력받아 모든 조치 API에 구조화해 전달한다. 강제 탈퇴
감사 `detail`에 남던 탈퇴 사용자 이메일을 제거했으며 토큰·원본 오류 전문도 복사하지 않는다.
백엔드 전체 `clean test`, 프론트 287개 테스트·lint·build를 통과했다. 실제 Chromium에서
계정 정지 다이얼로그의 필수/선택 입력, 비어 있을 때 확인 비활성화, 두 필드의 POST JSON,
성공 후 상태 변경과 콘솔 오류 0건을 확인했다.

### P2-04. 결과 계약과 버전 호환성이 동적 Map에 많이 의존한다

분석 결과와 모델 응답 내부가 `Map<String, Object>` 중심이라 필드 누락·타입 변경·과거 결과 호환성을 컴파일 타임에 보장하기 어렵다.

**개선 방향**: 안정된 top-level부터 versioned DTO/JSON Schema로 전환하고, 저장 schema version과 frontend parser version을 함께 관리한다. 과거 결과 fixture와 OpenAPI consumer contract diff를 CI에 추가한다.

**상태(2026-08-09)**: 첫 단계 완료. 현재 성공·실패 저장 결과에 `schemaVersion=1`을 기록하고,
버전 필드가 없는 과거 결과는 `0(legacy)`으로 정규화한다. 상세 API는 `resultSchemaVersion`을
최상위 필드로도 노출한다. `legacy-v0.json`과 `current-v1.json` fixture 계약 테스트에서 과거
결과 호환성과 타입화된 `ScoreSummary` shape을 고정했다. frontend parser도 legacy 0/current 1만
허용하고, 미래 버전·비정상 타입·최상위/저장 버전 불일치를 상세 화면 렌더링 전에 차단한다.
호환성 오류 화면에서는 삭제·재시도 등 결과 조작을 노출하지 않는다. 나머지 `feedback`,
`pipeline`, 엔진 세부 응답 DTO화와 OpenAPI consumer contract diff는 후속 범위다.

### P2-05. 문서와 의존성 기준이 일부 현재 상태와 어긋난다

- `docs/PROJECT_STRUCTURE.md` 기준 커밋이 `2cda065`로 오래됐다.
- `.env.example`에 제거된 `GET /api/health/engines` 설명이 남아 있다.
- 로컬 backend 기동 로그에서 MySQL 8.4가 현재 Flyway가 검증한 최신 MySQL 8.1보다 새롭다는 경고가 발생했다. migration V1~V24는 현재 정상 검증됐지만 향후 호환 위험이다.

**개선 방향**: 구조 문서와 endpoint 참조를 현재 HEAD로 갱신하고 doc-lint를 추가한다. Spring Boot/Flyway 호환표를 확인해 지원 조합으로 올리거나 MySQL 버전을 맞춘 뒤 실제 MySQL migration/rollback rehearsal을 수행한다.

**상태(2026-08-09)**: `mysql:8.4` 유지 결정은 그대로다. Spring Boot 3.5.3 기본 Flyway
11.7.2를 같은 11.x 계열의 11.20.3으로 올렸고, 격리된 MySQL 8.4.10 빈 볼륨에서 backend를
실제로 부팅했다. V1~V26 적용, Hibernate `ddl-auto: validate`, V25/V26 `success=1`, 신규 컬럼
조회, backend 관리 헬스 `UP`을 확인했으며 기존의 "database newer than this version of Flyway"
경고는 전체 로그에서 0건이었다. Redgate가 MySQL 8.4를 개별 verified version으로 명시한 것은
아니므로, 이는 이 프로젝트 migration과 스키마의 실호환 증거로 한정한다.

### P2-06. 테스트 gate와 공급망 보증을 더 강화할 수 있다

- coverage는 측정하지만 최소 하락 기준이 없다.
- 실제 관리자 브라우저 흐름, NVIDIA/OpenAI live, production same-origin release UI는 필수 CI가 아니다.
- Trivy는 report-only이며 SBOM/서명/provenance가 없다.
- 직전 SMTP 통합 테스트는 한 차례 실패 후 다음 실행에서 통과해 flake 가능성이 있다.

**개선 방향**: 현재보다 낮아지지 않는 보수적 coverage 기준과 diff coverage를 적용하고, SMTP 테스트를 반복 실행해 동시성/수명주기 문제를 제거한다. 관리자 E2E와 배포 digest smoke를 추가하며 live provider 테스트는 비용이 드는 주기/수동 workflow로 분리한다.

## 4. 페이지별 만족도 점검

| 페이지 | 확인 결과 | 만족하지 못하는 부분 | 우선 개선 |
|---|---|---|---|
| 홈 | 시각 완성도와 기능 설명이 좋고 모바일 메뉴 정상 | 로그인 후에도 마케팅 홈, footer 중복, 예시 수치 표시 약함, 실제 capability보다 강한 AI 표현 | 개인 대시보드, footer 통합, 명시적 예시/기능 상태 |
| 요금제 | 미출시·무료 상태를 솔직히 안내 | 정식 요금제 메뉴가 제품 기대를 높이며 플랜/결제 없음 | “베타 이용 안내”로 임시 전환 |
| 회원가입/로그인 | 검증·오류 문구와 온보딩 분기 동작 | 이메일 인증 없음, 관리자 선점 P0, 동의 버전 UX 보완 필요 | 권한 부여 분리, 이메일 검증, 동의 이력 |
| 비밀번호 찾기 | 화면과 outbox 구조 존재 | 현재 SMTP 불가 상태, 지연/스팸/지원 안내 부족 | capability 안내와 재발송/지원 경로 |
| 온보딩 | 질문이 짧고 이해하기 쉬움 | 답변 미사용, skip 미기록, 수정 불가, 직접 URL 우회 | 약속 축소 또는 실제 personalization 연결 |
| 업로드 | 형식·용량·drag/drop·모바일 배치 양호 | 불가 엔진 기본 선택, 업로드 진행률 없음, 두 단계 복잡성, 내부 경로 노출, polling 1회 실패 종료 | capability 연동, 단일 CTA, 복구 가능한 진행 UX |
| 결과 목록 | 통계·추이·비교·메모 기능 풍부 | 빈 상태도 과밀, MOCK/REAL/jobId 중심, 첫 행동 약함 | 점진 공개와 사용자 언어 |
| 결과 상세 | 세부 분석과 실제/대체 구분 장치 존재 | 파일 1,039줄, 기술 메타가 많고 결과 품질 신고/문의 경로 없음 | 핵심 요약 우선, 섹션 분리, 오류 신고 |
| 서비스 상태 | 내부 URL을 숨기고 기능별 상태를 명확히 표시 | 인증 사용자만 접근, upload와 capability 불일치 | 공개 최소 상태 페이지 + 동일 계약 소비 |
| 계정 | 비밀번호 변경과 탈퇴 가능 | 설정 수정·데이터 내보내기·세션·알림·문의 없음 | 데이터 권리와 개인 설정 허브 |
| 관리자 개요 | 사용자/분석/복구 수치를 업무 관점으로 잘 분리 | incident 연결과 조치 이유 부족 | reason/requestId/incidentId |
| 관리자 복구 | 분석·삭제·메일 DEAD_LETTER 분리와 빈 상태 명확 | 실패 원인 drill-down, runbook 링크, bulk 선택 없음 | 원인/최근 시도/런북/안전한 일괄 처리 |
| 감사로그 | 관리자·액션·대상·시간 필터 가능 | 사유·requestId·export 부족 | 구조화 필드와 export |
| 약관/개인정보 | 데이터 흐름과 보존을 비교적 상세히 기술 | 실제 사업자/책임자 placeholder와 초안 상태 | 출시 전 전문 검토 완료 |

390×844 viewport에서 업로드·결과·관리자 개요의 `documentScrollWidth`는 모두 390으로 측정돼 가로 넘침은 확인되지 않았다.

## 5. 권장 실행 순서

### 0단계 — 공개 출시 잠금과 의사결정 (즉시)

- 관리자 이메일 기반 공개 승격 경로를 release blocker로 지정
- 실제 제공할 Video LLM/OpenAI capability와 fallback 정책 확정
- 사업/개인정보 문서 책임자와 출시 승인자 확정

완료 기준: P0 두 항목이 issue와 CI/release checklist에 차단 조건으로 등록된다.

### 1단계 — 관리자 권한 모델 교체 (1~2일)

- 공개 signup/login role 동기화 제거
- out-of-band admin bootstrap/초대 도입
- 관리자 승격·강등·회수 감사로그와 통합 테스트

완료 기준: 공개 API 공격 시나리오로 ADMIN 생성 불가.

### 2단계 — 정직한 capability UX (2~4일)

- status/upload 공용 capability contract
- 불가 기능 자동 비활성화와 이유 표시
- REAL/MOCK/FALLBACK 사용자 언어 정리
- 업로드 진행률과 polling 복구

완료 기준: 엔진을 끈 E2E와 켠 E2E 모두 UI가 실제 동작을 정확히 설명.

### 3단계 — 온보딩과 핵심 사용자 여정 정리 (3~5일)

- 온보딩 저장값을 피드백 우선순위에만 연결하거나 기능 제거
- SKIPPED 상태와 계정 수정 화면
- 결과 0/1/2개 점진 공개, 개인 대시보드, 문의/신고 경로

완료 기준: 신규 가입→첫 분석→결과 이해→재연습 흐름을 내부 용어 없이 완료.

### 4단계 — launch gate와 실제 release 완성 (약 1주)

- 약관/개인정보 확정
- full SHA/digest release manifest, staging deploy, production 승인, rollback
- SMTP/NVIDIA/OpenAI/Alertmanager/backup restore 실환경 증거

완료 기준: 동일 digest의 staging 검증과 production 배포·복구 rehearsal 완료.

### 5단계 — 구조·계약·테스트 개선 (2~4주, 기능 개발과 병행)

- 대형 orchestration/page 점진 분리
- 결과 schema version과 contract diff
- coverage/diff coverage, 관리자 E2E, supply-chain provenance
- 관리자 사유/requestId/incidentId

완료 기준: 외부 I/O, 정책, 계산, 표현 계층이 독립 테스트 가능하고 변경 위험이 CI에서 차단됨.

## 6. 2026-08-03 초기 리뷰에서 실행한 검증

- 현재 브랜치, 변경 파일, 최근 커밋, 서비스 구조와 진입점 확인
- 인증·권한·온보딩·업로드·polling·결과·관리자·release 코드 정적 리뷰
- MySQL, Redis, MinIO, backend, frontend 컨테이너를 기동해 실제 화면 점검
- 공개 홈, 요금제, 약관, 개인정보, 회원가입, 로그인, 비밀번호 찾기 점검
- 일반 사용자 온보딩, 업로드, 결과 목록, 서비스 상태, 계정 점검
- 관리자 개요와 복구 화면 점검
- 390×844 모바일 폭에서 주요 페이지의 실제 document width 측정
- 검증용 관리자 이메일 회원가입으로 P0 취약점 재현
- 검증용 일반/관리자 계정 두 개를 정상 회원탈퇴 API로 삭제하고 로그인 401로 삭제 확인
- 리뷰를 위해 기동한 컨테이너와 브라우저 세션 종료
- GitHub Actions 최신 run과 직전 실패 로그 확인

2026-08-03 초기 리뷰에서는 실제 영상 신규 업로드 E2E, NVIDIA/OpenAI live 호출, 실제 SMTP
발송, 원격 배포/복구를 수행하지 않았고 애플리케이션 코드도 수정하지 않았다. 이후 구현과
검증 결과는 각 문제의 날짜별 상태 기록을 기준으로 한다.

## 7. 남은 위험과 다음 한 단계

P0-01 관리자 권한 모델은 완료됐다. 현재 공개 출시의 직접 차단 조건은 **P0-02 실제 사업자·개인정보
보호책임자 정보와 전문 검토**, production 호스트, SMTP/NVIDIA/OpenAI/Alertmanager 실환경 증거다.
GitHub Repository Variables의 사업자 정보 9개가 비어 있으므로 frontend release는 의도적으로
실패한다.

P2-04의 저장 schema version, 과거 fixture, frontend parser 호환성 계약과 P1-03 업로드·장시간
분석 UX, P1-04 결과 목록 단순화, P1-05 재방문 사용자 홈·계정 경로, P2-01 브라우저 인증
경계, P2-03 관리자 파괴적 조치 감사 계약을 완료했다. P2-02 backend 9단계로 dispatch/retry,
timeout/cancel, 기본 분석, OpenAI 피드백, Video LLM 실행, 결과 저장, 단계 전이, 최종 outcome
경계를 추출하고 Video LLM 일·월 quota도 단일 Redis 원자 예약으로 보강했으며, 비활성화
`SKIPPED` 계약도 frontend까지 정렬했다. analysis-engine 6단계로 media I/O·자원 해제,
Whisper STT provider/timeout, 최종 scoring·reliability penalty, pose/posture·gesture,
face/gaze/emotion, speech/pause/filler/volume 분석 계약을 분리했다. API 파일은 218줄의
orchestration·실패 응답 경계만 남아 analysis-engine P2-02의 현재 분리 목표를 완료했다.
E2E의 빈 DB와 기존 런타임 스토리지가 결합되던 P0-03도 `STORAGE_HOST_PATH` 격리 계약으로
차단했다. Video LLM 엔진도 media I/O·deadline, NVIDIA provider, response 3단계 분리를
완료했다. 외부 값 없이 진행 가능한 다음 로컬 단위는 **632줄인
`video-llm-engine/app/api/video_llm_analysis.py`의 inline/asset별 prompt payload와 영상 길이별
시간 지시문을 characterization test로 고정하고 prompt service로 추출할지, 장시간 영상의
split·구간 호출·offset merge orchestration을 먼저 분리할지 책임 응집도를 기준으로 결정하는 것**이다.
`AnalysisCommandService`의 남은 단계 실행 순서와 checkpoint orchestration은 더 잘게 나눌 때
오히려 흐름을 분산시키지 않는지 함께 판단한다.
production deploy·rollback과 실제 provider 검증은 호스트·키·수신처가 준비된 뒤 수행한다.
