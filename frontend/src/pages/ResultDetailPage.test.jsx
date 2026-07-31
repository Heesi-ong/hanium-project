import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";

import ResultDetailPage from "./ResultDetailPage";

const analysisApiMock = vi.hoisted(() => ({
    cancelAnalysis: vi.fn(),
    deleteResult: vi.fn(),
    getAnalysisStatus: vi.fn(),
    getResult: vi.fn(),
    requestVideoLlmReanalysis: vi.fn(),
    retryAnalysis: vi.fn(),
}));

const confirmMock = vi.hoisted(() => vi.fn());

const coachApiMock = vi.hoisted(() => ({
    getCoachMessages: vi.fn(),
    sendCoachMessage: vi.fn(),
}));

vi.mock("../api/analysisApi", () => ({
    cancelAnalysis: analysisApiMock.cancelAnalysis,
    deleteResult: analysisApiMock.deleteResult,
    getAnalysisStatus: analysisApiMock.getAnalysisStatus,
    getResult: analysisApiMock.getResult,
    requestVideoLlmReanalysis: analysisApiMock.requestVideoLlmReanalysis,
    retryAnalysis: analysisApiMock.retryAnalysis,
}));

vi.mock("../api/coachApi", () => ({
    getCoachMessages: coachApiMock.getCoachMessages,
    sendCoachMessage: coachApiMock.sendCoachMessage,
}));

vi.mock("../components/chart/AnalysisMetricBarChart", () => ({
    default: () => <div data-testid="analysis-metric-chart" />,
}));

vi.mock("../components/chart/EmotionDoughnutChart", () => ({
    default: () => <div data-testid="emotion-doughnut-chart" />,
}));

vi.mock("../components/chart/ResultScoreChart", () => ({
    default: () => <div data-testid="result-score-chart" />,
}));

vi.mock("../components/result-detail/VideoPlayerSection", () => ({
    default: () => <div data-testid="video-player-section" />,
}));

vi.mock("../context/ConfirmContext", () => ({
    useConfirm: () => confirmMock,
}));

function renderResultDetailPage() {
    return render(
        <MemoryRouter initialEntries={["/results/job-print-test"]}>
            <Routes>
                <Route path="/results/:jobId" element={<ResultDetailPage />} />
                <Route path="/results" element={<div>결과 목록</div>} />
                <Route path="/results/compare" element={<CompareDestinationStub />} />
            </Routes>
        </MemoryRouter>
    );
}

function CompareDestinationStub() {
    const location = useLocation();
    const jobIds = location.state?.results?.map((result) => result.jobId) || [];
    return <div data-testid="lineage-compare-destination">{jobIds.join(",")}</div>;
}

function createCompletedResult() {
    return {
        data: {
            result: {
                status: "COMPLETED",
                scoreSummary: {
                    level: "B",
                    totalScore: 82,
                    postureScore: 80,
                    gazeScore: 78,
                    speechScore: 84,
                    gestureScore: 79,
                    expressionScore: 81,
                },
                basicAnalysis: {
                    videoInfo: {},
                    frame: {},
                    pose: {},
                    gesture: {},
                    audio: {},
                    filler: {},
                    face: {},
                    emotion: {},
                },
                visualAnalysis: {},
                feedback: {},
                practicePlan: [],
                timelineFeedback: [],
                notableMoments: [],
                pipeline: {},
            },
        },
    };
}

