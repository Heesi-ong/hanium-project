import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import ResultScoreChart from "./ResultScoreChart";

vi.mock("react-chartjs-2", () => ({
    Radar: (props) => (
        <div
            data-testid="result-score-radar"
            data-chart-data={JSON.stringify(props.data)}
            role={props.role}
            aria-label={props["aria-label"]}
        />
    ),
}));

describe("ResultScoreChart", () => {
    it("keeps a real zero score while leaving a missing score empty", () => {
        render(
            <ResultScoreChart
                scoreSummary={{
                    postureScore: 0,
                    speechScore: null,
                    gestureScore: 72,
                }}
            />
        );

        const chartData = JSON.parse(
            screen.getByTestId("result-score-radar").dataset.chartData
        );

        expect(chartData.datasets[0].data).toEqual([0, null, 72]);
        expect(screen.getByLabelText(/자세 0점, 음성 정보 없음, 제스처 72점/))
            .toBeInTheDocument();
        expect(screen.getByText("0점")).toBeInTheDocument();
        expect(screen.getByText("-")).toBeInTheDocument();
        expect(screen.getByText("72점")).toBeInTheDocument();
    });

    it("shows a compatibility state when every area score is missing", () => {
        render(<ResultScoreChart scoreSummary={{}} />);

        expect(
            screen.getByText("이 결과에는 영역별 점수 정보가 없습니다.")
        ).toBeInTheDocument();
        expect(screen.queryByTestId("result-score-radar")).not.toBeInTheDocument();
        expect(screen.getAllByText("-")).toHaveLength(3);
    });
});
