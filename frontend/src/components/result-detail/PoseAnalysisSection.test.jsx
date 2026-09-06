import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import PoseAnalysisSection from "./PoseAnalysisSection";

function renderMetricCard(label, value, description) {
    return (
        <article className="metric-card">
            <span>{label}</span>
            <strong>{value ?? 0}</strong>
            <p>{description}</p>
        </article>
    );
}

function openFrameDetails(container) {
    const details = container.querySelector("details");
    details.open = true;
    fireEvent(details, new Event("toggle"));
}

describe("PoseAnalysisSection", () => {
    it("renders pose metrics and analysis method", () => {
        render(
            <PoseAnalysisSection
                renderMetricCard={renderMetricCard}
                poseInfo={{
                    postureScore: 88,
                    shoulderBalanceScore: 90,
                    detectionRate: 0.95,
                    detectedFrameCount: 19,
                    totalFrameCount: 20,
                    averageShoulderDiff: 0.0123,
                    analysisMethod: "mediapipe_tasks_pose_landmarker",
                }}
                poseFrameResults={[]}
            />
        );

        expect(screen.getByText("자세 점수")).toBeInTheDocument();
        expect(screen.getByText("88")).toBeInTheDocument();
        expect(screen.getByText("자세 검출률")).toBeInTheDocument();
        expect(screen.getByText("95%")).toBeInTheDocument();
        expect(screen.getByText("19 / 20")).toBeInTheDocument();
        expect(screen.getByText("MediaPipe Tasks PoseLandmarker")).toBeInTheDocument();
        expect(screen.getByText("핵심 자세 점수")).toBeInTheDocument();
        expect(screen.getByText("검출 근거")).toBeInTheDocument();
        expect(screen.getByText("19 / 20 프레임 검출")).toBeInTheDocument();
    });

    it("marks undetected frames in the collapsible detail table", () => {
        const { container } = render(
            <PoseAnalysisSection
                renderMetricCard={renderMetricCard}
                poseInfo={{}}
                poseFrameResults={[
                    { sequence: 1, timestampSec: 0, poseDetected: true, shoulderDiff: 0.01, shoulderBalanceScore: 90 },
                    { sequence: 2, timestampSec: 1, poseDetected: false, shoulderDiff: null, shoulderBalanceScore: 0 },
                ]}
            />
        );

        openFrameDetails(container);

        expect(container.querySelector(".result-pose-table-wrap .mini-badge.success")).toHaveTextContent("검출");
        expect(container.querySelector(".result-pose-table-wrap .mini-badge.muted")).toHaveTextContent("미검출");
        expect(screen.getByRole("table", { name: "프레임별 자세 검출과 어깨 균형 측정값" })).toBeInTheDocument();
    });

    it("shows guidance when there are no frame results", () => {
        render(
            <PoseAnalysisSection
                renderMetricCard={renderMetricCard}
                poseInfo={{}}
                poseFrameResults={[]}
            />
        );

        expect(screen.getByText("표시할 프레임별 자세 분석 결과가 없습니다.")).toBeInTheDocument();
        expect(screen.getByText("프레임 정보 없음")).toBeInTheDocument();
    });
});
