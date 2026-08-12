import {
    createContext,
    useCallback,
    useContext,
    useMemo,
    useRef,
    useState,
} from "react";

const ConfirmContext = createContext(null);
const REASON_MAX_LENGTH = 500;
const INCIDENT_ID_MAX_LENGTH = 100;

// 관리자 파괴적 조치(정지, 강제 탈퇴, 결과 삭제, 수동 재큐잉)는 실행 사유를 필수로
// 입력받아야 한다(P2-03). 기존 useConfirm()은 메시지 + 확인/취소만 지원하고 앱 전역의
// 다른 확인창(회원탈퇴 등)이 그 boolean 반환 계약에 의존하고 있어 그대로 확장하면
// 회귀 위험이 크다. 대신 같은 Provider 안에 별도 state로 "사유 입력" 다이얼로그를 하나
// 더 두고, 새 훅 useReasonPrompt()로만 노출한다 — 기존 confirm()은 전혀 건드리지 않는다.
export function ConfirmProvider({ children }) {
    const [request, setRequest] = useState(null);
    const resolveRef = useRef(null);

    const [reasonRequest, setReasonRequest] = useState(null);
    const [reasonDraft, setReasonDraft] = useState("");
    const [incidentIdDraft, setIncidentIdDraft] = useState("");
    const reasonResolveRef = useRef(null);

    const confirm = useCallback((message) => {
        return new Promise((resolve) => {
            resolveRef.current = resolve;
            setRequest({ message });
        });
    }, []);

    const handleConfirm = useCallback(() => {
        if (resolveRef.current) {
            resolveRef.current(true);
            resolveRef.current = null;
        }
        setRequest(null);
    }, []);

    const handleCancel = useCallback(() => {
        if (resolveRef.current) {
            resolveRef.current(false);
            resolveRef.current = null;
        }
        setRequest(null);
    }, []);

    // message: 대상과 영향 범위를 설명하는 안내문. 반환값은 구조화된
    // { reason, incidentId }(확인) 또는 null(취소)입니다.
    const promptReason = useCallback((message) => {
        return new Promise((resolve) => {
            reasonResolveRef.current = resolve;
            setReasonDraft("");
            setIncidentIdDraft("");
            setReasonRequest({ message });
        });
    }, []);

    const handleReasonConfirm = useCallback(() => {
        const trimmedReason = reasonDraft.trim();

        if (!trimmedReason) {
            return;
        }

        if (reasonResolveRef.current) {
            reasonResolveRef.current({
                reason: trimmedReason,
                incidentId: incidentIdDraft.trim() || undefined,
            });
            reasonResolveRef.current = null;
        }
        setReasonRequest(null);
        setReasonDraft("");
        setIncidentIdDraft("");
    }, [incidentIdDraft, reasonDraft]);

    const handleReasonCancel = useCallback(() => {
        if (reasonResolveRef.current) {
            reasonResolveRef.current(null);
            reasonResolveRef.current = null;
        }
        setReasonRequest(null);
        setReasonDraft("");
        setIncidentIdDraft("");
    }, []);

    const value = useMemo(() => confirm, [confirm]);
    const reasonPromptValue = useMemo(() => promptReason, [promptReason]);

    return (
        <ConfirmContext.Provider value={value}>
            <ReasonPromptContext.Provider value={reasonPromptValue}>
                {children}

                {request && (
                    <div className="confirm-dialog-overlay" role="presentation">
                        <div
                            className="confirm-dialog"
                            role="alertdialog"
                            aria-modal="true"
                        >
                            <p>{request.message}</p>

                            <div className="confirm-dialog-actions">
                                <button
                                    type="button"
                                    className="secondary-button"
                                    onClick={handleCancel}
                                >
                                    취소
                                </button>

                                <button
                                    type="button"
                                    className="danger-button"
                                    onClick={handleConfirm}
                                >
                                    확인
                                </button>
                            </div>
                        </div>
                    </div>
                )}

                {reasonRequest && (
                    <div className="confirm-dialog-overlay" role="presentation">
                        <div
                            className="confirm-dialog"
                            role="alertdialog"
                            aria-modal="true"
                        >
                            <p>{reasonRequest.message}</p>

                            <label className="reason-prompt-label">
                                <span>사유 (필수)</span>
                                <textarea
                                    className="text-input reason-prompt-input"
                                    value={reasonDraft}
                                    maxLength={REASON_MAX_LENGTH}
                                    placeholder="이 조치를 실행하는 이유를 입력해주세요."
                                    onChange={(event) => setReasonDraft(event.target.value)}
                                    autoFocus
                                />
                            </label>

                            <label className="reason-prompt-label">
                                <span>인시던트/문의 참조 ID (선택)</span>
                                <input
                                    className="text-input"
                                    value={incidentIdDraft}
                                    maxLength={INCIDENT_ID_MAX_LENGTH}
                                    placeholder="예: INC-2026-001 또는 문의 티켓 번호"
                                    onChange={(event) => setIncidentIdDraft(event.target.value)}
                                />
                            </label>

                            <div className="confirm-dialog-actions">
                                <button
                                    type="button"
                                    className="secondary-button"
                                    onClick={handleReasonCancel}
                                >
                                    취소
                                </button>

                                <button
                                    type="button"
                                    className="danger-button"
                                    onClick={handleReasonConfirm}
                                    disabled={!reasonDraft.trim()}
                                >
                                    확인
                                </button>
                            </div>
                        </div>
                    </div>
                )}
            </ReasonPromptContext.Provider>
        </ConfirmContext.Provider>
    );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useConfirm() {
    const context = useContext(ConfirmContext);
    if (!context) {
        throw new Error("useConfirm must be used within ConfirmProvider");
    }

    return context;
}

const ReasonPromptContext = createContext(null);

// eslint-disable-next-line react-refresh/only-export-components
export function useReasonPrompt() {
    const context = useContext(ReasonPromptContext);
    if (!context) {
        throw new Error("useReasonPrompt must be used within ConfirmProvider");
    }

    return context;
}
