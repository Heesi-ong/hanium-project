import {
    CategoryScale,
    Chart as ChartJS,
    Legend,
    LinearScale,
    LineElement,
    PointElement,
    Tooltip,
} from "chart.js";
import { useState } from "react";
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

const SCORE_SERIES = [
    {
        key: "totalScore",
        label: "총점",
        color: "#E2704A",
        backgroundColor: "rgba(226, 112, 74, 0.12)",
    },
    {
        key: "postureScore",
        label: "자세",
        color: "#3C9D67",
        backgroundColor: "rgba(60, 157, 103, 0.1)",
        practiceGoal: "POSTURE",
    },
    {
        key: "speechScore",
        label: "음성",
        color: "#477EC7",
        backgroundColor: "rgba(71, 126, 199, 0.1)",
        practiceGoal: "SPEECH",
    },
    {
        key: "gestureScore",
        label: "제스처",
        color: "#8C63C7",
        backgroundColor: "rgba(140, 99, 199, 0.1)",
        practiceGoal: "GESTURE",
    },
];

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
    return `${year}.${month}.${day}`;
}

function formatTooltipDateTime(value) {
    if (!value) {
        return "-";
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
        return value;
    }

    return date.toLocaleString("ko-KR", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
    });
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
            !result?.dataIssue &&
            typeof result?.scoreSummary?.totalScore === "number"
        ))
        .sort((a, b) => toTimestamp(a.createdAt) - toTimestamp(b.createdAt));
}

function getScoreDelta(results, key) {
    const values = results
        .map((result) => result?.scoreSummary?.[key])
        .filter((value) => Number.isFinite(value));

    if (values.length < 2) {
        return null;
    }

    return values.at(-1) - values[0];
}

function formatDelta(delta) {
    if (!Number.isFinite(delta)) {
        return "비교 불가";
    }
    if (delta > 0) {
        return `+${delta}점`;
    }
    return `${delta}점`;
}

function ScoreTrendChart({ results }) {
    const completedResults = getCompletedScoreResults(results);
    const [visibleSeries, setVisibleSeries] = useState(() => (
        SCORE_SERIES.map((series) => series.key)
    ));

    if (completedResults.length < 2) {
        return (
            <article className="chart-card">
                <h2>회차별 성장 추이</h2>
                <p className="chart-card-description">
                    완료된 분석 결과의 총점과 항목별 변화를 시간순으로 확인합니다.
                </p>

                <EmptyState title="추이를 보려면 완료된 분석이 2개 이상 필요합니다." />
            </article>
        );
    }

    const visibleDefinitions = SCORE_SERIES.filter((series) => (
        visibleSeries.includes(series.key)
    ));
    const chartData = {
        labels: completedResults.map((result) => formatDateTime(result.createdAt)),
        datasets: visibleDefinitions.map((series) => ({
            label: series.label,
            data: completedResults.map((result) => (
                Number.isFinite(result?.scoreSummary?.[series.key])
                    ? result.scoreSummary[series.key]
                    : null
            )),
            borderColor: series.color,
            backgroundColor: series.backgroundColor,
            pointRadius: completedResults.map((result) => (
                series.practiceGoal && result?.practiceGoal === series.practiceGoal ? 7 : 4
            )),
            pointHoverRadius: 7,
            pointBorderWidth: completedResults.map((result) => (
                series.practiceGoal && result?.practiceGoal === series.practiceGoal ? 3 : 1
            )),
            spanGaps: false,
            tension: 0.3,
        })),
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
                display: false,
            },
            tooltip: {
                callbacks: {
                    title: (items) => formatTooltipDateTime(
                        completedResults[items[0]?.dataIndex]?.createdAt
                    ),
                    label: (context) => `${context.dataset.label}: ${context.raw}점`,
                    afterLabel: (context) => {
                        const result = completedResults[context.dataIndex];
                        const series = visibleDefinitions[context.datasetIndex];
                        return series?.practiceGoal && result?.practiceGoal === series.practiceGoal
                            ? "이 회차의 집중 연습 목표"
                            : "";
                    },
                },
            },
        },
    };

    function toggleSeries(key) {
        setVisibleSeries((current) => {
            if (!current.includes(key)) {
                return [...current, key];
            }
            if (current.length === 1) {
                return current;
            }
            return current.filter((item) => item !== key);
        });
    }

    return (
        <article className="chart-card">
            <h2>회차별 성장 추이</h2>
            <p className="chart-card-description">
                완료된 분석 결과의 총점과 자세·음성·제스처 변화를 시간순으로 확인합니다.
            </p>

            <div className="trend-series-controls" aria-label="표시할 점수 항목">
                {SCORE_SERIES.map((series) => {
                    const active = visibleSeries.includes(series.key);
                    return (
                        <button
                            type="button"
                            className={active ? "trend-series-button active" : "trend-series-button"}
                            style={{ "--series-color": series.color }}
                            aria-pressed={active}
                            key={series.key}
                            onClick={() => toggleSeries(series.key)}
                        >
                            <span aria-hidden="true" />
                            {series.label}
                        </button>
                    );
                })}
            </div>

            <div className="chart-container line">
                <Line data={chartData} options={chartOptions} />
            </div>

            <div className="trend-delta-grid" aria-label="첫 회차 대비 최근 변화">
                {SCORE_SERIES.map((series) => {
                    const delta = getScoreDelta(completedResults, series.key);
                    const deltaClass = Number.isFinite(delta)
                        ? delta > 0
                            ? "positive"
                            : delta < 0
                                ? "negative"
                                : "neutral"
                        : "neutral";
                    return (
                        <div className="trend-delta-item" key={series.key}>
                            <span>{series.label}</span>
                            <strong className={deltaClass}>{formatDelta(delta)}</strong>
                        </div>
                    );
                })}
            </div>
        </article>
    );
}

export default ScoreTrendChart;
