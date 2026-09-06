function PracticePlanSection({ practicePlan }) {
    const items = Array.isArray(practicePlan) ? practicePlan : [];

    return (
        <article
            className="detail-card result-practice-card practice-plan-card"
            aria-labelledby="practice-plan-title"
        >
            <header className="result-practice-card-header">
                <div>
                    <span className="result-feedback-kicker">Practice sequence</span>
                    <h2 id="practice-plan-title">연습 계획</h2>
                    <p>분석 결과가 제안한 순서대로 한 항목씩 연습해보세요.</p>
                </div>
                <span className="mini-badge muted">
                    {items.length > 0 ? `${items.length}단계` : "계획 없음"}
                </span>
            </header>

            {items.length > 0 ? (
                <ol className="practice-list result-practice-list">
                    {items.map((item, index) => (
                        <li className="practice-item" key={`${item.title}-${index}`}>
                            <span aria-hidden="true">{String(index + 1).padStart(2, "0")}</span>

                            <div>
                                <h3>{item.title || "연습 항목"}</h3>
                                <p>{item.description || "-"}</p>
                                {item.duration && (
                                    <strong>
                                        <span aria-hidden="true">◷</span>
                                        {item.duration}
                                    </strong>
                                )}
                            </div>
                        </li>
                    ))}
                </ol>
            ) : (
                <div className="result-practice-empty">
                    <span aria-hidden="true">—</span>
                    <div>
                        <strong>표시할 연습 계획이 없습니다.</strong>
                        <p>분석 결과에 연습 항목이 포함되면 단계별로 표시됩니다.</p>
                    </div>
                </div>
            )}
        </article>
    );
}

export default PracticePlanSection;
