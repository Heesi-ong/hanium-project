import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { getAdminProblemJobs, getAdminStatus } from "../api/adminApi";
import AdminPage from "./AdminPage";

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
  },
};

describe("AdminPage", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("운영 상태와 점검 필요 항목을 표시한다", async () => {
    getAdminStatus.mockResolvedValue(status);
    getAdminProblemJobs.mockResolvedValue({ jobs: [] });

    render(<AdminPage />);

    expect(await screen.findByText("전체 서비스 상태: 점검 필요")).toBeInTheDocument();
    expect(screen.getByText("qwen3:4b")).toBeInTheDocument();
    expect(screen.getByText("실패 2건")).toBeInTheDocument();
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

    const view = render(<AdminPage />);
    await waitFor(() => expect(signal).toBeDefined());

    view.unmount();

    expect(signal.aborted).toBe(true);
  });
});
