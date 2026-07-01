function formatObjectValue(value) {
    if (value === null || value === undefined) {
        return "-";
    }

    if (typeof value === "object") {
        return JSON.stringify(value, null, 2);
    }

    return String(value);
}

function PipelineSection({ pipeline }) {
    const entries = Object.entries(pipeline || {});

    return (
        <article className="detail-card">
            <h2>파이프라인 정보</h2>

            {entries.length === 0 ? (
                <p className="muted-text">표시할 파이프라인 정보가 없습니다.</p>
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

export default PipelineSection;