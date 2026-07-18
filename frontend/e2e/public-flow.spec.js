import { expect, test } from "@playwright/test";

// 공개(로그인 불필요) 페이지의 핵심 흐름을 검증합니다.
// 백엔드 없이 프론트 dev 서버만으로 실행 가능하며, 라우팅/렌더 회귀와
// "제목이 배경과 대비가 없어 안 보이는" 유형(2026-07-16 P0 버그)을 잡습니다.

// 두 sRGB 색의 WCAG 명암비를 계산합니다(1~21). 4.5 이상이면 일반 텍스트 AA 통과.
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

test.describe("public pages", () => {
    test("landing renders with a visible, readable hero heading", async ({ page }) => {
        await page.goto("/");

        const heading = page.getByRole("heading", { level: 1 }).first();
        await expect(heading).toBeVisible();

        // 히어로 제목의 실제 글자색과 유효 배경색을 읽어 명암비를 계산합니다.
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
        expect(fg, `hero color parsed: ${color}`).not.toBeNull();
        expect(bg, `hero background parsed: ${background}`).not.toBeNull();

        // 4.5:1 미만이면 사실상 안 보이는 대비 → 회귀로 간주하고 실패.
        expect(contrastRatio(fg, bg)).toBeGreaterThanOrEqual(4.5);
    });

    test("primary navigation and footer links route correctly", async ({ page }) => {
        await page.goto("/");

        await page.getByRole("link", { name: "요금제" }).first().click();
        await expect(page).toHaveURL(/\/pricing$/);

        await page.getByRole("link", { name: "개인정보처리방침" }).first().click();
        await expect(page).toHaveURL(/\/privacy$/);

        await page.goto("/terms");
        await expect(page.getByRole("heading", { level: 1 }).first()).toBeVisible();
    });

    test("login and signup pages render their forms", async ({ page }) => {
        await page.goto("/login");
        await expect(page.getByRole("button", { name: /로그인/ }).first()).toBeVisible();

        await page.goto("/signup");
        await expect(page.getByRole("button", { name: /가입|시작/ }).first()).toBeVisible();
    });

    test("unknown route shows the not-found page", async ({ page }) => {
        await page.goto("/this-route-does-not-exist");
        await expect(page.getByText(/찾을 수 없|not found|404/i).first()).toBeVisible();
    });

    test("no uncaught console errors on the landing page", async ({ page }) => {
        const errors = [];
        page.on("console", (msg) => {
            if (msg.type() !== "error") return;

            // 로그인하지 않은 방문자가 랜딩 페이지를 열면 AuthContext가 세션 복구를
            // 시도하며 GET /api/auth/me를 호출합니다. 로그인 전이므로 401이 정상
            // 응답이고(skipAuthRedirect로 애플리케이션 코드가 이미 조용히 처리),
            // 브라우저가 실패한 리소스 로드를 자동으로 콘솔에 남기는 것뿐이라
            // 실제 애플리케이션 오류가 아닙니다. 이 항목만 걸러내고 그 외 오류는
            // 그대로 실패시킵니다(백엔드가 없는 격리 모드에서는 애초에 401 자체가
            // 발생하지 않아 이 필터가 아무 영향도 주지 않습니다).
            if (/status of 401/.test(msg.text())) return;

            errors.push(msg.text());
        });
        await page.goto("/");
        await page.waitForLoadState("networkidle");
        expect(errors, errors.join("\n")).toHaveLength(0);
    });
});
