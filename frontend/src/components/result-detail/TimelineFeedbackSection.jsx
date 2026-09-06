function TimelineFeedbackSection({ timelineFeedback }) {
    const feedbackItems = Array.isArray(timelineFeedback) ? timelineFeedback : [];

    return (
        <article className="detail-card result-speech-card result-timeline-feedback-card">
            <header className="result-speech-card-header">
                <div>
                    <span className="result-speech-kicker">Coaching moments</span>
                    <h2>타임라인 피드백</h2>
                    <p>발표 흐름에서 다시 확인할 장면과 다음 연습 행동을 함께 정리했습니다.</p>
                </div>
                <span className="result-speech-count">
                    {feedbackItems.length}개 피드백
                </span>
            </header>

            {feedbackItems.length > 0 ? (
                <ol className="result-timeline-feedback-list" aria-label="발표 타임라인 피드백">
                    {feedbackItems.map((item, index) => (
                        <li className="result-timeline-feedback-item" key={`${item.category}-${index}`}>
                            <span className="result-timeline-feedback-index" aria-hidden="true">
                                {String(index + 1).padStart(2, "0")}
                            </span>
                            <div className="result-timeline-feedback-body">
                                <div className="result-timeline-feedback-meta">
                                    <span>{item.category || "feedback"}</span>
                                    <span>발표 흐름 관찰</span>
                                </div>
                                <h3>{item.title || item.summary || "요약 정보가 없습니다."}</h3>
                                <div className="result-timeline-feedback-action">
                                    <span>다음 연습</span>
                                    <p>{item.recommendation || "-"}</p>
                                </div>
                            </div>
                        </li>
                    ))}
                </ol>
            ) : (
                <div className="result-speech-empty">
                    <span aria-hidden="true">✓</span>
                    <div>
                        <strong>표시할 타임라인 피드백이 없습니다.</strong>
                        <p>분석 결과에 구간별 코칭 항목이 포함되면 이곳에 표시됩니다.</p>
                    </div>
                </div>
            )}
        </article>
    );
}

export default TimelineFeedbackSection;
