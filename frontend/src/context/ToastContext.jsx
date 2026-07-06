import {
    createContext,
    useCallback,
    useContext,
    useEffect,
    useMemo,
    useRef,
    useState,
} from "react";

const ToastContext = createContext(null);
const SUPPORTED_TOAST_TYPES = ["success", "error", "info"];

export function ToastProvider({ children }) {
    const nextIdRef = useRef(1);
    const timersRef = useRef(new Map());
    const [toasts, setToasts] = useState([]);

    useEffect(() => {
        const timers = timersRef.current;

        return () => {
            timers.forEach((timerId) => clearTimeout(timerId));
            timers.clear();
        };
    }, []);

    const dismissToast = useCallback((id) => {
        const timerId = timersRef.current.get(id);
        if (timerId) {
            clearTimeout(timerId);
            timersRef.current.delete(id);
        }

        setToasts((currentToasts) =>
            currentToasts.filter((toast) => toast.id !== id)
        );
    }, []);

    const showToast = useCallback((message, type = "info") => {
        const id = nextIdRef.current;
        nextIdRef.current += 1;
        const toastType = SUPPORTED_TOAST_TYPES.includes(type) ? type : "info";

        setToasts((currentToasts) => [
            ...currentToasts,
            {
                id,
                type: toastType,
                message,
            },
        ]);

        const timerId = setTimeout(() => {
            dismissToast(id);
        }, 4000);

        timersRef.current.set(id, timerId);
    }, [dismissToast]);

    const value = useMemo(
        () => ({
            toasts,
            showToast,
            dismissToast,
        }),
        [toasts, showToast, dismissToast]
    );

    return (
        <ToastContext.Provider value={value}>
            {children}
        </ToastContext.Provider>
    );
}

// eslint-disable-next-line react-refresh/only-export-components
export function useToast() {
    const context = useContext(ToastContext);
    if (!context) {
        throw new Error("useToast must be used within ToastProvider");
    }

    return context;
}
