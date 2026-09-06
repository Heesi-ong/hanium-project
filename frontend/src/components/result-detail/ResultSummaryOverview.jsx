import "./resultSummaryOverview.css";
import {
    formatNumber,
    formatPercent,
    formatScoreLevel,
} from "./resultDetailFormatters";

function formatWithUnit(value, unit) {
    const formattedValue = formatNumber(value);
    return formattedValue === "-" ? "-" : `${formattedValue}${unit}`;
}

function formatScore(value, available) {
    return available && Number.isFinite(value) ? `${value}점` : "-";
}

function ResultSummaryOverview({
                                   scoreSummary,
                                   scoreAvailable = true,
                                   dataIssue = "",
                                   videoInfo,
                                   audioInfo,
                                   fillerInfo,
                                   poseInfo,
                                   gestureInfo,
                               }) {
    const totalScore = scoreAvailable && Number.isFinite(scoreSummary?.totalScore)
        ? scoreSummary.totalScore
        : null;
    const scoreLabel = dataIssue ? "결과 확인 필요" : formatScoreLevel(totalScore);
    const scoreState = dataIssue ? "issue" : totalScore === null ? "pending" : "ready";
    const scoreItems = [
        { label: "자세", value: scoreSummary?.postureScore },
        { label: "음성", value: scoreSummary?.speechScore },
        { label: "제스처", value: scoreSummary?.gestureScore },
    ];
    const signalItems = [
        { label: "발표 시간", value: formatWithUnit(videoInfo?.durationSec, "초") },
        { label: "말하기 속도", value: formatWithUnit(audioInfo?.speechSpeedWpm, " WPM") },
        { label: "침묵 횟수", value: formatWithUnit(audioInfo?.silenceCount, "회") },
        { label: "필러 수", value: formatWithUnit(fillerInfo?.fillerCount, "개") },
        { label: "자세 검출률", value: formatPercent(poseInfo?.detectionRate) },
        { label: "제스처 비율", value: formatPercent(gestureInfo?.gestureRate) },
    ];

    return (
        <article
            className="summary-overview"
            aria-labelledby="result-summary-title"
            data-score-state={scoreState}
        >
            <div className="summary-overview-main">
                <div className="summary-overview-copy">
                    <span className="summary-eyebrow">Analysis signal</span>
                    <h2 id="result-summary-title">발표 분석 핵심 요약</h2>
                    <p>
                        먼저 종합 점수와 세 영역의 균형을 확인하고, 아래 핵심 신호에서 원인을 살펴보세요.
                    </p>

                    <div className="summary-domain-scores" aria-label="영역별 핵심 점수">
                        {scoreItems.map((item) => (
                            <div className="summary-domain-score" key={item.label}>
                                <span>{item.label}</span>
                                <strong>{formatScore(item.value, scoreAvailable)}</strong>
                            </div>
                        ))}
                    </div>
                </div>

                <div
                    className="summary-score-box"
                    aria-label={totalScore === null ? `총점 없음, ${scoreLabel}` : `총점 ${totalScore}점, ${scoreLabel}`}
                >
                    <span>Overall score</span>
                    <strong>{totalScore ?? "-"}</strong>
                    <em>{scoreLabel}</em>
                    <small>{totalScore === null ? "점수 산정 전" : "100점 기준"}</small>
                </div>
            </div>

            <div className="summary-overview-grid">
                {signalItems.map((item, index) => (
                    <div className="summary-overview-item" key={item.label}>
                        <span className="summary-signal-index" aria-hidden="true">
                            {String(index + 1).padStart(2, "0")}
                        </span>
                        <div>
                            <span>{item.label}</span>
                            <strong>{item.value}</strong>
                        </div>
                    </div>
                ))}
            </div>
        </article>
    );
}

export default ResultSummaryOverview;
