import { motion, useReducedMotion } from "motion/react";

import AnimatedSection from "../motion/AnimatedSection";
import PageFadeIn from "../motion/PageFadeIn";
import { EASE_OUT } from "../motion/animationVariants";

function GuideSignalVisual({ sectionCount }) {
    const prefersReducedMotion = useReducedMotion();

    return (
        <div className="relative mx-auto w-full max-w-[420px]" aria-hidden="true">
            <svg viewBox="0 0 440 260" className="h-auto w-full" fill="none">
                <rect
                    x="1"
                    y="1"
                    width="438"
                    height="258"
                    rx="30"
                    fill="#100E0C"
                    stroke="rgba(255,255,255,0.1)"
                />
                <path d="M38 58H402" stroke="rgba(255,255,255,0.08)" />
                <circle cx="28" cy="29" r="5" fill="#F27424" />
                <circle cx="46" cy="29" r="5" fill="#F4B04A" opacity="0.76" />
                <circle cx="64" cy="29" r="5" fill="#4FC78A" opacity="0.76" />

                <rect x="40" y="83" width="92" height="112" rx="18" fill="#17130F" stroke="rgba(242,116,36,0.32)" />
                <rect x="174" y="83" width="92" height="112" rx="18" fill="#17130F" stroke="rgba(79,199,138,0.28)" />
                <rect x="308" y="83" width="92" height="112" rx="18" fill="#17130F" stroke="rgba(114,165,255,0.28)" />
                <rect x="58" y="107" width="54" height="8" rx="4" fill="#F27424" opacity="0.74" />
                <rect x="192" y="107" width="54" height="8" rx="4" fill="#4FC78A" opacity="0.7" />
                <rect x="326" y="107" width="54" height="8" rx="4" fill="#72A5FF" opacity="0.7" />
                <path d="M58 134H112M58 149H101M58 164H108" stroke="#3B3028" strokeWidth="7" strokeLinecap="round" />
                <path d="M192 134H246M192 149H235M192 164H242" stroke="#3B3028" strokeWidth="7" strokeLinecap="round" />
                <path d="M326 134H380M326 149H369M326 164H376" stroke="#3B3028" strokeWidth="7" strokeLinecap="round" />

                <motion.path
                    d="M132 139H174M266 139H308"
                    stroke="#FF934D"
                    strokeWidth="4"
                    strokeLinecap="round"
                    strokeDasharray="5 9"
                    initial={prefersReducedMotion ? false : { pathLength: 0, opacity: 0.25 }}
                    animate={{ pathLength: 1, opacity: 1 }}
                    transition={{ duration: 0.9, ease: EASE_OUT, delay: 0.2 }}
                />
                <motion.circle
                    cx="287"
                    cy="139"
                    r="6"
                    fill="#FF934D"
                    animate={
                        prefersReducedMotion
                            ? undefined
                            : { opacity: [0.45, 1, 0.45], scale: [0.9, 1.18, 0.9] }
                    }
                    transition={{ duration: 2.4, repeat: Infinity, ease: "easeInOut" }}
                />
            </svg>

            <div className="absolute bottom-5 left-1/2 flex min-h-9 -translate-x-1/2 items-center gap-2 rounded-full border border-border-subtle bg-background-primary/90 px-4 text-xs font-bold text-text-secondary backdrop-blur-sm">
                <span className="h-2 w-2 rounded-full bg-primary-bright" />
                핵심 안내 {sectionCount}개
            </div>
        </div>
    );
}

