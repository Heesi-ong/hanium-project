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

ChartJS.register(
    CategoryScale,
    LinearScale,
    BarElement,
    Tooltip,
    Legend
);

const SCORE_FIELDS = [
    { key: "totalScore", label: "총점" },
    { key: "postureScore", label: "자세" },
    { key: "gazeScore", label: "시선" },
    { key: "speechScore", label: "음성" },
    { key: "gestureScore", label: "제스처" },
    { key: "expressionScore", label: "표정" },
];

function toScore(value) {
    return typeof value === "number" ? value : 0;
}

function ScoreCompareBarChart({ resultA, resultB, labelA, labelB }) {
    const scoresA = resultA?.scoreSummary || {};
    const scoresB = resultB?.scoreSummary || {};

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
                    label: (context) => `${context.dataset.label}: ${context.raw}점`,
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
