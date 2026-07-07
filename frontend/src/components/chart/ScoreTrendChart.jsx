import {
    CategoryScale,
    Chart as ChartJS,
    Legend,
    LinearScale,
    LineElement,
    PointElement,
    Tooltip,
} from "chart.js";
import { Line } from "react-chartjs-2";
import EmptyState from "../EmptyState";
import "./chartStyles.css";

ChartJS.register(
    CategoryScale,
    LinearScale,
    PointElement,
    LineElement,
    Tooltip,
    Legend
);

function formatDateTime(value) {
    if (!value) {
        return "-";
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return value;
    }

    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, "0");
    const day = String(date.getDate()).padStart(2, "0");
    const hours = String(date.getHours()).padStart(2, "0");
    const minutes = String(date.getMinutes()).padStart(2, "0");

    return `${year}.${month}.${day} ${hours}:${minutes}`;
}

function toTimestamp(value) {
    const date = new Date(value || 0);
    const timestamp = date.getTime();

    return Number.isNaN(timestamp) ? 0 : timestamp;
}

function getCompletedScoreResults(results) {
    return (results || [])
        .filter((result) => (
            result?.status === "COMPLETED" &&
            typeof result?.scoreSummary?.totalScore === "number"
        ))
        .sort((a, b) => toTimestamp(a.createdAt) - toTimestamp(b.createdAt));
}

function ScoreTrendChart({ results }) {
    const completedResults = getCompletedScoreResults(results);

    if (completedResults.length < 2) {
        return (
            <article className="chart-card">
                <h2>회차별 총점 추이</h2>
                <p className="chart-card-description">
                    완료된 분석 결과의 총점 변화를 시간순으로 확인합니다.
                </p>

                <EmptyState title="추이를 보려면 완료된 분석이 2개 이상 필요합니다." />
            </article>
        );
    }

    const chartData = {
        labels: completedResults.map((result) => formatDateTime(result.createdAt)),
        datasets: [
            {
                label: "총점",
                data: completedResults.map((result) => result.scoreSummary.totalScore),
                borderColor: "#2563eb",
                backgroundColor: "rgba(37, 99, 235, 0.14)",
                pointRadius: 4,
                pointHoverRadius: 6,
                tension: 0.3,
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
            <h2>회차별 총점 추이</h2>
            <p className="chart-card-description">
                완료된 분석 결과의 총점 변화를 시간순으로 확인합니다.
            </p>

            <div className="chart-container line">
                <Line data={chartData} options={chartOptions} />
            </div>
        </article>
    );
}

export default ScoreTrendChart;
