import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  cancelAnalyzeJob,
  getAnalyzeJob,
  getPracticePurposes,
  getPracticeSeries,
  savePracticeContext,
} from "../api/analyzeApi";
import { uploadAnalyzeVideo } from "../api/uploadApi";
import UploadPage from "./UploadPage";

vi.mock("../api/analyzeApi", () => ({
  cancelAnalyzeJob: vi.fn(),
  getAnalyzeJob: vi.fn(),
  getPracticePurposes: vi.fn(),
  getPracticeSeries: vi.fn(),
  savePracticeContext: vi.fn(),
}));

vi.mock("../api/uploadApi", () => ({
  uploadAnalyzeVideo: vi.fn(),
}));

describe("UploadPage 연습 시리즈", () => {
  beforeEach(() => {
    vi.resetAllMocks();
    getPracticePurposes.mockResolvedValue({
      purposes: [
        {
          key: "project",
          label: "프로젝트 발표",
          focus: "프로젝트 설명",
          recommended_minutes: 12,
        },
      ],
    });
    getPracticeSeries.mockResolvedValue({
      series: [
        {
          series_id: "series-1",
          series_name: "한이음 최종 발표",
          purpose: "project",
        },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("기존 시리즈를 선택해 반복 발표를 이어갈 수 있다", async () => {
    render(
      <MemoryRouter>
        <UploadPage />
      </MemoryRouter>,
    );

    const select = await screen.findByLabelText("기존 연습 시리즈");
    fireEvent.change(select, { target: { value: "series-1" } });

    expect(select).toHaveValue("series-1");
    expect(screen.getByLabelText("새 연습 시리즈 이름")).toBeDisabled();
    expect(screen.getByLabelText("새 연습 시리즈 이름")).toHaveValue("한이음 최종 발표");
    expect(screen.getByLabelText("발표 영상 파일 선택")).toHaveAttribute("type", "file");
  });

  it("업로드 완료 후 작업 상태를 확인하고 결과 화면으로 이동한다", async () => {
    uploadAnalyzeVideo.mockImplementation(async (_file, onProgress) => {
      onProgress(100);
      return { job: { result_id: "job-1", status: "QUEUED", progress: 0 } };
    });
    savePracticeContext.mockResolvedValue({});
    getAnalyzeJob.mockResolvedValue({
      job: { result_id: "job-1", status: "COMPLETED", progress: 100 },
    });

    const view = render(
      <MemoryRouter initialEntries={["/upload"]}>
        <Routes>
          <Route path="/upload" element={<UploadPage />} />
          <Route path="/result/:resultId" element={<div>결과 화면</div>} />
        </Routes>
      </MemoryRouter>,
    );

    const file = new File(["video"], "presentation.mp4", { type: "video/mp4" });
    fireEvent.change(view.container.querySelector('input[type="file"]'), {
      target: { files: [file] },
    });
    fireEvent.click(screen.getByRole("button", { name: "분석 시작" }));

    expect(await screen.findByText("결과 화면")).toBeInTheDocument();
    expect(uploadAnalyzeVideo).toHaveBeenCalledWith(
      file,
      expect.any(Function),
      expect.any(AbortSignal),
    );
    expect(savePracticeContext).toHaveBeenCalledWith(
      "job-1",
      expect.objectContaining({ purpose: "project" }),
    );
    await waitFor(() => expect(getAnalyzeJob).toHaveBeenCalledWith("job-1", expect.any(Object)));
  });

  it("분석 상태 조회가 일시적으로 실패하면 즉시 실패 처리하지 않고 다시 시도한다", async () => {
    uploadAnalyzeVideo.mockImplementation(async (_file, onProgress) => {
      onProgress(100);
      return { job: { result_id: "job-1", status: "QUEUED", progress: 0 } };
    });
    savePracticeContext.mockResolvedValue({});
    getAnalyzeJob.mockRejectedValueOnce(new Error("일시적 네트워크 오류")).mockResolvedValueOnce({
      job: { result_id: "job-1", status: "COMPLETED", progress: 100 },
    });

    const view = render(
      <MemoryRouter initialEntries={["/upload"]}>
        <Routes>
          <Route path="/upload" element={<UploadPage />} />
          <Route path="/result/:resultId" element={<div>결과 화면</div>} />
        </Routes>
      </MemoryRouter>,
    );

    const file = new File(["video"], "presentation.mp4", { type: "video/mp4" });
    fireEvent.change(view.container.querySelector('input[type="file"]'), {
      target: { files: [file] },
    });
    fireEvent.click(screen.getByRole("button", { name: "분석 시작" }));

    expect(
      await screen.findByText(
        "분석 상태를 일시적으로 확인하지 못했습니다. 자동으로 다시 시도합니다.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText("처리 중 오류가 발생했습니다.")).not.toBeInTheDocument();

    await waitFor(() => expect(getAnalyzeJob).toHaveBeenCalledTimes(2), { timeout: 4000 });
    expect(await screen.findByText("결과 화면")).toBeInTheDocument();
  });

  it("업로드 전송 중 취소하면 요청을 중단하고 분석 작업을 생성하지 않는다", async () => {
    let uploadSignal;
    uploadAnalyzeVideo.mockImplementation(
      (_file, _onProgress, signal) =>
        new Promise((_resolve, reject) => {
          uploadSignal = signal;
          signal.addEventListener("abort", () =>
            reject(new DOMException("업로드가 취소되었습니다.", "AbortError")),
          );
        }),
    );

    const view = render(
      <MemoryRouter>
        <UploadPage />
      </MemoryRouter>,
    );
    const file = new File(["video"], "presentation.mp4", { type: "video/mp4" });
    fireEvent.change(view.container.querySelector('input[type="file"]'), {
      target: { files: [file] },
    });
    fireEvent.click(screen.getByRole("button", { name: "분석 시작" }));
    fireEvent.click(await screen.findByRole("button", { name: "업로드 취소" }));

    await waitFor(() => expect(uploadSignal.aborted).toBe(true));
    expect(await screen.findByText("영상 업로드를 취소했습니다.")).toBeInTheDocument();
    expect(cancelAnalyzeJob).not.toHaveBeenCalled();
  });
});
