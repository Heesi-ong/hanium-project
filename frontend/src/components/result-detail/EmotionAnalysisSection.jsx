import {
    formatAnalysisMethod,
    formatEmotionLabel,
    formatNumber,
    formatPercent,
} from "./resultDetailFormatters";

function EmotionAnalysisSection({
                                    emotionInfo,
                                    emotionCounts,
                                    emotionFrameResults,
                                    renderMetricCard,
                                }) {
    return (
        <article className="detail-card wide">
            <h2>표정/감정 분석 요약</h2>

            <div className="metric-grid">
                {renderMetricCard(
                    "표정 점수",
                    emotionInfo?.expressionScore,
                    "표정 표현 점수, 다양성 점수, 얼굴 검출률을 합산한 점수입니다. 자세·시선·음성·제스처와 함께 최종 점수에 20% 가중치로 반영됩니다."
                )}

                {renderMetricCard(
                    "표현력 원점수",
                    emotionInfo?.expressionRawScore,
                    "입 벌림, 눈 뜸 정도, 시선 안정성을 기반으로 계산한 프레임 평균 점수입니다."
                )}

                {renderMetricCard(
                    "표정 다양성 점수",
                    emotionInfo?.expressionVarietyScore,
                    "분석된 표정 상태 종류가 다양할수록 높은 점수입니다."
                )}

                <article className="metric-card">
                    <span>주요 표정 상태 (참고용)</span>
                    <strong>{formatEmotionLabel(emotionInfo?.emotionState?.dominantEmotion)}</strong>
                    <p>가장 많이 감지된 표정 상태입니다. 감정 상태 분류는 보조 지표이며 점수에는 반영되지 않습니다.</p>
                </article>

                <article className="metric-card">
                    <span>얼굴 검출률</span>
                    <strong>{formatPercent(emotionInfo?.detectionRate)}</strong>
                    <p>표정 분석에 사용할 수 있었던 얼굴 프레임 비율입니다.</p>
                </article>

                <article className="metric-card">
                    <span>검출 프레임</span>
                    <strong>
                        {emotionInfo?.detectedFrameCount ?? 0} / {emotionInfo?.totalFrameCount ?? 0}
                    </strong>
                    <p>얼굴이 검출되어 표정 분석에 사용된 프레임 수입니다.</p>
                </article>

                <article className="metric-card">
                    <span>분석 방식</span>
                    <strong>{formatAnalysisMethod(emotionInfo?.analysisMethod)}</strong>
                    <p>현재 표정/감정 분석에 사용된 계산 방식입니다.</p>
                </article>
            </div>

            {emotionInfo?.note && (
                <p className="muted-text">{emotionInfo.note}</p>
            )}

            <div className="pose-frame-table-wrap">
                <h3>표정 상태 집계</h3>

                <table className="pose-frame-table">
                    <thead>
                    <tr>
                        <th>표정 상태</th>
                        <th>프레임 수</th>
                    </tr>
                    </thead>

                    <tbody>
                    {Object.entries(emotionCounts || {}).map(([emotion, count]) => (
                        <tr key={emotion}>
                            <td>{formatEmotionLabel(emotion)}</td>
                            <td>{count}</td>
                        </tr>
                    ))}
                    </tbody>
                </table>
            </div>

            {Array.isArray(emotionFrameResults) && emotionFrameResults.length > 0 ? (
                <div className="pose-frame-table-wrap">
                    <h3>프레임별 표정/감정 분석</h3>

                    <table className="pose-frame-table">
                        <thead>
                        <tr>
                            <th>순서</th>
                            <th>시간</th>
                            <th>얼굴 검출</th>
                            <th>표정 상태</th>
                            <th>표현 점수</th>
                            <th>입 벌림</th>
                            <th>눈 뜸</th>
                            <th>시선 점수</th>
                        </tr>
                        </thead>

                        <tbody>
                        {emotionFrameResults.map((frameResult, index) => (
                            <tr key={`${frameResult.sequence}-${index}`}>
                                <td>{frameResult.sequence ?? index + 1}</td>
                                <td>{formatNumber(frameResult.timestampSec)}초</td>
                                <td>
                                    {frameResult.faceDetected ? (
                                        <span className="mini-badge success">검출</span>
                                    ) : (
                                        <span className="mini-badge muted">미검출</span>
                                    )}
                                </td>
                                <td>{formatEmotionLabel(frameResult.emotionLabel)}</td>
                                <td>{frameResult.expressionScore ?? 0}</td>
                                <td>{formatNumber(frameResult.mouthOpenness, 4)}</td>
                                <td>{formatNumber(frameResult.eyeOpenness, 4)}</td>
                                <td>{frameResult.gazeScore ?? 0}</td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
            ) : (
                <p className="muted-text">표시할 프레임별 표정/감정 분석 결과가 없습니다.</p>
            )}
        </article>
    );
}

export default EmotionAnalysisSection;