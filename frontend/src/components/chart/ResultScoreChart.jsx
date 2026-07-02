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
    return Number(value ?? 0);
}

function ResultScoreChart({ scoreSummary }) {
    const postureScore = toScore(scoreSummary?.postureScore);
    const gazeScore = toScore(scoreSummary?.gazeScore);
    const speechScore = toScore(scoreSummary?.speechScore);
    const gestureScore = toScore(scoreSummary?.gestureScore);
    const expressionScore = toScore(scoreSummary?.expressionScore);

    const chartData = {
        labels: ["자세", "시선", "음성", "제스처", "표정"],
        datasets: [
            {
                label: "영역별 점수",
                data: [
                    postureScore,
                    gazeScore,
                    speechScore,
                    gestureScore,
                    expressionScore,
                ],
                fill: true,
                tension: 0.25,
                pointRadius: 4,
                pointHoverRadius: 6,
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
                },
                pointLabels: {
                    font: {
                        size: 13,
                        weight: "600",
                    },
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
            <h2>영역별 점수 차트</h2>
            <p className="chart-card-description">
                자세, 시선, 음성, 제스처, 표정 점수를 한눈에 비교합니다.
            </p>

            <div className="chart-container radar">
                <Radar data={chartData} options={chartOptions} />
            </div>

            <div className="chart-summary">
                <div className="chart-summary-item">
                    <span>자세</span>
                    <strong>{postureScore}</strong>
                </div>
                <div className="chart-summary-item">
                    <span>시선</span>
                    <strong>{gazeScore}</strong>
                </div>
                <div className="chart-summary-item">
                    <span>음성</span>
                    <strong>{speechScore}</strong>
                </div>
                <div className="chart-summary-item">
                    <span>제스처</span>
                    <strong>{gestureScore}</strong>
                </div>
                <div className="chart-summary-item">
                    <span>표정</span>
                    <strong>{expressionScore}</strong>
                </div>
            </div>
        </article>
    );
}

export default ResultScoreChart;