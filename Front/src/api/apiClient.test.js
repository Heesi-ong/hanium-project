import { afterEach, describe, expect, it, vi } from "vitest";

import { API_UNAUTHORIZED_EVENT, apiRequest } from "./apiClient";

describe("apiRequest", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("401 응답을 전역 인증 만료 이벤트로 알린다", async () => {
    const listener = vi.fn();
    window.addEventListener(API_UNAUTHORIZED_EVENT, listener);
    vi.spyOn(window, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ detail: "로그인이 필요합니다." }), {
        status: 401,
        headers: { "content-type": "application/json" },
      }),
    );

    await expect(apiRequest("/api/auth/me")).rejects.toMatchObject({
      status: 401,
      message: "로그인이 필요합니다.",
    });

    expect(listener).toHaveBeenCalledTimes(1);
    window.removeEventListener(API_UNAUTHORIZED_EVENT, listener);
  });

  it("성공 응답이 JSON이 아니면 명확한 오류를 반환한다", async () => {
    vi.spyOn(window, "fetch").mockResolvedValue(
      new Response("<html>ok</html>", {
        status: 200,
        headers: { "content-type": "text/html" },
      }),
    );

    await expect(apiRequest("/api/test")).rejects.toMatchObject({
      code: "invalid_response",
      message: "서버 응답 형식이 올바르지 않습니다.",
    });
  });
});
