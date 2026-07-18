import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import FeedbackSection from "./FeedbackSection";

const baseFeedback = {
    overall: "전체 피드백",
    strengths: ["강점 1"],
    improvements: ["개선점 1"],
};

function renderFeedbackSection(visualAnalysis, options = {}) {
    return render(
        <FeedbackSection
            feedback={options.feedback || baseFeedback}
            visualAnalysis={visualAnalysis}
            pipeline={options.pipeline}
        />
    );
}

function getVisualAnalysisCard() {
    return screen.getByRole("heading", {
        name: "시각 분석 (Video LLM)",
    }).closest("article");
}

describe("FeedbackSection", () => {
    it("renders visual global summary fields with readable labels", () => {
        renderFeedbackSection({
            model: {
                generationMode: "REAL",
            },
            globalSummary: {
                visualDelivery: "시선과 자세가 안정적입니다.",
                mainStrength: "카메라를 꾸준히 응시합니다.",
                mainWeakness: "손동작 변화가 적습니다.",
            },
            observations: {
                eyeContact: [
                    {
                        startSec: 0,
                        endSec: 10,
                        label: "direct",
                    },
                ],
            },
        });

        const visualAnalysisCard = getVisualAnalysisCard();

        expect(within(visualAnalysisCard).getByText("전체 인상")).toBeInTheDocument();
        expect(
            within(visualAnalysisCard).getByText("시선과 자세가 안정적입니다.")
        ).toBeInTheDocument();
        expect(within(visualAnalysisCard).getByText("강점")).toBeInTheDocument();
        expect(
            within(visualAnalysisCard).getByText("카메라를 꾸준히 응시합니다.")
        ).toBeInTheDocument();
        expect(within(visualAnalysisCard).getByText("개선점")).toBeInTheDocument();
        expect(
            within(visualAnalysisCard).getByText("손동작 변화가 적습니다.")
        ).toBeInTheDocument();
        // 관찰 데이터는 원본 카테고리 키(eyeContact)가 아니라 한국어 라벨(시선)로 렌더됩니다.
        expect(within(visualAnalysisCard).getByText("시선")).toBeInTheDocument();
        expect(within(visualAnalysisCard).getByText("direct")).toBeInTheDocument();
        expect(within(visualAnalysisCard).getByText("0:00–0:10")).toBeInTheDocument();
        expect(within(visualAnalysisCard).queryByText("eyeContact")).not.toBeInTheDocument();
    });

    it("renders observation details (time range, label, confidence, description) per category", () => {
        renderFeedbackSection({
            model: { generationMode: "REAL" },
            globalSummary: {
                visualDelivery: "전체 인상",
                mainStrength: "강점",
                mainWeakness: "개선점",
            },
            observations: {
                eyeContact: [
                    {
                        startSec: 12,
                        endSec: 18,
                        label: "looking_down",
                        description: "중간 구간에서 시선이 아래로 이동했습니다.",
                        confidence: 0.74,
                    },
                ],
                posture: [
                    {
                        startSec: 0,
                        endSec: 60,
                        label: "stable",
                        description: "상체 자세가 안정적입니다.",
                        confidence: 0.81,
                    },
                ],
            },
        });

        const card = getVisualAnalysisCard();
        expect(within(card).getByText("시선")).toBeInTheDocument();
        expect(within(card).getByText("자세")).toBeInTheDocument();
        expect(within(card).getByText("0:12–0:18")).toBeInTheDocument();
        expect(
            within(card).getByText("중간 구간에서 시선이 아래로 이동했습니다.")
        ).toBeInTheDocument();
        expect(within(card).getByText("신뢰도 74%")).toBeInTheDocument();
        expect(within(card).getByText("신뢰도 81%")).toBeInTheDocument();
    });

    it("calls onSeekToTime with the observation startSec when the time is clicked", () => {
        const onSeekToTime = vi.fn();
        render(
            <FeedbackSection
                feedback={baseFeedback}
                onSeekToTime={onSeekToTime}
                visualAnalysis={{
                    model: { generationMode: "REAL" },
                    globalSummary: {
                        visualDelivery: "전체 인상",
                        mainStrength: "강점",
                        mainWeakness: "개선점",
                    },
                    observations: {
                        eyeContact: [
                            {
                                startSec: 12,
                                endSec: 18,
                                label: "looking_down",
                                description: "시선이 아래로 이동했습니다.",
                                confidence: 0.7,
                            },
                        ],
                    },
                }}
            />
        );

        const seekButton = screen.getByRole("button", {
            name: /영상을 .* 구간으로 이동/,
        });
        fireEvent.click(seekButton);

        expect(onSeekToTime).toHaveBeenCalledWith(12);
    });

    it("renders observation time as plain text (not a button) without onSeekToTime", () => {
        renderFeedbackSection({
            model: { generationMode: "REAL" },
            globalSummary: {
                visualDelivery: "전체 인상",
                mainStrength: "강점",
                mainWeakness: "개선점",
            },
            observations: {
                eyeContact: [
                    { startSec: 12, endSec: 18, label: "x", description: "d", confidence: 0.7 },
                ],
            },
        });

        const card = getVisualAnalysisCard();
        expect(
            within(card).queryByRole("button", { name: /구간으로 이동/ })
        ).not.toBeInTheDocument();
        expect(within(card).getByText("0:12–0:18")).toBeInTheDocument();
    });

    it("shows an empty observation note when observations are missing", () => {
        renderFeedbackSection({
            model: { generationMode: "REAL" },
            globalSummary: {
                visualDelivery: "전체 인상",
                mainStrength: "강점",
                mainWeakness: "개선점",
            },
        });

        const card = getVisualAnalysisCard();
        expect(
            within(card).getByText("표시할 세부 관찰 데이터가 없습니다.")
        ).toBeInTheDocument();
    });

    it.each([
        ["REAL", "실제 Video LLM"],
        ["FALLBACK", "Video LLM 실패 후 Mock 대체"],
        ["MOCK", "Mock Video LLM 분석"],
        ["SKIPPED", "Video LLM 분석 생략"],
    ])("renders %s generation mode badge", (generationMode, label) => {
        renderFeedbackSection({
            model: {
                generationMode,
            },
            globalSummary: {
                visualDelivery: "전체 인상",
                mainStrength: "강점",
                mainWeakness: "개선점",
            },
        });

        const visualAnalysisCard = getVisualAnalysisCard();
        const badge = within(visualAnalysisCard).getByText(label);

        expect(badge).toHaveClass("mini-badge");
        expect(
            within(visualAnalysisCard).queryByText("분석 방식 알 수 없음")
        ).not.toBeInTheDocument();
    });

    it.each([
        ["MOCK"],
        ["FALLBACK"],
    ])("shows a sample-data warning for %s mode", (generationMode) => {
        renderFeedbackSection({
            model: { generationMode },
            globalSummary: {
                visualDelivery: "전체 인상",
                mainStrength: "강점",
                mainWeakness: "개선점",
            },
        });

        const visualAnalysisCard = getVisualAnalysisCard();
        expect(
            within(visualAnalysisCard).getByText(/예시\(샘플\) 데이터/)
        ).toBeInTheDocument();
    });

    it("does not show a sample-data warning for REAL mode", () => {
        renderFeedbackSection({
            model: { generationMode: "REAL" },
            globalSummary: {
                visualDelivery: "전체 인상",
                mainStrength: "강점",
                mainWeakness: "개선점",
            },
        });

        const visualAnalysisCard = getVisualAnalysisCard();
        expect(
            within(visualAnalysisCard).queryByText(/예시\(샘플\) 데이터/)
        ).not.toBeInTheDocument();
    });

    it("renders an empty state when visual analysis is missing", () => {
        renderFeedbackSection(undefined);

        expect(screen.getByText("영상 분석 데이터가 아직 없습니다.")).toBeInTheDocument();
        expect(screen.queryByText("생성 방식")).not.toBeInTheDocument();
    });

    it("uses pipeline generation metadata when feedback and visual metadata are placeholders", () => {
        renderFeedbackSection(
            {
                model: {
                    generationMode: "UNKNOWN",
                },
                globalSummary: {
                    visualDelivery: "전체 인상",
                    mainStrength: "강점",
                    mainWeakness: "개선점",
                },
            },
            {
                feedback: {
                    ...baseFeedback,
                    generationMode: "UNKNOWN",
                },
                pipeline: {
                    openAiGenerationMode: "REAL",
                    videoLlmGenerationMode: "FALLBACK",
                },
            }
        );

        expect(screen.getByText("실제 AI 응답")).toBeInTheDocument();

        const visualAnalysisCard = getVisualAnalysisCard();
        expect(
            within(visualAnalysisCard).getByText("Video LLM 실패 후 Mock 대체")
        ).toBeInTheDocument();
        expect(
            within(visualAnalysisCard).getByText(/예시\(샘플\) 데이터/)
        ).toBeInTheDocument();
    });
});
