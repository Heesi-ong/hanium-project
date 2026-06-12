import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { getAnalyzeSections, getPracticeCoaching, getTimelineChart } from "../api/analyzeApi";
import ResultDetailPage from "./ResultDetailPage";

vi.mock("../api/analyzeApi", () => ({
  getAnalyzeReportUrl: vi.fn(() => "/report.md"),
  getAnalyzeSections: vi.fn(),
  getPracticeCoaching: vi.fn(),
  getTimelineChart: vi.fn(),
}));

const sections = {
  summary: { total_score: 88, summary_feedback: "기본 결과 정상" },
  score: { total_score: 88, score_availability: {} },
  feedback: {},
  speech: {},
  filler: {},
  gesture: {},
  volume: {},
  timeline: {},
};

describe("ResultDetailPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getAnalyzeSections.mockResolvedValue({ sections, original_filename: "sample.mp4" });
    getTimelineChart.mockResolvedValue({ chart_data: [] });
    getPracticeCoaching.mockRejectedValue(new Error("코칭 서비스 오류"));
  });

  it("연습 코칭 실패가 기본 분석 결과를 막지 않는다", async () => {
    render(
      <MemoryRouter initialEntries={["/result/job-1"]}>
        <Routes>
          <Route path="/result/:resultId" element={<ResultDetailPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText("기본 결과 정상")).toBeInTheDocument();
    expect(await screen.findByText("연습 코칭만 불러오지 못했습니다.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "코칭 다시 불러오기" })).toBeInTheDocument();
  });

  it("연습 코칭 재시도는 기본 결과를 다시 요청하지 않는다", async () => {
    getPracticeCoaching.mockRejectedValueOnce(new Error("일시 오류")).mockResolvedValueOnce({
      coaching: {
        purpose: { label: "프로젝트 발표", focus: "문제와 결과 전달" },
        duration_fit: { target_minutes: 12, actual_minutes: 10 },
        context: {},
        comparison: null,
        improvement_plan: [],
        expected_questions: [],
        content_analysis: { available: false, note: "텍스트 없음" },
        confidence: { visual: "제한적", audio: "제한적", note: "확인 필요" },
      },
    });

    render(
      <MemoryRouter initialEntries={["/result/job-1"]}>
        <Routes>
          <Route path="/result/:resultId" element={<ResultDetailPage />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole("button", { name: "코칭 다시 불러오기" }));

    expect(await screen.findByText("프로젝트 발표")).toBeInTheDocument();
    expect(getPracticeCoaching).toHaveBeenCalledTimes(2);
    expect(getAnalyzeSections).toHaveBeenCalledTimes(1);
    expect(getTimelineChart).toHaveBeenCalledTimes(1);
  });
});
