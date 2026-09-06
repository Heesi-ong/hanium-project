import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import PracticeProgressSection from "./PracticeProgressSection";

describe("PracticeProgressSection", () => {
    it("shows the selected goal delta against the baseline", () => {
        render(
            <PracticeProgressSection
                currentJobId="current"
                currentScoreSummary={{ postureScore: 78 }}
                baselineJobId="baseline"
                baselineScoreSummary={{ postureScore: 70 }}
                practiceGoal="POSTURE"
                canStartPractice
                onStartPractice={() => {}}
            />
        );

        expect(screen.getByText("+8점")).toBeInTheDocument();
        expect(screen.getByText("향상")).toBeInTheDocument();
        expect(screen.getByText("자세 목표")).toBeInTheDocument();
        expect(
            screen.getByRole("status", { name: "자세 재연습 점수 비교" })
        ).toBeInTheDocument();
    });

    it("passes a bounded practice context when a goal is selected", () => {
        const onStartPractice = vi.fn();
        render(
            <PracticeProgressSection
                currentJobId="current"
                currentScoreSummary={{}}
                canStartPractice
                onStartPractice={onStartPractice}
            />
        );

        fireEvent.click(screen.getByRole("button", { name: "자세 다시 연습" }));
        expect(onStartPractice).toHaveBeenCalledWith({
            baselineJobId: "current",
            practiceGoal: "POSTURE",
            label: "자세",
        });
    });

    it("shows a neutral label when the target score has not changed", () => {
        render(
            <PracticeProgressSection
                currentJobId="current"
                currentScoreSummary={{ speechScore: 72 }}
                baselineJobId="baseline"
                baselineScoreSummary={{ speechScore: 72 }}
                practiceGoal="SPEECH"
                canStartPractice
                onStartPractice={() => {}}
            />
        );

        expect(screen.getByText("변화 없음")).toBeInTheDocument();
        expect(screen.getByText("0점")).toBeInTheDocument();
    });

    it("explains why practice cannot start from an incomplete result", () => {
        render(
            <PracticeProgressSection
                currentJobId="current"
                currentScoreSummary={{}}
                canStartPractice={false}
                onStartPractice={() => {}}
            />
        );

        expect(screen.getByText("현재 사용 불가")).toBeInTheDocument();
        expect(
            screen.getByText("완료된 정상 결과에서 재연습 목표를 만들 수 있습니다.")
        ).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "자세 다시 연습" })).not.toBeInTheDocument();
    });
});
