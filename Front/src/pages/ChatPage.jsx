import React from "react";
import { useEffect, useRef, useState } from "react";
import { useLocation } from "react-router-dom";

import {
  archiveConversation,
  createConversation,
  deleteConversation,
  getConversations,
  getMessages,
  getModels,
  getUsageSummary,
  renameConversation,
  restoreConversation,
  sendChat,
} from "../api/chatApi";
import StateMessage from "../components/StateMessage";
import ActionDialog from "../components/ActionDialog";
import "./ChatPage.css";

const CONVERSATION_PAGE_SIZE = 10;
const MESSAGE_PAGE_SIZE = 50;

export default function ChatPage() {
  const location = useLocation();
  const contextHandled = useRef(false);
  const conversationListReady = useRef(false);
  const requestController = useRef(null);
  const [conversations, setConversations] = useState([]);
  const [activeId, setActiveId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [modelName, setModelName] = useState("Ollama");
  const [usage, setUsage] = useState(null);
  const [content, setContent] = useState("");
  const [error, setError] = useState("");
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState("");
  const [loading, setLoading] = useState(true);
  const [conversationOffset, setConversationOffset] = useState(0);
  const [conversationTotal, setConversationTotal] = useState(0);
  const [messageOffset, setMessageOffset] = useState(0);
  const [messageTotal, setMessageTotal] = useState(0);
  const [showArchived, setShowArchived] = useState(false);
  const [conversationDialog, setConversationDialog] = useState(null);

  const refreshList = async (preferredId, offset = conversationOffset) => {
    const result = await getConversations(CONVERSATION_PAGE_SIZE, offset, showArchived);
    setConversations(result.conversations);
    setConversationTotal(result.total);
    const candidateId = preferredId || activeId;
    const nextId = result.conversations.some((item) => item.id === candidateId)
      ? candidateId
      : result.conversations[0]?.id || null;
    setActiveId(nextId);
    return nextId;
  };

  const refreshUsage = async () => {
    const result = await getUsageSummary();
    setUsage(result.usage);
  };

  useEffect(() => {
    Promise.all([getModels(), getConversations(CONVERSATION_PAGE_SIZE, 0), getUsageSummary()])
      .then(([modelsResult, conversationsResult, usageResult]) => {
        setModelName(
          conversationsResult.conversations[0]?.modelName ||
            modelsResult.models.find((model) => model.provider === "ollama")?.displayName ||
            "Ollama",
        );
        setConversations(conversationsResult.conversations);
        setConversationTotal(conversationsResult.total);
        setActiveId(conversationsResult.conversations[0]?.id || null);
        setUsage(usageResult.usage);
        conversationListReady.current = true;
      })
      .catch((requestError) => setError(requestError.message))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (!conversationListReady.current) return;
    getConversations(CONVERSATION_PAGE_SIZE, conversationOffset, showArchived)
      .then((result) => {
        setConversations(result.conversations);
        setConversationTotal(result.total);
        setActiveId(result.conversations[0]?.id || null);
      })
      .catch((requestError) => setError(requestError.message));
  }, [conversationOffset, showArchived]);

  useEffect(() => {
    const analysisContext = location.state?.analysisContext;
    if (!analysisContext || contextHandled.current) return;
    contextHandled.current = true;
    createConversation({
      title: location.state?.analysisTitle || "발표 분석 코칭",
      system_prompt: `너는 발표 코치다. 아래 분석 결과를 기준으로 구체적이고 실행 가능한 조언을 제공해라.\n\n${analysisContext}`,
    })
      .then(async (result) => {
        setConversationOffset(0);
        await refreshList(result.conversation.id, 0);
        setMessages([]);
        setNotice("분석 결과가 AI 코치의 상담 문맥에 연결되었습니다.");
      })
      .catch((requestError) => setError(requestError.message));
  }, [location.state]);

  useEffect(() => {
    if (!activeId) {
      setMessages([]);
      return;
    }
    getMessages(activeId, MESSAGE_PAGE_SIZE, messageOffset)
      .then((result) => {
        setMessages(result.messages);
        setMessageTotal(result.total);
      })
      .catch((requestError) => setError(requestError.message));
    const activeConversation = conversations.find((conversation) => conversation.id === activeId);
    if (activeConversation?.modelName) setModelName(activeConversation.modelName);
  }, [activeId, messageOffset]);

  const newConversation = async () => {
    setError("");
    try {
      const result = await createConversation({ title: "새 대화" });
      setConversationOffset(0);
      await refreshList(result.conversation.id, 0);
      setMessages([]);
    } catch (requestError) {
      setError(requestError.message);
    }
  };

  const submit = async (event) => {
    event.preventDefault();
    const question = content.trim();
    if (!question || sending) return;
    setError("");
    setSending(true);
    setNotice("");
    try {
      let conversationId = activeId;
      if (!conversationId) {
        const created = await createConversation({ title: question.slice(0, 40) });
        conversationId = created.conversation.id;
        await refreshList(conversationId);
      }
      setContent("");
      requestController.current = new AbortController();
      const result = await sendChat(conversationId, question, requestController.current.signal);
      setMessages((current) => [...current, result.userMessage, result.assistantMessage]);
      setModelName(result.model.displayName || result.model.modelKey);
      await Promise.all([refreshList(conversationId), refreshUsage()]);
    } catch (requestError) {
      if (requestError.name !== "AbortError") {
        setError(requestError.message);
      } else {
        setNotice("응답 표시를 중단했습니다. 서버에서 완료된 응답은 대화에 저장될 수 있습니다.");
      }
    } finally {
      setSending(false);
      requestController.current = null;
    }
  };

  const manageConversation = async (action, title) => {
    if (!activeId) return;
    setError("");
    try {
      if (action === "rename") {
        await renameConversation(activeId, title);
      } else if (action === "archive") {
        await archiveConversation(activeId);
      } else if (action === "restore") {
        await restoreConversation(activeId);
      } else {
        await deleteConversation(activeId);
      }
      setMessages([]);
      const nextOffset =
        conversations.length === 1 && conversationOffset > 0
          ? Math.max(0, conversationOffset - CONVERSATION_PAGE_SIZE)
          : conversationOffset;
      setConversationOffset(nextOffset);
      await refreshList(null, nextOffset);
    } catch (requestError) {
      setError(requestError.message);
    }
  };

  const regenerateLast = async () => {
    const lastUser = [...messages].reverse().find((message) => message.role === "user");
    if (!lastUser || !activeId || sending) return;
    setSending(true);
    setError("");
    try {
      requestController.current = new AbortController();
      const result = await sendChat(activeId, lastUser.content, requestController.current.signal);
      setMessages((current) => [...current, result.userMessage, result.assistantMessage]);
      await Promise.all([refreshList(activeId), refreshUsage()]);
    } catch (requestError) {
      if (requestError.name !== "AbortError") setError(requestError.message);
    } finally {
      setSending(false);
      requestController.current = null;
    }
  };

  return (
    <>
      <main className="chat-layout">
        <aside className="card conversation-panel">
          <div className="panel-heading">
            <h2>대화</h2>
            <button className="button" onClick={newConversation}>
              새 대화
            </button>
          </div>
          <button
            className="text-button"
            onClick={() => {
              setConversationOffset(0);
              setShowArchived((current) => !current);
            }}
          >
            {showArchived ? "활성 대화 보기" : "보관된 대화 보기"}
          </button>
          <div className="conversation-list">
            {conversations.map((conversation) => (
              <button
                key={conversation.id}
                className={`conversation-item ${activeId === conversation.id ? "active" : ""}`}
                onClick={() => {
                  setMessageOffset(0);
                  setActiveId(conversation.id);
                }}
              >
                {conversation.title}
              </button>
            ))}
            {!conversations.length && !loading && (
              <p className="muted-text">아직 대화가 없습니다.</p>
            )}
          </div>
          <div className="conversation-actions">
            <button
              className="text-button"
              disabled={conversationOffset === 0}
              onClick={() =>
                setConversationOffset(Math.max(0, conversationOffset - CONVERSATION_PAGE_SIZE))
              }
            >
              이전
            </button>
            <span>
              {conversationTotal ? Math.floor(conversationOffset / CONVERSATION_PAGE_SIZE) + 1 : 0}{" "}
              페이지
            </span>
            <button
              className="text-button"
              disabled={conversationOffset + CONVERSATION_PAGE_SIZE >= conversationTotal}
              onClick={() => setConversationOffset(conversationOffset + CONVERSATION_PAGE_SIZE)}
            >
              다음
            </button>
          </div>
          {activeId && (
            <div className="conversation-actions">
              <button className="text-button" onClick={() => setConversationDialog("rename")}>
                이름 변경
              </button>
              {showArchived ? (
                <button className="text-button" onClick={() => manageConversation("restore")}>
                  복원
                </button>
              ) : (
                <button className="text-button" onClick={() => manageConversation("archive")}>
                  보관
                </button>
              )}
              <button className="text-button" onClick={() => setConversationDialog("delete")}>
                삭제
              </button>
            </div>
          )}
        </aside>

        <section className="card chat-panel">
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
          {messageTotal > MESSAGE_PAGE_SIZE && (
            <div className="conversation-actions">
              <button
                className="text-button"
                disabled={messageOffset === 0}
                onClick={() => setMessageOffset(Math.max(0, messageOffset - MESSAGE_PAGE_SIZE))}
              >
                이전 메시지
              </button>
              <span>
                {messageOffset + 1}-{Math.min(messageOffset + MESSAGE_PAGE_SIZE, messageTotal)} /{" "}
                {messageTotal}
              </span>
              <button
                className="text-button"
                disabled={messageOffset + MESSAGE_PAGE_SIZE >= messageTotal}
                onClick={() => setMessageOffset(messageOffset + MESSAGE_PAGE_SIZE)}
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
          <form className="chat-form" onSubmit={submit}>
            <textarea
              aria-label="AI 코치에게 보낼 메시지"
              value={content}
              onChange={(event) => setContent(event.target.value)}
              placeholder="메시지를 입력하세요"
              maxLength="8000"
              rows="3"
            />
            <button className="button" disabled={sending || !content.trim()}>
              {sending ? "Ollama 응답 대기 중..." : "전송"}
            </button>
            {sending ? (
              <button
                type="button"
                className="button danger"
                onClick={() => requestController.current?.abort()}
              >
                응답 중단
              </button>
            ) : (
              <button
                type="button"
                className="button secondary"
                disabled={!messages.length}
                onClick={regenerateLast}
              >
                마지막 응답 다시 생성
              </button>
            )}
          </form>
        </section>
      </main>
      <ActionDialog
        open={conversationDialog === "rename"}
        title="새 대화 이름"
        initialValue={conversations.find((item) => item.id === activeId)?.title || ""}
        confirmLabel="이름 변경"
        onCancel={() => setConversationDialog(null)}
        onConfirm={(title) => {
          setConversationDialog(null);
          manageConversation("rename", title);
        }}
      />
      <ActionDialog
        open={conversationDialog === "delete"}
        title="이 대화를 삭제하시겠습니까?"
        description="삭제한 대화는 복구할 수 없습니다."
        confirmLabel="대화 삭제"
        danger
        onCancel={() => setConversationDialog(null)}
        onConfirm={() => {
          setConversationDialog(null);
          manageConversation("delete");
        }}
      />
    </>
  );
}
