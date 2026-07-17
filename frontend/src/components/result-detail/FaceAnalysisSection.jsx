import CollapsibleDetails from "../CollapsibleDetails";
import {
    formatAnalysisMethod,
    formatEyeContactLevel,
    formatNumber,
    formatPercent,
} from "./resultDetailFormatters";

function FaceAnalysisSection({
                                 faceInfo,
                                 faceFrameResults,
                                 renderMetricCard,
                             }) {
    return (
        <article className="detail-card wide">
            <h2>얼굴/시선 분석 요약</h2>

            <div className="metric-grid">
                {renderMetricCard(
                    "시선 점수",
                    faceInfo?.gazeScore,
                    "눈동자(Iris) 위치가 눈 영역 중앙에 머문 시간 비율(카메라 응시 비율)을 기반으로 계산한 점수입니다."
                )}

                <article className="metric-card">
                    <span>얼굴 검출률</span>
                    <strong>{formatPercent(faceInfo?.detectionRate)}</strong>
                    <p>추출된 프레임 중 얼굴이 감지된 비율입니다.</p>
                </article>

                <article className="metric-card">
                    <span>검출 프레임</span>
                    <strong>
                        {faceInfo?.detectedFrameCount ?? 0} / {faceInfo?.totalFrameCount ?? 0}
                    </strong>
                    <p>얼굴이 감지된 프레임 수와 전체 프레임 수입니다.</p>
                </article>

                <article className="metric-card">
                    <span>카메라 응시 비율</span>
                    <strong>{formatPercent(faceInfo?.cameraGazeRatio)}</strong>
                    <p>얼굴이 검출된 프레임 중 눈동자가 카메라(정면)를 향한 프레임의 비율입니다.</p>
                </article>

                <article className="metric-card">
                    <span>아이컨택 수준</span>
                    <strong>{formatEyeContactLevel(faceInfo?.eyeContactLevel)}</strong>
                    <p>시선 점수를 기준으로 환산한 발표 시선 안정성입니다.</p>
                </article>

                <article className="metric-card">
                    <span>분석 방식</span>
                    <strong>{formatAnalysisMethod(faceInfo?.analysisMethod)}</strong>
                    <p>현재 얼굴/시선 분석에 사용된 계산 방식입니다.</p>
                </article>
            </div>

            {Array.isArray(faceFrameResults) && faceFrameResults.length > 0 ? (
                <CollapsibleDetails summary={`프레임별 얼굴/시선 분석 (${faceFrameResults.length}개 프레임) — 자세히 보기`}>
                    <div className="pose-frame-table-wrap">
                        <table className="pose-frame-table">
                            <thead>
                            <tr>
                                <th>순서</th>
                                <th>시간</th>
                                <th>검출</th>
                                <th>카메라 응시</th>
                                <th>눈동자 위치(가로/세로)</th>
                                <th>시선 점수</th>
                                <th>입 벌림</th>
                                <th>눈 뜸</th>
                            </tr>
                            </thead>

                            <tbody>
                            {faceFrameResults.map((frameResult, index) => (
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
                                    <td>
                                        {frameResult.gazingAtCamera ? (
                                            <span className="mini-badge success">응시</span>
                                        ) : (
                                            <span className="mini-badge muted">이탈</span>
                                        )}
                                    </td>
                                    <td>
                                        {formatNumber(frameResult.irisHorizontalRatio, 2)} / {formatNumber(frameResult.irisVerticalRatio, 2)}
                                    </td>
                                    <td>{frameResult.gazeScore ?? 0}</td>
                                    <td>{formatNumber(frameResult.mouthOpenness, 4)}</td>
                                    <td>{formatNumber(frameResult.eyeOpenness, 4)}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                </CollapsibleDetails>
            ) : (
                <p className="muted-text">표시할 프레임별 얼굴/시선 분석 결과가 없습니다.</p>
            )}
        </article>
    );
}

export default FaceAnalysisSection;