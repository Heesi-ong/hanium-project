import { expect, request, test } from "@playwright/test";

// 인증 계약 E2E: 회원가입 → 로그인 → 현재 사용자 조회 → 로그아웃을 실제 백엔드에 대해
// 검증합니다. UI 셀렉터에 의존하지 않고 API를 직접 호출하므로 견고합니다.
//
// 백엔드/DB가 함께 떠 있어야 하므로 기본은 skip. 실행하려면:
//   E2E_FULL_STACK=true API_BASE_URL=http://localhost:8080 npm run test:e2e
const FULL_STACK = process.env.E2E_FULL_STACK === "true";
const API_BASE_URL = process.env.API_BASE_URL || "http://localhost:8080";
const UI_ORIGIN = new URL(process.env.BASE_URL || "http://127.0.0.1:5173").origin;

test.describe("auth contract (full stack)", () => {
    test.skip(!FULL_STACK, "E2E_FULL_STACK=true 와 실행 중인 백엔드가 필요합니다.");

    test("signup, login, whoami, logout round-trip", async () => {
        // 쿠키를 유지하는 요청 컨텍스트(httpOnly 세션 쿠키 기반 인증과 일치).
        const api = await request.newContext({
            baseURL: API_BASE_URL,
            extraHTTPHeaders: { Origin: UI_ORIGIN },
        });
        const email = `e2e+${Date.now()}@example.com`;
        const password = "E2eTest!2026aB";

        const signup = await api.post("/api/auth/signup", {
            data: { email, password, agreedToTerms: true },
        });
        expect(signup.status(), await signup.text()).toBeLessThan(300);

        const login = await api.post("/api/auth/login", {
            data: { email, password },
        });
        expect(login.ok(), await login.text()).toBeTruthy();

        // 로그인 쿠키가 유지되므로 /me가 인증된 사용자를 돌려줘야 합니다.
        const me = await api.get("/api/auth/me");
        expect(me.ok(), await me.text()).toBeTruthy();
        const meBody = await me.json();
        const meData = meBody.data ?? meBody;
        expect(JSON.stringify(meData)).toContain(email);

        const logout = await api.post("/api/auth/logout");
        expect(logout.ok()).toBeTruthy();

        const anonymousMe = await api.get("/api/auth/me");
        expect(anonymousMe.ok(), await anonymousMe.text()).toBeTruthy();
        const anonymousMeBody = await anonymousMe.json();
        expect(anonymousMeBody.data).toBeNull();

        await api.dispose();
    });
});
