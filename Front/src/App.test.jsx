import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { getMe } from "./api/accountApi";
import { API_UNAUTHORIZED_EVENT } from "./api/apiClient";
import App from "./App";

vi.mock("./api/accountApi", () => ({
  getMe: vi.fn(),
  logout: vi.fn(),
}));

describe("App", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    window.history.pushState({}, "", "/");
  });

  it("인증 확인 중 서버 오류를 로그아웃으로 오인하지 않는다", async () => {
    const error = new Error("서버 연결 실패");
    error.status = 503;
    getMe.mockRejectedValue(error);

    render(<App />);

    expect(await screen.findByText("사용자 정보를 확인하지 못했습니다.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "다시 시도" })).toBeInTheDocument();
  });

  it("전역 인증 만료 이벤트가 발생하면 사용자 상태를 비운다", async () => {
    getMe.mockResolvedValue({
      user: { id: 1, displayName: "관리자 계정", role: "admin" },
    });

    render(<App />);

    expect(await screen.findByText("관리자 계정")).toBeInTheDocument();
    window.dispatchEvent(new CustomEvent(API_UNAUTHORIZED_EVENT));

    await waitFor(() => expect(screen.queryByText("관리자 계정")).not.toBeInTheDocument());
    expect(screen.getByRole("link", { name: "로그인" })).toBeInTheDocument();
  });
});
