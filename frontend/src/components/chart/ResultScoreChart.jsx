import {
    Chart as ChartJS,
    Filler,
    Legend,
    LineElement,
    PointElement,
    RadialLinearScale,
    Tooltip,
} from "chart.js";
import { Radar } from "react-chartjs-2";
import EmptyState from "../EmptyState";
import "./chartStyles.css";

ChartJS.register(
    RadialLinearScale,
    PointElement,
    LineElement,
    Filler,
    Tooltip,
    Legend
);

function toScore(value) {
    return Number.isFinite(value) ? value : null;
}

function formatScore(value) {
    return value === null ? "-" : `${value}점`;
}

function ResultScoreChart({ scoreSummary }) {
    const postureScore = toScore(scoreSummary?.postureScore);
    const speechScore = toScore(scoreSummary?.speechScore);
    const gestureScore = toScore(scoreSummary?.gestureScore);
    const scoreEntries = [
        { label: "자세", value: postureScore },
        { label: "음성", value: speechScore },
        { label: "제스처", value: gestureScore },
    ];
    const hasScore = scoreEntries.some((item) => item.value !== null);

    const chartData = {
        labels: ["자세", "음성", "제스처"],
        datasets: [
            {
                label: "영역별 점수",
                data: [
                    postureScore,
                    speechScore,
                    gestureScore,
                ],
                fill: true,
                tension: 0.25,
                pointRadius: 4,
                pointHoverRadius: 6,
                backgroundColor: "rgba(242, 116, 36, 0.18)",
                borderColor: "#d95f18",
                pointBackgroundColor: "#f27424",
                pointBorderColor: "#fff7f1",
            },
        ],
    };

    const chartOptions = {
        responsive: true,
        maintainAspectRatio: false,
        scales: {
            r: {
                min: 0,
                max: 100,
                ticks: {
                    stepSize: 20,
                    backdropColor: "transparent",
                    color: "#776e66",
                },
                pointLabels: {
                    color: "#2b2420",
                    font: {
                        size: 13,
                        weight: "600",
                    },
                },
                grid: {
                    color: "rgba(43, 36, 32, 0.12)",
                },
                angleLines: {
                    color: "rgba(43, 36, 32, 0.14)",
                },
            },
        },
        plugins: {
            legend: {
                position: "bottom",
            },
            tooltip: {
                callbacks: {
                    label: (context) => context.raw === null
                        ? `${context.dataset.label}: 정보 없음`
                        : `${context.dataset.label}: ${context.raw}점`,
                },
            },
        },
    };

    const chartLabel = scoreEntries
        .map((item) => `${item.label} ${item.value === null ? "정보 없음" : `${item.value}점`}`)
        .join(", ");

    return (
        <article className="chart-card">
            <h2>영역별 점수 차트</h2>
            <p className="chart-card-description">
                자세, 음성, 제스처 점수를 한눈에 비교합니다.
            </p>

            {hasScore ? (
                <div className="chart-container radar">
                    <Radar
                        data={chartData}
                        options={chartOptions}
                        role="img"
                        aria-label={`영역별 점수 방사형 차트: ${chartLabel}`}
                    />
                </div>
            ) : (
                <EmptyState
                    title="이 결과에는 영역별 점수 정보가 없습니다."
                    description="레거시 결과이거나 점수 산정이 완료되지 않은 경우 세부 점수가 제공되지 않을 수 있습니다."
                />
            )}

            <div className="chart-summary">
                {scoreEntries.map((item) => (
                    <div className="chart-summary-item" key={item.label}>
                        <span>{item.label}</span>
                        <strong>{formatScore(item.value)}</strong>
                    </div>
                ))}
            </div>
        </article>
    );
}

export default ResultScoreChart;
