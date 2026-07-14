import {
    createContext,
    useCallback,
    useContext,
    useMemo,
    useRef,
    useState,
} from "react";

const ConfirmContext = createContext(null);

export function ConfirmProvider({ children }) {
    const [request, setRequest] = useState(null);
    const resolveRef = useRef(null);

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

    const value = useMemo(() => confirm, [confirm]);

    return (
        <ConfirmContext.Provider value={value}>
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
