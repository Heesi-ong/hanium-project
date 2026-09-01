import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { expect, test } from "@playwright/test";

const ENABLED = process.env.E2E_FAILURE_RECOVERY === "true";
const API_BASE_URL = process.env.API_BASE_URL || "http://localhost:8080";
const MAX_WAIT_MS = Number(process.env.E2E_ANALYSIS_MAX_WAIT_MS || 10 * 60 * 1_000);
const CURRENT_DIR = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ROOT = path.resolve(CURRENT_DIR, "../..");
const VIDEO_PATH = path.resolve(PROJECT_ROOT, "sample-demo.mp4");
const ORIGIN_HEADERS = {
    Origin: new URL(process.env.BASE_URL || "http://localhost:5173").origin,
};
const TERMINAL_STATUSES = new Set(["COMPLETED", "FAILED", "CANCELLED", "DEAD_LETTER"]);
// backend의 analysis-engine Circuit Breaker 기본 open 대기(30초)와 JVM DNS
// 음수 캐시를 모두 지난 뒤 복구 호출을 허용한다.
const RECOVERY_SETTLE_MS = Number(process.env.E2E_RECOVERY_SETTLE_MS || 35_000);

function sleep(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

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

function compose(...args) {
    execFileSync("docker", ["compose", ...args], {
        cwd: PROJECT_ROOT,
        env: process.env,
        stdio: "inherit",
        timeout: 15 * 60 * 1_000,
    });
}

async function ensureAnalysisEngineReady() {
    // `up`은 최초 스택 기동 때 주입된 환경변수가 현재 셸과 다르면 컨테이너를
    // 재생성할 수 있다. 장애 복구 테스트에서는 기존 컨테이너를 start해 설정을 보존한다.
    compose("start", "analysis-engine");
    const containerId = execFileSync(
        "docker",
        ["compose", "ps", "-q", "analysis-engine"],
        { cwd: PROJECT_ROOT, env: process.env, encoding: "utf8" }
    ).trim();
    expect(containerId, "analysis-engine 컨테이너 ID를 찾지 못했습니다.").toBeTruthy();

    const deadline = Date.now() + 15 * 60 * 1_000;
    while (Date.now() < deadline) {
        const health = execFileSync(
            "docker",
            ["inspect", "--format", "{{.State.Health.Status}}", containerId],
            { cwd: PROJECT_ROOT, env: process.env, encoding: "utf8" }
        ).trim();
        if (health === "healthy") {
            return;
        }
        if (health === "unhealthy") {
            throw new Error("analysis-engine이 unhealthy 상태로 복구되지 않았습니다.");
        }
        await sleep(1_000);
    }
    throw new Error("analysis-engine 헬스체크 복구 대기 시간이 초과되었습니다.");
}

async function waitForStatus(
    api,
    jobId,
    acceptedStatuses,
    { temporarilyIgnoredStatuses = new Set(), ignoredStatusGraceMs = 0 } = {}
) {
    const startedAt = Date.now();
    const deadline = Date.now() + MAX_WAIT_MS;
    let lastStatus = null;

    while (Date.now() < deadline) {
        const response = await api.get(`${API_BASE_URL}/api/analysis/${jobId}/status`);
        const body = await readBody(response);
        expect(response.ok(), JSON.stringify(body)).toBeTruthy();
        lastStatus = unwrap(body);
        if (acceptedStatuses.has(lastStatus?.status)) {
            return lastStatus;
        }
        if (
            temporarilyIgnoredStatuses.has(lastStatus?.status)
            && Date.now() - startedAt < ignoredStatusGraceMs
        ) {
            await sleep(1_000);
            continue;
        }
        if (TERMINAL_STATUSES.has(lastStatus?.status)) {
            throw new Error(
                `예상하지 않은 종료 상태: expected=${[...acceptedStatuses]}, actual=${JSON.stringify(lastStatus)}`
            );
        }
        await sleep(1_000);
    }

    throw new Error(`상태 대기 시간 초과: jobId=${jobId}, last=${JSON.stringify(lastStatus)}`);
}

test.describe("analysis failure and recovery (full stack)", () => {
    test.skip(!ENABLED, "E2E_FAILURE_RECOVERY=true 와 Docker Compose 전체 스택이 필요합니다.");

    test("rejects corrupt upload, exposes engine failure, and completes retry", async ({ page }) => {
        test.setTimeout(MAX_WAIT_MS * 2 + 5 * 60 * 1_000);
        expect(fs.existsSync(VIDEO_PATH), `기준 영상이 없습니다: ${VIDEO_PATH}`).toBeTruthy();

        const email = `e2e-failure-recovery+${Date.now()}@example.com`;
        const password = "E2eRecovery!2026aB";
        const api = page.request;
        let authenticated = false;
        let engineStopped = false;
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

            const corruptUpload = await api.post(`${API_BASE_URL}/api/analysis/upload`, {
                headers: ORIGIN_HEADERS,
                multipart: {
                    file: {
                        name: "corrupt-presentation.mp4",
                        mimeType: "video/mp4",
                        buffer: Buffer.from("not-a-real-mp4"),
                    },
                },
            });
            expect(corruptUpload.status()).toBeGreaterThanOrEqual(400);
            expect(corruptUpload.status()).toBeLessThan(500);

            const upload = await api.post(`${API_BASE_URL}/api/analysis/upload`, {
                headers: ORIGIN_HEADERS,
                multipart: {
                    file: {
                        name: "failure-recovery-sample.mp4",
                        mimeType: "video/mp4",
                        buffer: fs.readFileSync(VIDEO_PATH),
                    },
                },
            });
            const uploadBody = await readBody(upload);
            expect(upload.ok(), JSON.stringify(uploadBody)).toBeTruthy();
            jobId = unwrap(uploadBody)?.jobId;
            expect(jobId).toMatch(/^\d{14}-[a-z0-9]{8}$/);

            compose("stop", "analysis-engine");
            engineStopped = true;

            const run = await api.post(`${API_BASE_URL}/api/analysis/${jobId}/run`, {
                headers: ORIGIN_HEADERS,
                data: { useVideoLlm: false, useOpenAi: false },
            });
            expect(run.ok(), JSON.stringify(await readBody(run))).toBeTruthy();

            terminalStatus = await waitForStatus(api, jobId, new Set(["FAILED"]));
            expect(terminalStatus.failReason).toBeTruthy();
            console.info(`[recovery] initial failure: ${terminalStatus.failReason}`);

            await ensureAnalysisEngineReady();
            engineStopped = false;
            // 엔진 헬스체크 통과만으로는 워커의 Circuit Breaker와 JVM DNS 캐시가
            // 복구됐다고 볼 수 없으므로 둘의 기본 대기 시간을 모두 지난다.
            await sleep(RECOVERY_SETTLE_MS);

            const retry = await api.post(`${API_BASE_URL}/api/analysis/${jobId}/retry`, {
                headers: ORIGIN_HEADERS,
            });
            const retryBody = await readBody(retry);
            expect(retry.ok(), JSON.stringify(retryBody)).toBeTruthy();
            expect(["QUEUED", "BASIC_ANALYZING"]).toContain(unwrap(retryBody)?.status);

            terminalStatus = await waitForStatus(
                api,
                jobId,
                new Set(["COMPLETED"]),
                {
                    temporarilyIgnoredStatuses: new Set(["FAILED"]),
                    ignoredStatusGraceMs: 30_000,
                }
            );

            const resultResponse = await api.get(`${API_BASE_URL}/api/results/${jobId}`);
            const resultBody = await readBody(resultResponse);
            expect(resultResponse.ok(), JSON.stringify(resultBody)).toBeTruthy();
            const result = unwrap(resultBody)?.result;
            expect(result?.status).toBe("COMPLETED");
            expect(Number(result?.scoreSummary?.totalScore)).toBeGreaterThanOrEqual(0);
            expect(result?.scoreExplanation?.formulaVersion).toBe("weighted-v2");
        } finally {
            if (engineStopped) {
                try {
                    await ensureAnalysisEngineReady();
                } catch (error) {
                    console.error("analysis-engine 복구에 실패했습니다.", error);
                }
            }
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
});
