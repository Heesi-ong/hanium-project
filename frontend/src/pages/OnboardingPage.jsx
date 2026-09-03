import { useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { motion, useReducedMotion } from "motion/react";
import { completeOnboarding, skipOnboarding } from "../api/onboardingApi";
import StateMessage from "../components/StateMessage";
import PageFadeIn from "../components/motion/PageFadeIn";
import { EASE_OUT } from "../components/motion/animationVariants";
import Button from "../components/ui/Button";
import { useAuth } from "../context/AuthContext";
import {
    EXPERIENCE_LEVEL_OPTIONS,
    IMPROVEMENT_GOAL_OPTIONS,
    PURPOSE_OPTIONS,
} from "../constants/onboarding";

function getSkipButtonLabel(isEditingCompletedOnboarding, skipping) {
    if (isEditingCompletedOnboarding) return "취소";
    return skipping ? "처리 중..." : "나중에 하기";
}

function getOptionLabel(options, value) {
    return options.find((option) => option.value === value)?.label || value;
}

function OnboardingSignalVisual({ purpose, experienceLevel, improvementGoal }) {
    const prefersReducedMotion = useReducedMotion();
    const signals = [
        { number: "01", label: "목적", value: getOptionLabel(PURPOSE_OPTIONS, purpose) },
        {
            number: "02",
            label: "경험",
            value: getOptionLabel(EXPERIENCE_LEVEL_OPTIONS, experienceLevel),
        },
        {
            number: "03",
            label: "집중 신호",
            value: getOptionLabel(IMPROVEMENT_GOAL_OPTIONS, improvementGoal),
        },
    ];

    return (
        <div className="relative overflow-hidden rounded-card border border-border-subtle bg-background-primary/70 p-5 sm:p-6">
            <div className="pointer-events-none absolute -right-14 -top-16 h-44 w-44 rounded-full bg-primary-orange/15 blur-3xl" aria-hidden="true" />
            <svg viewBox="0 0 440 116" className="relative h-auto w-full" fill="none" aria-hidden="true">
                <path d="M52 58H388" stroke="rgba(255,255,255,0.1)" strokeWidth="2" />
                <motion.path
                    d="M52 58H388"
                    stroke="#F27424"
                    strokeWidth="3"
                    strokeLinecap="round"
                    initial={prefersReducedMotion ? false : { pathLength: 0, opacity: 0.2 }}
                    animate={{ pathLength: 1, opacity: 1 }}
                    transition={{ duration: 1, ease: EASE_OUT, delay: 0.15 }}
                />
                {[52, 220, 388].map((cx, index) => (
                    <g key={cx}>
                        <circle cx={cx} cy="58" r="27" fill="#17130F" stroke="rgba(255,147,77,0.42)" />
                        <motion.circle
                            cx={cx}
                            cy="58"
                            r="8"
                            fill={index === 2 ? "#FF934D" : "#F27424"}
                            animate={prefersReducedMotion || index !== 2 ? undefined : { opacity: [0.5, 1, 0.5], scale: [0.85, 1.16, 0.85] }}
                            transition={{ duration: 2.2, repeat: Infinity, ease: "easeInOut" }}
                        />
                    </g>
                ))}
            </svg>

            <ol className="relative mt-3 grid gap-2" aria-label="선택한 코칭 기준">
                {signals.map((signal) => (
                    <li key={signal.number} className="grid min-w-0 grid-cols-[1.75rem_4rem_minmax(0,1fr)] items-center gap-1.5 rounded-xl border border-white/[0.06] bg-surface-primary/80 px-3 py-3 text-sm">
                        <span className="font-black text-primary-bright">{signal.number}</span>
                        <span className="font-bold text-text-muted">{signal.label}</span>
                        <span className="truncate text-right font-bold text-text-primary" title={signal.value}>
                            {signal.value}
                        </span>
                    </li>
                ))}
            </ol>
        </div>
    );
}

function OnboardingPage() {
    const navigate = useNavigate();
    const location = useLocation();
    const { user, updateUser } = useAuth();

    const redirectPath = location.state?.from || "/";
    const isEditingCompletedOnboarding = Boolean(user?.onboardingCompleted);
    // 계정 화면에서 "수정"으로 다시 들어온 경우, 이전에 저장한 값을 기본값으로 미리
    // 채운다. 처음 온보딩하는 경우 user에 값이 없어 기존 기본값(첫 옵션)을 그대로 쓴다.
    const [purpose, setPurpose] = useState(user?.purpose || PURPOSE_OPTIONS[0].value);
    const [experienceLevel, setExperienceLevel] = useState(
        user?.experienceLevel || EXPERIENCE_LEVEL_OPTIONS[0].value
    );
    const [improvementGoal, setImprovementGoal] = useState(
        user?.improvementGoal || IMPROVEMENT_GOAL_OPTIONS[0].value
    );
    const [loading, setLoading] = useState(false);
    const [skipping, setSkipping] = useState(false);
    const [error, setError] = useState("");
    const skipButtonLabel = getSkipButtonLabel(isEditingCompletedOnboarding, skipping);
    const isSubmitting = loading || skipping;

    async function handleSubmit(event) {
        event.preventDefault();

        try {
            setLoading(true);
            setError("");

            await completeOnboarding({ purpose, experienceLevel, improvementGoal });
            updateUser({
                onboardingCompleted: true,
                onboardingSkipped: false,
                purpose,
                experienceLevel,
                improvementGoal,
            });
            navigate(redirectPath, { replace: true });
        } catch (requestError) {
            setError(requestError.message || "온보딩 정보 저장 중 오류가 발생했습니다.");
        } finally {
            setLoading(false);
        }
    }

    // 예전에는 이 버튼이 화면만 넘기고 서버에 아무것도 남기지 않아, 다음 로그인 때마다
    // 다시 온보딩으로 보내졌다. 이제는 건너뛴 사실 자체를 서버에 기록해 반복 노출을 막는다.
    async function handleSkip() {
        if (isEditingCompletedOnboarding) {
            navigate(redirectPath, { replace: true });
            return;
        }

        try {
            setSkipping(true);
            setError("");

            await skipOnboarding();
            updateUser({ onboardingSkipped: true });
            navigate(redirectPath, { replace: true });
        } catch (requestError) {
            setError(requestError.message || "건너뛰기 처리 중 오류가 발생했습니다.");
        } finally {
            setSkipping(false);
        }
    }

    return (
        <PageFadeIn className="py-2 sm:py-4">
            <div className="grid min-w-0 overflow-hidden rounded-card border border-border-subtle bg-surface-primary shadow-stage lg:grid-cols-[minmax(0,0.92fr)_minmax(420px,1.08fr)]">
                <section className="relative border-b border-border-subtle bg-background-secondary p-6 sm:p-9 lg:border-b-0 lg:border-r lg:p-11">
                    <div className="pointer-events-none absolute -left-24 top-24 h-64 w-64 rounded-full bg-primary-orange/[0.08] blur-3xl" aria-hidden="true" />
                    <div className="relative">
                        <p className="text-xs font-black uppercase tracking-[0.16em] text-primary-bright">
                            {isEditingCompletedOnboarding ? "Coach setup · Edit" : "Coach setup · 01"}
                        </p>
                        <h1 className="mt-4 max-w-[34rem] text-[clamp(2.25rem,4.4vw,3.5rem)] font-bold leading-[1.05] tracking-[-0.045em] text-text-primary">
                            {isEditingCompletedOnboarding
                                ? "코칭 기준을 다시 맞춰볼까요?"
                                : "첫 분석 전에 코칭 방향을 맞춰볼게요."}
                        </h1>
                        <p className="mt-5 max-w-[36rem] text-sm leading-7 text-text-secondary sm:text-base sm:leading-8">
                            세 가지 답변으로 결과 화면의 연습 맥락을 준비합니다. 분석 방식이나 점수를 바꾸지는 않으며, 계정 설정에서 언제든 수정할 수 있습니다.
                        </p>

                        <div className="mt-8">
                            <OnboardingSignalVisual
                                purpose={purpose}
                                experienceLevel={experienceLevel}
                                improvementGoal={improvementGoal}
                            />
                        </div>

                        <div className="mt-5 flex items-start gap-3 rounded-xl border border-border-subtle bg-surface-primary/70 p-4 text-sm leading-6 text-text-secondary">
                            <span className="inline-flex h-7 w-7 flex-none items-center justify-center rounded-lg bg-primary-orange/15 font-black text-primary-bright" aria-hidden="true">
                                i
                            </span>
                            <p>
                                {isEditingCompletedOnboarding
                                    ? "취소하면 저장된 설정을 변경하지 않고 계정 화면으로 돌아갑니다."
                                    : "지금 정하기 어렵다면 나중에 할 수 있습니다. 건너뛴 여부는 반복 안내를 막기 위해 저장됩니다."}
                            </p>
                        </div>
                    </div>
                </section>

                <section className="p-6 sm:p-9 lg:p-11" aria-labelledby="onboarding-form-title">
                    <div className="flex flex-col gap-3 border-b border-border-subtle pb-6 sm:flex-row sm:items-end sm:justify-between">
                        <div>
                            <p className="text-xs font-black uppercase tracking-[0.14em] text-text-muted">3 coaching signals</p>
                            <h2 id="onboarding-form-title" className="mt-2 text-2xl font-bold tracking-[-0.03em] text-text-primary sm:text-3xl">
                                발표 연습 설정
                            </h2>
                        </div>
                        <span className="inline-flex min-h-9 w-fit items-center rounded-full border border-success/25 bg-success/10 px-3 text-xs font-bold text-success">
                            선택 즉시 미리보기 반영
                        </span>
                    </div>

                    <form className="mt-6 grid gap-4" onSubmit={handleSubmit} aria-label="발표 코칭 설정" aria-busy={isSubmitting}>
                        <div className="rounded-xl border border-border-subtle bg-background-primary/45 p-4 transition-colors focus-within:border-border-emphasis sm:p-5">
                            <label htmlFor="onboarding-purpose" className="block font-bold text-text-primary">
                                <span className="mr-3 text-xs font-black text-primary-bright" aria-hidden="true">01</span>
                                주로 어떤 목적으로 사용하시나요?
                            </label>
                            <p id="onboarding-purpose-help" className="mt-2 text-sm leading-6 text-text-muted">
                                연습 장면에 가장 가까운 항목을 선택하세요.
                            </p>
                            <select
                                id="onboarding-purpose"
                                value={purpose}
                                onChange={(event) => setPurpose(event.target.value)}
                                className="mt-3 min-h-12 w-full rounded-xl border border-border-subtle bg-surface-secondary px-4 text-base font-bold text-text-primary transition-colors hover:border-border-emphasis disabled:cursor-not-allowed disabled:opacity-50 [color-scheme:dark]"
                                aria-describedby="onboarding-purpose-help"
                                disabled={isSubmitting}
                            >
                                {PURPOSE_OPTIONS.map((option) => (
                                    <option key={option.value} value={option.value}>
                                        {option.label}
                                    </option>
                                ))}
                            </select>
                        </div>

                        <div className="rounded-xl border border-border-subtle bg-background-primary/45 p-4 transition-colors focus-within:border-border-emphasis sm:p-5">
                            <label htmlFor="onboarding-experience" className="block font-bold text-text-primary">
                                <span className="mr-3 text-xs font-black text-primary-bright" aria-hidden="true">02</span>
                                발표 경험 수준은 어느 정도인가요?
                            </label>
                            <p id="onboarding-experience-help" className="mt-2 text-sm leading-6 text-text-muted">
                                현재 느끼는 숙련도를 기준으로 골라도 충분합니다.
                            </p>
                            <select
                                id="onboarding-experience"
                                value={experienceLevel}
                                onChange={(event) => setExperienceLevel(event.target.value)}
                                className="mt-3 min-h-12 w-full rounded-xl border border-border-subtle bg-surface-secondary px-4 text-base font-bold text-text-primary transition-colors hover:border-border-emphasis disabled:cursor-not-allowed disabled:opacity-50 [color-scheme:dark]"
                                aria-describedby="onboarding-experience-help"
                                disabled={isSubmitting}
                            >
                                {EXPERIENCE_LEVEL_OPTIONS.map((option) => (
                                    <option key={option.value} value={option.value}>
                                        {option.label}
                                    </option>
                                ))}
                            </select>
                        </div>

                        <div className="rounded-xl border border-border-subtle bg-background-primary/45 p-4 transition-colors focus-within:border-border-emphasis sm:p-5">
                            <label htmlFor="onboarding-goal" className="block font-bold text-text-primary">
                                <span className="mr-3 text-xs font-black text-primary-bright" aria-hidden="true">03</span>
                                가장 개선하고 싶은 부분은 무엇인가요?
                            </label>
                            <p id="onboarding-goal-help" className="mt-2 text-sm leading-6 text-text-muted">
                                이번 연습에서 가장 먼저 확인하고 싶은 신호입니다.
                            </p>
                            <select
                                id="onboarding-goal"
                                value={improvementGoal}
                                onChange={(event) => setImprovementGoal(event.target.value)}
                                className="mt-3 min-h-12 w-full rounded-xl border border-border-subtle bg-surface-secondary px-4 text-base font-bold text-text-primary transition-colors hover:border-border-emphasis disabled:cursor-not-allowed disabled:opacity-50 [color-scheme:dark]"
                                aria-describedby="onboarding-goal-help"
                                disabled={isSubmitting}
                            >
                                {IMPROVEMENT_GOAL_OPTIONS.map((option) => (
                                    <option key={option.value} value={option.value}>
                                        {option.label}
                                    </option>
                                ))}
                            </select>
                        </div>

                        <StateMessage type="error">{error}</StateMessage>

                        <div className="mt-2 grid gap-3 sm:grid-cols-2">
                            <Button type="submit" className="min-h-12 w-full" disabled={isSubmitting}>
                                {loading ? "저장 중..." : "저장하고 시작하기"}
                            </Button>
                            <Button type="button" variant="secondary" className="min-h-12 w-full" onClick={handleSkip} disabled={isSubmitting}>
                                {skipButtonLabel}
                            </Button>
                        </div>
                    </form>
                </section>
            </div>
        </PageFadeIn>
    );
}

export default OnboardingPage;
