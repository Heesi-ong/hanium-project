// 대화 목록, 보관함 전환, 페이지 이동, 대화 관리 액션을 보여주는 사이드바다.
function ConversationSidebar({
  activeId,
  conversations,
  loading,
  nextCursor,
  offset,
  onChangePage,
  onManage,
  onNew,
  onSelect,
  onToggleArchived,
  onOpenDialog,
  pageSize,
  showArchived,
  total,
}) {
  return (
    <aside className="card conversation-panel">
      <div className="panel-heading">
        <h2>대화</h2>
        <button className="button" onClick={onNew}>
          새 대화
        </button>
      </div>
      <button className="text-button" onClick={onToggleArchived}>
        {showArchived ? "활성 대화 보기" : "보관된 대화 보기"}
      </button>
      <div className="conversation-list">
        {conversations.map((conversation) => (
          <button
            key={conversation.id}
            className={`conversation-item ${activeId === conversation.id ? "active" : ""}`}
            onClick={() => onSelect(conversation.id)}
          >
            {conversation.title}
          </button>
        ))}
        {!conversations.length && !loading && <p className="muted-text">아직 대화가 없습니다.</p>}
      </div>
      <div className="conversation-actions">
        <button
          className="text-button"
          disabled={offset === 0}
          onClick={() => onChangePage(Math.max(0, offset - pageSize))}
        >
          이전
        </button>
        <span>{total ? Math.floor(offset / pageSize) + 1 : 0} 페이지</span>
        <button
          className="text-button"
          disabled={!nextCursor}
          onClick={() => onChangePage(offset + pageSize, nextCursor)}
        >
          다음
        </button>
      </div>
      {activeId && (
        <div className="conversation-actions">
          <button className="text-button" onClick={() => onOpenDialog("rename")}>
            이름 변경
          </button>
          {showArchived ? (
            <button className="text-button" onClick={() => onManage("restore")}>
              복원
            </button>
          ) : (
            <button className="text-button" onClick={() => onManage("archive")}>
              보관
            </button>
          )}
          <button className="text-button" onClick={() => onOpenDialog("delete")}>
            삭제
          </button>
        </div>
      )}
    </aside>
  );
}

export default ConversationSidebar;
