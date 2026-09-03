import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import ScoreTrendChart from "./ScoreTrendChart";

vi.mock("react-chartjs-2", () => ({
    Line: (props) => (
        <div
            data-testid="score-trend-line"
            data-chart-data={JSON.stringify(props.data)}
        />
    ),
}));

describe("ScoreTrendChart", () => {
    it("renders completed score results in chronological order and ignores failed results", () => {
        render(
            <ScoreTrendChart
                results={[
                    {
                        status: "COMPLETED",
                        createdAt: "2026-07-03T10:30:00",
                        scoreSummary: {
                            totalScore: 82,
                            postureScore: 84,
                            speechScore: 80,
                            gestureScore: 76,
                        },
                    },
                    {
                        status: "FAILED",
                        createdAt: "2026-07-02T09:00:00",
                        scoreSummary: {
                            totalScore: 10,
                        },
                    },
                    {
                        status: "COMPLETED",
                        createdAt: "2026-07-01T08:15:00",
                        scoreSummary: {
                            totalScore: 65,
                            postureScore: 60,
                            speechScore: 70,
                            gestureScore: 55,
                        },
                    },
                ]}
            />
        );

        const chartData = JSON.parse(
            screen.getByTestId("score-trend-line").dataset.chartData
        );

        expect(chartData.labels).toEqual([
            "2026.07.01",
            "2026.07.03",
        ]);
        expect(chartData.datasets[0].data).toEqual([65, 82]);
        expect(chartData.datasets.map((dataset) => dataset.label)).toEqual([
            "총점",
            "자세",
            "음성",
            "제스처",
        ]);
        expect(chartData.datasets[1].data).toEqual([60, 84]);
        expect(screen.getByLabelText("첫 회차 대비 최근 변화")).toHaveTextContent("+17점");
    });

    it("allows score series to be hidden while keeping at least one visible", () => {
        render(
            <ScoreTrendChart
                results={[
                    {
                        status: "COMPLETED",
                        createdAt: "2026-07-01T08:15:00",
                        scoreSummary: {
                            totalScore: 65,
                            postureScore: 60,
                            speechScore: 70,
                            gestureScore: 55,
                        },
                    },
                    {
                        status: "COMPLETED",
                        createdAt: "2026-07-03T10:30:00",
                        scoreSummary: {
                            totalScore: 82,
                            postureScore: 84,
                            speechScore: 80,
                            gestureScore: 76,
                        },
                    },
                ]}
            />
        );

        fireEvent.click(screen.getByRole("button", { name: "음성" }));
        const chartData = JSON.parse(
            screen.getByTestId("score-trend-line").dataset.chartData
        );
        expect(chartData.datasets.map((dataset) => dataset.label)).toEqual([
            "총점",
            "자세",
            "제스처",
        ]);
        expect(screen.getByRole("button", { name: "음성" })).toHaveAttribute(
            "aria-pressed",
            "false"
        );
    });

    it("shows guidance instead of a chart when fewer than two valid results exist", () => {
        render(
            <ScoreTrendChart
                results={[
                    {
                        status: "COMPLETED",
                        createdAt: "2026-07-01T08:15:00",
                        scoreSummary: {
                            totalScore: 65,
                        },
                    },
                    {
                        status: "FAILED",
                        createdAt: "2026-07-02T09:00:00",
                        scoreSummary: {
                            totalScore: 10,
                        },
                    },
                ]}
            />
        );

        expect(
            screen.getByText("추이를 보려면 완료된 분석이 2개 이상 필요합니다.")
        ).toBeInTheDocument();
        expect(screen.queryByTestId("score-trend-line")).not.toBeInTheDocument();
    });
});
