import { afterEach, describe, expect, it } from "vitest";

import apiClient from "./apiClient";

afterEach(() => {
    localStorage.clear();
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
        expect(requestConfig.withCredentials).toBe(true);
        expect(requestConfig.headers.Authorization).toBeUndefined();
    });
});
