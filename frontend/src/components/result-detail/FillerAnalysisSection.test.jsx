import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import FillerAnalysisSection from "./FillerAnalysisSection";

function renderMetricCard(label, value, description) {
    return (
        <article className="metric-card">
            <span>{label}</span>
            <strong>{value ?? 0}</strong>
            <p>{description}</p>
        </article>
    );
}

function openFillerWordDetails(container) {
    const details = container.querySelector("details");
    details.open = true;
    fireEvent(details, new Event("toggle"));
}

describe("FillerAnalysisSection", () => {
    it("renders filler metrics and analysis method", () => {
        render(
            <FillerAnalysisSection
                renderMetricCard={renderMetricCard}
                fillerInfo={{
                    fillerScore: 76,
                    fillerCount: 5,
                    fillerRatio: 0.04,
                    analysisMethod: "stt_based_analysis",
                }}
                fillerWords={[]}
            />
        );

        expect(screen.getByText("필러 점수")).toBeInTheDocument();
        expect(screen.getByText("76")).toBeInTheDocument();
        expect(screen.getByText("필러 수")).toBeInTheDocument();
        expect(screen.getByText("5개")).toBeInTheDocument();
        expect(screen.getByText("필러 비율")).toBeInTheDocument();
        expect(screen.getByText("4%")).toBeInTheDocument();
        expect(screen.getByText("5회 감지")).toBeInTheDocument();
    });

    it("shows a note when provided", () => {
        render(
            <FillerAnalysisSection
                renderMetricCard={renderMetricCard}
                fillerInfo={{ note: "간투사 사전이 갱신되었습니다." }}
                fillerWords={[]}
            />
        );

        expect(screen.getByText("간투사 사전이 갱신되었습니다.")).toBeInTheDocument();
    });

    it("lists detected filler words inside the collapsible detail table", () => {
        const { container } = render(
            <FillerAnalysisSection
                renderMetricCard={renderMetricCard}
                fillerInfo={{}}
                fillerWords={[
                    { word: "음", count: 3 },
                    { word: "그", count: 2 },
                ]}
            />
        );

        openFillerWordDetails(container);

        expect(screen.getByText("음")).toBeInTheDocument();
        expect(screen.getByText("그")).toBeInTheDocument();
        expect(screen.getByText("감지된 필러 표현 (2종) — 자세히 보기")).toBeInTheDocument();
    });

    it("shows guidance when there are no detected filler words", () => {
        render(
            <FillerAnalysisSection
                renderMetricCard={renderMetricCard}
                fillerInfo={{}}
                fillerWords={[]}
            />
        );

        expect(screen.getByText("감지된 필러 표현이 없습니다.")).toBeInTheDocument();
        expect(screen.getByText("감지된 표현 없음")).toBeInTheDocument();
    });
});
