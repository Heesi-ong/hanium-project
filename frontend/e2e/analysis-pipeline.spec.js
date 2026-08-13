import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { expect, test } from "@playwright/test";

// 실제 backend, analysis-worker, analysis-engine, DB/Redis/MinIO가 함께 떠 있을 때
// 업로드부터 결과 조회까지의 비동기 분석 계약을 검증합니다. 외부 유료 API와 네트워크
// 품질에 테스트가 좌우되지 않도록 Video LLM/OpenAI는 끄되, 정량 분석 엔진과 워커는
// 실제 구현을 사용합니다.
const ENABLED = process.env.E2E_ANALYSIS_PIPELINE === "true";
const API_BASE_URL = process.env.API_BASE_URL || "http://localhost:8080";
const BROWSER_ORIGIN_HEADERS = {
    Origin: new URL(process.env.BASE_URL || "http://127.0.0.1:5173").origin,
};
const MAX_WAIT_MS = Number(process.env.E2E_ANALYSIS_MAX_WAIT_MS || 10 * 60 * 1_000);

const CURRENT_DIR = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_VIDEO_PATH = path.resolve(CURRENT_DIR, "../../sample-demo.mp4");
const VIDEO_PATH = path.resolve(process.env.E2E_VIDEO_PATH || DEFAULT_VIDEO_PATH);
const TERMINAL_STATUSES = new Set(["COMPLETED", "FAILED", "CANCELLED", "DEAD_LETTER"]);

function unwrap(body) {
    return body?.data ?? body;
}

async function responseBody(response) {
    const text = await response.text();
    if (!text) return null;

    try {
        return JSON.parse(text);
    } catch {
        return { rawText: text };
    }
}

