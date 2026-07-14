import type { ButtonHTMLAttributes, ReactNode } from "react";

export type ButtonVariant = "primary" | "secondary" | "ghost";

export const BUTTON_BASE_CLASSNAME =
    "inline-flex items-center justify-center rounded-full px-6 py-3 text-sm font-medium transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed";

export const BUTTON_VARIANT_CLASSNAMES: Record<ButtonVariant, string> = {
    primary:
        "bg-primary-orange text-warm-white hover:bg-primary-bright active:bg-primary-deep",
    secondary:
        "bg-surface-secondary text-text-primary border border-white/10 hover:bg-elevated-surface",
    ghost: "bg-transparent text-text-primary hover:bg-surface-primary",
};

export function buttonVariantClassName(variant: ButtonVariant = "primary", className = ""): string {
    return [BUTTON_BASE_CLASSNAME, BUTTON_VARIANT_CLASSNAMES[variant], className]
        .filter(Boolean)
        .join(" ");
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
    variant?: ButtonVariant;
    children: ReactNode;
}

function Button({ variant = "primary", className = "", children, ...rest }: ButtonProps) {
    const combinedClassName = buttonVariantClassName(variant, className);

    return (
        <button type="button" className={combinedClassName} {...rest}>
            {children}
        </button>
    );
}

export default Button;
