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
    it.each([
        ["REAL", "실제 OpenAI API가 생성한 응답 원문입니다."],
        ["FALLBACK", "외부 AI 호출 실패 후 내부 대체 응답"],
        ["MOCK", "외부 AI를 호출하지 않고 내부 Mock 로직"],
        ["SKIPPED", "사용자 설정에 따라 외부 AI 피드백 생성을 건너뛴"],
    ])("explains the feedback source for %s mode", (generationMode, description) => {
        renderFeedbackSection(
            { model: { generationMode: "SKIPPED" } },
            {
                feedback: {
                    ...baseFeedback,
                    generationMode,
                },
            }
        );

        const feedbackCard = screen.getByRole("article", { name: "종합 피드백" });
        expect(within(feedbackCard).getByText(description, { exact: false }))
            .toBeInTheDocument();
        expect(within(feedbackCard).getByLabelText("종합 피드백 응답 원문"))
            .toHaveTextContent("전체 피드백");
    });

    it("shows separate strength and improvement counts without changing their content", () => {
        renderFeedbackSection(
            { model: { generationMode: "REAL" } },
            {
                feedback: {
                    generationMode: "REAL",
                    overall: "전체 피드백",
                    strengths: ["발표 구조가 명확합니다.", "시선이 안정적입니다."],
                    improvements: ["결론의 말하기 속도를 조절하세요."],
                },
            }
        );

        const feedbackCard = screen.getByRole("article", { name: "종합 피드백" });
        const strengthSection = within(feedbackCard).getByRole("heading", { name: "강점" })
            .closest("section");
        const improvementSection = within(feedbackCard).getByRole("heading", { name: "개선점" })
            .closest("section");

        expect(within(strengthSection).getByText("2개")).toBeInTheDocument();
        expect(within(strengthSection).getByText("발표 구조가 명확합니다."))
            .toBeInTheDocument();
        expect(within(improvementSection).getByText("1개")).toBeInTheDocument();
        expect(within(improvementSection).getByText("결론의 말하기 속도를 조절하세요."))
            .toBeInTheDocument();
    });

    it("hides untrusted global summary and renders only supported observation categories", () => {
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
                gesture: [
                    {
                        startSec: 0,
                        endSec: 10,
                        label: "balanced",
                    },
                ],
            },
        });

        const visualAnalysisCard = getVisualAnalysisCard();

        expect(within(visualAnalysisCard).queryByText("전체 인상")).not.toBeInTheDocument();
        expect(within(visualAnalysisCard).queryByText("시선과 자세가 안정적입니다.")).not.toBeInTheDocument();
        expect(within(visualAnalysisCard).getByText("제스처")).toBeInTheDocument();
        expect(within(visualAnalysisCard).getByText("balanced")).toBeInTheDocument();
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
                gesture: [
                    {
                        startSec: 12,
                        endSec: 18,
                        label: "balanced",
                        description: "중간 구간에서 제스처가 확인되었습니다.",
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
        expect(within(card).getByText("제스처")).toBeInTheDocument();
        expect(within(card).getByText("자세")).toBeInTheDocument();
        expect(within(card).getByText("0:12–0:18")).toBeInTheDocument();
        expect(
            within(card).getByText("중간 구간에서 제스처가 확인되었습니다.")
        ).toBeInTheDocument();
        expect(within(card).getByText("신뢰도 74%")).toBeInTheDocument();
        expect(within(card).getByText("신뢰도 81%")).toBeInTheDocument();
        expect(within(card).getByText("2개 관찰")).toBeInTheDocument();
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
                        gesture: [
                            {
                                startSec: 12,
                                endSec: 18,
                                label: "balanced",
                                description: "제스처가 확인되었습니다.",
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
                gesture: [
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
        expect(within(visualAnalysisCard).getByText(/시각 분석 출처/))
            .toBeInTheDocument();
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
