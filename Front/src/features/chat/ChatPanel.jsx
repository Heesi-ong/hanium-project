// 채팅 메시지 목록, 이전 메시지 로딩, 메시지 입력·전송 UI를 담당한다.
import StateMessage from "../../components/StateMessage";

function ChatPanel({
  content,
  error,
  loading,
  messageNextCursor,
  messageOffset,
  messagePageSize,
  messageTotal,
  messages,
  modelError,
  modelLoading,
  modelName,
  notice,
  onChangeContent,
  onChangeMessagePage,
  onRegenerate,
  onRefreshModels,
  onRefreshUsage,
  onStop,
  onSubmit,
  practiceQuestion,
  sending,
  usage,
  usageError,
  usageLoading,
}) {
  return (
    <section className="card chat-panel">
      {practiceQuestion && (
        <StateMessage title="AI 청중의 예상 질문" compact>
          {practiceQuestion}
        </StateMessage>
      )}
      <div className="panel-heading">
        <div>
          <h1>로컬 AI 채팅</h1>
          <p className="muted-text">{modelName}</p>
        </div>
        {usage && (
          <span className="usage-chip">
            총 {usage.totalTokens} tokens · 비용 {usage.estimatedCost}
          </span>
        )}
      </div>
      {(modelError || usageError) && (
        <StateMessage compact type="error" title="일부 부가 정보를 불러오지 못했습니다.">
          <div className="chat-optional-errors">
            {modelError && (
              <button className="text-button" onClick={onRefreshModels} disabled={modelLoading}>
                모델 정보 다시 불러오기
              </button>
            )}
            {usageError && (
              <button className="text-button" onClick={onRefreshUsage} disabled={usageLoading}>
                사용량 다시 불러오기
              </button>
            )}
          </div>
        </StateMessage>
      )}
      <div className="message-list">
        {messages.map((message) => (
          <article key={message.id} className={`message ${message.role}`}>
            <strong>{message.role === "user" ? "나" : "Ollama"}</strong>
            <p>{message.content}</p>
            {message.role === "assistant" && (
              <button
                className="message-copy"
                onClick={() => navigator.clipboard.writeText(message.content)}
              >
                복사
              </button>
            )}
          </article>
        ))}
        {loading && <StateMessage compact title="대화 목록을 불러오는 중입니다." />}
        {!messages.length && !loading && (
          <StateMessage compact type="empty">
            질문을 입력하면 대화가 시작됩니다.
          </StateMessage>
        )}
      </div>
      {messageTotal > messagePageSize && (
        <div className="conversation-actions">
          <button
            className="text-button"
            disabled={messageOffset === 0}
            onClick={() => onChangeMessagePage(Math.max(0, messageOffset - messagePageSize))}
          >
            이전 메시지
          </button>
          <span>
            {messageOffset + 1}-{Math.min(messageOffset + messagePageSize, messageTotal)} /{" "}
            {messageTotal}
          </span>
          <button
            className="text-button"
            disabled={!messageNextCursor}
            onClick={() => onChangeMessagePage(messageOffset + messagePageSize, messageNextCursor)}
          >
            다음 메시지
          </button>
        </div>
      )}
      {error && (
        <StateMessage compact type="error">
          {error}
        </StateMessage>
      )}
      {notice && (
        <StateMessage compact type="success">
          {notice}
        </StateMessage>
      )}
      <form className="chat-form" onSubmit={onSubmit}>
        <textarea
          aria-label="AI 코치에게 보낼 메시지"
          value={content}
          onChange={(event) => onChangeContent(event.target.value)}
          placeholder="메시지를 입력하세요"
          maxLength="8000"
          rows="3"
        />
        <button className="button" disabled={sending || !content.trim()}>
          {sending ? "Ollama 응답 대기 중..." : "전송"}
        </button>
        {sending ? (
          <button type="button" className="button danger" onClick={onStop}>
            응답 중단
          </button>
        ) : (
          <button
            type="button"
            className="button secondary"
            disabled={!messages.length}
            onClick={onRegenerate}
          >
            마지막 응답 다시 생성
          </button>
        )}
      </form>
    </section>
  );
}

export default ChatPanel;
