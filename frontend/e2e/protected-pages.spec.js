import { expect, test } from "@playwright/test";

// 로그인 필요한 보호 페이지의 자동 감사(E4).
// 각 페이지가 (1) 로그인으로 튕기지 않고 렌더되는지, (2) 제목이 배경과 충분한 대비를
// 갖는지(P0 회귀 방지), (3) 콘솔 오류가 없는지를 확인합니다.
//
// 백엔드/DB/엔진이 함께 떠 있어야 하고, **앱과 /api가 같은 출처**(운영 nginx 구성)여야
// 로그인 쿠키가 페이지 요청에 붙습니다. 실행:
//   E2E_FULL_STACK=true BASE_URL=https://<배포도메인> npm run test:e2e
const FULL_STACK = process.env.E2E_FULL_STACK === "true";

const USER_ROUTES = ["/onboarding", "/upload", "/results", "/account", "/status"];

function contrastRatio(rgb1, rgb2) {
    const luminance = ([r, g, b]) => {
        const channel = (c) => {
            const s = c / 255;
            return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        };
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
    };
    const l1 = luminance(rgb1);
    const l2 = luminance(rgb2);
    const [light, dark] = l1 > l2 ? [l1, l2] : [l2, l1];
    return (light + 0.05) / (dark + 0.05);
}

function parseRgb(cssColor) {
    const match = cssColor.match(/rgba?\(([^)]+)\)/);
    if (!match) return null;
    return match[1].split(",").slice(0, 3).map((n) => parseFloat(n.trim()));
}

test.describe("protected pages audit (full stack)", () => {
    test.skip(!FULL_STACK, "E2E_FULL_STACK=true 와 같은 출처로 서빙되는 전체 스택이 필요합니다.");

    // 신규 사용자를 만들어 로그인합니다(브라우저 컨텍스트에 쿠키가 저장됨).
    test.beforeEach(async ({ page }) => {
        const email = `e2e-audit+${Date.now()}@example.com`;
        const password = "E2eAudit!2026aB";
        await page.request.post("/api/auth/signup", {
            data: { email, password, agreedToTerms: true },
        });
        const login = await page.request.post("/api/auth/login", {
            data: { email, password },
        });
        expect(login.ok(), await login.text()).toBeTruthy();
    });

    for (const route of USER_ROUTES) {
        test(`renders ${route} without redirect, with readable heading and no console errors`, async ({
            page,
        }) => {
            const errors = [];
            page.on("console", (msg) => {
                if (msg.type() === "error") errors.push(msg.text());
            });

            await page.goto(route);
            await page.waitForLoadState("networkidle");

            // 로그인으로 튕기지 않아야 함(인증 쿠키가 유효).
            expect(page.url()).not.toContain("/login");

            const heading = page.getByRole("heading").first();
            await expect(heading).toBeVisible();

            const { color, background } = await heading.evaluate((el) => {
                const cs = window.getComputedStyle(el);
                let node = el;
                let bg = "rgba(0, 0, 0, 0)";
                while (node) {
                    const b = window.getComputedStyle(node).backgroundColor;
                    if (b && b !== "rgba(0, 0, 0, 0)" && b !== "transparent") {
                        bg = b;
                        break;
                    }
                    node = node.parentElement;
                }
                return { color: cs.color, background: bg };
            });

            const fg = parseRgb(color);
            const bg = parseRgb(background);
            if (fg && bg) {
                expect(
                    contrastRatio(fg, bg),
                    `${route} heading contrast (${color} on ${background})`
                ).toBeGreaterThanOrEqual(4.5);
            }

            expect(errors, `${route} console errors:\n${errors.join("\n")}`).toHaveLength(0);
        });
    }
});
