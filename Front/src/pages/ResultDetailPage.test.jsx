import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { createMemoryRouter, MemoryRouter, Route, RouterProvider, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  createAiCoaching,
  getAiCoaching,
  getAnalyzeSections,
  getPracticeCoaching,
  getTimelineChart,
  regenerateAiCoaching,
} from "../api/analyzeApi";
import ResultDetailPage from "./ResultDetailPage";

vi.mock("../api/analyzeApi", () => ({
  createAiCoaching: vi.fn(),
  getAiCoaching: vi.fn(),
  getAnalyzeReportUrl: vi.fn(() => "/report.md"),
  getAnalyzeSections: vi.fn(),
  getPracticeCoaching: vi.fn(),
  getTimelineChart: vi.fn(),
  regenerateAiCoaching: vi.fn(),
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
    vi.resetAllMocks();
    getAnalyzeSections.mockResolvedValue({ sections, original_filename: "sample.mp4" });
    getTimelineChart.mockResolvedValue({ chart_data: [] });
    getPracticeCoaching.mockRejectedValue(new Error("코칭 서비스 오류"));
    getAiCoaching.mockResolvedValue({ ai_coaching: null, status: "not_generated" });
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

  it("타임라인 실패가 기본 분석 결과를 막지 않고 해당 영역만 재시도한다", async () => {
    getTimelineChart.mockRejectedValue(new Error("타임라인 오류"));

    render(
      <MemoryRouter initialEntries={["/result/job-1"]}>
        <Routes>
          <Route path="/result/:resultId" element={<ResultDetailPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText("기본 결과 정상")).toBeInTheDocument();
    expect(await screen.findByText("타임라인만 불러오지 못했습니다.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "타임라인 다시 불러오기" }));
    expect(getAnalyzeSections).toHaveBeenCalledTimes(1);
    expect(getTimelineChart).toHaveBeenCalledTimes(2);
  });

  it("빠른 결과 전환 후 이전 요청 응답을 표시하지 않는다", async () => {
    let resolveOld;
    getAnalyzeSections.mockImplementation((resultId) => {
      if (resultId === "job-1") {
        return new Promise((resolve) => {
          resolveOld = resolve;
        });
      }
      return Promise.resolve({
        sections: { ...sections, summary: { total_score: 90, summary_feedback: "새 결과" } },
        original_filename: "new.mp4",
      });
    });
    const router = createMemoryRouter(
      [{ path: "/result/:resultId", element: <ResultDetailPage /> }],
      { initialEntries: ["/result/job-1"] },
    );
    render(<RouterProvider router={router} />);

    await router.navigate("/result/job-2");
    expect(await screen.findByText("새 결과")).toBeInTheDocument();
    resolveOld({ sections, original_filename: "old.mp4" });
    await waitFor(() => expect(screen.queryByText("기본 결과 정상")).not.toBeInTheDocument());
  });

  it("AI 코칭을 선택적으로 생성하고 성공 결과를 표시한다", async () => {
    createAiCoaching.mockResolvedValue({
      ai_coaching: {
        status: "completed",
        model: "qwen3:4b",
        prompt_version: "presentation-coach-2026.06.1",
        coaching: {
          summary: "검증 결과를 먼저 설명하세요.",
          strengths: [],
          priorities: [],
          expected_questions: [],
          limitations: [],
        },
      },
    });
    render(
      <MemoryRouter initialEntries={["/result/job-1"]}>
        <Routes>
          <Route path="/result/:resultId" element={<ResultDetailPage />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole("button", { name: "AI 코칭 생성" }));

    expect(await screen.findByText("검증 결과를 먼저 설명하세요.")).toBeInTheDocument();
    expect(getAnalyzeSections).toHaveBeenCalledTimes(1);
  });

  it("AI 코칭 생성 실패가 기본 분석 결과를 막지 않는다", async () => {
    createAiCoaching.mockRejectedValue(new Error("Ollama 서버 오류"));
    render(
      <MemoryRouter initialEntries={["/result/job-1"]}>
        <Routes>
          <Route path="/result/:resultId" element={<ResultDetailPage />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole("button", { name: "AI 코칭 생성" }));

    expect(await screen.findByText("AI 코칭 영역만 처리하지 못했습니다.")).toBeInTheDocument();
    expect(screen.getByText("기본 결과 정상")).toBeInTheDocument();
  });

  it("저장된 AI 코칭을 다시 생성할 수 있다", async () => {
    const saved = {
      status: "completed",
      model: "qwen3:4b",
      prompt_version: "v1",
      coaching: {
        summary: "기존 코칭",
        strengths: [],
        priorities: [],
        expected_questions: [],
        limitations: [],
      },
    };
    getAiCoaching.mockResolvedValue({ ai_coaching: saved });
    regenerateAiCoaching.mockResolvedValue({
      ai_coaching: { ...saved, coaching: { ...saved.coaching, summary: "재생성 코칭" } },
    });
    render(
      <MemoryRouter initialEntries={["/result/job-1"]}>
        <Routes>
          <Route path="/result/:resultId" element={<ResultDetailPage />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole("button", { name: "코칭 다시 생성" }));

    expect(await screen.findByText("재생성 코칭")).toBeInTheDocument();
    expect(regenerateAiCoaching).toHaveBeenCalledWith("job-1", expect.any(Object));
  });
});
