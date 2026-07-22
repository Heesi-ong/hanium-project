import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import ScoreCompareBarChart from "./ScoreCompareBarChart";

vi.mock("react-chartjs-2", () => ({
    Bar: (props) => (
        <div
            data-testid="score-compare-bar"
            data-chart-data={JSON.stringify(props.data)}
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

        expect(chartData.labels).toEqual(["총점", "자세", "시선", "음성", "제스처", "표정"]);
        expect(chartData.datasets[0].label).toBe("1차 연습");
        expect(chartData.datasets[0].data).toEqual([65, 70, 60, 55, 80, 50]);
        expect(chartData.datasets[1].label).toBe("2차 연습");
        expect(chartData.datasets[1].data).toEqual([82, 75, 88, 60, 85, 90]);
    });

    it("treats missing score fields as zero instead of throwing", () => {
        render(
            <ScoreCompareBarChart
                resultA={{ scoreSummary: {} }}
                resultB={{}}
                labelA="A"
                labelB="B"
            />
        );

        const chartData = JSON.parse(
            screen.getByTestId("score-compare-bar").dataset.chartData
        );

        expect(chartData.datasets[0].data).toEqual([0, 0, 0, 0, 0, 0]);
        expect(chartData.datasets[1].data).toEqual([0, 0, 0, 0, 0, 0]);
    });
});
