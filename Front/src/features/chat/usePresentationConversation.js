// 분석 결과 문맥을 기반으로 발표 코칭 대화를 생성하거나 기존 대화를 복구한다.
import { useEffect, useRef, useState } from "react";

import { createConversation, getMessages } from "../../api/chatApi";
import { presentationChatSessionKey, presentationContextDigest } from "./presentationChatSession";

export default function usePresentationConversation({
  loading,
  locationState,
  refreshList,
  routeResultId,
  searchParams,
  setActiveId,
  setConversationCursorPages,
  setConversationOffset,
  setError,
  setMessages,
  setNotice,
  userId,
}) {
  const contextHandled = useRef("");
  const [practiceQuestion, setPracticeQuestion] = useState("");

  useEffect(() => {
    const analysisContext = locationState?.analysisContext;
    const analysisResultId = routeResultId || locationState?.analysisResultId;
    const question = searchParams.get("question") || locationState?.practiceQuestion || "";
    const contextKey = analysisResultId
      ? `result:${analysisResultId}:question:${question}`
      : analysisContext
        ? `context:${presentationContextDigest(`${analysisContext}:${question}`)}`
        : "";
    if (!contextKey || contextHandled.current === contextKey || loading) return;
    contextHandled.current = contextKey;
    setPracticeQuestion(question);
    const sessionKey = presentationChatSessionKey(userId, contextKey);
    const savedConversationId = analysisResultId ? window.sessionStorage.getItem(sessionKey) : null;
    let cancelled = false;
    const createPresentationConversation = async () => {
      const result = await createConversation({
        title: locationState?.analysisTitle || "발표 분석 코칭",
        analysis_result_id: analysisResultId,
        practice_question: question || undefined,
        system_prompt: analysisResultId
          ? undefined
          : `너는 발표 코치이자 청중이다. 아래 분석 결과와 예상 질문을 기준으로 사용자가 직접 작성한 답변을 평가해라. 명확성, 근거, 간결성, 설득력을 각각 평가하고, 개선된 답변 예시와 후속 질문 한 개를 제공해라. 질문 자체를 사용자 답변으로 간주하지 마라.\n\n${analysisContext}`,
      });
      if (cancelled) return;
      if (analysisResultId) {
        window.sessionStorage.setItem(sessionKey, String(result.conversation.id));
      }
      setConversationOffset(0);
      setConversationCursorPages([null]);
      await refreshList(result.conversation.id, 0);
      if (cancelled) return;
      setMessages([]);
      setNotice("분석 결과가 AI 코치의 상담 문맥에 연결되었습니다.");
    };
    const restoreOrCreate = async () => {
      if (savedConversationId) {
        try {
          await getMessages(Number(savedConversationId), 1, 0);
          if (cancelled) return;
          setActiveId(Number(savedConversationId));
          setNotice("분석 결과가 AI 코치의 상담 문맥에 연결되었습니다.");
          return;
        } catch (requestError) {
          if (requestError.status !== 404) throw requestError;
          window.sessionStorage.removeItem(sessionKey);
        }
      }
      await createPresentationConversation();
    };
    restoreOrCreate().catch((requestError) => {
      if (!cancelled) setError(requestError.message);
    });
    return () => {
      cancelled = true;
    };
  }, [
    loading,
    locationState,
    refreshList,
    routeResultId,
    searchParams,
    setActiveId,
    setConversationCursorPages,
    setConversationOffset,
    setError,
    setMessages,
    setNotice,
    userId,
  ]);

  return practiceQuestion;
}
