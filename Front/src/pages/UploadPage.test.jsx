import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { getPracticePurposes, getPracticeSeries } from "../api/analyzeApi";
import UploadPage from "./UploadPage";

vi.mock("../api/analyzeApi", () => ({
  cancelAnalyzeJob: vi.fn(),
  getAnalyzeJob: vi.fn(),
  getPracticePurposes: vi.fn(),
  getPracticeSeries: vi.fn(),
  savePracticeContext: vi.fn(),
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
  });
});
