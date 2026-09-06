import { useCallback, useEffect, useRef, useState } from "react";
import StateMessage from "../StateMessage";
import { getCoachMessages, resetCoachConversation, sendCoachMessage } from "../../api/coachApi";
import { getErrorMessage } from "../../api/errorUtils";
import { useConfirm } from "../../context/ConfirmContext";

function getGenerationModeLabel(mode) {
    if (mode === "REAL") {
        return "실제 AI 응답";
    }

    if (mode === "FALLBACK") {
        return "AI 실패 후 대체 안내";
    }

    if (mode === "MOCK") {
        return "Mock 안내";
    }

    if (mode === "SKIPPED") {
        return "AI 사용 안 함";
    }

    return "";
}

function getGenerationModeClassName(mode) {
    if (mode === "REAL") {
        return "mini-badge success";
    }

    if (mode === "FALLBACK") {
        return "mini-badge warning";
    }

    return "mini-badge muted";
}

function CoachUnavailableCard({ reason, badge }) {
    return (
        <article
            className="detail-card coach-chat-card unavailable"
            aria-labelledby="coach-chat-title"
        >
            <header className="coach-chat-header">
                <div>
                    <span className="result-feedback-kicker">Result Q&amp;A</span>
                    <h2 id="coach-chat-title">AI 코치에게 물어보기</h2>
                    <p>분석 결과를 바탕으로 다음 연습 방법을 질문할 수 있습니다.</p>
                </div>
                <span className="mini-badge muted">{badge}</span>
            </header>
            <div className="coach-chat-unavailable" role="note">
                <span aria-hidden="true">i</span>
                <div>
                    <strong>현재 AI 코치를 사용할 수 없습니다.</strong>
                    <p>{reason}</p>
                </div>
            </div>
        </article>
    );
}

