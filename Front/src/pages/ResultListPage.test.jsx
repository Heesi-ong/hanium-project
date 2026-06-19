import React from "react";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { getAnalyzeResults } from "../api/analyzeApi";
import ResultListPage from "./ResultListPage";

vi.mock("../api/analyzeApi", () => ({
  cancelAnalyzeJob: vi.fn(),
  deleteAnalyzeResult: vi.fn(),
  getAnalyzeResults: vi.fn(),
  retryAnalyzeJob: vi.fn(),
}));

const response = (name) => ({
  results: [
    {
      result_id: name,
      status: "COMPLETED",
      original_filename: name,
      total_score: 80,
      created_at: "2026-06-14T00:00:00",
    },
  ],
  summary: {},
  total: 1,
});

describe("ResultListPage 요청 상태", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("검색 입력을 debounce하여 서버에 요청한다", async () => {
    vi.useFakeTimers();
    getAnalyzeResults.mockResolvedValue(response("기본.mp4"));
    render(
      <MemoryRouter>
        <ResultListPage />
      </MemoryRouter>,
    );
    await act(async () => {});

    fireEvent.change(screen.getByLabelText("검색어"), { target: { value: "발표" } });
    expect(getAnalyzeResults).toHaveBeenCalledTimes(1);
    await act(async () => {
      vi.advanceTimersByTime(300);
    });

    expect(getAnalyzeResults).toHaveBeenLastCalledWith(
      expect.objectContaining({ search: "발표" }),
      expect.any(Object),
    );
    vi.useRealTimers();
  });

  it("이전 요청이 늦게 완료되어도 최신 결과를 덮어쓰지 않는다", async () => {
    let resolveFirst;
    getAnalyzeResults
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveFirst = resolve;
          }),
      )
      .mockResolvedValueOnce(response("최신결과.mp4"));

    render(
      <MemoryRouter>
        <ResultListPage />
      </MemoryRouter>,
    );
    fireEvent.click(screen.getByRole("button", { name: "성공" }));

    expect(await screen.findByText("최신결과.mp4")).toBeInTheDocument();
    resolveFirst(response("이전결과.mp4"));
    await waitFor(() => expect(screen.queryByText("이전결과.mp4")).not.toBeInTheDocument());
  });
});
