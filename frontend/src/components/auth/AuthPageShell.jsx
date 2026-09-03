import { motion, useReducedMotion } from "motion/react";

import PageFadeIn from "../motion/PageFadeIn";
import { EASE_OUT } from "../motion/animationVariants";

function AuthSignalVisual() {
    const prefersReducedMotion = useReducedMotion();

    return (
        <div className="mt-6" aria-hidden="true">
            <svg
                className="h-auto w-full"
                viewBox="0 0 520 230"
                fill="none"
                xmlns="http://www.w3.org/2000/svg"
            >
                <rect
                    x="1"
                    y="1"
                    width="518"
                    height="228"
                    rx="28"
                    fill="#100E0C"
                    stroke="rgba(255,255,255,0.1)"
                />
                <path d="M38 56H482" stroke="rgba(255,255,255,0.07)" />
                <circle cx="30" cy="29" r="5" fill="#F27424" />
                <circle cx="48" cy="29" r="5" fill="#F4B04A" opacity="0.72" />
                <circle cx="66" cy="29" r="5" fill="#4FC78A" opacity="0.72" />

                <path
                    d="M40 184H480"
                    stroke="rgba(255,255,255,0.08)"
                    strokeDasharray="4 8"
                />
                <motion.path
                    d="M40 163C75 163 76 111 111 111C146 111 145 173 180 173C215 173 216 81 251 81C286 81 285 140 320 140C355 140 356 98 391 98C426 98 426 126 480 126"
                    stroke="#F27424"
                    strokeWidth="5"
                    strokeLinecap="round"
                    initial={prefersReducedMotion ? false : { pathLength: 0, opacity: 0.35 }}
                    animate={{ pathLength: 1, opacity: 1 }}
                    transition={{ duration: 1.1, ease: EASE_OUT, delay: 0.12 }}
                />
                <motion.path
                    d="M40 188C92 188 91 155 143 155C195 155 196 194 248 194C300 194 300 151 352 151C404 151 405 174 480 174"
                    stroke="#4FC78A"
                    strokeWidth="3"
                    strokeLinecap="round"
                    opacity="0.7"
                    initial={prefersReducedMotion ? false : { pathLength: 0 }}
                    animate={{ pathLength: 1 }}
                    transition={{ duration: 1, ease: EASE_OUT, delay: 0.3 }}
                />
                <motion.circle
                    cx="251"
                    cy="81"
                    r="8"
                    fill="#FF934D"
                    animate={
                        prefersReducedMotion
                            ? undefined
                            : { opacity: [0.55, 1, 0.55], scale: [0.9, 1.12, 0.9] }
                    }
                    transition={{ duration: 2.2, repeat: Infinity, ease: "easeInOut" }}
                />
                <rect x="40" y="76" width="92" height="9" rx="4.5" fill="#2A2018" />
                <rect x="40" y="92" width="58" height="7" rx="3.5" fill="#211A14" />
            </svg>

            <div className="mt-3 grid grid-cols-3 gap-2 text-[11px] font-bold text-text-muted">
                <span>계정 확인</span>
                <span className="text-center text-primary-bright">분석 연결</span>
                <span className="text-right">결과 보호</span>
            </div>
        </div>
    );
}

function AuthPageShell({
    eyebrow,
    title,
    description,
    contextEyebrow,
    contextTitle,
    contextDescription,
    contextPoints = [],
    children,
    footer,
}) {
    return (
        <PageFadeIn className="flex min-h-[calc(100svh-200px)] items-center py-4 sm:py-8">
            <div className="grid w-full max-w-[1080px] overflow-hidden rounded-card border border-border-subtle bg-surface-primary shadow-stage lg:grid-cols-[minmax(0,0.92fr)_minmax(420px,0.78fr)]">
                <article className="order-1 bg-surface-primary p-6 sm:p-9 lg:order-2 lg:p-10">
                    <div>
                        <p className="mb-3 text-xs font-black uppercase tracking-[0.16em] text-primary-bright">
                            {eyebrow}
                        </p>
                        <h1 className="m-0 text-[clamp(2rem,5vw,3rem)] font-bold leading-tight tracking-[-0.04em] text-text-primary">
                            {title}
                        </h1>
                        <p className="mt-4 max-w-[34rem] text-sm leading-7 text-text-secondary sm:text-base">
                            {description}
                        </p>
                    </div>

                    {children}

                    {footer && (
                        <footer className="mt-6 border-t border-border-subtle pt-5">
                            {footer}
                        </footer>
                    )}
                </article>

                <aside className="relative order-2 overflow-hidden border-t border-border-subtle bg-background-secondary p-6 sm:p-9 lg:order-1 lg:min-h-[640px] lg:border-r lg:border-t-0 lg:p-10">
                    <div
                        className="pointer-events-none absolute -left-24 -top-24 h-64 w-64 rounded-full bg-primary-bright/10 blur-3xl"
                        aria-hidden="true"
                    />
                    <div
                        className="pointer-events-none absolute -bottom-28 -right-20 h-72 w-72 rounded-full bg-success/10 blur-3xl"
                        aria-hidden="true"
                    />

                    <div className="relative flex h-full flex-col justify-between">
                        <div>
                            <p className="text-xs font-black uppercase tracking-[0.16em] text-soft-orange">
                                {contextEyebrow}
                            </p>
                            <h2 className="mt-4 max-w-[26rem] text-2xl font-bold leading-tight tracking-[-0.03em] text-text-primary sm:text-3xl">
                                {contextTitle}
                            </h2>
                            <p className="mt-4 max-w-[30rem] text-sm leading-7 text-text-secondary">
                                {contextDescription}
                            </p>

                            <AuthSignalVisual />
                        </div>

                        <ul className="mt-7 grid gap-3 sm:grid-cols-2 lg:grid-cols-1">
                            {contextPoints.map((point, index) => (
                                <li
                                    key={point}
                                    className="flex min-h-11 items-center gap-3 rounded-xl border border-border-subtle bg-surface-primary/70 px-4 py-3 text-sm text-text-secondary"
                                >
                                    <span className="inline-flex h-7 w-7 flex-none items-center justify-center rounded-lg bg-primary-orange/15 text-xs font-black text-highlight-orange">
                                        {String(index + 1).padStart(2, "0")}
                                    </span>
                                    <span>{point}</span>
                                </li>
                            ))}
                        </ul>
                    </div>
                </aside>
            </div>
        </PageFadeIn>
    );
}

export default AuthPageShell;
