import { useCallback, useEffect, useRef, useState } from "react";
import StateMessage from "../StateMessage";
import { getCoachMessages, sendCoachMessage } from "../../api/coachApi";
import { getErrorMessage } from "../../api/errorUtils";

function getGenerationModeLabel(mode) {
    if (mode === "REAL") {
        return "AI 응답";
    }

    if (mode === "MOCK" || mode === "FALLBACK") {
        return "기본 안내";
    }

    return "";
}

function CoachChatSection({ jobId, isCompleted, disabledReason }) {
    const [messages, setMessages] = useState([]);
    const [loading, setLoading] = useState(true);
    const [sending, setSending] = useState(false);
    const [error, setError] = useState("");
    const [input, setInput] = useState("");
    const messagesEndRef = useRef(null);

    const loadMessages = useCallback(async () => {
        try {
            setLoading(true);
            setError("");

            const response = await getCoachMessages(jobId);
            setMessages(response.data?.messages || []);
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "AI 코치 대화 이력을 불러오는 중 오류가 발생했습니다."
            ));
        } finally {
            setLoading(false);
        }
    }, [jobId]);

    useEffect(() => {
        if (isCompleted && !disabledReason) {
            // eslint-disable-next-line react-hooks/set-state-in-effect -- jobId/isCompleted 변화에 맞춰 서버 대화 이력을 동기화하는 effect입니다.
            loadMessages();
        }
    }, [disabledReason, isCompleted, loadMessages]);

    useEffect(() => {
        if (typeof messagesEndRef.current?.scrollIntoView === "function") {
            messagesEndRef.current.scrollIntoView({ block: "nearest" });
        }
    }, [messages]);

    async function handleSubmit(event) {
        event.preventDefault();

        const content = input.trim();

        if (!content || sending) {
            return;
        }

        try {
            setSending(true);
            setError("");

            const response = await sendCoachMessage(jobId, content);
            setMessages(response.data?.messages || []);
            setInput("");
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "메시지 전송 중 오류가 발생했습니다."
            ));
        } finally {
            setSending(false);
        }
    }

    if (!isCompleted) {
        return (
            <article className="detail-card">
                <h2>AI 코치에게 물어보기</h2>
                <p className="muted-text">분석이 완료된 후 이용할 수 있습니다.</p>
            </article>
        );
    }

    if (disabledReason) {
        return (
            <article className="detail-card">
                <h2>AI 코치에게 물어보기</h2>
                <p className="muted-text">{disabledReason}</p>
            </article>
        );
    }

    return (
        <article className="detail-card">
            <h2>AI 코치에게 물어보기</h2>
            <p className="muted-text">
                이 분석 결과에 대해 궁금한 점을 물어보세요. 하루에 보낼 수 있는 메시지 수는 제한되어 있습니다.
            </p>

            {loading ? (
                <p className="muted-text">대화 이력을 불러오는 중입니다.</p>
            ) : (
                <div className="coach-chat-messages">
                    {messages.length === 0 ? (
                        <p className="muted-text">아직 대화가 없습니다. 궁금한 점을 물어보세요.</p>
                    ) : (
                        messages.map((message, index) => (
                            <div
                                key={`${message.role}-${index}`}
                                className={`coach-chat-message ${message.role === "USER" ? "user" : "assistant"}`}
                            >
                                <div className="coach-chat-message-meta">
                                    <span>{message.role === "USER" ? "나" : "AI 코치"}</span>
                                    {message.role === "ASSISTANT" && getGenerationModeLabel(message.generationMode) && (
                                        <span className="mini-badge muted">
                                            {getGenerationModeLabel(message.generationMode)}
                                        </span>
                                    )}
                                </div>
                                <p>{message.content}</p>
                            </div>
                        ))
                    )}
                    <div ref={messagesEndRef} />
                </div>
            )}

            <StateMessage type="error">{error}</StateMessage>

            <form className="coach-chat-form" onSubmit={handleSubmit}>
                <textarea
                    className="text-input"
                    rows={2}
                    maxLength={1000}
                    placeholder="예: 말이 너무 빠른가요? 어떻게 개선할 수 있을까요?"
                    value={input}
                    onChange={(event) => setInput(event.target.value)}
                    disabled={sending}
                />

                <button
                    type="submit"
                    className="primary-button"
                    disabled={sending || !input.trim()}
                >
                    {sending ? "전송 중..." : "전송"}
                </button>
            </form>
        </article>
    );
}

export default CoachChatSection;
