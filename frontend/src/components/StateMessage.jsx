import { motion, useReducedMotion } from "motion/react";
import { EASE_OUT } from "./motion/animationVariants";

function StateMessage({ type = "info", children, messageKey }) {
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

    // 메시지가 바뀔 때마다(예: 다른 에러가 새로 뜰 때) key로 다시 마운트시켜
    // 진입 효과가 매번 재생되게 합니다. 에러/실패는 짧게 좌우로 흔들어 주의를 끕니다.
    // children이 문자열이 아닌 JSX(예: 폴링 상태 배지)인 호출부는 messageKey로
    // 명시적인 식별자를 넘겨야 재생 시점을 판단할 수 있습니다 — 지정하지 않으면
    // 문자열 children에 한해서만 자동으로 값 자체를 key로 씁니다.
    return (
        <motion.div
            key={messageKey ?? (typeof children === "string" ? children : undefined)}
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
            transition={{ duration: isAlert ? 0.4 : 0.28, ease: EASE_OUT }}
        >
            {children}
        </motion.div>
    );
}

export default StateMessage;
