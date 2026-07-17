import { motion, useReducedMotion } from "motion/react";

function StateMessage({ type = "info", children }) {
    const prefersReducedMotion = useReducedMotion();

    if (!children) {
        return null;
    }

    const className = {
        error: "error-message",
        success: "success-text",
        polling: "polling-text",
        failure: "failure-text",
        info: "polling-text",
    }[type] || "polling-text";

    const isAlert = ["error", "failure"].includes(type);
    const liveRegionProps = isAlert
        ? { role: "alert" }
        : { role: "status", "aria-live": "polite" };

    // 메시지 문자열이 바뀔 때마다(예: 다른 에러가 새로 뜰 때) key로 다시 마운트시켜
    // 진입 효과가 매번 재생되게 합니다. 에러/실패는 짧게 좌우로 흔들어 주의를 끕니다.
    return (
        <motion.div
            key={typeof children === "string" ? children : undefined}
            className={className}
            {...liveRegionProps}
            initial={prefersReducedMotion ? false : { opacity: 0, y: -6 }}
            animate={
                prefersReducedMotion
                    ? { opacity: 1 }
                    : isAlert
                        ? { opacity: 1, y: 0, x: [-6, 6, -4, 4, 0] }
                        : { opacity: 1, y: 0 }
            }
            transition={{ duration: isAlert ? 0.4 : 0.28, ease: [0.16, 1, 0.3, 1] }}
        >
            {children}
        </motion.div>
    );
}

export default StateMessage;