function GuidePageShell({ eyebrow, title, notice, sections, actions }) {
    return (
        <PageFadeIn className="py-2 sm:py-4">
            <article className="min-w-0">
                <div className="grid overflow-hidden rounded-card border border-border-subtle bg-surface-primary shadow-stage lg:grid-cols-[minmax(0,1.05fr)_minmax(320px,0.75fr)]">
                    <div className="p-6 sm:p-9 lg:p-12">
                        <p className="text-xs font-black uppercase tracking-[0.16em] text-primary-bright">
                            {eyebrow}
                        </p>
                        <h1 className="mt-4 max-w-[44rem] text-[clamp(2.25rem,6vw,4.5rem)] font-bold leading-[1.04] tracking-[-0.045em] text-text-primary">
                            {title}
                        </h1>
                        <div
                            className="mt-7 rounded-xl border border-warning/35 bg-warning/10 p-4 text-sm font-bold leading-7 text-warning sm:p-5"
                            role="note"
                        >
                            <span className="mr-2 inline-flex h-7 w-7 items-center justify-center rounded-lg border border-current align-middle text-xs">
                                !
                            </span>
                            {notice}
                        </div>
                    </div>

                    <div className="relative flex items-center border-t border-border-subtle bg-background-secondary p-5 sm:p-8 lg:border-l lg:border-t-0">
                        <div
                            className="pointer-events-none absolute -right-20 -top-20 h-56 w-56 rounded-full bg-success/10 blur-3xl"
                            aria-hidden="true"
                        />
                        <GuideSignalVisual sectionCount={sections.length} />
                    </div>
                </div>

                <div className="mt-7 grid min-w-0 gap-7 lg:grid-cols-[240px_minmax(0,1fr)]">
                    <aside className="lg:sticky lg:top-28 lg:self-start">
                        <nav
                            className="rounded-card border border-border-subtle bg-surface-primary/80 p-5 backdrop-blur-sm"
                            aria-label={`${title} 목차`}
                        >
                            <p className="text-xs font-black uppercase tracking-[0.14em] text-text-muted">
                                이 안내에서
                            </p>
                            <ol className="mt-4 grid gap-1">
                                {sections.map((section, index) => (
                                    <li key={section.id}>
                                        <a
                                            className="flex min-h-11 items-center gap-3 rounded-lg px-2 text-sm font-bold text-text-secondary transition-colors hover:bg-primary-orange/10 hover:text-text-primary"
                                            href={`#${section.id}`}
                                        >
                                            <span className="text-xs text-primary-bright">
                                                {String(index + 1).padStart(2, "0")}
                                            </span>
                                            <span>{section.title}</span>
                                        </a>
                                    </li>
                                ))}
                            </ol>
                        </nav>
                    </aside>

                    <div className="grid min-w-0 gap-4">
                        {sections.map((section, index) => (
                            <AnimatedSection
                                key={section.id}
                                id={section.id}
                                className="scroll-mt-28 rounded-card border border-border-subtle bg-surface-primary p-6 shadow-card sm:p-8"
                            >
                                <div className="flex items-start gap-4">
                                    <span className="inline-flex h-10 w-10 flex-none items-center justify-center rounded-xl bg-primary-orange/15 text-xs font-black text-highlight-orange">
                                        {String(index + 1).padStart(2, "0")}
                                    </span>
                                    <div className="min-w-0">
                                        <h2 className="m-0 text-xl font-bold tracking-[-0.025em] text-text-primary sm:text-2xl">
                                            {section.title}
                                        </h2>
                                        {section.content && (
                                            <p className="mt-4 text-sm leading-7 text-text-secondary sm:text-base sm:leading-8">
                                                {section.content}
                                            </p>
                                        )}
                                        {section.bullets && (
                                            <ul className="mt-4 grid gap-3 text-sm leading-7 text-text-secondary sm:text-base">
                                                {section.bullets.map((item) => (
                                                    <li key={item} className="flex gap-3">
                                                        <span className="mt-2.5 h-1.5 w-1.5 flex-none rounded-full bg-primary-bright" />
                                                        <span>{item}</span>
                                                    </li>
                                                ))}
                                            </ul>
                                        )}
                                    </div>
                                </div>
                            </AnimatedSection>
                        ))}

                        {actions && (
                            <div className="mt-2 flex flex-col gap-3 rounded-card border border-border-emphasis bg-primary-orange/10 p-5 sm:flex-row sm:items-center sm:p-6">
                                {actions}
                            </div>
                        )}
                    </div>
                </div>
            </article>
        </PageFadeIn>
    );
}

export default GuidePageShell;
