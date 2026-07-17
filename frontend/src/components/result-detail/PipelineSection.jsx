import CollapsibleDetails from "../CollapsibleDetails";

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
            <p className="muted-text">분석 단계별 소요 시간 등 내부 실행 정보입니다. 일반적인 결과 확인에는 필요하지 않습니다.</p>

            {entries.length === 0 ? (
                <p className="muted-text">표시할 파이프라인 정보가 없습니다.</p>
            ) : (
                <CollapsibleDetails summary="파이프라인 상세 정보 — 자세히 보기">
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
                </CollapsibleDetails>
            )}
        </article>
    );
}

export default PipelineSection;