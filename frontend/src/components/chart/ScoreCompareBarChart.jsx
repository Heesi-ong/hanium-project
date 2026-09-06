import { useState } from "react";
import { useReducedMotion } from "motion/react";
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
                ctx.fillStyle = "#F5EFE8";
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
    Legend
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
    const prefersReducedMotion = useReducedMotion();
    const [visibleSeries, setVisibleSeries] = useState([true, true]);
    const scoresA = resultA?.dataIssue ? {} : resultA?.scoreSummary || {};
    const scoresB = resultB?.dataIssue ? {} : resultB?.scoreSummary || {};

    const chartData = {
        labels: SCORE_FIELDS.map((field) => field.label),
        datasets: [
            {
                label: labelA,
                data: SCORE_FIELDS.map((field) => toScore(scoresA[field.key])),
                backgroundColor: "rgba(216, 195, 163, 0.7)",
                borderColor: "#D8C3A3",
                borderWidth: 2,
                hidden: !visibleSeries[0],
                borderRadius: 8,
                maxBarThickness: 34,
            },
            {
                label: labelB,
                data: SCORE_FIELDS.map((field) => toScore(scoresB[field.key])),
                backgroundColor: "#F27424",
                hidden: !visibleSeries[1],
                borderRadius: 8,
                maxBarThickness: 34,
            },
        ],
    };

    const chartOptions = {
        responsive: true,
        maintainAspectRatio: false,
        animation: prefersReducedMotion ? false : { duration: 300 },
        scales: {
            x: {
                ticks: { color: "#B7ADA4" },
                grid: { display: false },
                border: { color: "rgba(255,255,255,0.12)" },
            },
            y: {
                min: 0,
                max: 100,
                ticks: {
                    color: "#B7ADA4",
                    callback: (value) => `${value}점`,
                },
                grid: { color: "rgba(255,255,255,0.07)" },
                border: { display: false },
            },
        },
        plugins: {
            legend: {
                display: false,
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
        <article className="chart-card score-compare-chart">
            <h2>항목별 점수 비교</h2>
            <p className="chart-card-description">
                두 결과의 총점과 항목별 점수를 나란히 비교합니다.
            </p>

            <div className="compare-chart-controls" aria-label="비교 차트 표시 설정">
                {[labelA, labelB].map((label, index) => (
                    <button
                        type="button"
                        key={index}
                        data-side={index === 0 ? "a" : "b"}
                        aria-pressed={visibleSeries[index]}
                        onClick={() => setVisibleSeries(previous => previous.map((visible, itemIndex) => (
                            itemIndex === index ? !visible : visible
                        )))}
                    >
                        <b aria-hidden="true">{index === 0 ? "A" : "B"}</b>
                        <span>{index === 0 ? "A" : "B"} · {label}</span>
                        <small>{visibleSeries[index] ? "표시 중" : "숨김"}</small>
                    </button>
                ))}
            </div>

            <div className="chart-container bar">
                <Bar
                    data={chartData}
                    options={chartOptions}
                    plugins={[scoreValueLabelPlugin]}
                    role="img"
                    aria-label="A 기준 결과와 B 비교 결과의 항목별 점수 차트"
                />
            </div>
            <p className="compare-chart-hint">A/B 버튼으로 막대를 표시하거나 숨길 수 있습니다. 정확한 수치는 아래 점수표에서 확인하세요.</p>
        </article>
    );
}

export default ScoreCompareBarChart;
export { SCORE_FIELDS };
