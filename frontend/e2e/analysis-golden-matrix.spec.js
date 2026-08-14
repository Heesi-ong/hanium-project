import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { expect, test } from "@playwright/test";

const ENABLED = process.env.E2E_GOLDEN_MATRIX === "true";
const API_BASE_URL = process.env.API_BASE_URL || "http://localhost:8080";
const MAX_WAIT_MS = Number(process.env.E2E_ANALYSIS_MAX_WAIT_MS || 10 * 60 * 1_000);
const ORIGIN_HEADERS = {
    Origin: new URL(process.env.BASE_URL || "http://localhost:5173").origin,
};
const CURRENT_DIR = path.dirname(fileURLToPath(import.meta.url));
const TERMINAL_STATUSES = new Set(["COMPLETED", "FAILED", "CANCELLED", "DEAD_LETTER"]);

function unwrap(body) {
    return body?.data ?? body;
}

async function readBody(response) {
    const text = await response.text();
    try {
        return text ? JSON.parse(text) : null;
    } catch {
        return { rawText: text };
    }
}

async function waitForTerminalStatus(api, jobId) {
    const deadline = Date.now() + MAX_WAIT_MS;
    let lastStatus = null;

    while (Date.now() < deadline) {
        const response = await api.get(`${API_BASE_URL}/api/analysis/${jobId}/status`);
        const body = await readBody(response);
        expect(response.ok(), JSON.stringify(body)).toBeTruthy();
        lastStatus = unwrap(body);

        if (TERMINAL_STATUSES.has(lastStatus?.status)) {
            return lastStatus;
        }
        await new Promise((resolve) => setTimeout(resolve, 1_500));
    }

    throw new Error(`분석 완료 대기 시간 초과: jobId=${jobId}, last=${JSON.stringify(lastStatus)}`);
}

test.describe("analysis golden matrix (full stack)", () => {
    // MANIFEST는 이 describe 안에서만, ENABLED일 때만 읽습니다. 모듈 최상단에서 무조건
    // 읽으면 E2E_GOLDEN_MATRIX가 꺼져 있어도 fixture manifest가 없거나 파손됐을 때
    // 이 파일과 무관한 다른 e2e spec까지 Playwright collection 단계에서 전부 깨뜨립니다
    // (analysis-pipeline.spec.js에서 실제로 겪은 문제와 동일한 패턴, 2026-08-13 수정).
    if (!ENABLED) {
        test.skip(true, "E2E_GOLDEN_MATRIX=true 와 생성된 fixture, 전체 분석 스택이 필요합니다.");
        return;
    }

    const MANIFEST = JSON.parse(fs.readFileSync(
        path.resolve(CURRENT_DIR, "fixtures/analysis-golden-matrix-v1.json"),
        "utf8"
    ));

    test.describe.configure({ mode: "serial" });

    for (const fixture of MANIFEST.cases) {
        test(`${fixture.id} score stays within golden tolerance`, async ({ page }) => {
            test.setTimeout(MAX_WAIT_MS + 2 * 60 * 1_000);
            const videoPath = path.resolve(CURRENT_DIR, fixture.relativePath);
            expect(fs.existsSync(videoPath), `golden 영상이 없습니다: ${videoPath}`).toBeTruthy();

            const email = `e2e-golden-${fixture.id}+${Date.now()}@example.com`;
            const password = "E2eGolden!2026aB";
            const api = page.request;
            let authenticated = false;
            let jobId = null;
            let terminalStatus = null;

            try {
                const signup = await api.post(`${API_BASE_URL}/api/auth/signup`, {
                    data: { email, password, agreedToTerms: true },
                });
                expect(signup.ok(), JSON.stringify(await readBody(signup))).toBeTruthy();
                const login = await api.post(`${API_BASE_URL}/api/auth/login`, {
                    data: { email, password },
                });
                expect(login.ok(), JSON.stringify(await readBody(login))).toBeTruthy();
                authenticated = true;

                const upload = await api.post(`${API_BASE_URL}/api/analysis/upload`, {
                    headers: ORIGIN_HEADERS,
                    multipart: {
                        file: {
                            name: fixture.videoFile,
                            mimeType: "video/mp4",
                            buffer: fs.readFileSync(videoPath),
                        },
                    },
                });
                const uploadBody = await readBody(upload);
                expect(upload.ok(), JSON.stringify(uploadBody)).toBeTruthy();
                jobId = unwrap(uploadBody)?.jobId;
                expect(jobId).toMatch(/^\d{14}-[a-z0-9]{8}$/);

                const run = await api.post(`${API_BASE_URL}/api/analysis/${jobId}/run`, {
                    headers: ORIGIN_HEADERS,
                    data: { useVideoLlm: false, useOpenAi: false },
                });
                expect(run.ok(), JSON.stringify(await readBody(run))).toBeTruthy();

                terminalStatus = await waitForTerminalStatus(api, jobId);
                expect(
                    terminalStatus.status,
                    terminalStatus.failReason || terminalStatus.statusDescription
                ).toBe("COMPLETED");

                const resultResponse = await api.get(`${API_BASE_URL}/api/results/${jobId}`);
                const resultBody = await readBody(resultResponse);
                expect(resultResponse.ok(), JSON.stringify(resultBody)).toBeTruthy();
                const result = unwrap(resultBody)?.result;
                const actualTotalScore = Number(result?.scoreSummary?.totalScore);
                expect(Number.isFinite(actualTotalScore)).toBe(true);
                expect(actualTotalScore).toBeGreaterThanOrEqual(0);
                expect(actualTotalScore).toBeLessThanOrEqual(100);

                const drift = Math.abs(actualTotalScore - fixture.expected.totalScore);
                console.info(
                    `[golden:${fixture.id}] expected=${fixture.expected.totalScore}, ` +
                    `actual=${actualTotalScore}, drift=${drift}, ` +
                    `tolerance=${fixture.tolerance.totalScore}`
                );
                expect(drift).toBeLessThanOrEqual(fixture.tolerance.totalScore);

                expect(result?.scoreExplanation?.formulaVersion).toBe("weighted-v1");
                expect(result?.scoreExplanation?.roundingPolicy).toBe("truncate_toward_zero");
                expect(Number(result?.scoreExplanation?.rawScore)).toBeGreaterThanOrEqual(0);
                expect(Number(result?.scoreExplanation?.penaltyApplied)).toBeGreaterThanOrEqual(0);
            } finally {
                if (authenticated && jobId && !TERMINAL_STATUSES.has(terminalStatus?.status)) {
                    await api.post(`${API_BASE_URL}/api/analysis/${jobId}/cancel`, {
                        headers: ORIGIN_HEADERS,
                    }).catch(() => null);
                }
                if (authenticated) {
                    await api.delete(`${API_BASE_URL}/api/users/me`, {
                        headers: ORIGIN_HEADERS,
                        data: { password },
                    }).catch(() => null);
                }
            }
        });
    }
});
