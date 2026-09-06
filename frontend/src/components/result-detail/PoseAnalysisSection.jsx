import CollapsibleDetails from "../CollapsibleDetails";
import {
    formatAnalysisMethod,
    formatNumber,
    formatPercent,
} from "./resultDetailFormatters";

function PoseAnalysisSection({
                                 poseInfo,
                                 poseFrameResults,
                                 renderMetricCard,
                             }) {
    const frameResults = Array.isArray(poseFrameResults) ? poseFrameResults : [];
    const detectedFrameCount = poseInfo?.detectedFrameCount ?? 0;
    const totalFrameCount = poseInfo?.totalFrameCount ?? 0;

    return (
        <article className="detail-card wide result-speech-card result-movement-card result-pose-card">
            <header className="result-speech-card-header">
                <div>
                    <span className="result-speech-kicker">Body alignment</span>
                    <h2>자세 분석 요약</h2>
                    <p>자세 점수와 어깨 균형을 먼저 확인하고, 프레임 검출 근거는 필요할 때 펼쳐보세요.</p>
                </div>
                <span className={`mini-badge ${detectedFrameCount > 0 ? "success" : "muted"}`}>
                    {totalFrameCount > 0
                        ? `${detectedFrameCount} / ${totalFrameCount} 프레임 검출`
                        : "프레임 정보 없음"}
                </span>
            </header>

            <section className="result-speech-score-section" aria-labelledby="pose-score-heading">
                <div className="result-speech-section-heading">
                    <div>
                        <span aria-hidden="true">01</span>
                        <h3 id="pose-score-heading">핵심 자세 점수</h3>
                    </div>
                    <p>백엔드가 계산한 점수를 그대로 표시합니다.</p>
                </div>

                <div className="result-speech-score-grid result-pose-score-grid">
                    {renderMetricCard(
                        "자세 점수",
                        poseInfo?.postureScore,
                        "검출률과 어깨 균형 점수를 합산한 자세 평가 점수입니다."
                    )}

                    {renderMetricCard(
                        "어깨 균형 점수",
                        poseInfo?.shoulderBalanceScore,
                        "좌우 어깨 높이 차이를 기반으로 계산한 균형 점수입니다."
                    )}
                </div>
            </section>

            <section className="result-speech-detail-section" aria-labelledby="pose-detail-heading">
                <div className="result-speech-section-heading">
                    <div>
                        <span aria-hidden="true">02</span>
                        <h3 id="pose-detail-heading">검출 근거</h3>
                    </div>
                    <p>촬영 영상에서 실제로 검출된 자세 데이터입니다.</p>
                </div>

                <dl className="result-speech-detail-grid result-pose-detail-grid">
                    <div className="result-speech-detail-item">
                        <dt>자세 검출률</dt>
                        <dd>
                            <strong>{formatPercent(poseInfo?.detectionRate)}</strong>
                            <span>추출 프레임 중 포즈 감지 비율</span>
                        </dd>
                    </div>
                    <div className="result-speech-detail-item">
                        <dt>검출 프레임</dt>
                        <dd>
                            <strong>{detectedFrameCount} / {totalFrameCount}</strong>
                            <span>검출 수 / 전체 분석 수</span>
                        </dd>
                    </div>
                    <div className="result-speech-detail-item">
                        <dt>평균 어깨 차이</dt>
                        <dd>
                            <strong>{formatNumber(poseInfo?.averageShoulderDiff, 4)}</strong>
                            <span>좌우 어깨 y좌표 차이 평균</span>
                        </dd>
                    </div>
                    <div className="result-speech-detail-item">
                        <dt>분석 방식</dt>
                        <dd>
                            <strong>{formatAnalysisMethod(poseInfo?.analysisMethod)}</strong>
                            <span>현재 적용된 계산 방식</span>
                        </dd>
                    </div>
                </dl>
            </section>

            {frameResults.length > 0 ? (
                <CollapsibleDetails
                    headingLevel={3}
                    className="result-speech-details result-movement-details"
                    summary={`프레임별 자세 분석 (${frameResults.length}개 프레임) — 자세히 보기`}
                >
                    <div className="pose-frame-table-wrap result-speech-table-wrap result-movement-table-wrap result-pose-table-wrap">
                        <table className="pose-frame-table">
                            <caption className="sr-only">프레임별 자세 검출과 어깨 균형 측정값</caption>
                            <thead>
                            <tr>
                                <th>순서</th>
                                <th>시간</th>
                                <th>검출</th>
                                <th>어깨 차이</th>
                                <th>어깨 균형 점수</th>
                            </tr>
                            </thead>

                            <tbody>
                            {frameResults.map((frameResult, index) => (
                                <tr key={`${frameResult.sequence}-${index}`}>
                                    <td>{frameResult.sequence ?? index + 1}</td>
                                    <td className="result-frame-time">{formatNumber(frameResult.timestampSec)}초</td>
                                    <td>
                                        {frameResult.poseDetected ? (
                                            <span className="mini-badge success">검출</span>
                                        ) : (
                                            <span className="mini-badge muted">미검출</span>
                                        )}
                                    </td>
                                    <td>{formatNumber(frameResult.shoulderDiff, 4)}</td>
                                    <td>{frameResult.shoulderBalanceScore ?? 0}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </CollapsibleDetails>
            ) : (
                <div className="result-speech-empty compact">
                    <span aria-hidden="true">—</span>
                    <div>
                        <strong>표시할 프레임별 자세 분석 결과가 없습니다.</strong>
                        <p>프레임 원문이 없어도 위의 집계 점수와 검출 정보는 그대로 확인할 수 있습니다.</p>
                    </div>
                </div>
            )}
        </article>
    );
}

export default PoseAnalysisSection;
