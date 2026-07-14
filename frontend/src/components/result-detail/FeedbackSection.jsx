function getVisualGenerationModeLabel(mode) {
    if (mode === "REAL") {
        return "실제 영상 AI";
    }

    if (mode === "FALLBACK") {
        return "영상 AI 실패 후 Mock 대체";
    }

    if (mode === "MOCK") {
        return "Mock 영상 분석";
    }

    if (mode === "SKIPPED") {
        return "Video LLM 분석 생략";
    }

    return "분석 방식 알 수 없음";
}

function getVisualGenerationModeClassName(mode) {
    if (mode === "REAL") {
        return "mini-badge success";
    }

    return "mini-badge muted";
}

function hasText(value) {
    return typeof value === "string" && value.trim().length > 0;
}

function VisualAnalysisBox({ visualAnalysis }) {
    const globalSummary = visualAnalysis?.globalSummary || {};
    const generationMode = visualAnalysis?.model?.generationMode || "UNKNOWN";
    const summaryItems = [
        ["전체 인상", globalSummary.visualDelivery],
        ["강점", globalSummary.mainStrength],
        ["개선점", globalSummary.mainWeakness],
    ].filter(([, value]) => hasText(value));

    return (
        <article className="detail-card">
            <h2>시각 분석</h2>

            {summaryItems.length === 0 ? (
                <p className="muted-text">영상 분석 데이터가 아직 없습니다.</p>
            ) : (
                <>
                    <div className="key-value-list">
                        <div className="key-value-item">
                            <span>생성 방식</span>
                            <strong>
                                <span className={getVisualGenerationModeClassName(generationMode)}>
                                    {getVisualGenerationModeLabel(generationMode)}
                                </span>
                            </strong>
                        </div>

                        {summaryItems.map(([label, value]) => (
                            <div className="key-value-item" key={label}>
                                <span>{label}</span>
                                <strong>{value.trim()}</strong>
                            </div>
                        ))}
                    </div>

                    <p className="muted-text">세부 관찰 데이터는 준비 중입니다.</p>
                </>
            )}
        </article>
    );
}

function FeedbackSection({ feedback, visualAnalysis }) {
    return (
        <div className="detail-grid">
            <article className="detail-card wide">
                <h2>종합 피드백</h2>

                <div className="feedback-block">
                    <h3>전체 평가</h3>
                    <p>{feedback?.overall || "표시할 종합 피드백이 없습니다."}</p>
                </div>

                <div className="feedback-columns">
                    <div>
                        <h3>강점</h3>
                        {Array.isArray(feedback?.strengths) &&
                        feedback.strengths.length > 0 ? (
                            <ul>
                                {feedback.strengths.map((item, index) => (
                                    <li key={`${item}-${index}`}>{item}</li>
                                ))}
                            </ul>
                        ) : (
                            <p className="muted-text">표시할 강점이 없습니다.</p>
                        )}
                    </div>

                    <div>
                        <h3>개선점</h3>
                        {Array.isArray(feedback?.improvements) &&
                        feedback.improvements.length > 0 ? (
                            <ul>
                                {feedback.improvements.map((item, index) => (
                                    <li key={`${item}-${index}`}>{item}</li>
                                ))}
                            </ul>
                        ) : (
                            <p className="muted-text">표시할 개선점이 없습니다.</p>
                        )}
                    </div>
                </div>
            </article>

            <VisualAnalysisBox visualAnalysis={visualAnalysis} />
        </div>
    );
}

export default FeedbackSection;
