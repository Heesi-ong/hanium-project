import { render, screen } from "@testing-library/react";
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
                        },
                    },
                ]}
            />
        );

        const chartData = JSON.parse(
            screen.getByTestId("score-trend-line").dataset.chartData
        );

        expect(chartData.labels).toEqual([
            "2026.07.01 08:15",
            "2026.07.03 10:30",
        ]);
        expect(chartData.datasets[0].data).toEqual([65, 82]);
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
