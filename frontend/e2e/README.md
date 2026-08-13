# 프론트엔드 E2E (Playwright)

브라우저에서 실제 페이지를 띄워 라우팅/렌더 회귀와 인증 계약을 검증합니다.

## 설치 (최초 1회)

```bash
cd frontend
npm install                 # package.json에 @playwright/test 추가됨
npx playwright install chromium
```

## 실행

```bash
# 공개 페이지 흐름 (백엔드 불필요, dev 서버 자동 기동)
npm run test:e2e

# 이미 떠 있는 서버를 대상으로
BASE_URL=http://localhost:5173 npm run test:e2e

# 인증 계약(회원가입→로그인→/me→로그아웃)까지 (백엔드/DB 필요)
E2E_FULL_STACK=true API_BASE_URL=http://localhost:8080 npm run test:e2e
```

실제 분석 파이프라인은 backend, analysis-worker, analysis-engine, MySQL, Redis, MinIO가
모두 healthy인 상태에서 별도로 실행합니다. 저장소 루트의 `sample-demo.mp4`를 기본 입력으로
사용하며 Video LLM/OpenAI 외부 호출은 끄고 정량 분석 엔진과 워커는 실제 구현을 사용합니다.

```bash
E2E_STORAGE_DIR="$(mktemp -d)"

OPENAI_ENABLED=false FEEDBACK_LLM_PROVIDER=openai NVIDIA_API_KEY= \
VIDEO_LLM_POLICY=DISABLED VIDEO_LLM_ENABLED=false \
STORAGE_HOST_PATH="$E2E_STORAGE_DIR" docker compose up -d --wait

E2E_ANALYSIS_PIPELINE=true \
BASE_URL=http://localhost:5173 \
API_BASE_URL=http://localhost:8080 \
npm run test:e2e -- e2e/analysis-pipeline.spec.js
```

로컬 `.env`에 NVIDIA feedback provider와 키가 있어도 외부 호출이 켜지지 않도록 위 provider와
빈 키 값을 함께 지정합니다. 스펙의 인증된 직접 API 요청은 `BASE_URL`의 Origin을 보내므로,
backend의 쿠키 Origin 보호를 우회하지 않고 허용된 브라우저 요청으로 검증됩니다.
`STORAGE_HOST_PATH`도 반드시 E2E 전용 빈 디렉터리로 지정해야 합니다. 격리된 Compose 프로젝트의
빈 DB와 기존 `./storage`를 함께 사용하면, 워커의 고아 정리 스케줄러가 기존 업로드·결과를
DB에 없는 데이터로 판단해 삭제할 수 있습니다. 검증 종료 후 해당 임시 디렉터리는 별도로
확인한 뒤 정리합니다.

기본 완료 대기 시간은 10분입니다. 느린 CI/ARM 환경에서는
`E2E_ANALYSIS_MAX_WAIT_MS`, 다른 영상은 `E2E_VIDEO_PATH`로 조정할 수 있습니다. E2E는
브라우저 UI의 실제 polling 주기를 그대로 사용하며 별도 API polling을 추가하지 않습니다.
테스트 계정과 생성된 분석 데이터는 종료 시 회원탈퇴 API로 정리합니다.

기본 영상은 `fixtures/sample-demo-golden-v1.json`의 버전·기대 총점·허용 편차와 함께 관리합니다.
현재 기준은 `sample-demo.mp4` 총점 48, 허용 편차 3이며 실행 로그에 expected/actual/drift를
출력합니다. 분석 공식을 의도적으로 변경했을 때만 실제 결과를 검토한 뒤 fixture 버전을 올리고,
단순히 실패를 없애기 위해 허용 편차를 넓히거나 기대값을 덮어쓰지 않습니다. `E2E_VIDEO_PATH`로
다른 영상을 지정한 경우에도 현재 fixture를 적용하므로, 별도 영상 회귀를 추가하려면 영상별
fixture 선택 로직을 먼저 추가해야 합니다.

## 스펙

- `public-flow.spec.js` — 랜딩/네비게이션/약관/404. **히어로 제목의 명암비를 실측**해 2026-07-16 P0("제목이 다크-온-다크로 안 보임") 유형 회귀를 잡습니다. 백엔드 없이 실행됩니다.
- `auth-api.spec.js` — 회원가입→로그인→현재 사용자 조회→로그아웃을 API로 검증. `E2E_FULL_STACK=true`일 때만 실행. UI 셀렉터에 의존하지 않아 견고합니다.
- `protected-pages.spec.js` — 로그인 후 `/onboarding · /upload · /results · /account · /status`를 순회하며 (1) 로그인으로 튕기지 않는지, (2) 제목 명암비, (3) 콘솔 오류를 감사. `E2E_FULL_STACK=true`이고 **앱과 `/api`가 같은 출처**(운영 nginx 구성)일 때만 실행됩니다(쿠키 인증 때문). 관리자 페이지는 관리자 계정이 필요해 별도 확장 대상입니다.
- `analysis-pipeline.spec.js` — 실제 영상 업로드→DB 큐→analysis-worker→analysis-engine→완료 상태→golden 총점 drift→결과/영상 토큰 조회를 검증하고 테스트 데이터를 정리합니다. `E2E_ANALYSIS_PIPELINE=true`일 때만 실행됩니다.

## CI 연동 (권장)

`verify.yml`의 frontend job은 공개 페이지 E2E를, `analysis-pipeline-e2e` job은 실제 샘플 영상
golden E2E를 실행합니다. 단위 테스트 coverage와 Python/backend OpenAPI·coverage 산출물도 CI에
업로드됩니다.

```yaml
      - run: npx playwright install --with-deps chromium
      - run: npm run test:e2e
```

전체 스택 E2E(auth-api)는 별도 `frontend-e2e-full-stack` job에서 실행됩니다.

## 커버리지 (단위 테스트)

```bash
npm run test:coverage      # @vitest/coverage-v8 필요, 리포트는 frontend/coverage/
```

백엔드는 `./gradlew test`가 끝나면 Jacoco 리포트가 `backend/build/reports/jacoco/test/html/index.html`에 생성됩니다.
두 Python 엔진은 `pytest` 실행만으로 70% 최소 기준을 적용하고 `htmlcov/`, `coverage.xml`을 생성합니다.
