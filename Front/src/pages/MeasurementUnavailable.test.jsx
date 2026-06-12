import React from "react";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { getAnalyzeResults, getPracticeGrowth } from "../api/analyzeApi";
import GrowthPage from "./GrowthPage";
import ResultListPage from "./ResultListPage";

vi.mock("../api/analyzeApi", () => ({
  cancelAnalyzeJob: vi.fn(),
  deleteAnalyzeResult: vi.fn(),
  getAnalyzeResults: vi.fn(),
  getPracticeGrowth: vi.fn(),
  retryAnalyzeJob: vi.fn(),
}));

describe("측정 불가 결과 표시", () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it("결과 목록에서 측정 불가 총점을 0점으로 표시하지 않는다", async () => {
    getAnalyzeResults.mockResolvedValue({
      results: [
        {
          result_id: "job-1",
          status: "COMPLETED",
          original_filename: "sample.mp4",
          total_score: null,
          created_at: "2026-06-12T00:00:00",
        },
        {
          result_id: "job-2",
          status: "COMPLETED",
          original_filename: "actual-zero.mp4",
          total_score: 0,
          created_at: "2026-06-12T00:01:00",
        },
      ],
      summary: { average_score: null },
      total: 2,
    });

    const view = render(
      <MemoryRouter>
        <ResultListPage />
      </MemoryRouter>,
    );

    expect((await screen.findAllByText("측정 불가")).length).toBeGreaterThan(0);
    expect(screen.queryByText("0점")).not.toBeInTheDocument();
    expect(screen.getByText("N/A")).toBeInTheDocument();
    expect(view.container.querySelector(".mini-score-circle.unavailable")).toHaveTextContent("N/A");
    expect(view.container.querySelector(".mini-score-circle:not(.unavailable)")).toHaveTextContent(
      "0",
    );
  });

  it("성장 추이에서 측정 불가 값에 0퍼센트 막대를 만들지 않는다", async () => {
    getPracticeGrowth.mockResolvedValue({
      growth: [
        {
          result_id: "job-1",
          original_filename: "sample.mp4",
          completed_at: "2026-06-12T00:00:00",
          total_score: null,
          metrics: { gaze_score: null },
          practice_context: {},
        },
      ],
    });

    const view = render(
      <MemoryRouter>
        <GrowthPage />
      </MemoryRouter>,
    );

    expect((await screen.findAllByText("측정 불가")).length).toBeGreaterThan(0);
    expect(view.container.querySelector(".growth-track")).not.toBeInTheDocument();
    expect(screen.getAllByText("측정 데이터 없음").length).toBeGreaterThan(0);
  });
});
