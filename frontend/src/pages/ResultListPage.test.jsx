import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";

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

// 비교하기를 누르면 실제로 /results/compare로 이동하며 선택한 두 결과가
// navigation state에 담겨 전달되는지 확인하기 위한 목적지 스텁입니다.
function CompareDestinationStub() {
    const location = useLocation();
    const jobIds = (location.state?.results || []).map((result) => result.jobId);

    return <div data-testid="compare-destination">{jobIds.join(",")}</div>;
}

function renderResultListPageWithCompareRoute() {
    return render(
        <MemoryRouter initialEntries={["/results"]}>
            <Routes>
                <Route path="/results" element={<ResultListPage />} />
                <Route path="/results/compare" element={<CompareDestinationStub />} />
            </Routes>
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
                    {
                        jobId: "job-list-pipeline-openai",
                        status: "COMPLETED",
                        statusDescription: "분석 완료",
                        fileName: "pipeline-openai.mp4",
                        createdAt: "2026-07-15T11:00:00",
                        scoreSummary: {
                            totalScore: 88,
                            level: "A",
                        },
                        pipeline: {
                            openAiGenerationMode: "REAL",
                            openAiModel: "pipeline-openai-model",
                            openAiRealApiUsed: true,
                        },
                        feedback: {
                            generationMode: "UNKNOWN",
                            model: "-",
                            realApiUsed: false,
                            overall: "파이프라인 메타데이터 복구 피드백",
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
        expect(screen.getAllByText("실제 OpenAI").length).toBeGreaterThanOrEqual(1);
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

    it("filters OpenAI modes with pipeline metadata when feedback metadata is a placeholder", async () => {
        renderResultListPage();

        expect(await screen.findByText("pipeline-openai.mp4")).toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: "REAL" }));

        await waitFor(() => {
            expect(screen.getByText("presentation.mp4")).toBeInTheDocument();
            expect(screen.getByText("pipeline-openai.mp4")).toBeInTheDocument();
            expect(screen.queryByText("demo.mp4")).not.toBeInTheDocument();
        });

        fireEvent.click(screen.getByRole("button", { name: "OpenAI 전체" }));
        fireEvent.change(
            screen.getByPlaceholderText("파일명, jobId, OpenAI/Video LLM 방식 검색"),
            {
                target: {
                    value: "pipeline-openai-model",
                },
            }
        );

        await waitFor(() => {
            expect(screen.getByText("pipeline-openai.mp4")).toBeInTheDocument();
            expect(screen.queryByText("presentation.mp4")).not.toBeInTheDocument();
            expect(screen.queryByText("demo.mp4")).not.toBeInTheDocument();
        });
    });

    it("only allows selecting up to two results for comparison and enables the compare button at exactly two", async () => {
        renderResultListPage();

        await screen.findByText("presentation.mp4");

        fireEvent.click(screen.getByRole("button", { name: "결과 비교" }));

        const checkboxes = screen.getAllByRole("checkbox", { name: "비교 대상으로 선택" });
        expect(checkboxes).toHaveLength(3);

        const compareButton = screen.getByRole("button", { name: "선택한 결과 비교하기" });
        expect(compareButton).toBeDisabled();

        fireEvent.click(checkboxes[0]);
        expect(compareButton).toBeDisabled();

        fireEvent.click(checkboxes[1]);
        expect(compareButton).toBeEnabled();

        // 이미 2개를 고른 상태에서 세 번째를 눌러도 선택되지 않아야 합니다.
        fireEvent.click(checkboxes[2]);
        expect(checkboxes[2]).not.toBeChecked();
        expect(compareButton).toBeEnabled();
    });

    it("navigates to /results/compare with the two selected results in navigation state", async () => {
        renderResultListPageWithCompareRoute();

        await screen.findByText("presentation.mp4");

        fireEvent.click(screen.getByRole("button", { name: "결과 비교" }));

        const checkboxes = screen.getAllByRole("checkbox", { name: "비교 대상으로 선택" });
        fireEvent.click(checkboxes[0]);
        fireEvent.click(checkboxes[1]);

        fireEvent.click(screen.getByRole("button", { name: "선택한 결과 비교하기" }));

        // 기본 정렬(최신순)이라 목록 순서는 pipeline-openai(11:00) -> real-video-llm(10:00)
        // -> video-llm(09:00)입니다. 앞의 두 체크박스를 선택했으므로 이 두 jobId가 전달됩니다.
        expect(await screen.findByTestId("compare-destination")).toHaveTextContent(
            "job-list-pipeline-openai,job-list-real-video-llm"
        );
    });
});
