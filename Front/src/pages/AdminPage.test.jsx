import React from "react";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { getAdminMetrics, getAdminUsers, updateAdminUserStatus } from "../api/adminApi";
import AdminPage from "./AdminPage";

vi.mock("../api/adminApi", () => ({
  getAdminMetrics: vi.fn(),
  getAdminUsers: vi.fn(),
  updateAdminUserStatus: vi.fn(),
}));

describe("AdminPage", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    getAdminMetrics.mockResolvedValue({
      users: { total: 2, active: 1, disabled: 1, created_last_24_hours: 1 },
      analysis: {
        total: 3,
        completed: 2,
        failed: 1,
        success_rate: 66.67,
        completed_last_24_hours: 1,
        average_completed_processing_seconds: 10,
      },
    });
    getAdminUsers.mockResolvedValue({
      total: 45,
      users: [
        {
          id: 7,
          email: "user@example.com",
          status: "active",
          status_change_allowed: true,
          created_at: "2026-06-15",
        },
      ],
    });
  });

  it("개인정보를 제한한 관리자 통계와 사용자 목록을 표시한다", async () => {
    render(
      <MemoryRouter>
        <AdminPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText("user@example.com")).toBeInTheDocument();
    expect(screen.getByText("2명")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "시스템 운영 상태" })).toHaveAttribute(
      "href",
      "/admin/system",
    );
    expect(screen.queryByText("분석 상세 결과")).not.toBeInTheDocument();
  });

  it("확인 대화상자를 거쳐 일반 사용자 계정을 정지한다", async () => {
    updateAdminUserStatus.mockResolvedValue({});
    render(
      <MemoryRouter>
        <AdminPage />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole("button", { name: "계정 정지" }));
    expect(updateAdminUserStatus).not.toHaveBeenCalled();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "계정 정지" }));

    await waitFor(() => expect(updateAdminUserStatus).toHaveBeenCalledWith(7, "disabled"));
  });

  it("사용자 목록 페이지를 이동할 때 limit과 offset을 전달한다", async () => {
    render(
      <MemoryRouter>
        <AdminPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText("1 / 3 페이지 · 총 45명")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "다음" }));

    await waitFor(() =>
      expect(getAdminUsers).toHaveBeenLastCalledWith(
        { search: "", status: "", limit: 20, offset: 20 },
        expect.any(AbortSignal),
      ),
    );
  });
});
