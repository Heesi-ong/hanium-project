import {
    BarElement,
    CategoryScale,
    Chart as ChartJS,
    Legend,
    LinearScale,
    Tooltip,
} from "chart.js";
import { Bar } from "react-chartjs-2";
import "./chartStyles.css";

// 0점 항목은 막대 높이가 0이라 차트만 보면 결측치와 구분하기 어렵습니다.
// 실제 숫자만 라벨로 표시하고 결측치는 null로 유지해 두 상태를 구분합니다.
const scoreValueLabelPlugin = {
    id: "scoreCompareValueLabels",
    afterDatasetsDraw(chart) {
        const { ctx, chartArea } = chart;
        if (!chartArea) {
            return;
        }

        chart.data.datasets.forEach((dataset, datasetIndex) => {
            if (!chart.isDatasetVisible(datasetIndex)) {
                return;
            }

            const meta = chart.getDatasetMeta(datasetIndex);
            meta.data.forEach((bar, index) => {
                const value = dataset.data[index];
                if (!Number.isFinite(value)) {
                    return;
                }

                const labelY = Math.max(bar.y - 6, chartArea.top + 12);

                ctx.save();
                ctx.fillStyle = "#2B2420";
                ctx.font = "600 11px sans-serif";
                ctx.textAlign = "center";
                ctx.textBaseline = "bottom";
                ctx.fillText(`${value}`, bar.x, labelY);
                ctx.restore();
            });
        });
    },
};

ChartJS.register(
    CategoryScale,
    LinearScale,
    BarElement,
    Tooltip,
    Legend,
    scoreValueLabelPlugin
);

const SCORE_FIELDS = [
    { key: "totalScore", label: "총점" },
    { key: "postureScore", label: "자세" },
    { key: "speechScore", label: "음성" },
    { key: "gestureScore", label: "제스처" },
];

function toScore(value) {
    return Number.isFinite(value) ? value : null;
}

function ScoreCompareBarChart({ resultA, resultB, labelA, labelB }) {
    const scoresA = resultA?.dataIssue ? {} : resultA?.scoreSummary || {};
    const scoresB = resultB?.dataIssue ? {} : resultB?.scoreSummary || {};

    const chartData = {
        labels: SCORE_FIELDS.map((field) => field.label),
        datasets: [
            {
                label: labelA,
                data: SCORE_FIELDS.map((field) => toScore(scoresA[field.key])),
                backgroundColor: "rgba(114, 165, 255, 0.65)",
                borderRadius: 8,
                maxBarThickness: 34,
            },
            {
                label: labelB,
                data: SCORE_FIELDS.map((field) => toScore(scoresB[field.key])),
                backgroundColor: "rgba(226, 112, 74, 0.75)",
                borderRadius: 8,
                maxBarThickness: 34,
            },
        ],
    };

    const chartOptions = {
        responsive: true,
        maintainAspectRatio: false,
        scales: {
            y: {
                min: 0,
                max: 100,
                ticks: {
                    callback: (value) => `${value}점`,
                },
            },
        },
        plugins: {
            legend: {
                position: "bottom",
            },
            tooltip: {
                callbacks: {
                    label: (context) => Number.isFinite(context.raw)
                        ? `${context.dataset.label}: ${context.raw}점`
                        : `${context.dataset.label}: 데이터 없음`,
                },
            },
        },
    };

    return (
        <article className="chart-card">
            <h2>항목별 점수 비교</h2>
            <p className="chart-card-description">
                두 결과의 총점과 항목별 점수를 나란히 비교합니다.
            </p>

            <div className="chart-container bar">
                <Bar data={chartData} options={chartOptions} />
            </div>
        </article>
    );
}

export default ScoreCompareBarChart;
export { SCORE_FIELDS };
