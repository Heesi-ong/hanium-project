import {
    BarElement,
    CategoryScale,
    Chart as ChartJS,
    Legend,
    LinearScale,
    Tooltip,
} from "chart.js";
import { Bar } from "react-chartjs-2";
import EmptyState from "../EmptyState";
import { buildScoreComposition } from "./scoreComposition";
import "./chartStyles.css";

ChartJS.register(
    CategoryScale,
    LinearScale,
    BarElement,
    Tooltip,
    Legend
);

const scoreCompositionValuePlugin = {
    id: "scoreCompositionValueLabels",
    afterDatasetsDraw(chart) {
        const { ctx, chartArea } = chart;
        const dataset = chart.data.datasets[0];

        if (!chartArea || !dataset) {
            return;
        }

        const meta = chart.getDatasetMeta(0);
        meta.data.forEach((bar, index) => {
            const range = dataset.data[index];
            if (!Array.isArray(range)) {
                return;
            }

            const value = index < 3
                ? range[1] - range[0]
                : index === 4
                    ? Math.max(range[1] - range[0], 0)
                    : range[1];
            const prefix = index < 3 ? "+" : index === 4 && value > 0 ? "−" : "";

            ctx.save();
            ctx.fillStyle = "#2B2420";
            ctx.font = "700 11px sans-serif";
            ctx.textAlign = "left";
            ctx.textBaseline = "middle";
            ctx.fillText(
                `${prefix}${Math.round(value * 10) / 10}`,
                Math.min(bar.x + 7, chartArea.right - 28),
                bar.y
            );
            ctx.restore();
        });
    },
};

function ScoreCompositionChart({ scoreSummary, scoreExplanation }) {
    const composition = buildScoreComposition(scoreSummary, scoreExplanation);

    if (!composition) {
        return (
            <article className="chart-card">
                <h2>점수 구성 워터폴</h2>
                <p className="chart-card-description">
                    자세·음성·제스처의 기여도와 신뢰도 감점을 최종 점수까지 연결합니다.
                </p>
                <EmptyState title="이 결과에는 점수 구성 정보가 없습니다." />
            </article>
        );
    }

    const colors = [
        "rgba(60, 157, 103, 0.82)",
        "rgba(71, 126, 199, 0.82)",
        "rgba(140, 99, 199, 0.82)",
        "rgba(43, 36, 32, 0.58)",
        "rgba(213, 83, 72, 0.82)",
        "rgba(226, 112, 74, 0.92)",
    ];
    const chartData = {
        labels: composition.labels,
        datasets: [
            {
                label: "점수 구성",
                data: composition.values,
                backgroundColor: colors,
                borderRadius: 7,
                borderSkipped: false,
                maxBarThickness: 28,
            },
        ],
    };
    const chartOptions = {
        responsive: true,
        maintainAspectRatio: false,
        indexAxis: "y",
        scales: {
            x: {
                min: 0,
                max: 100,
                ticks: {
                    callback: (value) => `${value}점`,
                },
            },
        },
        plugins: {
            legend: {
                display: false,
            },
            tooltip: {
                callbacks: {
                    label: (context) => {
                        const range = context.raw;
                        if (!Array.isArray(range)) {
                            return "";
                        }
                        const index = context.dataIndex;
                        const value = index < 3
                            ? range[1] - range[0]
                            : index === 4
                                ? composition.penalty
                                : range[1];
                        return `${context.label}: ${Math.round(value * 10) / 10}점`;
                    },
                },
            },
        },
    };

    return (
        <article className="chart-card">
            <h2>점수 구성 워터폴</h2>
            <p className="chart-card-description">
                자세·음성·제스처의 가중 기여도를 합산한 뒤 분석 신뢰도 감점을 적용한 결과입니다.
            </p>

            <div className="chart-container waterfall">
                <Bar
                    data={chartData}
                    options={chartOptions}
                    plugins={[scoreCompositionValuePlugin]}
                />
            </div>

            <div className="chart-summary">
                <div className="chart-summary-item">
                    <span>가중 원점수</span>
                    <strong>{composition.rawScore}점</strong>
                </div>
                <div className="chart-summary-item">
                    <span>신뢰도 감점</span>
                    <strong className={composition.penalty > 0 ? "score-penalty" : ""}>
                        −{composition.penalty}점
                    </strong>
                </div>
                <div className="chart-summary-item">
                    <span>최종 점수</span>
                    <strong>{composition.totalScore}점</strong>
                </div>
            </div>
        </article>
    );
}

export default ScoreCompositionChart;
