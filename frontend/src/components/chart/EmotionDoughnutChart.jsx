import {
    ArcElement,
    Chart as ChartJS,
    Colors,
    Legend,
    Tooltip,
} from "chart.js";
import { Doughnut } from "react-chartjs-2";
import "./chartStyles.css";

ChartJS.register(
    ArcElement,
    Tooltip,
    Legend,
    Colors
);

function formatEmotionLabel(label) {
    if (label === "neutral") {
        return "중립";
    }

    if (label === "engaged") {
        return "몰입/집중";
    }

    if (label === "speaking") {
        return "발화 중";
    }

    if (label === "low_energy") {
        return "낮은 에너지";
    }

    if (label === "unknown") {
        return "알 수 없음";
    }

    return label || "-";
}

function EmotionDoughnutChart({ emotionCounts }) {
    const entries = Object.entries(emotionCounts || {});
    const labels = entries.map(([key]) => formatEmotionLabel(key));
    const values = entries.map(([, value]) => Number(value ?? 0));
    const totalCount = values.reduce((sum, value) => sum + value, 0);

    const chartData = {
        labels,
        datasets: [
            {
                label: "프레임 수",
                data: values,
                borderWidth: 2,
            },
        ],
    };

    const chartOptions = {
        responsive: true,
        maintainAspectRatio: false,
        cutout: "62%",
        plugins: {
            legend: {
                position: "bottom",
            },
            tooltip: {
                callbacks: {
                    label: (context) => {
                        const value = Number(context.raw ?? 0);
                        const percent = totalCount > 0
                            ? Math.round((value / totalCount) * 100)
                            : 0;

                        return `${context.label}: ${value}프레임 (${percent}%)`;
                    },
                },
            },
        },
    };

    return (
        <article className="chart-card">
            <h2>표정 상태 분포 차트</h2>
            <p className="chart-card-description">
                프레임별로 추정된 표정 상태의 분포를 보여줍니다.
            </p>

            <div className="chart-container doughnut">
                <Doughnut data={chartData} options={chartOptions} />
            </div>

            <div className="chart-summary">
                {entries.map(([emotion, count]) => (
                    <div className="chart-summary-item" key={emotion}>
                        <span>{formatEmotionLabel(emotion)}</span>
                        <strong>{count}</strong>
                    </div>
                ))}
            </div>
        </article>
    );
}

export default EmotionDoughnutChart;