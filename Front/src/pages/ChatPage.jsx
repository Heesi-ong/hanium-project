// 분석 결과와 연결되는 발표 코칭 대화 목록과 메시지 송수신 화면을 담당한다.
import React from "react";
import { useCallback, useEffect, useRef, useState } from "react";
import { useLocation, useParams, useSearchParams } from "react-router-dom";

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
import ActionDialog from "../components/ActionDialog";
import ChatPanel from "../features/chat/ChatPanel";
import ConversationSidebar from "../features/chat/ConversationSidebar";
import usePresentationConversation from "../features/chat/usePresentationConversation";
import "./ChatPage.css";

const CONVERSATION_PAGE_SIZE = 10;
const MESSAGE_PAGE_SIZE = 50;

export default function ChatPage({ user }) {
  const location = useLocation();
  const { resultId: routeResultId } = useParams();
  const [searchParams] = useSearchParams();
  const conversationListReady = useRef(false);
  const requestController = useRef(null);
  const conversationController = useRef(null);
  const messageController = useRef(null);
  const [conversations, setConversations] = useState([]);
  const [activeId, setActiveId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [modelName, setModelName] = useState("Ollama");
  const [modelLoading, setModelLoading] = useState(true);
  const [modelError, setModelError] = useState("");
  const [usage, setUsage] = useState(null);
  const [usageLoading, setUsageLoading] = useState(true);
  const [usageError, setUsageError] = useState("");
  const [content, setContent] = useState("");
  const [error, setError] = useState("");
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState("");
  const [loading, setLoading] = useState(true);
  const [conversationOffset, setConversationOffset] = useState(0);
  const [conversationTotal, setConversationTotal] = useState(0);
  const [conversationCursorPages, setConversationCursorPages] = useState([null]);
  const [conversationNextCursor, setConversationNextCursor] = useState(null);
  const [messageOffset, setMessageOffset] = useState(0);
  const [messageTotal, setMessageTotal] = useState(0);
  const [messageCursorPages, setMessageCursorPages] = useState([null]);
  const [messageNextCursor, setMessageNextCursor] = useState(null);
  const [showArchived, setShowArchived] = useState(false);
  const [conversationDialog, setConversationDialog] = useState(null);

  const loadMessages = useCallback(
    async (conversationId = activeId, offset = messageOffset) => {
      if (!conversationId) {
        setMessages([]);
        return;
      }
      messageController.current?.abort();
      const controller = new AbortController();
      messageController.current = controller;
      const cursor = messageCursorPages[Math.floor(offset / MESSAGE_PAGE_SIZE)] || "";
      const result = await getMessages(
        conversationId,
        MESSAGE_PAGE_SIZE,
        0,
        cursor,
        controller.signal,
      );
      if (messageController.current !== controller) return;
      setMessages(result.messages);
      setMessageTotal(result.total);
      setMessageNextCursor(result.next_cursor || null);
    },
    [activeId, messageCursorPages, messageOffset],
  );

  const refreshList = async (preferredId, offset = conversationOffset) => {
    conversationController.current?.abort();
    const controller = new AbortController();
    conversationController.current = controller;
    const cursor = conversationCursorPages[Math.floor(offset / CONVERSATION_PAGE_SIZE)] || "";
    const result = await getConversations(
      CONVERSATION_PAGE_SIZE,
      0,
      showArchived,
      cursor,
      controller.signal,
    );
    if (conversationController.current !== controller) return null;
    setConversations(result.conversations);
    setConversationTotal(result.total);
    setConversationNextCursor(result.next_cursor || null);
    const candidateId = preferredId || activeId;
    const nextId = result.conversations.some((item) => item.id === candidateId)
      ? candidateId
      : result.conversations[0]?.id || null;
    if (nextId !== activeId) {
      setMessageOffset(0);
      setMessageCursorPages([null]);
    }
    setActiveId(nextId);
    return nextId;
  };

  const refreshUsage = async () => {
    setUsageLoading(true);
    setUsageError("");
    try {
      const result = await getUsageSummary();
      setUsage(result.usage);
    } catch (requestError) {
      setUsageError(requestError.message || "사용량을 불러오지 못했습니다.");
    } finally {
      setUsageLoading(false);
    }
  };

  const refreshModels = async () => {
    setModelLoading(true);
    setModelError("");
    try {
      const result = await getModels();
      setModelName(
        result.models.find((model) => model.provider === "ollama")?.displayName || "Ollama",
      );
    } catch (requestError) {
      setModelName("Ollama");
      setModelError(requestError.message || "모델 정보를 불러오지 못했습니다.");
    } finally {
      setModelLoading(false);
    }
  };

  const practiceQuestion = usePresentationConversation({
    loading,
    locationState: location.state,
    refreshList,
    routeResultId,
    searchParams,
    setActiveId,
    setConversationCursorPages,
    setConversationOffset,
    setError,
    setMessages,
    setNotice,
    userId: user?.id,
  });

  useEffect(() => {
    const controller = new AbortController();
    setModelLoading(true);
    setUsageLoading(true);
    getModels(controller.signal)
      .then((modelsResult) => {
        setModelName(
          modelsResult.models.find((model) => model.provider === "ollama")?.displayName || "Ollama",
        );
      })
      .catch((requestError) => {
        if (requestError.name !== "AbortError") {
          setModelName("Ollama");
          setModelError(requestError.message || "모델 정보를 불러오지 못했습니다.");
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setModelLoading(false);
      });
    getUsageSummary(controller.signal)
      .then((usageResult) => setUsage(usageResult.usage))
      .catch((requestError) => {
        if (requestError.name !== "AbortError") {
          setUsageError(requestError.message || "사용량을 불러오지 못했습니다.");
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setUsageLoading(false);
      });
    getConversations(CONVERSATION_PAGE_SIZE, 0, false, "", controller.signal)
      .then((conversationsResult) => {
        setConversations(conversationsResult.conversations);
        setConversationTotal(conversationsResult.total);
        setConversationNextCursor(conversationsResult.next_cursor || null);
        setActiveId(conversationsResult.conversations[0]?.id || null);
        conversationListReady.current = true;
      })
      .catch((requestError) => {
        if (requestError.name !== "AbortError") setError(requestError.message);
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!conversationListReady.current) return;
    conversationController.current?.abort();
    const controller = new AbortController();
    conversationController.current = controller;
    const cursor =
      conversationCursorPages[Math.floor(conversationOffset / CONVERSATION_PAGE_SIZE)] || "";
    getConversations(CONVERSATION_PAGE_SIZE, 0, showArchived, cursor, controller.signal)
      .then((result) => {
        if (conversationController.current !== controller) return;
        setConversations(result.conversations);
        setConversationTotal(result.total);
        setConversationNextCursor(result.next_cursor || null);
        setMessageOffset(0);
        setMessageCursorPages([null]);
        setActiveId(result.conversations[0]?.id || null);
      })
      .catch((requestError) => {
        if (requestError.name !== "AbortError") setError(requestError.message);
      });
    return () => controller.abort();
  }, [conversationOffset, showArchived, conversationCursorPages]);

  useEffect(() => {
    loadMessages().catch((requestError) => {
      if (requestError.name !== "AbortError") setError(requestError.message);
    });
    return () => messageController.current?.abort();
  }, [loadMessages]);

  useEffect(() => {
    const activeConversation = conversations.find((conversation) => conversation.id === activeId);
    if (activeConversation?.modelName) setModelName(activeConversation.modelName);
  }, [activeId, conversations]);

  useEffect(
    () => () => {
      requestController.current?.abort();
      conversationController.current?.abort();
      messageController.current?.abort();
    },
    [],
  );

  const newConversation = async () => {
    setError("");
    try {
      const result = await createConversation({ title: "새 대화" });
      setConversationOffset(0);
      setConversationCursorPages([null]);
      await refreshList(result.conversation.id, 0);
      setMessages([]);
      setMessageOffset(0);
      setMessageCursorPages([null]);
    } catch (requestError) {
      setError(requestError.message);
    }
  };

  const submit = async (event) => {
    event.preventDefault();
    const question = content.trim();
    if (!question || sending) return;
    let conversationId = activeId;
    setError("");
    setSending(true);
    setNotice("");
    try {
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
      await Promise.allSettled([refreshList(conversationId), refreshUsage()]);
    } catch (requestError) {
      if (requestError.name !== "AbortError") {
        setError(requestError.message);
      } else {
        setNotice("응답 표시를 중단했습니다. 서버에서 완료된 응답은 대화에 저장될 수 있습니다.");
        await loadMessages(conversationId);
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
      await Promise.allSettled([refreshList(activeId), refreshUsage()]);
    } catch (requestError) {
      if (requestError.name !== "AbortError") {
        setError(requestError.message);
      } else {
        setNotice("응답 표시를 중단했습니다. 서버에서 완료된 응답은 대화에 저장될 수 있습니다.");
        await loadMessages(activeId);
      }
    } finally {
      setSending(false);
      requestController.current = null;
    }
  };

  const changeConversationPage = (nextOffset, nextCursor) => {
    if (nextCursor) {
      setConversationCursorPages((current) => {
        const next = [...current];
        next[Math.floor(nextOffset / CONVERSATION_PAGE_SIZE)] = nextCursor;
        return next;
      });
    }
    setConversationOffset(nextOffset);
  };

  const changeMessagePage = (nextOffset, nextCursor) => {
    if (nextCursor) {
      setMessageCursorPages((current) => {
        const next = [...current];
        next[Math.floor(nextOffset / MESSAGE_PAGE_SIZE)] = nextCursor;
        return next;
      });
    }
    setMessageOffset(nextOffset);
  };

  return (
    <>
      <main className="chat-layout">
        <ConversationSidebar
          activeId={activeId}
          conversations={conversations}
          loading={loading}
          nextCursor={conversationNextCursor}
          offset={conversationOffset}
          onChangePage={changeConversationPage}
          onManage={manageConversation}
          onNew={newConversation}
          onOpenDialog={setConversationDialog}
          onSelect={(conversationId) => {
            setMessageOffset(0);
            setMessageCursorPages([null]);
            setActiveId(conversationId);
          }}
          onToggleArchived={() => {
            setConversationOffset(0);
            setConversationCursorPages([null]);
            setShowArchived((current) => !current);
          }}
          pageSize={CONVERSATION_PAGE_SIZE}
          showArchived={showArchived}
          total={conversationTotal}
        />
        <ChatPanel
          content={content}
          error={error}
          loading={loading}
          messageNextCursor={messageNextCursor}
          messageOffset={messageOffset}
          messagePageSize={MESSAGE_PAGE_SIZE}
          messageTotal={messageTotal}
          messages={messages}
          modelError={modelError}
          modelLoading={modelLoading}
          modelName={modelName}
          notice={notice}
          onChangeContent={setContent}
          onChangeMessagePage={changeMessagePage}
          onRefreshModels={refreshModels}
          onRefreshUsage={refreshUsage}
          onRegenerate={regenerateLast}
          onStop={() => requestController.current?.abort()}
          onSubmit={submit}
          practiceQuestion={practiceQuestion}
          sending={sending}
          usage={usage}
          usageError={usageError}
          usageLoading={usageLoading}
        />
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