describe("ResultDetailPage", () => {
    beforeEach(() => {
        vi.useRealTimers();
        analysisApiMock.cancelAnalysis.mockReset();
        analysisApiMock.deleteResult.mockReset();
        analysisApiMock.getAnalysisStatus.mockReset();
        analysisApiMock.getResult.mockReset();
        analysisApiMock.requestVideoLlmReanalysis.mockReset();
        analysisApiMock.retryAnalysis.mockReset();
        confirmMock.mockReset();
        confirmMock.mockResolvedValue(true);
        coachApiMock.getCoachMessages.mockReset();
        coachApiMock.sendCoachMessage.mockReset();
        analysisApiMock.getResult.mockResolvedValue(createCompletedResult());
        coachApiMock.getCoachMessages.mockResolvedValue({ data: { messages: [] } });

        Object.defineProperty(window, "print", {
            configurable: true,
            value: vi.fn(),
        });
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    it("calls window.print when the print button is clicked", async () => {
        renderResultDetailPage();

        const printButton = await screen.findByRole("button", {
            name: "인쇄 / PDF로 저장",
        });

        fireEvent.click(printButton);

        expect(window.print).toHaveBeenCalledTimes(1);
        expect(
            screen.getByText("AI Presentation Coach — 분석 결과 리포트")
        ).toBeInTheDocument();
        expect(screen.getByText(/jobId: job-print-test/)).toBeInTheDocument();

        await waitFor(() => {
            expect(analysisApiMock.getResult).toHaveBeenCalledWith("job-print-test");
        });
    });

    it("shows the Korean status label instead of the raw status enum", async () => {
        renderResultDetailPage();

        await waitFor(() => {
            expect(analysisApiMock.getResult).toHaveBeenCalledWith("job-print-test");
        });

        expect(await screen.findByText("분석 완료")).toBeInTheDocument();
        expect(screen.queryByText("COMPLETED")).not.toBeInTheDocument();
    });

    it("shows a data issue warning when the result payload is incomplete", async () => {
        analysisApiMock.getResult.mockResolvedValue({
            data: {
                dataIssue: "RESULT_DATA_INCOMPLETE",
                dataIssueDescription: "분석 결과 파일은 있지만 점수 또는 피드백 데이터가 불완전합니다.",
                result: {
                    ...createCompletedResult().data.result,
                    scoreSummary: {
                        level: "-",
                        totalScore: 0,
                    },
                    feedback: {
                        generationMode: "UNKNOWN",
                        overall: "",
                    },
                },
            },
        });

        renderResultDetailPage();

        expect(await screen.findByRole("alert")).toHaveTextContent("불완전");
        expect(
            screen.getByText("분석 결과 파일은 있지만 점수 또는 피드백 데이터가 불완전합니다.")
        ).toBeInTheDocument();
        expect(coachApiMock.getCoachMessages).not.toHaveBeenCalled();
    });

    it("polls status when an in-progress result is opened and reloads on completion", async () => {
        vi.useFakeTimers();

        const runningResult = createCompletedResult();
        runningResult.data.result.status = "QUEUED";
        runningResult.data.result.scoreSummary = {
            level: "-",
            totalScore: null,
        };

        analysisApiMock.getResult
            .mockResolvedValueOnce(runningResult)
            .mockResolvedValueOnce(createCompletedResult());
        analysisApiMock.getAnalysisStatus.mockResolvedValue({
            data: {
                jobId: "job-print-test",
                status: "COMPLETED",
                statusDescription: "분석 완료",
                failReason: null,
            },
        });

        renderResultDetailPage();

        await act(async () => {
            await vi.advanceTimersByTimeAsync(0);
        });

        expect(screen.getAllByText("분석 대기 중").length).toBeGreaterThan(0);

        expect(screen.getByText(/분석 상태를 자동으로 확인하는 중입니다/)).toBeInTheDocument();

        await act(async () => {
            await vi.advanceTimersByTimeAsync(1500);
        });

        expect(analysisApiMock.getAnalysisStatus).toHaveBeenCalledWith("job-print-test");
        expect(analysisApiMock.getResult).toHaveBeenCalledTimes(2);

        expect(screen.getByText("분석이 완료되었습니다. 최신 결과가 화면에 반영되었습니다.")).toBeInTheDocument();
    });

    it("requests real Video LLM reanalysis with a stable key and opens the child job", async () => {
        const fallbackResult = createCompletedResult();
        fallbackResult.data.analysisKind = "STANDARD";
        fallbackResult.data.videoLlmGenerationMode = "FALLBACK";
        analysisApiMock.getResult
            .mockResolvedValueOnce(fallbackResult)
            .mockResolvedValueOnce({
                data: {
                    analysisKind: "VIDEO_LLM_REANALYSIS",
                    sourceJobId: "job-print-test",
                    result: {
                        ...createCompletedResult().data.result,
                        status: "QUEUED",
                    },
                },
            });
        analysisApiMock.requestVideoLlmReanalysis
            .mockRejectedValueOnce({ error: "NETWORK_ERROR" })
            .mockResolvedValueOnce({
                data: {
                    reanalysisJobId: "job-reanalysis-child",
                },
            });

        renderResultDetailPage();

        fireEvent.click(await screen.findByRole("button", {
            name: "실제 Video LLM으로 다시 분석",
        }));

        await waitFor(() => {
            expect(analysisApiMock.requestVideoLlmReanalysis).toHaveBeenCalledTimes(1);
        });

        const [firstSourceJobId, firstOptions] =
            analysisApiMock.requestVideoLlmReanalysis.mock.calls[0];
        expect(firstSourceJobId).toBe("job-print-test");
        expect(firstOptions.useOpenAi).toBe(true);
        expect(firstOptions.idempotencyKey).toMatch(
            /^video-llm-reanalysis:[A-Za-z0-9._:-]+$/
        );

        fireEvent.click(await screen.findByRole("button", {
            name: "실제 Video LLM으로 다시 분석",
        }));

        await waitFor(() => {
            expect(analysisApiMock.requestVideoLlmReanalysis).toHaveBeenCalledTimes(2);
        });
        const [secondSourceJobId, secondOptions] =
            analysisApiMock.requestVideoLlmReanalysis.mock.calls[1];
        expect(secondSourceJobId).toBe(firstSourceJobId);
        expect(secondOptions.idempotencyKey).toBe(firstOptions.idempotencyKey);
        expect(confirmMock).toHaveBeenCalledWith(
            expect.stringContaining("일일·월간 사용 한도")
        );

        await waitFor(() => {
            expect(analysisApiMock.getResult)
                .toHaveBeenCalledWith("job-reanalysis-child");
        });
    });

    it("shows links between a source result and its reanalysis result", async () => {
        const sourceResult = createCompletedResult();
        sourceResult.data.latestReanalysisJobId = "job-reanalysis-latest";
        analysisApiMock.getResult.mockResolvedValue(sourceResult);

        renderResultDetailPage();

        expect(await screen.findByRole("link", {
            name: "최신 재분석 결과 보기",
        })).toHaveAttribute("href", "/results/job-reanalysis-latest");
    });

    it("loads the lineage counterpart and compares source before reanalysis", async () => {
        const sourceResult = createCompletedResult();
        sourceResult.data.jobId = "job-print-test";
        sourceResult.data.analysisKind = "STANDARD";
        sourceResult.data.latestReanalysisJobId = "job-reanalysis-latest";
        sourceResult.data.result.fileName = "원본.mp4";

        const reanalysisResult = createCompletedResult();
        reanalysisResult.data.jobId = "job-reanalysis-latest";
        reanalysisResult.data.analysisKind = "VIDEO_LLM_REANALYSIS";
        reanalysisResult.data.sourceJobId = "job-print-test";
        reanalysisResult.data.result.fileName = "재분석.mp4";

        analysisApiMock.getResult.mockImplementation((requestedJobId) => {
            if (requestedJobId === "job-reanalysis-latest") {
                return Promise.resolve(reanalysisResult);
            }
            return Promise.resolve(sourceResult);
        });

        renderResultDetailPage();

        fireEvent.click(await screen.findByRole("button", {
            name: "원본과 재분석 비교",
        }));

        expect(
            await screen.findByTestId("lineage-compare-destination")
        ).toHaveTextContent("job-print-test,job-reanalysis-latest");
        expect(analysisApiMock.getResult)
            .toHaveBeenCalledWith("job-reanalysis-latest");
    });

    it("keeps source first when comparison starts from a reanalysis detail", async () => {
        const reanalysisResult = createCompletedResult();
        reanalysisResult.data.jobId = "job-print-test";
        reanalysisResult.data.analysisKind = "VIDEO_LLM_REANALYSIS";
        reanalysisResult.data.sourceJobId = "job-source";

        const sourceResult = createCompletedResult();
        sourceResult.data.jobId = "job-source";
        sourceResult.data.analysisKind = "STANDARD";
        sourceResult.data.latestReanalysisJobId = "job-print-test";

        analysisApiMock.getResult.mockImplementation((requestedJobId) => {
            if (requestedJobId === "job-source") {
                return Promise.resolve(sourceResult);
            }
            return Promise.resolve(reanalysisResult);
        });

        renderResultDetailPage();

        fireEvent.click(await screen.findByRole("button", {
            name: "원본과 재분석 비교",
        }));

        expect(
            await screen.findByTestId("lineage-compare-destination")
        ).toHaveTextContent("job-source,job-print-test");
    });
});
