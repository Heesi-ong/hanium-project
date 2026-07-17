import { motion, useReducedMotion } from "motion/react";

// 스크롤 없이도 바로 보이는 화면(로그인, 회원가입 등 인증 페이지)에 마운트되는 즉시
// 살짝 떠오르며 나타나는 진입 효과입니다. 스크롤해 들어와야 재생되는 AnimatedSection과
// 달리 whileInView를 쓰지 않고 mount 시점에 바로 재생됩니다.
function PageFadeIn({ children, className = "" }) {
    const prefersReducedMotion = useReducedMotion();

    return (
        <motion.section
            className={className}
            initial={prefersReducedMotion ? false : { opacity: 0, y: 18 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
        >
            {children}
        </motion.section>
    );
}

export default PageFadeIn;
