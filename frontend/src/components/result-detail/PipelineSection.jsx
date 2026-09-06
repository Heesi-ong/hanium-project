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
        <article className="detail-card result-pipeline-card">
            <header className="result-pipeline-card-header">
                <div>
                    <span>Execution metadata</span>
                    <h2>파이프라인 정보</h2>
                    <p>분석 단계별 소요 시간 등 내부 실행 정보입니다. 일반적인 결과 확인에는 필요하지 않습니다.</p>
                </div>
                <span>{entries.length}개 항목</span>
            </header>

            {entries.length === 0 ? (
                <div className="result-pipeline-empty">
                    <span aria-hidden="true">—</span>
                    <div>
                        <strong>표시할 파이프라인 정보가 없습니다.</strong>
                        <p>백엔드 응답에 실행 메타데이터가 포함된 경우에만 표시됩니다.</p>
                    </div>
                </div>
            ) : (
                <CollapsibleDetails
                    headingLevel={3}
                    className="result-pipeline-details"
                    summary="파이프라인 상세 정보 — 자세히 보기"
                >
                    <dl className="result-pipeline-list">
                        {entries.map(([key, value]) => (
                            <div className="result-pipeline-item" key={key}>
                                <dt>{key}</dt>

                                {typeof value === "object" && value !== null ? (
                                    <dd>
                                        <pre tabIndex={0} aria-label={`${key} 상세 값`}>
                                            {formatObjectValue(value)}
                                        </pre>
                                    </dd>
                                ) : (
                                    <dd><code>{formatObjectValue(value)}</code></dd>
                                )}
                            </div>
                        ))}
                    </dl>
                </CollapsibleDetails>
            )}
        </article>
    );
}

export default PipelineSection;
