import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import ScoreCompositionChart from "./ScoreCompositionChart";
import { buildScoreComposition } from "./scoreComposition";

vi.mock("react-chartjs-2", () => ({
    Bar: (props) => (
        <div
            data-testid="score-composition-bar"
            data-chart-data={JSON.stringify(props.data)}
        />
    ),
}));

describe("ScoreCompositionChart", () => {
    const scoreSummary = { totalScore: 80 };
    const scoreExplanation = {
        weightedContributions: {
            postureScore: 40,
            speechScore: 34.1667,
            gestureScore: 11.1667,
        },
        rawScore: 85,
        penaltyApplied: 5,
    };

    it("builds cumulative contribution bars through penalty and final score", () => {
        const composition = buildScoreComposition(scoreSummary, scoreExplanation);

        expect(composition.values[0]).toEqual([0, 40]);
        expect(composition.values[1]).toEqual([40, 74.1667]);
        expect(composition.values[2]).toEqual([74.1667, 85.3334]);
        expect(composition.values[3]).toEqual([0, 85]);
        expect(composition.values[4]).toEqual([80, 85]);
        expect(composition.values[5]).toEqual([0, 80]);
    });

    it("renders the stored raw score, penalty, and final score without recalculating them", () => {
        render(
            <ScoreCompositionChart
                scoreSummary={scoreSummary}
                scoreExplanation={scoreExplanation}
            />
        );

        const chartData = JSON.parse(
            screen.getByTestId("score-composition-bar").dataset.chartData
        );
        expect(chartData.labels).toEqual([
            "자세 기여",
            "음성 기여",
            "제스처 기여",
            "가중 원점수",
            "신뢰도 감점",
            "최종 점수",
        ]);
        expect(screen.getByText("85점")).toBeInTheDocument();
        expect(screen.getByText("−5점")).toBeInTheDocument();
        expect(screen.getByText("80점")).toBeInTheDocument();
    });

    it("shows a compatibility message when legacy results lack contribution data", () => {
        render(
            <ScoreCompositionChart
                scoreSummary={{ totalScore: 80 }}
                scoreExplanation={{}}
            />
        );

        expect(screen.getByText("이 결과에는 점수 구성 정보가 없습니다.")).toBeInTheDocument();
        expect(screen.queryByTestId("score-composition-bar")).not.toBeInTheDocument();
    });
});
