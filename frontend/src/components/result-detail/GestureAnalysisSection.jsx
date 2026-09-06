import CollapsibleDetails from "../CollapsibleDetails";
import {
    formatAnalysisMethod,
    formatBoolean,
    formatNumber,
    formatPercent,
} from "./resultDetailFormatters";

function BooleanSignal({ value }) {
    const label = formatBoolean(value);
    const className = value === true ? "yes" : value === false ? "no" : "unknown";

    return <span className={`result-boolean-signal ${className}`}>{label}</span>;
}

function GestureAnalysisSection({
                                    gestureInfo,
                                    gestureFrameResults,
                                    renderMetricCard,
                                }) {
    const frameResults = Array.isArray(gestureFrameResults) ? gestureFrameResults : [];
    const gestureFrameCount = gestureInfo?.gestureFrameCount ?? 0;
    const totalFrameCount = gestureInfo?.totalFrameCount ?? 0;

    return (
        <article className="detail-card wide result-speech-card result-movement-card result-gesture-card">
            <header className="result-speech-card-header">
                <div>
                    <span className="result-speech-kicker">Gesture delivery</span>
                    <h2>제스처 분석 요약</h2>
                    <p>제스처의 사용량과 손 검출 상태를 점수, 측정값, 프레임 원문 순서로 확인하세요.</p>
                </div>
                <span className={`mini-badge ${gestureFrameCount > 0 ? "success" : "muted"}`}>
                    {totalFrameCount > 0
                        ? `${gestureFrameCount} / ${totalFrameCount} 프레임 감지`
                        : "프레임 정보 없음"}
                </span>
            </header>

            <section className="result-speech-score-section" aria-labelledby="gesture-score-heading">
                <div className="result-speech-section-heading">
                    <div>
                        <span aria-hidden="true">01</span>
                        <h3 id="gesture-score-heading">핵심 제스처 점수</h3>
                    </div>
                    <p>백엔드가 계산한 네 점수를 그대로 표시합니다.</p>
                </div>

                <div className="result-speech-score-grid">
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
                </div>
            </section>

            <section className="result-speech-detail-section" aria-labelledby="gesture-detail-heading">
                <div className="result-speech-section-heading">
                    <div>
                        <span aria-hidden="true">02</span>
                        <h3 id="gesture-detail-heading">검출 근거</h3>
                    </div>
                    <p>포즈 프레임에서 계산된 손과 손목 측정값입니다.</p>
                </div>

                <dl className="result-speech-detail-grid result-gesture-detail-grid">
                    <div className="result-speech-detail-item">
                        <dt>제스처 비율</dt>
                        <dd><strong>{formatPercent(gestureInfo?.gestureRate)}</strong><span>전체 포즈 프레임 대비</span></dd>
                    </div>
                    <div className="result-speech-detail-item">
                        <dt>제스처 프레임</dt>
                        <dd><strong>{gestureFrameCount} / {totalFrameCount}</strong><span>감지 수 / 전체 분석 수</span></dd>
                    </div>
                    <div className="result-speech-detail-item">
                        <dt>손 검출률</dt>
                        <dd><strong>{formatPercent(gestureInfo?.handVisibilityRate)}</strong><span>양손 손목 landmark 기준</span></dd>
                    </div>
                    <div className="result-speech-detail-item">
                        <dt>평균 손목 이동량</dt>
                        <dd><strong>{formatNumber(gestureInfo?.averageWristMovement, 4)}</strong><span>연속 프레임 좌표 이동 평균</span></dd>
                    </div>
                    <div className="result-speech-detail-item">
                        <dt>분석 방식</dt>
                        <dd><strong>{formatAnalysisMethod(gestureInfo?.analysisMethod)}</strong><span>현재 적용된 계산 방식</span></dd>
                    </div>
                </dl>
            </section>

            {gestureInfo?.note && (
                <div className="result-speech-notice">
                    <span aria-hidden="true">i</span>
                    <p>{gestureInfo.note}</p>
                </div>
            )}

            {frameResults.length > 0 ? (
                <CollapsibleDetails
                    headingLevel={3}
                    className="result-speech-details result-movement-details"
                    summary={`프레임별 제스처 분석 (${frameResults.length}개 프레임) — 자세히 보기`}
                >
                    <div className="pose-frame-table-wrap result-speech-table-wrap result-movement-table-wrap result-gesture-table-wrap">
                        <table className="pose-frame-table">
                            <caption className="sr-only">프레임별 제스처와 양손 검출 측정값</caption>
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
                            {frameResults.map((frameResult, index) => (
                                <tr key={`${frameResult.sequence}-${index}`}>
                                    <td>{frameResult.sequence ?? index + 1}</td>
                                    <td className="result-frame-time">{formatNumber(frameResult.timestampSec)}초</td>
                                    <td>
                                        {frameResult.gestureDetected ? (
                                            <span className="mini-badge success">감지</span>
                                        ) : (
                                            <span className="mini-badge muted">없음</span>
                                        )}
                                    </td>
                                    <td><BooleanSignal value={frameResult.leftHandVisible} /></td>
                                    <td><BooleanSignal value={frameResult.rightHandVisible} /></td>
                                    <td><BooleanSignal value={frameResult.leftHandActive} /></td>
                                    <td><BooleanSignal value={frameResult.rightHandActive} /></td>
                                    <td>{formatNumber(frameResult.leftWristMovement, 4)}</td>
                                    <td>{formatNumber(frameResult.rightWristMovement, 4)}</td>
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
                        <strong>표시할 프레임별 제스처 분석 결과가 없습니다.</strong>
                        <p>프레임 원문이 없어도 위의 집계 점수와 검출 정보는 그대로 확인할 수 있습니다.</p>
                    </div>
                </div>
            )}
        </article>
    );
}

export default GestureAnalysisSection;
