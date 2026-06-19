import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import { getAdminProblemJobs, getAdminStatus } from "../api/adminApi";
import AdminSystemPage from "./AdminSystemPage";

vi.mock("../api/adminApi", () => ({
  getAdminProblemJobs: vi.fn(),
  getAdminStatus: vi.fn(),
  retryAdminProblemJob: vi.fn(),
}));

const status = {
  status: "degraded",
  checks: {
    database: { ok: true },
    worker: {
      ok: true,
      active_worker_count: 1,
      worker_count: 1,
      maintenance_running: true,
      worker_heartbeat_stale: false,
      maintenance_stale: false,
    },
    ollama: { ok: false, configured_model: "qwen3:4b" },
    queue: { queued: 1, processing: 0, failed: 2, stalled: 0 },
    disk: { ok: true, free_mb: 2048, minimum_free_mb: 1024 },
    storage: { ok: true, missing: [] },
    models: { ok: true, files: [{ path: "face.task", exists: true }], missing: [] },
  },
};

const renderAdminSystemPage = () =>
  render(
    <MemoryRouter>
      <AdminSystemPage />
    </MemoryRouter>,
  );

describe("AdminSystemPage", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("운영 상태와 점검 필요 항목을 표시한다", async () => {
    getAdminStatus.mockResolvedValue(status);
    getAdminProblemJobs.mockResolvedValue({ jobs: [] });

    renderAdminSystemPage();

    expect(await screen.findByText("전체 서비스 상태: 점검 필요")).toBeInTheDocument();
    expect(screen.getByText("qwen3:4b")).toBeInTheDocument();
    expect(screen.getByText("실패 2건")).toBeInTheDocument();
    expect(screen.getByText("확인된 모델 1개")).toBeInTheDocument();
    expect(screen.getByText("누락 경로 0개")).toBeInTheDocument();
    expect(screen.getByText("점검 필요")).toHaveClass("status-error");
  });

  it("화면 이탈 시 진행 중인 상태 요청을 취소한다", async () => {
    let signal;
    getAdminStatus.mockImplementation(
      (requestSignal) =>
        new Promise(() => {
          signal = requestSignal;
        }),
    );
    getAdminProblemJobs.mockResolvedValue({ jobs: [] });

    const view = renderAdminSystemPage();
    await waitFor(() => expect(signal).toBeDefined());

    view.unmount();

    expect(signal.aborted).toBe(true);
  });

  it("문제 작업 조회가 실패해도 기본 운영 상태를 표시한다", async () => {
    getAdminStatus.mockResolvedValue(status);
    getAdminProblemJobs.mockRejectedValue(new Error("문제 작업 오류"));

    renderAdminSystemPage();

    expect(await screen.findByText("전체 서비스 상태: 점검 필요")).toBeInTheDocument();
    expect(screen.getByText("문제 작업 오류")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "문제 작업 다시 불러오기" })).toBeInTheDocument();
  });

  it("운영 상태 조회가 실패해도 문제 작업 목록을 표시한다", async () => {
    getAdminStatus.mockRejectedValue(new Error("상태 오류"));
    getAdminProblemJobs.mockResolvedValue({
      jobs: [{ result_id: "job-1", user_email: "user@example.com", status: "FAILED" }],
    });

    renderAdminSystemPage();

    expect(await screen.findByText("job-1")).toBeInTheDocument();
    expect(screen.queryByText("user@example.com")).not.toBeInTheDocument();
    expect(screen.getByText("상태 오류")).toBeInTheDocument();
  });
});
