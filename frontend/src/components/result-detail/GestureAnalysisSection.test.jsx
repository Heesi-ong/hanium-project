import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import GestureAnalysisSection from "./GestureAnalysisSection";

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

describe("GestureAnalysisSection", () => {
    it("renders gesture metrics and analysis method", () => {
        render(
            <GestureAnalysisSection
                renderMetricCard={renderMetricCard}
                gestureInfo={{
                    gestureScore: 70,
                    gestureRate: 0.42,
                    gestureFrameCount: 8,
                    totalFrameCount: 20,
                    handVisibilityRate: 0.9,
                    averageWristMovement: 0.0456,
                    analysisMethod: "mediapipe_tasks_pose_landmarker_wrist_elbow_based",
                }}
                gestureFrameResults={[]}
            />
        );

        expect(screen.getByText("제스처 점수")).toBeInTheDocument();
        expect(screen.getByText("70")).toBeInTheDocument();
        expect(screen.getByText("제스처 비율")).toBeInTheDocument();
        expect(screen.getByText("42%")).toBeInTheDocument();
        expect(screen.getByText("8 / 20")).toBeInTheDocument();
        expect(screen.getByText("MediaPipe Tasks 팔/손목 기반")).toBeInTheDocument();
        expect(screen.getByText("핵심 제스처 점수")).toBeInTheDocument();
        expect(screen.getByText("검출 근거")).toBeInTheDocument();
        expect(screen.getByText("8 / 20 프레임 감지")).toBeInTheDocument();
    });

    it("shows a note when provided", () => {
        render(
            <GestureAnalysisSection
                renderMetricCard={renderMetricCard}
                gestureInfo={{ note: "손이 프레임 밖으로 나간 구간이 있습니다." }}
                gestureFrameResults={[]}
            />
        );

        expect(screen.getByText("손이 프레임 밖으로 나간 구간이 있습니다.")).toBeInTheDocument();
    });

    it("renders per-frame hand visibility in the collapsible detail table", () => {
        const { container } = render(
            <GestureAnalysisSection
                renderMetricCard={renderMetricCard}
                gestureInfo={{}}
                gestureFrameResults={[
                    {
                        sequence: 1,
                        timestampSec: 0,
                        gestureDetected: true,
                        leftHandVisible: true,
                        rightHandVisible: false,
                        leftHandActive: true,
                        rightHandActive: false,
                        leftWristMovement: 0.02,
                        rightWristMovement: 0,
                    },
                ]}
            />
        );

        openFrameDetails(container);

        expect(screen.getByText("감지")).toBeInTheDocument();
        expect(screen.getAllByText("예").length).toBeGreaterThan(0);
        expect(screen.getAllByText("아니오").length).toBeGreaterThan(0);
        expect(container.querySelectorAll(".result-boolean-signal.yes")).toHaveLength(2);
        expect(container.querySelectorAll(".result-boolean-signal.no")).toHaveLength(2);
        expect(screen.getByRole("table", { name: "프레임별 제스처와 양손 검출 측정값" })).toBeInTheDocument();
    });

    it("shows guidance when there are no frame results", () => {
        render(
            <GestureAnalysisSection
                renderMetricCard={renderMetricCard}
                gestureInfo={{}}
                gestureFrameResults={[]}
            />
        );

        expect(screen.getByText("표시할 프레임별 제스처 분석 결과가 없습니다.")).toBeInTheDocument();
        expect(screen.getByText("프레임 정보 없음")).toBeInTheDocument();
    });
});
