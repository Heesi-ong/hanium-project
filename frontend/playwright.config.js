import { defineConfig, devices } from "@playwright/test";

// 프론트엔드 E2E 설정.
// - 기본은 로컬 dev 서버(`npm run dev`, 5173)를 자동 기동해 공개 페이지 흐름을 검증합니다.
//   (공개 페이지는 백엔드 없이도 렌더/라우팅 회귀를 잡을 수 있습니다.)
// - 업로드→분석→결과 같은 로그인 필요 흐름은 백엔드/엔진/DB가 함께 떠 있어야 하며,
//   BASE_URL과 E2E_FULL_STACK=true 를 주면 auth-upload 스펙이 활성화됩니다.
const BASE_URL = process.env.BASE_URL || "http://127.0.0.1:5173";
const REUSE_SERVER = !process.env.CI;

export default defineConfig({
    testDir: "./e2e",
    timeout: 30_000,
    expect: { timeout: 5_000 },
    fullyParallel: true,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 1 : 0,
    reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
    use: {
        baseURL: BASE_URL,
        trace: "on-first-retry",
        screenshot: "only-on-failure",
    },
    projects: [
        { name: "chromium", use: { ...devices["Desktop Chrome"] } },
    ],
    // BASE_URL을 외부(이미 떠 있는 서버)로 지정하면 webServer를 띄우지 않습니다.
    webServer: process.env.BASE_URL
        ? undefined
        : {
              command: "npm run dev -- --host 127.0.0.1",
              url: "http://127.0.0.1:5173",
              reuseExistingServer: REUSE_SERVER,
              timeout: 60_000,
          },
});