function CoachChatSection({ jobId, isCompleted, disabledReason }) {
    const confirm = useConfirm();
    const [messages, setMessages] = useState([]);
    const [dailyUsage, setDailyUsage] = useState(null);
    const [loading, setLoading] = useState(true);
    const [sending, setSending] = useState(false);
    const [resetting, setResetting] = useState(false);
    const [error, setError] = useState("");
    const [input, setInput] = useState("");
    const messagesEndRef = useRef(null);
    const usageCapacity = Number.isFinite(dailyUsage?.capacity)
        ? Math.max(0, dailyUsage.capacity)
        : 0;
    const usageUsed = Number.isFinite(dailyUsage?.used)
        ? Math.min(Math.max(0, dailyUsage.used), usageCapacity)
        : 0;
    const isUsageExhausted = Boolean(dailyUsage && dailyUsage.remaining <= 0);

    const loadMessages = useCallback(async () => {
        try {
            setLoading(true);
            setError("");

            const response = await getCoachMessages(jobId);
            setMessages(response.data?.messages || []);
            setDailyUsage(response.data?.dailyUsage || null);
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
            setDailyUsage(response.data?.dailyUsage || null);
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

    async function handleResetConversation() {
        const confirmed = await confirm(
            "이 대화 내용을 모두 삭제하고 새로 시작하시겠습니까? 삭제된 대화는 복구할 수 없습니다."
        );

        if (!confirmed) {
            return;
        }

        try {
            setResetting(true);
            setError("");

            await resetCoachConversation(jobId);

            setMessages([]);
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "대화 초기화 중 오류가 발생했습니다."
            ));
        } finally {
            setResetting(false);
        }
    }

    if (!isCompleted) {
        return (
            <CoachUnavailableCard
                badge="분석 완료 후 사용"
                reason="분석이 완료된 후 이용할 수 있습니다."
            />
        );
    }

    if (disabledReason) {
        return (
            <CoachUnavailableCard
                badge="사용 불가"
                reason={disabledReason}
            />
        );
    }

    return (
        <article
            className="detail-card coach-chat-card"
            aria-labelledby="coach-chat-title"
        >
            <header className="coach-chat-header">
                <div>
                    <span className="result-feedback-kicker">Result Q&amp;A</span>
                    <h2 id="coach-chat-title">AI 코치에게 물어보기</h2>
                    <p>이 결과에서 궁금한 점을 질문하고 다음 연습 행동을 구체화하세요.</p>
                </div>
                {!loading && messages.length > 0 && (
                    <button
                        type="button"
                        className="secondary-button coach-chat-reset"
                        onClick={handleResetConversation}
                        disabled={resetting || sending}
                    >
                        {resetting ? "초기화 중..." : "대화 초기화"}
                    </button>
                )}
            </header>

            {dailyUsage ? (
                <div className="coach-chat-usage" aria-label="AI 코치 일일 사용량">
                    <div className="coach-chat-usage-copy">
                        <strong>오늘 질문 사용량</strong>
                        <span>
                            오늘 {dailyUsage.capacity}회 중 {usageUsed}회 사용했습니다.
                            {" "}(남은 횟수: {dailyUsage.remaining}회)
                        </span>
                    </div>
                    <div
                        className="coach-chat-usage-track"
                        role="progressbar"
                        aria-label="오늘 AI 코치 질문 사용량"
                        aria-valuemin={0}
                        aria-valuemax={usageCapacity}
                        aria-valuenow={usageUsed}
                        aria-valuetext={`${usageCapacity}회 중 ${usageUsed}회 사용`}
                    >
                        <span
                            style={{
                                width: usageCapacity > 0
                                    ? `${Math.min(100, (usageUsed / usageCapacity) * 100)}%`
                                    : "0%",
                            }}
                        />
                    </div>
                </div>
            ) : (
                <p className="coach-chat-limit-note" id="coach-chat-limit-note">
                    하루에 보낼 수 있는 메시지 수는 제한되어 있습니다.
                </p>
            )}

            {isUsageExhausted && (
                <StateMessage type="error">
                    오늘 사용 가능한 AI 코치 메시지를 모두 사용했습니다. 내일 다시 시도해주세요.
                </StateMessage>
            )}

            {loading ? (
                <div className="coach-chat-loading" role="status" aria-live="polite">
                    <span className="coach-chat-loading-dot" aria-hidden="true" />
                    <span>대화 이력을 불러오는 중입니다.</span>
                </div>
            ) : (
                <div
                    className="coach-chat-messages"
                    role="log"
                    aria-label="AI 코치 대화"
                    aria-live="polite"
                >
                    {messages.length === 0 ? (
                        <div className="coach-chat-empty">
                            <span aria-hidden="true">?</span>
                            <div>
                                <strong>아직 대화가 없습니다.</strong>
                                <p>분석 점수나 피드백에서 궁금한 내용을 질문해보세요.</p>
                            </div>
                        </div>
                    ) : (
                        messages.map((message, index) => (
                            <div
                                key={`${message.role}-${index}`}
                                className={`coach-chat-message ${message.role === "USER" ? "user" : "assistant"}`}
                            >
                                <div className="coach-chat-message-meta">
                                    <span>{message.role === "USER" ? "나" : "AI 코치"}</span>
                                    {message.role === "ASSISTANT" && getGenerationModeLabel(message.generationMode) && (
                                        <span className={getGenerationModeClassName(message.generationMode)}>
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

            <form
                className="coach-chat-form"
                onSubmit={handleSubmit}
                aria-busy={sending}
            >
                <div className="coach-chat-input-wrap">
                    <div className="coach-chat-input-heading">
                        <label htmlFor="coach-chat-input">질문 작성</label>
                        <span>{input.length} / 1000</span>
                    </div>
                    <textarea
                        id="coach-chat-input"
                        className="text-input"
                        rows={3}
                        maxLength={1000}
                        placeholder="예: 말이 너무 빠른가요? 어떻게 개선할 수 있을까요?"
                        value={input}
                        onChange={(event) => setInput(event.target.value)}
                        disabled={sending || isUsageExhausted}
                        aria-describedby={!dailyUsage ? "coach-chat-limit-note" : undefined}
                    />
                </div>

                <button
                    type="submit"
                    className="primary-button"
                    disabled={sending || !input.trim() || isUsageExhausted}
                >
                    {sending ? "전송 중..." : "전송"}
                </button>
            </form>
        </article>
    );
}

export default CoachChatSection;
