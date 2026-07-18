import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

import ResultListPage from "./ResultListPage";

const analysisApiMock = vi.hoisted(() => ({
    deleteResult: vi.fn(),
    getResults: vi.fn(),
}));

vi.mock("../api/analysisApi", () => ({
    deleteResult: analysisApiMock.deleteResult,
    getResults: analysisApiMock.getResults,
}));

vi.mock("../context/ConfirmContext", () => ({
    useConfirm: () => vi.fn(),
}));

vi.mock("../components/chart/ScoreTrendChart", () => ({
    default: () => <div data-testid="score-trend-chart" />,
}));

function renderResultListPage() {
    return render(
        <MemoryRouter>
            <ResultListPage />
        </MemoryRouter>
    );
}

describe("ResultListPage", () => {
    beforeEach(() => {
        analysisApiMock.deleteResult.mockReset();
        analysisApiMock.getResults.mockReset();
        analysisApiMock.getResults.mockResolvedValue({
            data: {
                content: [
                    {
                        jobId: "job-list-video-llm",
                        status: "COMPLETED",
                        statusDescription: "분석 완료",
                        fileName: "presentation.mp4",
                        createdAt: "2026-07-15T09:00:00",
                        scoreSummary: {
                            totalScore: 82,
                            level: "B",
                        },
                        pipeline: {
                            videoLlmGenerationMode: "MOCK",
                            videoLlmAnalysis: "video-llm-engine mock",
                        },
                        feedback: {
                            generationMode: "REAL",
                            model: "gpt-4.1-mini",
                            realApiUsed: true,
                            overall: "전체 피드백",
                        },
                    },
                    {
                        jobId: "job-list-real-video-llm",
                        status: "COMPLETED",
                        statusDescription: "분석 완료",
                        fileName: "demo.mp4",
                        createdAt: "2026-07-15T10:00:00",
                        scoreSummary: {
                            totalScore: 90,
                            level: "A",
                        },
                        visualAnalysis: {
                            model: {
                                name: "nvidia/nemotron",
                                version: "nvidia-nim",
                                generationMode: "REAL",
                            },
                        },
                        feedback: {
                            generationMode: "MOCK",
                            model: "-",
                            realApiUsed: false,
                            overall: "데모 피드백",
                        },
                    },
                ],
                last: true,
            },
        });
    });

    it("shows both OpenAI and Video LLM generation modes on result cards", async () => {
        renderResultListPage();

        expect(await screen.findByText("presentation.mp4")).toBeInTheDocument();
        expect(screen.getByText("실제 OpenAI")).toBeInTheDocument();
        expect(screen.getByText("샘플 시각 분석")).toBeInTheDocument();
        expect(screen.getByText("Video LLM · video-llm-engine mock")).toBeInTheDocument();
    });

    it("labels OpenAI-only filters clearly and searches Video LLM metadata", async () => {
        renderResultListPage();

        expect(await screen.findByRole("button", { name: "OpenAI 전체" }))
            .toBeInTheDocument();
        expect(screen.getByText("OpenAI Mock")).toBeInTheDocument();
        expect(screen.getByText("OpenAI Real")).toBeInTheDocument();
        expect(screen.getByText("OpenAI Fallback")).toBeInTheDocument();

        fireEvent.change(
            screen.getByPlaceholderText("파일명, jobId, OpenAI/Video LLM 방식 검색"),
            {
                target: {
                    value: "video-llm-engine mock",
                },
            }
        );

        await waitFor(() => {
            expect(screen.getByText("presentation.mp4")).toBeInTheDocument();
            expect(screen.queryByText("demo.mp4")).not.toBeInTheDocument();
        });
    });
});
