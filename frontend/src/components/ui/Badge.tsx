import type { HTMLAttributes, ReactNode } from "react";

type BadgeTone = "neutral" | "success" | "warning" | "error" | "information";

interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
    tone?: BadgeTone;
    children: ReactNode;
}

const TONE_CLASSNAMES: Record<BadgeTone, string> = {
    neutral: "bg-surface-secondary text-text-secondary border border-white/10",
    success: "bg-success/15 text-success border border-success/30",
    warning: "bg-warning/15 text-warning border border-warning/30",
    error: "bg-error/15 text-error border border-error/30",
    information: "bg-information/15 text-information border border-information/30",
};

function Badge({ tone = "neutral", className = "", children, ...rest }: BadgeProps) {
    const toneClassName = TONE_CLASSNAMES[tone];
    const combinedClassName = [
        "inline-flex items-center gap-1 rounded-full px-3 py-1 text-xs font-medium",
        toneClassName,
        className,
    ]
        .filter(Boolean)
        .join(" ");

    return (
        <span className={combinedClassName} {...rest}>
            {children}
        </span>
    );
}

export default Badge;
