export const SECTION_VIEWPORT = { once: true, amount: 0.2 };
export const EASE_OUT = [0.16, 1, 0.3, 1];

export const fadeUp = {
    hidden: { opacity: 0, y: 28, filter: "blur(12px)" },
    visible: {
        opacity: 1,
        y: 0,
        filter: "blur(0px)",
        transition: { duration: 0.72, ease: EASE_OUT },
    },
};
