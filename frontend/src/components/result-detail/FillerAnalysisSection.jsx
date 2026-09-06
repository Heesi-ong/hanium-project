import CollapsibleDetails from "../CollapsibleDetails";
import {
    formatAnalysisMethod,
    formatPercent,
} from "./resultDetailFormatters";

function FillerAnalysisSection({
                                   fillerInfo,
                                   fillerWords,
                                   renderMetricCard,
                               }) {
    const fillerCount = fillerInfo?.fillerCount ?? 0;

    return (
        <article className="detail-card wide result-speech-card result-filler-card">
            <header className="result-speech-card-header">
                <div>
                    <span className="result-speech-kicker">Verbal habits</span>
                    <h2>필러 분석 요약</h2>
                    <p>반복된 간투사의 수와 비율을 확인하고 다음 발표에서 줄일 표현을 찾습니다.</p>
                </div>
                <span className={`mini-badge ${fillerCount > 0 ? "warning" : "success"}`}>
                    {fillerCount > 0 ? `${fillerCount}회 감지` : "감지된 표현 없음"}
                </span>
            </header>

            <div className="result-filler-overview">
                <div className="result-filler-score">
                    {renderMetricCard(
                        "필러 점수",
                        fillerInfo?.fillerScore,
                        "전체 단어 수 대비 필러 비율이 낮을수록 높은 점수입니다."
                    )}
                </div>

                <dl className="result-filler-facts">
                    <div>
                        <dt>필러 수</dt>
                        <dd>{fillerCount}개</dd>
                        <span>STT transcript에서 감지</span>
                    </div>
                    <div>
                        <dt>필러 비율</dt>
                        <dd>{formatPercent(fillerInfo?.fillerRatio)}</dd>
                        <span>전체 단어 수 대비</span>
                    </div>
                    <div>
                        <dt>분석 방식</dt>
                        <dd>{formatAnalysisMethod(fillerInfo?.analysisMethod)}</dd>
                        <span>현재 적용된 계산 방식</span>
                    </div>
                </dl>
            </div>

            {fillerInfo?.note && (
                <div className="result-speech-notice">
                    <span aria-hidden="true">i</span>
                    <p>{fillerInfo.note}</p>
                </div>
            )}

            {Array.isArray(fillerWords) && fillerWords.length > 0 ? (
                <CollapsibleDetails
                    headingLevel={3}
                    className="result-speech-details"
                    summary={`감지된 필러 표현 (${fillerWords.length}종) — 자세히 보기`}
                >
                    <div className="pose-frame-table-wrap result-speech-table-wrap">
                        <table className="pose-frame-table">
                            <caption className="sr-only">감지된 필러 표현과 사용 횟수</caption>
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
                </CollapsibleDetails>
            ) : (
                <div className="result-speech-empty compact">
                    <span aria-hidden="true">✓</span>
                    <div>
                        <strong>감지된 필러 표현이 없습니다.</strong>
                        <p>현재 STT 결과에서 집계된 간투사가 없습니다.</p>
                    </div>
                </div>
            )}
        </article>
    );
}

export default FillerAnalysisSection;
