import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import ScoreCompareBarChart from "./ScoreCompareBarChart";

vi.mock("motion/react", () => ({ useReducedMotion: () => true }));

vi.mock("react-chartjs-2", () => ({
    Bar: (props) => (
        <div
            data-testid="score-compare-bar"
            data-chart-data={JSON.stringify(props.data)}
            role={props.role}
            aria-label={props["aria-label"]}
            data-animation={String(props.options.animation)}
            data-plugin-ids={props.plugins.map(plugin => plugin.id).join(",")}
        />
    ),
}));

describe("ScoreCompareBarChart", () => {
    it("builds two datasets from each result's scoreSummary in a fixed field order", () => {
        render(
            <ScoreCompareBarChart
                resultA={{
                    scoreSummary: {
                        totalScore: 65,
                        postureScore: 70,
                        gazeScore: 60,
                        speechScore: 55,
                        gestureScore: 80,
                        expressionScore: 50,
                    },
                }}
                resultB={{
                    scoreSummary: {
                        totalScore: 82,
                        postureScore: 75,
                        gazeScore: 88,
                        speechScore: 60,
                        gestureScore: 85,
                        expressionScore: 90,
                    },
                }}
                labelA="1차 연습"
                labelB="2차 연습"
            />
        );

        const chartData = JSON.parse(
            screen.getByTestId("score-compare-bar").dataset.chartData
        );

        expect(chartData.labels).toEqual(["총점", "자세", "음성", "제스처"]);
        expect(chartData.datasets[0].label).toBe("1차 연습");
        expect(chartData.datasets[0].data).toEqual([65, 70, 55, 80]);
        expect(chartData.datasets[1].label).toBe("2차 연습");
        expect(chartData.datasets[1].data).toEqual([82, 75, 60, 85]);
        expect(screen.getByRole("img", { name: "A 기준 결과와 B 비교 결과의 항목별 점수 차트" }))
            .toBeInTheDocument();
        expect(screen.getByTestId("score-compare-bar")).toHaveAttribute("data-animation", "false");
        expect(screen.getByTestId("score-compare-bar")).toHaveAttribute("data-plugin-ids", "scoreCompareValueLabels");
    });

    it("toggles each dataset through named buttons without changing score values", () => {
        render(<ScoreCompareBarChart resultA={{ scoreSummary: { totalScore: 0 } }} resultB={{}} labelA="기준 영상" labelB="비교 영상" />);
        const toggleA = screen.getByRole("button", { name: /A · 기준 영상/ });
        expect(toggleA).toHaveAttribute("aria-pressed", "true");
        fireEvent.click(toggleA);
        expect(toggleA).toHaveAttribute("aria-pressed", "false");
        const data = JSON.parse(screen.getByTestId("score-compare-bar").dataset.chartData);
        expect(data.datasets[0].hidden).toBe(true);
        expect(data.datasets[0].data).toEqual([0, null, null, null]);
        expect(data.datasets[1].hidden).toBe(false);
        fireEvent.click(toggleA);
        expect(toggleA).toHaveAttribute("aria-pressed", "true");
    });

    it("keeps missing score fields distinct from a real zero score", () => {
        render(
            <ScoreCompareBarChart
                resultA={{ scoreSummary: { totalScore: 0 } }}
                resultB={{
                    dataIssue: "RESULT_DATA_UNAVAILABLE",
                    scoreSummary: {
                        totalScore: 0,
                        postureScore: 0,
                        gazeScore: 0,
                        speechScore: 0,
                        gestureScore: 0,
                        expressionScore: 0,
                    },
                }}
                labelA="A"
                labelB="B"
            />
        );

        const chartData = JSON.parse(
            screen.getByTestId("score-compare-bar").dataset.chartData
        );

        expect(chartData.datasets[0].data).toEqual([0, null, null, null]);
        expect(chartData.datasets[1].data).toEqual([null, null, null, null]);
    });
});
