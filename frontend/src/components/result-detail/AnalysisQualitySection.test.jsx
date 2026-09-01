import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import AnalysisQualitySection from "./AnalysisQualitySection";

describe("AnalysisQualitySection", () => {
    it("shows quality evidence and retake guidance", () => {
        render(
            <AnalysisQualitySection
                analysisQuality={{
                    available: true,
                    lowConfidence: true,
                    poseDetectionRate: 0.42,
                    penaltyApplied: 8,
                    formulaVersion: "weighted-v2",
                    penaltyReasons: [
                        "자세 검출률이 50% 미만입니다.",
                        "STT에 실패해 음성 추정값을 사용했습니다.",
                    ],
                }}
                scoreExplanation={{}}
            />
        );

        expect(screen.getByText("낮은 신뢰도")).toBeInTheDocument();
        expect(screen.getByText("42%")).toBeInTheDocument();
        expect(screen.getByText("8점")).toBeInTheDocument();
        expect(screen.getByText(/상반신과 양쪽 어깨/)).toBeInTheDocument();
        expect(screen.getByText(/주변 소음을 줄이고/)).toBeInTheDocument();
    });

    it("does not present missing metadata as zero quality", () => {
        render(<AnalysisQualitySection analysisQuality={{}} scoreExplanation={{}} />);

        expect(screen.getByText("품질 정보 없음")).toBeInTheDocument();
        expect(screen.getByText(/새로 분석한 결과부터 표시/)).toBeInTheDocument();
        expect(screen.queryByText("0%")).not.toBeInTheDocument();
    });
});
