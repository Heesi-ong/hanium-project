import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import FeedbackSection from "./FeedbackSection";

const baseFeedback = {
    overall: "전체 피드백",
    strengths: ["강점 1"],
    improvements: ["개선점 1"],
};

function renderFeedbackSection(visualAnalysis) {
    return render(
        <FeedbackSection feedback={baseFeedback} visualAnalysis={visualAnalysis} />
    );
}

function getVisualAnalysisCard() {
    return screen.getByRole("heading", {
        name: "시각 분석",
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
        expect(
            within(visualAnalysisCard).getByText("세부 관찰 데이터는 준비 중입니다.")
        ).toBeInTheDocument();
        expect(within(visualAnalysisCard).queryByText("eyeContact")).not.toBeInTheDocument();
    });

    it.each([
        ["REAL", "실제 영상 AI"],
        ["FALLBACK", "영상 AI 실패 후 Mock 대체"],
        ["MOCK", "Mock 영상 분석"],
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
    });

    it("renders an empty state when visual analysis is missing", () => {
        renderFeedbackSection(undefined);

        expect(screen.getByText("영상 분석 데이터가 아직 없습니다.")).toBeInTheDocument();
        expect(screen.queryByText("생성 방식")).not.toBeInTheDocument();
    });
});
