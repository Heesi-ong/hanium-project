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

### P1-07. 외부 연동과 운영 복구가 아직 실환경에서 증명되지 않았다

로컬/CI가 대체하지 못하는 항목은 실제 SMTP TLS·반송, NVIDIA REAL, OpenAI 품질·비용, 운영 도메인 TLS, Alertmanager 실제 수신, 원격 암호화 백업 복구, staging MinIO 백필이다.

**수정 방법**

- 고정 샘플 영상으로 NVIDIA/OpenAI 품질, timeout, 비용을 기록한다.
- 실제 수신함에서 비밀번호 메일과 장애/해결 알림을 확인한다.
- 암호화 백업을 새 MySQL 인스턴스에 복구하고 사용자·job·asset 수를 대조한다.
- 분석/스토리지 삭제/메일 DEAD_LETTER를 의도적으로 만들어 경보→관리자 재큐잉→감사로그→복구를 확인한다.

**완료 기준**: 담당자, 실행 시각, 증거 링크, 재검증 만료일이 있는 go-live checklist가 모두 승인된다.

### P2-01. 브라우저 인증 경계의 불필요한 노출을 줄여야 한다

- HttpOnly cookie를 쓰면서 로그인 응답 JSON에도 `accessToken`을 반환한다. 브라우저는 이를 저장하지 않으므로 일반 웹 로그인에서는 제거하는 편이 안전하다.
- cookie 인증인데 Spring Security CSRF는 비활성화돼 있다. `SameSite=Lax`가 기본 방어를 제공하지만 같은 사이트 하위 도메인과 향후 CORS 변경까지 고려한 명시적 CSRF/Origin 검증이 없다.
- 공개 페이지 진입 때 `/api/auth/me`의 예상된 401이 브라우저 콘솔에 resource error로 남는다.

**개선 방향**: 웹 로그인은 cookie-only 응답으로 바꾸고 bearer가 필요하면 별도 명시적 클라이언트 계약으로 분리한다. 상태 변경 요청은 CSRF token 또는 엄격한 Origin 검증을 추가한다. 익명 세션 확인은 콘솔/E2E 오류 예산을 오염시키지 않도록 처리한다.

### P2-02. 핵심 로직이 대형 파일에 집중돼 있다

- `analysis-engine/app/api/basic_analysis.py`: 2,431줄
- `video-llm-engine/app/api/video_llm_analysis.py`: 1,336줄
- `backend/.../AnalysisCommandService.java`: 1,045줄
- `frontend/src/pages/ResultDetailPage.jsx`: 1,039줄
- `frontend/src/pages/ResultListPage.jsx`: 778줄
- `frontend/src/pages/UploadPage.jsx`: 738줄

**개선 방향**: 동작 변경 없이 characterization test를 먼저 고정하고, 엔진은 media/provider/scoring/orchestration/schema, backend는 command/dispatch/retry/persistence policy, frontend는 hook/mapper/section 순으로 추출한다. 줄 수보다 외부 I/O와 순수 계산 경계가 독립 테스트 가능한지를 완료 기준으로 삼는다.

### P2-03. 관리자 파괴적 조치의 사유·상관관계가 부족하다

정지, 강제 탈퇴, 결과 삭제, 수동 재큐잉은 감사로그를 남기지만 사용자 입력 사유와 incident/reference ID가 구조화된 필수 필드가 아니다.

**개선 방향**: 파괴적 조치 DTO에 길이 제한 `reason`을 필수로 추가하고 requestId/incidentId를 감사로그 컬럼으로 저장한다. UI 확인창에서도 대상과 영향 범위를 다시 보여주고, 민감정보·토큰·원본 오류 전문은 detail에 저장하지 않는다.

### P2-04. 결과 계약과 버전 호환성이 동적 Map에 많이 의존한다

분석 결과와 모델 응답 내부가 `Map<String, Object>` 중심이라 필드 누락·타입 변경·과거 결과 호환성을 컴파일 타임에 보장하기 어렵다.

**개선 방향**: 안정된 top-level부터 versioned DTO/JSON Schema로 전환하고, 저장 schema version과 frontend parser version을 함께 관리한다. 과거 결과 fixture와 OpenAPI consumer contract diff를 CI에 추가한다.

### P2-05. 문서와 의존성 기준이 일부 현재 상태와 어긋난다

- `docs/PROJECT_STRUCTURE.md` 기준 커밋이 `2cda065`로 오래됐다.
- `.env.example`에 제거된 `GET /api/health/engines` 설명이 남아 있다.
- 로컬 backend 기동 로그에서 MySQL 8.4가 현재 Flyway가 검증한 최신 MySQL 8.1보다 새롭다는 경고가 발생했다. migration V1~V24는 현재 정상 검증됐지만 향후 호환 위험이다.

**개선 방향**: 구조 문서와 endpoint 참조를 현재 HEAD로 갱신하고 doc-lint를 추가한다. Spring Boot/Flyway 호환표를 확인해 지원 조합으로 올리거나 MySQL 버전을 맞춘 뒤 실제 MySQL migration/rollback rehearsal을 수행한다.

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

## 6. 이번 리뷰에서 실행한 검증

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

실제 영상을 새로 업로드하는 E2E, NVIDIA/OpenAI live 호출, 실제 SMTP 발송, 원격 배포/복구는 이번 리뷰에서 수행하지 않았다. 애플리케이션 코드는 수정하지 않았다.

## 7. 남은 위험과 다음 한 단계

가장 먼저 구현할 단위는 **P0-01 관리자 권한 모델 교체**다. 이 수정 전에는 다른 UI 개선이나 production 배포를 승인하면 안 된다. 변경 범위는 `AuthController`, 관리자 bootstrap/role service, migration 또는 운영 CLI, 관련 통합 테스트와 운영 문서로 제한한다. 이 단계가 통과한 뒤 capability UI를 수정하는 것이 다음 순서다.
