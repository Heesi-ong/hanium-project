import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import AnalysisTraceSection from "./AnalysisTraceSection";

describe("AnalysisTraceSection", () => {
    it("renders each step with its label, duration and detail", () => {
        render(
            <AnalysisTraceSection
                analysisTrace={[
                    {
                        stepNo: 2,
                        totalSteps: 9,
                        key: "frame_extract",
                        label: "프레임을 추출하는 중...",
                        durationMs: 450,
                        detail: "프레임 20장 추출",
                    },
                    {
                        stepNo: 5,
                        totalSteps: 9,
                        key: "pose_gesture",
                        label: "자세와 제스처를 분석하는 중...",
                        durationMs: 1234,
                        detail: "포즈 검출 17/20 프레임",
                    },
                ]}
            />
        );

        expect(screen.getByText("프레임을 추출하는 중...")).toBeInTheDocument();
        expect(screen.getByText("450ms")).toBeInTheDocument();
        expect(screen.getByText("프레임 20장 추출")).toBeInTheDocument();

        expect(screen.getByText("자세와 제스처를 분석하는 중...")).toBeInTheDocument();
        expect(screen.getByText("1.2초")).toBeInTheDocument();
        expect(screen.getByText("2/9")).toBeInTheDocument();
        expect(screen.getByText("5/9")).toBeInTheDocument();
    });

    it("renders nothing when there are no steps", () => {
        const { container } = render(<AnalysisTraceSection analysisTrace={[]} />);
        expect(container).toBeEmptyDOMElement();
    });

    it("renders nothing when analysisTrace is not an array", () => {
        const { container } = render(<AnalysisTraceSection analysisTrace={undefined} />);
        expect(container).toBeEmptyDOMElement();
    });
});
