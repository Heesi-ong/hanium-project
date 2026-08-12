import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";

import ResultListPage from "./ResultListPage";

const analysisApiMock = vi.hoisted(() => ({
    deleteResult: vi.fn(),
    getResults: vi.fn(),
    updateResultMemo: vi.fn(),
}));

vi.mock("../api/analysisApi", () => ({
    deleteResult: analysisApiMock.deleteResult,
    getResults: analysisApiMock.getResults,
    updateResultMemo: analysisApiMock.updateResultMemo,
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

// 목록 화면의 문의용 ID 고급 검색은 기본적으로 접혀 있어, 그 안의 입력을 찾기 전에
// 먼저 펼쳐야 합니다. jsdom은 실제 브라우저와 달리
// <summary> 클릭 시 <details>의 open을 자동으로 토글하지 않으므로, CollapsibleDetails
// 자체 테스트와 동일하게 open 속성 변경 + toggle 이벤트를 직접 시뮬레이션합니다.
function openAdvancedFilters(container) {
    const details = container.querySelector(".inquiry-id-details");
    details.open = true;
    fireEvent(details, new Event("toggle"));
}

describe("ResultListPage", () => {
    beforeEach(() => {
        analysisApiMock.deleteResult.mockReset();
        analysisApiMock.getResults.mockReset();
        analysisApiMock.updateResultMemo.mockReset();
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
                            improvements: ["시선 처리를 더 자연스럽게 개선해보세요."],
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

    it("limits the default card to filename, date, score, improvement point and status", async () => {
        renderResultListPage();

        expect(await screen.findByText("presentation.mp4")).toBeInTheDocument();
        expect(screen.getAllByText("분석 완료").length).toBeGreaterThanOrEqual(1);
        // 오전/오후 vs AM/PM 표기는 실행 환경의 ICU 데이터에 따라 달라질 수 있어(로컬 macOS와
        // GitHub Actions 러너에서 다르게 관찰됨, 2026-08-12) 날짜/시간 값만 고정 검증합니다.
        expect(
            screen.getByText(/^2026\. 07\. 15\. (오전|AM) 09:00$/)
        ).toBeInTheDocument();
        expect(
            screen.getByText("개선 포인트: 시선 처리를 더 자연스럽게 개선해보세요.")
        ).toBeInTheDocument();

        // 생성 방식 배지는 더 이상 목록 카드에 표시되지 않고 상세 화면의 "분석 정보"
        // 접힘 영역으로 옮겨졌습니다(P1-04).
        expect(screen.queryByText("실제 OpenAI")).not.toBeInTheDocument();
        expect(screen.queryByText("샘플 시각 분석")).not.toBeInTheDocument();
    });

    it("keeps only the support-oriented jobId search behind an advanced panel", async () => {
        const { container } = renderResultListPage();

        await screen.findByText("presentation.mp4");

        expect(screen.queryByText("OpenAI Mock")).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "REAL" })).not.toBeInTheDocument();

        openAdvancedFilters(container);

        expect(screen.getByText("고급 검색 — 문의용 ID")).toBeInTheDocument();
        expect(
            screen.getByPlaceholderText("문의 시 안내받은 결과 ID(jobId)로 검색")
        ).toBeInTheDocument();
    });

    it("searches only by filename or memo through the basic search box", async () => {
        renderResultListPage();

        await screen.findByText("presentation.mp4");

        fireEvent.change(screen.getByPlaceholderText("파일명 또는 메모 검색"), {
            target: { value: "demo" },
        });

        await waitFor(() => {
            expect(screen.getByText("demo.mp4")).toBeInTheDocument();
            expect(screen.queryByText("presentation.mp4")).not.toBeInTheDocument();
        });
    });

    it("searches by jobId only through the advanced search", async () => {
        const { container } = renderResultListPage();

        expect(await screen.findByText("pipeline-openai.mp4")).toBeInTheDocument();

        openAdvancedFilters(container);
        fireEvent.change(
            screen.getByPlaceholderText("문의 시 안내받은 결과 ID(jobId)로 검색"),
            {
                target: {
                    value: "job-list-pipeline-openai",
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

    it("edits a result's memo and shows it as the card title instead of the filename", async () => {
        analysisApiMock.updateResultMemo.mockResolvedValue({ success: true });

        renderResultListPage();

        await screen.findByText("presentation.mp4");

        const card = screen.getByText("presentation.mp4").closest("article");
        fireEvent.click(within(card).getByRole("button", { name: "메모 편집" }));

        fireEvent.change(within(card).getByPlaceholderText("예: 1차 리허설, 발표 대회 최종본"), {
            target: { value: "1차 리허설" },
        });
        fireEvent.click(within(card).getByRole("button", { name: "저장" }));

        await waitFor(() => {
            expect(analysisApiMock.updateResultMemo).toHaveBeenCalledWith(
                "job-list-video-llm",
                "1차 리허설"
            );
        });
        expect(await screen.findByText("1차 리허설")).toBeInTheDocument();
        expect(screen.queryByText("presentation.mp4")).not.toBeInTheDocument();
    });

    it("cancels memo editing without calling the API", async () => {
        renderResultListPage();

        await screen.findByText("presentation.mp4");

        const card = screen.getByText("presentation.mp4").closest("article");
        fireEvent.click(within(card).getByRole("button", { name: "메모 편집" }));
        fireEvent.change(within(card).getByPlaceholderText("예: 1차 리허설, 발표 대회 최종본"), {
            target: { value: "저장 안 할 메모" },
        });
        fireEvent.click(within(card).getByRole("button", { name: "취소" }));

        expect(analysisApiMock.updateResultMemo).not.toHaveBeenCalled();
        expect(screen.getByText("presentation.mp4")).toBeInTheDocument();
    });

    it("shows an error message when saving the memo fails", async () => {
        analysisApiMock.updateResultMemo.mockRejectedValue({
            message: "메모 저장 중 오류가 발생했습니다.",
        });

        renderResultListPage();

        await screen.findByText("presentation.mp4");

        const card = screen.getByText("presentation.mp4").closest("article");
        fireEvent.click(within(card).getByRole("button", { name: "메모 편집" }));
        fireEvent.change(within(card).getByPlaceholderText("예: 1차 리허설, 발표 대회 최종본"), {
            target: { value: "실패할 메모" },
        });
        fireEvent.click(within(card).getByRole("button", { name: "저장" }));

        expect(
            await screen.findByText("메모 저장 중 오류가 발생했습니다.")
        ).toBeInTheDocument();
        // 저장이 실패하면 편집 폼이 그대로 열려 있어야 합니다(입력값도 유지된 채로).
        expect(
            within(card).getByPlaceholderText("예: 1차 리허설, 발표 대회 최종본")
        ).toHaveValue("실패할 메모");
    });

    it("shows only guidance and an upload CTA when there are no results", async () => {
        analysisApiMock.getResults.mockResolvedValue({
            data: { content: [], last: true },
        });

        renderResultListPage();

        expect(await screen.findByText("아직 분석 결과가 없습니다.")).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "첫 영상 업로드하기" })).toHaveAttribute(
            "href",
            "/upload"
        );
        expect(screen.getByRole("link", { name: "홈에서 샘플 지표 보기" })).toHaveAttribute(
            "href",
            "/"
        );

        // 요약 카드, 추이 차트, 필터/정렬/비교 UI는 결과가 없으면 전혀 노출되지 않습니다.
        expect(screen.queryByText("전체 결과")).not.toBeInTheDocument();
        expect(screen.queryByTestId("score-trend-chart")).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "결과 비교" })).not.toBeInTheDocument();
    });

    it("hides the summary grid, trend chart, and comparison controls when there is only one result", async () => {
        analysisApiMock.getResults.mockResolvedValue({
            data: {
                content: [
                    {
                        jobId: "job-list-only-one",
                        status: "COMPLETED",
                        statusDescription: "분석 완료",
                        fileName: "only-one.mp4",
                        createdAt: "2026-07-15T09:00:00",
                        scoreSummary: { totalScore: 77, level: "B" },
                        feedback: { improvements: ["발음 속도를 조금 늦춰보세요."] },
                    },
                ],
                last: true,
            },
        });

        renderResultListPage();

        expect(await screen.findByText("only-one.mp4")).toBeInTheDocument();
        expect(screen.getByText("총 1개")).toBeInTheDocument();

        expect(screen.queryByText("전체 결과")).not.toBeInTheDocument();
        expect(screen.queryByTestId("score-trend-chart")).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "결과 비교" })).not.toBeInTheDocument();
        expect(
            screen.queryByText("고급 검색 — 문의용 ID")
        ).not.toBeInTheDocument();
    });

    it("shows a retry state instead of an empty-account state when the initial request fails", async () => {
        analysisApiMock.getResults.mockRejectedValue({
            message: "결과 서버에 일시적으로 연결할 수 없습니다.",
        });

        renderResultListPage();

        expect(
            await screen.findByText("결과 목록을 불러오지 못했습니다.")
        ).toBeInTheDocument();
        expect(screen.getByText("결과 서버에 일시적으로 연결할 수 없습니다.")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "다시 시도" })).toBeInTheDocument();
        expect(screen.queryByText("아직 분석 결과가 없습니다.")).not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "첫 영상 업로드하기" })).not.toBeInTheDocument();
    });

    it("uses the server totalElements instead of the currently loaded page length", async () => {
        analysisApiMock.getResults.mockResolvedValue({
            data: {
                content: [
                    {
                        jobId: "job-list-page-one",
                        status: "COMPLETED",
                        statusDescription: "분석 완료",
                        fileName: "page-one.mp4",
                        createdAt: "2026-07-15T09:00:00",
                        scoreSummary: { totalScore: 77, level: "B" },
                    },
                ],
                totalElements: 3,
                last: false,
            },
        });

        renderResultListPage();

        expect(await screen.findByText("page-one.mp4")).toBeInTheDocument();
        expect(screen.getByText("총 3개")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "결과 비교" })).not.toBeInTheDocument();
        expect(screen.getByRole("button", { name: "더 보기" })).toBeInTheDocument();
    });

    it("shows an unavailable score as neutral and keeps it last for score sorting", async () => {
        analysisApiMock.getResults.mockResolvedValue({
            data: {
                content: [
                    {
                        jobId: "job-list-no-score",
                        status: "FAILED",
                        statusDescription: "분석 실패",
                        fileName: "failed.mp4",
                        createdAt: "2026-07-15T11:00:00",
                        scoreSummary: { totalScore: null },
                    },
                    {
                        jobId: "job-list-low-score",
                        status: "COMPLETED",
                        statusDescription: "분석 완료",
                        fileName: "scored.mp4",
                        createdAt: "2026-07-15T10:00:00",
                        scoreSummary: { totalScore: 20 },
                    },
                ],
                totalElements: 2,
                last: true,
            },
        });

        renderResultListPage();

        const failedCard = (await screen.findByText("failed.mp4")).closest("article");
        expect(within(failedCard).getByText("-")).toHaveClass("score-muted");

        fireEvent.change(screen.getByRole("combobox"), {
            target: { value: "SCORE_ASC" },
        });

        const cards = screen.getAllByRole("article").filter((article) =>
            article.classList.contains("result-card")
        );
        expect(within(cards[0]).getByText("scored.mp4")).toBeInTheDocument();
        expect(within(cards[1]).getByText("failed.mp4")).toBeInTheDocument();
    });
});
