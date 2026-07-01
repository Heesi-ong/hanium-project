import {
    formatAnalysisMethod,
    formatPercent,
} from "./resultDetailFormatters";

function FillerAnalysisSection({
                                   fillerInfo,
                                   fillerWords,
                                   renderMetricCard,
                               }) {
    return (
        <article className="detail-card wide">
            <h2>필러 분석 요약</h2>

            <div className="metric-grid">
                {renderMetricCard(
                    "필러 점수",
                    fillerInfo?.fillerScore,
                    "전체 단어 수 대비 필러 비율이 낮을수록 높은 점수입니다."
                )}

                <article className="metric-card">
                    <span>필러 수</span>
                    <strong>{fillerInfo?.fillerCount ?? 0}개</strong>
                    <p>STT transcript에서 감지한 필러 표현 수입니다.</p>
                </article>

                <article className="metric-card">
                    <span>필러 비율</span>
                    <strong>{formatPercent(fillerInfo?.fillerRatio)}</strong>
                    <p>전체 단어 수 대비 필러 표현의 비율입니다.</p>
                </article>

                <article className="metric-card">
                    <span>분석 방식</span>
                    <strong>{formatAnalysisMethod(fillerInfo?.analysisMethod)}</strong>
                    <p>현재 필러 분석에 사용된 계산 방식입니다.</p>
                </article>
            </div>

            {fillerInfo?.note && <p className="muted-text">{fillerInfo.note}</p>}

            {Array.isArray(fillerWords) && fillerWords.length > 0 ? (
                <div className="pose-frame-table-wrap">
                    <h3>감지된 필러 표현</h3>

                    <table className="pose-frame-table">
                        <thead>
                        <tr>
                            <th>순서</th>
                            <th>필러 표현</th>
                            <th>횟수</th>
                        </tr>
                        </thead>

                        <tbody>
                        {fillerWords.map((item, index) => (
                            <tr key={`${item.word}-${index}`}>
                                <td>{index + 1}</td>
                                <td>{item.word}</td>
                                <td>{item.count}</td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
            ) : (
                <p className="muted-text">감지된 필러 표현이 없습니다.</p>
            )}
        </article>
    );
}

export default FillerAnalysisSection;