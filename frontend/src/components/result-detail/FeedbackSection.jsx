function formatObjectValue(value) {
    if (value === null || value === undefined) {
        return "-";
    }

    if (typeof value === "object") {
        return JSON.stringify(value, null, 2);
    }

    return String(value);
}

function VisualAnalysisBox({ visualAnalysis }) {
    const entries = Object.entries(visualAnalysis || {});

    return (
        <article className="detail-card">
            <h2>시각 분석</h2>

            {entries.length === 0 ? (
                <p className="muted-text">표시할 데이터가 없습니다.</p>
            ) : (
                <div className="key-value-list">
                    {entries.map(([key, value]) => (
                        <div className="key-value-item" key={key}>
                            <span>{key}</span>

                            {typeof value === "object" && value !== null ? (
                                <pre>{formatObjectValue(value)}</pre>
                            ) : (
                                <strong>{formatObjectValue(value)}</strong>
                            )}
                        </div>
                    ))}
                </div>
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