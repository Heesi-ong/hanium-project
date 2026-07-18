import { afterEach, describe, expect, it } from "vitest";

import apiClient, { DEFAULT_API_TIMEOUT_MS } from "./apiClient";

afterEach(() => {
    localStorage.clear();
    apiClient.defaults.adapter = undefined;
});

describe("apiClient", () => {
    it("sends cookies with API requests without attaching bearer tokens", async () => {
        localStorage.setItem(
            "presentationCoachAuth",
            JSON.stringify({ accessToken: "stored-token" })
        );

        let requestConfig;
        await apiClient.get("/api/health", {
            adapter: (config) => {
                requestConfig = config;
                return Promise.resolve({
                    data: { success: true },
                    status: 200,
                    statusText: "OK",
                    headers: {},
                    config,
                    request: {},
                });
            },
        });

        expect(apiClient.defaults.withCredentials).toBe(true);
        expect(apiClient.defaults.timeout).toBe(DEFAULT_API_TIMEOUT_MS);
        expect(requestConfig.withCredentials).toBe(true);
        expect(requestConfig.headers.Authorization).toBeUndefined();
    });

    it("normalizes network errors without pretending they are server 500 responses", async () => {
        await expect(apiClient.get("/api/health", {
            adapter: (config) => Promise.reject({
                config,
                request: {},
                message: "Network Error",
            }),
        })).rejects.toMatchObject({
            success: false,
            status: 0,
            error: "NETWORK_ERROR",
            message: "서버와 통신할 수 없습니다. 네트워크 연결을 확인해주세요.",
        });
    });

    it("normalizes timeout errors with a client-side status", async () => {
        await expect(apiClient.get("/api/health", {
            adapter: (config) => Promise.reject({
                config,
                code: "ECONNABORTED",
                message: "timeout",
            }),
        })).rejects.toMatchObject({
            success: false,
            status: 0,
            error: "REQUEST_TIMEOUT",
            message: "요청 시간이 초과되었습니다. 네트워크 상태를 확인한 뒤 다시 시도해주세요.",
        });
    });

    it("normalizes non-json server errors instead of rejecting raw response text", async () => {
        await expect(apiClient.get("/api/health", {
            adapter: (config) => Promise.reject({
                config,
                response: {
                    data: "<html>bad gateway</html>",
                    status: 502,
                    statusText: "Bad Gateway",
                    headers: {},
                    config,
                    request: {},
                },
            }),
        })).rejects.toMatchObject({
            success: false,
            status: 502,
            error: "HTTP_ERROR",
            message: "서버 요청을 처리하는 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
        });
    });
});
