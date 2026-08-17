import "./resultSummaryOverview.css";
import {
    formatEmotionLabel,
    formatNumber,
    formatPercent,
    formatScoreLevel,
} from "./resultDetailFormatters";

function ResultSummaryOverview({
                                   scoreSummary,
                                   videoInfo,
                                   audioInfo,
                                   fillerInfo,
                                   poseInfo,
                                   faceInfo,
                                   gestureInfo,
                                   emotionInfo,
                               }) {
    const totalScore = scoreSummary?.totalScore ?? 0;
    const scoreLabel = formatScoreLevel(totalScore);

    return (
        <article className="summary-overview">
            <div className="summary-overview-main">
                <div>
                    <span className="summary-eyebrow">Analysis Summary</span>
                    <h2>발표 분석 핵심 요약</h2>
                    <p>
                        발표 영상의 음성, 자세, 시선, 제스처, 표정 분석 결과를 요약했습니다.
                    </p>
                </div>

                <div className="summary-score-box">
                    <span>총점</span>
                    <strong>{totalScore}</strong>
                    <em>{scoreLabel}</em>
                </div>
            </div>

            <div className="summary-overview-grid">
                <div className="summary-overview-item">
                    <span>발표 시간</span>
                    <strong>{formatNumber(videoInfo?.durationSec)}초</strong>
                </div>

                <div className="summary-overview-item">
                    <span>말하기 속도</span>
                    <strong>{audioInfo?.speechSpeedWpm ?? 0} WPM</strong>
                </div>

                <div className="summary-overview-item">
                    <span>침묵 횟수</span>
                    <strong>{audioInfo?.silenceCount ?? 0}회</strong>
                </div>

                <div className="summary-overview-item">
                    <span>필러 수</span>
                    <strong>{fillerInfo?.fillerCount ?? 0}개</strong>
                </div>

                <div className="summary-overview-item">
                    <span>자세 검출률</span>
                    <strong>{formatPercent(poseInfo?.detectionRate)}</strong>
                </div>

                <div className="summary-overview-item">
                    <span>얼굴 검출률</span>
                    <strong>{formatPercent(faceInfo?.detectionRate)}</strong>
                </div>

                <div className="summary-overview-item">
                    <span>제스처 비율</span>
                    <strong>{formatPercent(gestureInfo?.gestureRate)}</strong>
                </div>

                <div className="summary-overview-item">
                    <span>주요 표정 (참고용)</span>
                    <strong>{formatEmotionLabel(emotionInfo?.emotionState?.dominantEmotion)}</strong>
                </div>
            </div>
        </article>
    );
}

export default ResultSummaryOverview;