test.describe("analysis pipeline (full stack)", () => {
    test.skip(!ENABLED, "E2E_ANALYSIS_PIPELINE=true 와 실행 중인 전체 분석 스택이 필요합니다.");
    test.setTimeout(MAX_WAIT_MS + 2 * 60 * 1_000);

    test("single CTA survives refresh, completes analysis and cleans up", async ({ page }) => {
        expect(fs.existsSync(VIDEO_PATH), `E2E 영상 파일이 없습니다: ${VIDEO_PATH}`).toBeTruthy();

        const email = `e2e-analysis+${Date.now()}@example.com`;
        const password = "E2eAnalysis!2026aB";
        let authenticated = false;
        let jobId = null;
        let terminalStatus = null;

        try {
            // page.request는 브라우저 context와 쿠키 저장소를 공유합니다. API로 준비한
            // 로그인 세션이 실제 /upload 페이지에도 그대로 적용되어야 합니다.
            const api = page.request;
            const signup = await api.post(`${API_BASE_URL}/api/auth/signup`, {
                data: { email, password, agreedToTerms: true },
            });
            expect(signup.ok(), JSON.stringify(await responseBody(signup))).toBeTruthy();

            const login = await api.post(`${API_BASE_URL}/api/auth/login`, {
                data: { email, password },
            });
            expect(login.ok(), JSON.stringify(await responseBody(login))).toBeTruthy();
            authenticated = true;

            await page.goto("/upload");
            await expect(page).toHaveURL(/\/upload$/);

            // E2E가 실제 외부 API 과금이나 네트워크에 의존하지 않도록 서비스 상태가
            // 두 옵션을 미사용으로 내렸는지 UI에서 확인한 뒤 진행합니다.
            await expect(page.getByRole("heading", { name: /고급 분석 옵션/ }))
                .toContainText("Video LLM 미사용");
            await expect(page.getByRole("heading", { name: /고급 분석 옵션/ }))
                .toContainText("AI 피드백 미사용");

            await page.locator('input[type="file"]').setInputFiles(VIDEO_PATH);

            const uploadResponsePromise = page.waitForResponse((response) =>
                response.url().endsWith("/api/analysis/upload") &&
                response.request().method() === "POST"
            );
            const runResponsePromise = page.waitForResponse((response) =>
                /\/api\/analysis\/\d{14}-[a-z0-9]{8}\/run$/.test(response.url()) &&
                response.request().method() === "POST"
            );

            await page.getByRole("button", {
                name: "업로드하고 분석 시작",
            }).click();

            const upload = await uploadResponsePromise;
            const uploadBody = await responseBody(upload);
            expect(upload.ok(), JSON.stringify(uploadBody)).toBeTruthy();

            const uploaded = unwrap(uploadBody);
            expect(uploaded?.status).toBe("UPLOADED");
            expect(uploaded?.jobId).toMatch(/^\d{14}-[a-z0-9]{8}$/);
            jobId = uploaded.jobId;

            const run = await runResponsePromise;
            const runBody = await responseBody(run);
            expect(run.ok(), JSON.stringify(runBody)).toBeTruthy();
            expect(["QUEUED", "BASIC_ANALYZING"]).toContain(unwrap(runBody)?.status);

            const storedJobId = await page.evaluate(() => {
                const rawValue = localStorage.getItem("presentationCoachActiveAnalysis");
                return rawValue ? JSON.parse(rawValue).jobId : null;
            });
            expect(storedJobId).toBe(jobId);

            // 실제 사용자 새로고침을 수행하고 로컬 식별자 → 소유권 적용 상태 API →
            // 자동 polling이 이어지는지 UI 표식과 진행률로 확인합니다. 첫 상태 조회는
            // 의도적으로 503을 한 번 주입해 일시 장애 뒤 자동 재시도까지 검증합니다.
            let pageStatusRequestCount = 0;
            await page.route(`**/api/analysis/${jobId}/status`, async (route) => {
                pageStatusRequestCount += 1;

                if (pageStatusRequestCount === 1) {
                    await route.fulfill({
                        status: 503,
                        contentType: "application/json",
                        headers: {
                            "access-control-allow-origin": "http://localhost:5173",
                            "access-control-allow-credentials": "true",
                        },
                        body: JSON.stringify({
                            success: false,
                            status: 503,
                            error: "SERVICE_UNAVAILABLE",
                            message: "E2E가 주입한 일시적 상태 조회 실패입니다.",
                        }),
                    });
                    return;
                }

                await route.continue();
            });

            await page.reload();
            await expect(page.getByText("이전에 업로드한 영상")).toBeVisible();
            await expect(page.getByRole("progressbar", { name: "분석 진행률" }))
                .toBeVisible();
            await expect(page.getByText("분석 상태를 자동으로 확인하는 중입니다."))
                .toBeVisible();
            await expect.poll(() => pageStatusRequestCount).toBeGreaterThanOrEqual(2);

            // 별도 API polling을 추가하면 실제 브라우저의 status+progress polling과 합쳐져
            // 사용자 rate limit 사용량을 왜곡합니다. UI가 스스로 완료를 감지해 이동하는지를
            // 기다린 뒤 최종 상태는 한 번만 조회합니다.
            await expect(page).toHaveURL(
                new RegExp(`/results/${jobId}$`),
                { timeout: MAX_WAIT_MS }
            );

            const statusResponse = await api.get(
                `${API_BASE_URL}/api/analysis/${jobId}/status`
            );
            const statusBody = await responseBody(statusResponse);
            expect(statusResponse.ok(), JSON.stringify(statusBody)).toBeTruthy();
            terminalStatus = unwrap(statusBody);
            expect(
                terminalStatus.status,
                `분석 실패: ${terminalStatus.failReason || terminalStatus.statusDescription}`
            ).toBe("COMPLETED");

            const resultResponse = await api.get(`${API_BASE_URL}/api/results/${jobId}`);
            const resultBody = await responseBody(resultResponse);
            expect(resultResponse.ok(), JSON.stringify(resultBody)).toBeTruthy();

            const resultData = unwrap(resultBody);
            expect(resultData?.jobId).toBe(jobId);
            expect(resultData?.result).toBeTruthy();
            expect(Object.keys(resultData.result).length).toBeGreaterThan(0);
            expect(resultData.result?.feedback?.generationMode).toBe("SKIPPED");
            expect(resultData.result?.feedback?.realApiUsed).toBe(false);
            expect(resultData.result?.pipeline?.openAiGenerationMode).toBe("SKIPPED");

            const tokenResponse = await api.post(
                `${API_BASE_URL}/api/results/${jobId}/video-access-token`,
                { headers: BROWSER_ORIGIN_HEADERS }
            );
            const tokenBody = await responseBody(tokenResponse);
            expect(tokenResponse.ok(), JSON.stringify(tokenBody)).toBeTruthy();
            expect(unwrap(tokenBody)?.token).toBeTruthy();
        } finally {
            const api = page.request;
            if (authenticated && jobId && !TERMINAL_STATUSES.has(terminalStatus?.status)) {
                await api.post(`${API_BASE_URL}/api/analysis/${jobId}/cancel`, {
                    headers: BROWSER_ORIGIN_HEADERS,
                }).catch(() => null);
            }

            if (authenticated) {
                await api.delete(`${API_BASE_URL}/api/users/me`, {
                    headers: BROWSER_ORIGIN_HEADERS,
                    data: { password },
                }).catch(() => null);
            }
        }
    });
});
