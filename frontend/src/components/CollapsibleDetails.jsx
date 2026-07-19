import { useRef, useState } from "react";

// 기술적/상세 데이터(프레임별 표, STT 세그먼트 등)를 기본은 접어두고 필요할 때만
// 펼쳐 보게 하는 공용 래퍼입니다. 네이티브 <details>를 그대로 쓰기 때문에 open 여부를
// React state로 관리하지 않습니다 — open prop을 명시하면 폴링 등으로 부모가 재렌더링될
// 때마다 사용자가 펼친 상태가 강제로 다시 닫히므로, 초기 렌더 이후에는 브라우저가
// 스스로 토글 상태를 유지하게 둡니다.
// children은 사용자가 한 번이라도 펼치기 전까지는 마운트하지 않습니다. STT
// segment처럼 개수 제한이 없는 목록은 접힌 상태에서도 (네이티브 <details>가
// display:none으로 숨길 뿐) React가 매번 재조정을 해야 해서, 분석 중 폴링처럼
// 부모가 자주 재렌더링되는 화면에서 사용자가 펼쳐보지도 않을 표를 계속 그리는
// 낭비가 생깁니다. 한 번 펼친 뒤에는 다시 접어도 마운트 상태를 유지해 재접힘마다
// 다시 그리지 않습니다.
// headingLevel을 지정하면 summary가 스크린 리더의 헤딩 탐색(H 키)에도 잡히도록
// role="heading"을 부여합니다. 이 접힘 섹션이 이전에는 h3 등 실제 헤딩이었던
// 자리를 대신하는 경우에만 지정하세요.
function CollapsibleDetails({ summary, children, className = "", headingLevel }) {
    const headingProps = headingLevel
        ? { role: "heading", "aria-level": headingLevel }
        : {};

    const detailsRef = useRef(null);
    const [hasOpened, setHasOpened] = useState(false);

    function handleToggle() {
        if (detailsRef.current?.open) {
            setHasOpened(true);
        }
    }

    return (
        <details
            ref={detailsRef}
            className={`collapsible-details ${className}`.trim()}
            onToggle={handleToggle}
        >
            <summary {...headingProps}>{summary}</summary>
            <div className="collapsible-details-content">
                {hasOpened ? children : null}
            </div>
        </details>
    );
}

export default CollapsibleDetails;
