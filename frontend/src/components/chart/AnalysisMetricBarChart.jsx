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

function toPercent(value) {
    if (typeof value !== "number") {
        return 0;
    }

    return Math.round(value * 100);
}

function AnalysisMetricBarChart({
                                    poseInfo,
                                    gestureInfo,
                                }) {
    const poseDetectionRate = toPercent(poseInfo?.detectionRate);
    const gestureRate = toPercent(gestureInfo?.gestureRate);
    const handVisibilityRate = toPercent(gestureInfo?.handVisibilityRate);

    const chartData = {
        labels: [
            "자세 검출률",
            "제스처 비율",
            "손 검출률",
        ],
        datasets: [
            {
                label: "비율",
                data: [
                    poseDetectionRate,
                    gestureRate,
                    handVisibilityRate,
                ],
                borderRadius: 10,
                maxBarThickness: 46,
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
                    callback: (value) => `${value}%`,
                },
            },
            x: {
                ticks: {
                    font: {
                        size: 12,
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
                    label: (context) => `${context.dataset.label}: ${context.raw}%`,
                },
            },
        },
    };

    return (
        <article className="chart-card">
            <h2>주요 분석 비율 차트</h2>
            <p className="chart-card-description">
                검출률과 사용 비율을 비교해 영상 분석 품질을 확인합니다.
            </p>

            <div className="chart-container bar">
                <Bar data={chartData} options={chartOptions} />
            </div>

            <div className="chart-summary">
                <div className="chart-summary-item">
                    <span>자세 검출률</span>
                    <strong>{poseDetectionRate}%</strong>
                </div>
                <div className="chart-summary-item">
                    <span>제스처 비율</span>
                    <strong>{gestureRate}%</strong>
                </div>
                <div className="chart-summary-item">
                    <span>손 검출률</span>
                    <strong>{handVisibilityRate}%</strong>
                </div>
            </div>
        </article>
    );
}

export default AnalysisMetricBarChart;
