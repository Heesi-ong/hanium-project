import { useToast } from "../context/ToastContext";

function ToastContainer() {
    const { toasts, dismissToast } = useToast();

    if (toasts.length === 0) {
        return null;
    }

    return (
        <div className="toast-container">
            {toasts.map((toast) => {
                const liveRegionProps = toast.type === "error"
                    ? { role: "alert" }
                    : { role: "status", "aria-live": "polite" };

                return (
                    <div
                        className={`toast toast-${toast.type}`}
                        key={toast.id}
                        {...liveRegionProps}
                    >
                        <span>{toast.message}</span>
                        <button
                            type="button"
                            aria-label="알림 닫기"
                            onClick={() => dismissToast(toast.id)}
                        >
                            ×
                        </button>
                    </div>
                );
            })}
        </div>
    );
}

export default ToastContainer;
