import { motion, useReducedMotion } from "motion/react";
import { useToast } from "../context/ToastContext";

function ToastContainer() {
    const { toasts, dismissToast } = useToast();
    const prefersReducedMotion = useReducedMotion();

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
                    <motion.div
                        className={`toast toast-${toast.type}`}
                        key={toast.id}
                        {...liveRegionProps}
                        initial={prefersReducedMotion ? false : { opacity: 0, y: -14, scale: 0.96 }}
                        animate={{ opacity: 1, y: 0, scale: 1 }}
                        transition={{ duration: 0.28, ease: [0.16, 1, 0.3, 1] }}
                    >
                        <span>{toast.message}</span>
                        <button
                            type="button"
                            aria-label="알림 닫기"
                            onClick={() => dismissToast(toast.id)}
                        >
                            ×
                        </button>
                    </motion.div>
                );
            })}
        </div>
    );
}

export default ToastContainer;
