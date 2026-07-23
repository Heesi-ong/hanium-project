import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { expect, request, test } from "@playwright/test";

// 실제 backend, analysis-worker, analysis-engine, DB/Redis/MinIO가 함께 떠 있을 때
// 업로드부터 결과 조회까지의 비동기 분석 계약을 검증합니다. 외부 유료 API와 네트워크
// 품질에 테스트가 좌우되지 않도록 Video LLM/OpenAI는 끄되, 정량 분석 엔진과 워커는
// 실제 구현을 사용합니다.
const ENABLED = process.env.E2E_ANALYSIS_PIPELINE === "true";
const API_BASE_URL = process.env.API_BASE_URL || "http://localhost:8080";
const POLL_INTERVAL_MS = Number(process.env.E2E_ANALYSIS_POLL_INTERVAL_MS || 2_000);
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

async function waitForTerminalStatus(api, jobId) {
    const startedAt = Date.now();
    let latestStatus = null;

    while (Date.now() - startedAt < MAX_WAIT_MS) {
        const response = await api.get(`/api/analysis/${jobId}/status`);
        const body = await responseBody(response);
        expect(response.ok(), JSON.stringify(body)).toBeTruthy();

        latestStatus = unwrap(body);
        if (TERMINAL_STATUSES.has(latestStatus?.status)) {
            return latestStatus;
        }

        await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
    }

    throw new Error(
        `분석이 제한 시간 안에 종료되지 않았습니다. jobId=${jobId} `
        + `latestStatus=${JSON.stringify(latestStatus)}`
    );
}

test.describe("analysis pipeline (full stack)", () => {
    test.skip(!ENABLED, "E2E_ANALYSIS_PIPELINE=true 와 실행 중인 전체 분석 스택이 필요합니다.");
    test.setTimeout(MAX_WAIT_MS + 2 * 60 * 1_000);

    test("upload, queue, analyze, read result and clean up", async () => {
        expect(fs.existsSync(VIDEO_PATH), `E2E 영상 파일이 없습니다: ${VIDEO_PATH}`).toBeTruthy();

        const api = await request.newContext({ baseURL: API_BASE_URL });
        const email = `e2e-analysis+${Date.now()}@example.com`;
        const password = "E2eAnalysis!2026aB";
        let authenticated = false;
        let jobId = null;
        let terminalStatus = null;

        try {
            const signup = await api.post("/api/auth/signup", {
                data: { email, password, agreedToTerms: true },
            });
            expect(signup.ok(), JSON.stringify(await responseBody(signup))).toBeTruthy();

            const login = await api.post("/api/auth/login", {
                data: { email, password },
            });
            expect(login.ok(), JSON.stringify(await responseBody(login))).toBeTruthy();
            authenticated = true;

            const upload = await api.post("/api/analysis/upload", {
                multipart: {
                    file: {
                        name: path.basename(VIDEO_PATH),
                        mimeType: "video/mp4",
                        buffer: fs.readFileSync(VIDEO_PATH),
                    },
                },
                timeout: 2 * 60 * 1_000,
            });
            const uploadBody = await responseBody(upload);
            expect(upload.ok(), JSON.stringify(uploadBody)).toBeTruthy();

            const uploaded = unwrap(uploadBody);
            expect(uploaded?.status).toBe("UPLOADED");
            expect(uploaded?.jobId).toMatch(/^\d{14}-[a-z0-9]{8}$/);
            jobId = uploaded.jobId;

            const run = await api.post(`/api/analysis/${jobId}/run`, {
                data: { useVideoLlm: false, useOpenAi: false },
            });
            const runBody = await responseBody(run);
            expect(run.ok(), JSON.stringify(runBody)).toBeTruthy();
            expect(["QUEUED", "BASIC_ANALYZING"]).toContain(unwrap(runBody)?.status);

            terminalStatus = await waitForTerminalStatus(api, jobId);
            expect(
                terminalStatus.status,
                `분석 실패: ${terminalStatus.failReason || terminalStatus.statusDescription}`
            ).toBe("COMPLETED");

            const resultResponse = await api.get(`/api/results/${jobId}`);
            const resultBody = await responseBody(resultResponse);
            expect(resultResponse.ok(), JSON.stringify(resultBody)).toBeTruthy();

            const resultData = unwrap(resultBody);
            expect(resultData?.jobId).toBe(jobId);
            expect(resultData?.result).toBeTruthy();
            expect(Object.keys(resultData.result).length).toBeGreaterThan(0);

            const tokenResponse = await api.post(`/api/results/${jobId}/video-access-token`);
            const tokenBody = await responseBody(tokenResponse);
            expect(tokenResponse.ok(), JSON.stringify(tokenBody)).toBeTruthy();
            expect(unwrap(tokenBody)?.token).toBeTruthy();
        } finally {
            if (authenticated && jobId && !TERMINAL_STATUSES.has(terminalStatus?.status)) {
                await api.post(`/api/analysis/${jobId}/cancel`).catch(() => null);
            }

            if (authenticated) {
                await api.delete("/api/users/me", { data: { password } }).catch(() => null);
            }

            await api.dispose();
        }
    });
});
