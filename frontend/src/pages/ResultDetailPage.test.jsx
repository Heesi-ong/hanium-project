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

vi.mock("../components/chart/ResultScoreChart", () => ({
    default: () => <div data-testid="result-score-chart" />,
}));

vi.mock("../components/chart/ScoreCompositionChart", () => ({
    default: () => <div data-testid="score-composition-chart" />,
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
                    speechScore: 84,
                    gestureScore: 79,
                },
                scoreExplanation: {},
                analysisQuality: {
                    available: true,
                    lowConfidence: false,
                    poseDetectionRate: 0.91,
                    penaltyApplied: 0,
                    penaltyReasons: [],
                    formulaVersion: "weighted-v2",
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
                    analysisTrace: [],
                    frameGallery: [],
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

    it("shows the analysis trace and frame gallery sections when the result includes them", async () => {
        const result = createCompletedResult();
        result.data.result.basicAnalysis.analysisTrace = [
            {
                stepNo: 5,
                totalSteps: 9,
                key: "pose_gesture",
                label: "자세와 제스처를 분석하는 중...",
                durationMs: 1234,
                detail: "포즈 검출 17/20 프레임",
            },
        ];
        result.data.result.basicAnalysis.frameGallery = [
            {
                sequence: 1,
                timestampSec: 1,
                poseDetected: true,
                gestureDetected: false,
                fileName: "frame_001.jpg",
            },
        ];
        analysisApiMock.getResult.mockResolvedValue(result);

        renderResultDetailPage();

        expect(
            await screen.findByText(/분석 처리 과정 — OpenCV·MediaPipe/)
        ).toBeInTheDocument();
        expect(
            screen.getByText(/분석 프레임 미리보기 — MediaPipe 스켈레톤 오버레이 \(1장\)/)
        ).toBeInTheDocument();
    });

    it("omits the analysis trace and frame gallery sections when they are absent", async () => {
        renderResultDetailPage();

        await waitFor(() => {
            expect(analysisApiMock.getResult).toHaveBeenCalledWith("job-print-test");
        });

        expect(screen.queryByText(/분석 처리 과정 —/)).not.toBeInTheDocument();
        expect(screen.queryByText(/분석 프레임 미리보기 —/)).not.toBeInTheDocument();
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
        expect(screen.getAllByText("결과 확인 필요")).toHaveLength(2);
        expect(screen.queryByText("개선 필요")).not.toBeInTheDocument();
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

    // 이 페이지는 1000줄이 넘는 대형 컴포넌트로 지목됐다(2026-08-03 서비스화 점검 P2-02).
    // 실제 리팩터링 전에, 동작을 바꾸지 않는다는 전제로 지금까지 커버되지 않던 로드 실패
    // 경로(접근 거부/미존재/일반 오류)를 먼저 고정한다.
    it("shows an access-denied empty state when the result belongs to another user", async () => {
        analysisApiMock.getResult.mockRejectedValue({ error: "ANALYSIS_JOB_ACCESS_DENIED" });

        renderResultDetailPage();

        expect(await screen.findByText("접근 권한이 없는 결과입니다.")).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "목록으로 이동" })).toHaveAttribute(
            "href",
            "/results"
        );
    });

    it("shows a not-found empty state when the result no longer exists", async () => {
        analysisApiMock.getResult.mockRejectedValue({ error: "ANALYSIS_JOB_NOT_FOUND" });

        renderResultDetailPage();

        expect(
            await screen.findByText("삭제되었거나 존재하지 않는 결과입니다.")
        ).toBeInTheDocument();
    });

    it("shows a non-interactive compatibility state for a future result schema", async () => {
        analysisApiMock.getResult.mockRejectedValue({
            error: "UNSUPPORTED_RESULT_SCHEMA",
            message: "이 분석 결과는 현재 화면보다 새로운 형식(v2)입니다.",
        });

        renderResultDetailPage();

        expect(
            await screen.findByText("지원하지 않는 결과 형식입니다.")
        ).toBeInTheDocument();
        expect(
            screen.getByText("이 분석 결과는 현재 화면보다 새로운 형식(v2)입니다.")
        ).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "삭제" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "분석 재시도" })).not.toBeInTheDocument();
        expect(screen.getByRole("link", { name: "목록으로 이동" }))
            .toHaveAttribute("href", "/results");
    });

    it("shows a safe error state when result schema metadata is invalid", async () => {
        analysisApiMock.getResult.mockRejectedValue({
            error: "INVALID_RESULT_SCHEMA",
            message: "분석 결과의 버전 정보가 서로 일치하지 않습니다.",
        });

        renderResultDetailPage();

        expect(
            await screen.findByText("결과 형식을 확인할 수 없습니다.")
        ).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "삭제" })).not.toBeInTheDocument();
    });

    it("shows a generic error message and a retry button for unrecognized load failures", async () => {
        analysisApiMock.getResult.mockRejectedValue({ message: "네트워크 오류" });

        renderResultDetailPage();

        expect(await screen.findByText("네트워크 오류")).toBeInTheDocument();

        const retryButton = screen.getByRole("button", { name: "다시 불러오기" });
        analysisApiMock.getResult.mockResolvedValue(createCompletedResult());
        fireEvent.click(retryButton);

        await waitFor(() => {
            expect(analysisApiMock.getResult).toHaveBeenCalledTimes(2);
        });
    });

    it("retries a failed job without forcing external AI options back on", async () => {
        const failedResult = createCompletedResult();
        failedResult.data.result.status = "FAILED";
        analysisApiMock.getResult.mockResolvedValue(failedResult);
        analysisApiMock.retryAnalysis.mockResolvedValue({ data: {} });
        analysisApiMock.getAnalysisStatus.mockResolvedValue({
            data: {
                jobId: "job-print-test",
                status: "QUEUED",
                statusDescription: "분석 대기 중",
                failReason: null,
            },
        });

        renderResultDetailPage();

        fireEvent.click(await screen.findByRole("button", { name: "분석 재시도" }));

        await waitFor(() => {
            expect(analysisApiMock.retryAnalysis).toHaveBeenCalledWith("job-print-test");
        });
    });

    // 폴링 중 분석이 실패로 끝나면, 최신 결과를 다시 불러온 뒤 실패 사유를 보여줘야 한다.
    it("reloads the result and shows the fail reason when polling detects a FAILED status", async () => {
        vi.useFakeTimers();

        const runningResult = createCompletedResult();
        runningResult.data.result.status = "QUEUED";

        const failedResult = createCompletedResult();
        failedResult.data.result.status = "FAILED";
        failedResult.data.result.failReason = "분석 엔진 오류";

        analysisApiMock.getResult
            .mockResolvedValueOnce(runningResult)
            .mockResolvedValueOnce(failedResult);
        analysisApiMock.getAnalysisStatus.mockResolvedValue({
            data: {
                jobId: "job-print-test",
                status: "FAILED",
                statusDescription: "분석 실패",
                failReason: "분석 엔진 오류",
            },
        });

        renderResultDetailPage();

        await act(async () => {
            await vi.advanceTimersByTimeAsync(0);
        });
        await act(async () => {
            await vi.advanceTimersByTimeAsync(1500);
        });

        expect(analysisApiMock.getResult).toHaveBeenCalledTimes(2);
        expect(screen.getByText("분석 엔진 오류")).toBeInTheDocument();
    });

    // 폴링 중 취소되면, 최신 결과를 다시 불러오고 이전 오류 메시지를 지워야 한다.
    it("reloads the result and clears the error when polling detects a CANCELLED status", async () => {
        vi.useFakeTimers();

        const runningResult = createCompletedResult();
        runningResult.data.result.status = "QUEUED";

        const cancelledResult = createCompletedResult();
        cancelledResult.data.result.status = "CANCELLED";

        analysisApiMock.getResult
            .mockResolvedValueOnce(runningResult)
            .mockResolvedValueOnce(cancelledResult);
        analysisApiMock.getAnalysisStatus.mockResolvedValue({
            data: {
                jobId: "job-print-test",
                status: "CANCELLED",
                statusDescription: "분석 취소됨",
                failReason: null,
            },
        });

        renderResultDetailPage();

        await act(async () => {
            await vi.advanceTimersByTimeAsync(0);
        });
        await act(async () => {
            await vi.advanceTimersByTimeAsync(1500);
        });

        expect(analysisApiMock.getResult).toHaveBeenCalledTimes(2);
        expect(
            screen.getByText("분석이 취소되었습니다. 필요하면 다시 시도할 수 있습니다.")
        ).toBeInTheDocument();
    });

});
