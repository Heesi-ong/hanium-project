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
        expect(screen.getByText("자세")).toBeInTheDocument();
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
});
