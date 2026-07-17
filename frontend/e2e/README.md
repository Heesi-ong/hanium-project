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

## 스펙

- `public-flow.spec.js` — 랜딩/네비게이션/약관/404. **히어로 제목의 명암비를 실측**해 2026-07-16 P0("제목이 다크-온-다크로 안 보임") 유형 회귀를 잡습니다. 백엔드 없이 실행됩니다.
- `auth-api.spec.js` — 회원가입→로그인→현재 사용자 조회→로그아웃을 API로 검증. `E2E_FULL_STACK=true`일 때만 실행. UI 셀렉터에 의존하지 않아 견고합니다.
- `protected-pages.spec.js` — 로그인 후 `/onboarding · /upload · /results · /account · /status`를 순회하며 (1) 로그인으로 튕기지 않는지, (2) 제목 명암비, (3) 콘솔 오류를 감사. `E2E_FULL_STACK=true`이고 **앱과 `/api`가 같은 출처**(운영 nginx 구성)일 때만 실행됩니다(쿠키 인증 때문). 관리자 페이지는 관리자 계정이 필요해 별도 확장 대상입니다.

## CI 연동 (권장)

`verify.yml`의 frontend job에 아래 스텝을 추가하면 됩니다(현재 이 파일은 별도로 편집 중일 수 있어 여기서는 문서로만 남깁니다).

```yaml
      - run: npx playwright install --with-deps chromium
      - run: npm run test:e2e
```

전체 스택 E2E(auth-api)는 backend/analysis-engine/video-llm-engine/DB가 필요하므로, `docker compose up -d --wait` 후 별도 job에서 `E2E_FULL_STACK=true`로 실행하는 것을 권장합니다.

## 커버리지 (단위 테스트)

```bash
npm run test:coverage      # @vitest/coverage-v8 필요, 리포트는 frontend/coverage/
```

백엔드는 `./gradlew test`가 끝나면 Jacoco 리포트가 `backend/build/reports/jacoco/test/html/index.html`에 생성됩니다.
