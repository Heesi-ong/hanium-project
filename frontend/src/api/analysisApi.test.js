import { afterEach, describe, expect, it } from "vitest";

import apiClient from "./apiClient";
import {
    ANALYSIS_COMMAND_TIMEOUT_MS,
    ANALYSIS_POLL_TIMEOUT_MS,
    UPLOAD_ANALYSIS_TIMEOUT_MS,
    cancelAnalysis,
    getAnalysisProgress,
    getAnalysisStatus,
    requestVideoLlmReanalysis,
    retryAnalysis,
    runAnalysis,
    uploadAnalysisVideo,
} from "./analysisApi";

const originalAdapter = apiClient.defaults.adapter;

afterEach(() => {
    apiClient.defaults.adapter = originalAdapter;
});

function captureRequestConfig() {
    let requestConfig;

    apiClient.defaults.adapter = (config) => {
        requestConfig = config;

        return Promise.resolve({
            data: { success: true },
            status: 200,
            statusText: "OK",
            headers: {},
            config,
            request: {},
        });
    };

    return () => requestConfig;
}

describe("analysisApi", () => {
    it("keeps the long timeout only on video uploads", async () => {
        const getRequestConfig = captureRequestConfig();

        await uploadAnalysisVideo(new File(["video"], "presentation.mp4", {
            type: "video/mp4",
        }));

        expect(getRequestConfig().timeout).toBe(UPLOAD_ANALYSIS_TIMEOUT_MS);
    });

    it.each([
        ["runAnalysis", () => runAnalysis("20260718120000-aaaabbbb")],
        ["retryAnalysis", () => retryAnalysis("20260718120000-aaaabbbb")],
        ["cancelAnalysis", () => cancelAnalysis("20260718120000-aaaabbbb")],
    ])("uses a short command timeout for %s", async (_, action) => {
        const getRequestConfig = captureRequestConfig();

        await action();

        expect(getRequestConfig().timeout).toBe(ANALYSIS_COMMAND_TIMEOUT_MS);
    });

    it("sends an idempotency key for Video LLM reanalysis", async () => {
        const getRequestConfig = captureRequestConfig();

        await requestVideoLlmReanalysis("20260718120000-aaaabbbb", {
            useOpenAi: true,
            idempotencyKey: "video-llm-reanalysis:test-key-1234",
        });

        const requestConfig = getRequestConfig();
        expect(requestConfig.timeout).toBe(ANALYSIS_COMMAND_TIMEOUT_MS);
        expect(requestConfig.url)
            .toBe("/api/analysis/20260718120000-aaaabbbb/video-llm-reanalysis");
        expect(requestConfig.headers.get("Idempotency-Key"))
            .toBe("video-llm-reanalysis:test-key-1234");
        expect(JSON.parse(requestConfig.data)).toEqual({ useOpenAi: true });
    });

    it.each([
        ["getAnalysisStatus", () => getAnalysisStatus("20260718120000-aaaabbbb")],
        ["getAnalysisProgress", () => getAnalysisProgress("20260718120000-aaaabbbb")],
    ])("uses a short polling timeout for %s", async (_, action) => {
        const getRequestConfig = captureRequestConfig();

        await action();

        expect(getRequestConfig().timeout).toBe(ANALYSIS_POLL_TIMEOUT_MS);
    });
});
