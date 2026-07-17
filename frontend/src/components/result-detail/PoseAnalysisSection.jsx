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
    return (
        <article className="detail-card wide">
            <h2>자세 분석 요약</h2>

            <div className="metric-grid">
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

                <article className="metric-card">
                    <span>자세 검출률</span>
                    <strong>{formatPercent(poseInfo?.detectionRate)}</strong>
                    <p>추출된 프레임 중 포즈가 감지된 비율입니다.</p>
                </article>

                <article className="metric-card">
                    <span>검출 프레임</span>
                    <strong>
                        {poseInfo?.detectedFrameCount ?? 0} / {poseInfo?.totalFrameCount ?? 0}
                    </strong>
                    <p>포즈가 감지된 프레임 수와 전체 프레임 수입니다.</p>
                </article>

                <article className="metric-card">
                    <span>평균 어깨 차이</span>
                    <strong>{formatNumber(poseInfo?.averageShoulderDiff, 4)}</strong>
                    <p>좌우 어깨 y좌표 차이의 평균값입니다.</p>
                </article>

                <article className="metric-card">
                    <span>분석 방식</span>
                    <strong>{formatAnalysisMethod(poseInfo?.analysisMethod)}</strong>
                    <p>현재 자세 분석에 사용된 계산 방식입니다.</p>
                </article>
            </div>

            {Array.isArray(poseFrameResults) && poseFrameResults.length > 0 ? (
                <CollapsibleDetails summary={`프레임별 자세 분석 (${poseFrameResults.length}개 프레임) — 자세히 보기`}>
                    <div className="pose-frame-table-wrap">
                        <table className="pose-frame-table">
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
                            {poseFrameResults.map((frameResult, index) => (
                                <tr key={`${frameResult.sequence}-${index}`}>
                                    <td>{frameResult.sequence ?? index + 1}</td>
                                    <td>{formatNumber(frameResult.timestampSec)}초</td>
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
                <p className="muted-text">표시할 프레임별 자세 분석 결과가 없습니다.</p>
            )}
        </article>
    );
}

export default PoseAnalysisSection;