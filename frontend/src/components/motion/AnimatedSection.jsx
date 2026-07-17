import { motion, useReducedMotion } from "motion/react";
import { fadeUp, SECTION_VIEWPORT } from "./animationVariants";

// 섹션 자신도 fadeUp과 같은 방식으로 나타나면서, 동시에 variants={fadeUp}을 쓰는 자식이
// 있으면 그 자식들의 등장을 staggerChildren 간격으로 순서대로 재생합니다. 이렇게 해야
// 일반 컴포넌트(모션이 아닌 자식)를 감싸도 섹션 자체가 fade-up되어 보이고, HomePage처럼
// 카드 단위로 순차 등장시키고 싶은 곳에서는 자식에 variants={fadeUp}만 추가하면 됩니다.
const sectionReveal = {
    hidden: fadeUp.hidden,
    visible: {
        ...fadeUp.visible,
        transition: {
            ...fadeUp.visible.transition,
            staggerChildren: 0.08,
            delayChildren: 0.06,
        },
    },
};

// 화면에 스크롤되어 들어올 때 fade-up되는 공용 섹션 래퍼입니다. 긴 페이지(홈, 결과 상세)에서
// 콘텐츠가 한꺼번에 나타나지 않고 단계적으로 드러나게 해 시각적 부담을 줄입니다.
// viewport.once=true라 섹션당 한 번만 재생됩니다.
function AnimatedSection({ children, className = "", id }) {
    const prefersReducedMotion = useReducedMotion();

    return (
        <motion.section
            id={id}
            className={className}
            variants={sectionReveal}
            initial={prefersReducedMotion ? false : "hidden"}
            whileInView="visible"
            viewport={SECTION_VIEWPORT}
        >
            {children}
        </motion.section>
    );
}

export default AnimatedSection;
