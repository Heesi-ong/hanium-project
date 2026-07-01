function PracticePlanSection({ practicePlan }) {
    return (
        <article className="detail-card">
            <h2>연습 계획</h2>

            {Array.isArray(practicePlan) && practicePlan.length > 0 ? (
                <div className="practice-list">
                    {practicePlan.map((item, index) => (
                        <div className="practice-item" key={`${item.title}-${index}`}>
                            <span>{index + 1}</span>

                            <div>
                                <h3>{item.title || "연습 항목"}</h3>
                                <p>{item.description || "-"}</p>
                                {item.duration && <strong>{item.duration}</strong>}
                            </div>
                        </div>
                    ))}
                </div>
            ) : (
                <p className="muted-text">표시할 연습 계획이 없습니다.</p>
            )}
        </article>
    );
}

export default PracticePlanSection;