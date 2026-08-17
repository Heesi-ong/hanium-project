function formatRate(value) {
    return Number.isFinite(value) ? `${Math.round(value * 100)}%` : "-";
}

function buildRetakeSuggestions(reasons) {
    const suggestions = [];
    const normalized = Array.isArray(reasons) ? reasons : [];

    if (normalized.some((reason) => reason.includes("자세 검출률"))) {
        suggestions.push("카메라에 상반신과 양쪽 어깨가 모두 들어오도록 구도를 조정하세요.");
    }
    if (normalized.some((reason) => reason.includes("얼굴 검출률"))) {
        suggestions.push("얼굴을 화면 중앙에 두고 정면 조명을 밝게 조정하세요.");
    }
    if (normalized.some((reason) => reason.includes("STT"))) {
        suggestions.push("주변 소음을 줄이고 마이크와 가까운 거리에서 또렷하게 말하세요.");
    }
    if (normalized.some((reason) => reason.includes("영상이 너무 짧"))) {
        suggestions.push("분석할 발화가 충분하도록 10초 이상의 영상을 촬영하세요.");
    }

    return suggestions;
}

function AnalysisQualitySection({ analysisQuality, scoreExplanation }) {
    const quality = analysisQuality || {};
    const explanation = scoreExplanation || {};
    const reasons = Array.isArray(quality.penaltyReasons)
        ? quality.penaltyReasons
        : [];
    const suggestions = buildRetakeSuggestions(reasons);
    const available = quality.available === true;
    const penalty = Number.isFinite(quality.penaltyApplied)
        ? quality.penaltyApplied
        : Number.isFinite(explanation.penaltyApplied)
            ? explanation.penaltyApplied
            : 0;
    const formulaVersion = quality.formulaVersion !== "UNKNOWN"
        ? quality.formulaVersion
        : explanation.formulaVersion;

    return (
        <article className="detail-card analysis-quality-card">
            <div className="card-header-row">
                <div>
                    <h2>점수 근거와 분석 품질</h2>
                    <p className="muted-text">
                        점수 산식은 그대로 유지하며, 촬영 조건 때문에 신뢰도가 낮아진 부분을 구분해 표시합니다.
                    </p>
                </div>
                <span className={`mini-badge ${available && !quality.lowConfidence ? "success" : "muted"}`}>
                    {!available
                        ? "품질 정보 없음"
                        : quality.lowConfidence
                            ? "낮은 신뢰도"
                            : "정상 신뢰도"}
                </span>
            </div>

            {!available ? (
                <p className="muted-text">
                    이 결과에는 분석 품질 메타데이터가 없습니다. 새로 분석한 결과부터 표시됩니다.
                </p>
            ) : (
                <>
                    <div className="analysis-quality-metrics">
                        <div><span>자세 검출률</span><strong>{formatRate(quality.poseDetectionRate)}</strong></div>
                        <div><span>얼굴 검출률</span><strong>{formatRate(quality.faceDetectionRate)}</strong></div>
                        <div><span>신뢰도 감점</span><strong>{penalty}점</strong></div>
                        <div><span>점수 산식</span><strong>{formulaVersion || "-"}</strong></div>
                    </div>

                    {reasons.length > 0 ? (
                        <div className="analysis-quality-guidance">
                            <div>
                                <h3>감점 근거</h3>
                                <ul>{reasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>
                            </div>
                            <div>
                                <h3>다시 촬영할 때</h3>
                                <ul>
                                    {suggestions.map((suggestion) => (
                                        <li key={suggestion}>{suggestion}</li>
                                    ))}
                                </ul>
                            </div>
                        </div>
                    ) : (
                        <p className="muted-text">촬영 조건에 따른 신뢰도 감점이 적용되지 않았습니다.</p>
                    )}
                </>
            )}
        </article>
    );
}

export default AnalysisQualitySection;
