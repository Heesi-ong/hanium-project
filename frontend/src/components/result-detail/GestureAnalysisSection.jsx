import CollapsibleDetails from "../CollapsibleDetails";
import {
    formatAnalysisMethod,
    formatBoolean,
    formatNumber,
    formatPercent,
} from "./resultDetailFormatters";

function GestureAnalysisSection({
                                    gestureInfo,
                                    gestureFrameResults,
                                    renderMetricCard,
                                }) {
    return (
        <article className="detail-card wide">
            <h2>제스처 분석 요약</h2>

            <div className="metric-grid">
                {renderMetricCard(
                    "제스처 점수",
                    gestureInfo?.gestureScore,
                    "제스처 사용 비율, 손 검출률, 손목 움직임을 합산한 점수입니다."
                )}

                {renderMetricCard(
                    "제스처 다양성 점수",
                    gestureInfo?.gestureVarietyScore,
                    "제스처가 너무 적거나 과하지 않고 적정 비율일 때 높은 점수입니다."
                )}

                {renderMetricCard(
                    "손 검출 점수",
                    gestureInfo?.handVisibilityScore,
                    "프레임에서 양손 손목이 안정적으로 검출된 정도입니다."
                )}

                {renderMetricCard(
                    "손목 움직임 점수",
                    gestureInfo?.gestureMovementScore,
                    "프레임 간 손목 이동량이 적절할수록 높은 점수입니다."
                )}

                <article className="metric-card">
                    <span>제스처 비율</span>
                    <strong>{formatPercent(gestureInfo?.gestureRate)}</strong>
                    <p>전체 포즈 프레임 중 제스처가 감지된 비율입니다.</p>
                </article>

                <article className="metric-card">
                    <span>제스처 프레임</span>
                    <strong>
                        {gestureInfo?.gestureFrameCount ?? 0} / {gestureInfo?.totalFrameCount ?? 0}
                    </strong>
                    <p>제스처가 감지된 프레임 수와 전체 프레임 수입니다.</p>
                </article>

                <article className="metric-card">
                    <span>손 검출률</span>
                    <strong>{formatPercent(gestureInfo?.handVisibilityRate)}</strong>
                    <p>양손 손목 landmark가 안정적으로 보인 비율입니다.</p>
                </article>

                <article className="metric-card">
                    <span>평균 손목 이동량</span>
                    <strong>{formatNumber(gestureInfo?.averageWristMovement, 4)}</strong>
                    <p>연속 프레임 간 손목 좌표 이동 거리의 평균입니다.</p>
                </article>

                <article className="metric-card">
                    <span>분석 방식</span>
                    <strong>{formatAnalysisMethod(gestureInfo?.analysisMethod)}</strong>
                    <p>현재 제스처 분석에 사용된 계산 방식입니다.</p>
                </article>
            </div>

            {gestureInfo?.note && (
                <p className="muted-text">{gestureInfo.note}</p>
            )}

            {Array.isArray(gestureFrameResults) && gestureFrameResults.length > 0 ? (
                <CollapsibleDetails
                    headingLevel={3}
                    summary={`프레임별 제스처 분석 (${gestureFrameResults.length}개 프레임) — 자세히 보기`}
                >
                    <div className="pose-frame-table-wrap">
                        <table className="pose-frame-table">
                            <thead>
                            <tr>
                                <th>순서</th>
                                <th>시간</th>
                                <th>제스처</th>
                                <th>왼손 보임</th>
                                <th>오른손 보임</th>
                                <th>왼손 활성</th>
                                <th>오른손 활성</th>
                                <th>왼손 이동</th>
                                <th>오른손 이동</th>
                            </tr>
                            </thead>

                            <tbody>
                            {gestureFrameResults.map((frameResult, index) => (
                                <tr key={`${frameResult.sequence}-${index}`}>
                                    <td>{frameResult.sequence ?? index + 1}</td>
                                    <td>{formatNumber(frameResult.timestampSec)}초</td>
                                    <td>
                                        {frameResult.gestureDetected ? (
                                            <span className="mini-badge success">감지</span>
                                        ) : (
                                            <span className="mini-badge muted">없음</span>
                                        )}
                                    </td>
                                    <td>{formatBoolean(frameResult.leftHandVisible)}</td>
                                    <td>{formatBoolean(frameResult.rightHandVisible)}</td>
                                    <td>{formatBoolean(frameResult.leftHandActive)}</td>
                                    <td>{formatBoolean(frameResult.rightHandActive)}</td>
                                    <td>{formatNumber(frameResult.leftWristMovement, 4)}</td>
                                    <td>{formatNumber(frameResult.rightWristMovement, 4)}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </CollapsibleDetails>
            ) : (
                <p className="muted-text">표시할 프레임별 제스처 분석 결과가 없습니다.</p>
            )}
        </article>
    );
}

export default GestureAnalysisSection;