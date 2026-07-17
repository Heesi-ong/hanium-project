// 기술적/상세 데이터(프레임별 표, STT 세그먼트 등)를 기본은 접어두고 필요할 때만
// 펼쳐 보게 하는 공용 래퍼입니다. 네이티브 <details>를 그대로 쓰기 때문에 open 여부를
// React state로 관리하지 않습니다 — open prop을 명시하면 폴링 등으로 부모가 재렌더링될
// 때마다 사용자가 펼친 상태가 강제로 다시 닫히므로, 초기 렌더 이후에는 브라우저가
// 스스로 토글 상태를 유지하게 둡니다.
function CollapsibleDetails({ summary, children, className = "" }) {
    return (
        <details className={`collapsible-details ${className}`.trim()}>
            <summary>{summary}</summary>
            <div className="collapsible-details-content">{children}</div>
        </details>
    );
}

export default CollapsibleDetails;
