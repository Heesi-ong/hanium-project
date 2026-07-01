function TimelineFeedbackSection({ timelineFeedback }) {
    return (
        <article className="detail-card">
            <h2>타임라인 피드백</h2>

            {Array.isArray(timelineFeedback) && timelineFeedback.length > 0 ? (
                <div className="timeline-list">
                    {timelineFeedback.map((item, index) => (
                        <div className="timeline-item" key={`${item.category}-${index}`}>
                            <span>{item.category || "feedback"}</span>
                            <h3>{item.title || item.summary || "요약 정보가 없습니다."}</h3>
                            <p>{item.recommendation || "-"}</p>
                        </div>
                    ))}
                </div>
            ) : (
                <p className="muted-text">표시할 타임라인 피드백이 없습니다.</p>
            )}
        </article>
    );
}

export default TimelineFeedbackSection;