import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {
    motion,
    useReducedMotion,
    useScroll,
    useSpring,
    useTransform,
} from "motion/react";
import { useAuth } from "../context/AuthContext";
import { getResults } from "../api/analysisApi";
import { buttonVariantClassName } from "../components/ui/Button";
import AnimatedSection from "../components/motion/AnimatedSection";
import { EASE_OUT, fadeUp } from "../components/motion/animationVariants";
import "./HomePage.css";

// 실행 중 상태에 업로드만 끝나 분석 재개가 필요한 UPLOADED를 더한 홈 대시보드 기준입니다.
const DASHBOARD_ACTIVE_STATUSES = new Set([
    "UPLOADED",
    "QUEUED",
    "BASIC_ANALYZING",
    "VIDEO_LLM_ANALYZING",
    "COMPACTING",
    "OPENAI_GENERATING",
    "MERGING_RESULT",
]);

const FEATURE_ITEMS = [
    {
        title: "자세·시선·제스처 분석",
        description:
            "자세, 시선, 제스처, 표정을 정량 지표로 분석해 어디를 개선해야 하는지 짚어줍니다.",
        color: "#F27424",
        icon: (
            <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#FAF6F1" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="7" r="3" />
                <path d="M6 21v-2a6 6 0 0 1 12 0v2" />
            </svg>
        ),
    },
    {
        title: "영상 재생 + 주요 순간 이동",
        description:
            "업로드한 영상을 결과 화면에서 바로 재생하고, 자세나 시선이 가장 흔들린 순간으로 클릭 한 번에 이동합니다.",
        color: "#4FC78A",
        icon: (
            <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#FAF6F1" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="9" />
                <path d="M10 8.5l6 3.5-6 3.5v-7z" fill="#FAF6F1" stroke="none" />
            </svg>
        ),
    },
    {
        title: "AI 코칭 피드백",
        description:
            "정량 분석 결과를 바탕으로 이해하기 쉬운 코칭 문장과 연습 계획을 생성합니다.",
        color: "#F6A66B",
        icon: (
            <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#FAF6F1" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3z" />
            </svg>
        ),
    },
    {
        title: "회차별 성장 추이",
        description:
            "완료한 분석들의 총점을 시간순으로 비교해, 연습할수록 나아지는 과정을 확인합니다.",
        color: "#72A5FF",
        icon: (
            <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#FAF6F1" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="4 16 10 10 14 13 20 6" />
                <polyline points="14 6 20 6 20 12" />
            </svg>
        ),
    },
];

const ANALYSIS_DETAIL_ITEMS = [
    {
        title: "자세·제스처",
        description:
            "자세 점수, 어깨 균형, 자세 검출률과 함께 제스처 비율, 손 검출률, 손목 움직임을 확인합니다.",
    },
    {
        title: "시선·얼굴",
        description:
            "얼굴 검출률, 카메라 응시 비율, 아이컨택 수준과 프레임별 시선 분석 결과를 제공합니다.",
    },
    {
        title: "표정·감정",
        description:
            "표정 점수, 표현력 기본 점수, 표정 다양성 점수와 주요 표정 상태 집계를 함께 보여줍니다.",
    },
    {
        title: "음성·발화",
        description:
            "WPM(분당 단어 수), 단어 수, 발화·침묵 시간, 침묵 비율, 필러 표현과 음성을 텍스트로 옮긴 결과(STT)를 확인합니다.",
    },
];

const HOW_IT_WORKS_STEPS = [
    {
        title: "발표 영상 업로드",
        description: "mp4, mov, avi, mkv 형식의 발표 영상을 선택해 분석 작업을 생성합니다.",
    },
    {
        title: "자동 분석 진행",
        description:
            "기본 분석 엔진과 선택한 옵션에 따라 Video LLM, AI 피드백 단계가 순서대로 진행됩니다.",
    },
    {
        title: "결과 확인 및 성장 추적",
        description:
            "점수와 AI 피드백을 확인하고, 다음에 분석한 결과와 비교해 성장을 추적합니다.",
    },
];

const HERO_METRICS = [
    { label: "총점", target: 82, suffix: "", accent: "#F27424" },
    { label: "응시율", target: 71, suffix: "%", accent: "#4FC78A" },
    { label: "WPM", target: 128, suffix: "", accent: "#72A5FF" },
];

const FAQ_ITEMS = [
    {
        question: "어떤 영상 파일을 업로드할 수 있나요?",
        answer:
            "mp4, mov, avi, mkv 형식을 지원하며, 파일 크기는 최대 500MB, 영상 길이는 최대 30분까지 업로드할 수 있습니다.",
    },
    {
        question: "업로드한 영상은 계속 보관되나요?",
        answer:
            "분석이 완료된 원본 영상은 30일간 보관된 후 자동으로 삭제됩니다. 분석 결과와 점수 데이터는 계속 남아 있습니다.",
    },
    {
        question: "분석 중간에 취소하거나 다시 시도할 수 있나요?",
        answer:
            "분석이 진행되는 동안 언제든 취소할 수 있고, 실패하거나 취소된 분석은 결과 화면에서 다시 시도할 수 있습니다.",
    },
    {
        question: "회원 탈퇴 시 제 데이터는 어떻게 되나요?",
        answer: "탈퇴하면 계정과 함께 그동안 업로드한 영상, 분석 결과가 모두 삭제됩니다.",
    },
];

function getDashboardResultTitle(result) {
    return (
        result?.memo ||
        result?.fileName ||
        result?.originalFileName ||
        "분석 결과"
    );
}

function getDashboardNextAction(results) {
    const inProgressCount = results.filter((result) =>
        DASHBOARD_ACTIVE_STATUSES.has(result.status)
    ).length;

    const uploadedCount = results.filter((result) => result.status === "UPLOADED").length;

    if (uploadedCount > 0) {
        return `업로드를 마친 분석이 ${uploadedCount}건 있습니다. 영상 업로드 화면에서 분석을 이어서 시작하세요.`;
    }

    if (inProgressCount > 0) {
        return `현재 진행 중인 분석이 ${inProgressCount}건 있습니다. 결과 목록에서 현재 단계를 확인해보세요.`;
    }

    const latestCompletedWithImprovement = results.find(
        (result) =>
            result.status === "COMPLETED" &&
            result.feedback?.improvements?.[0]
    );

    if (latestCompletedWithImprovement) {
        return `다음 연습 포인트: ${latestCompletedWithImprovement.feedback.improvements[0]}`;
    }

    return "다음 발표 연습 영상을 업로드해 성장 추이를 이어가 보세요.";
}

function AuthenticatedDashboard({ user, results, error, onRetry }) {
    const completedCount = results?.filter((result) => result.status === "COMPLETED").length || 0;
    const inProgressCount = results?.filter((result) =>
        DASHBOARD_ACTIVE_STATUSES.has(result.status)
    ).length || 0;
    const latestScoredResult = results?.find(
        (result) =>
            result.status === "COMPLETED" &&
            typeof result.scoreSummary?.totalScore === "number"
    );

    return (
        <div className="landing-page">
            <section className="min-h-[calc(100vh-80px)] bg-background-primary px-6 py-16 text-text-primary sm:px-10 lg:px-16">
                <div className="mx-auto max-w-[1200px]">
                    <div className="mb-10 flex flex-wrap items-end justify-between gap-6">
                        <div className="max-w-[720px]">
                            <p className="mb-3 text-sm font-semibold tracking-wide text-primary-bright">
                                내 대시보드
                            </p>
                            <h1 className="mb-4 text-3xl font-semibold leading-tight text-text-primary sm:text-5xl">
                                다시 연습을 이어가세요
                            </h1>
                            <p className="text-base leading-relaxed text-text-secondary">
                                {user?.email ? `${user.email} 계정의 ` : "내 "}
                                진행 중 작업과 최근 분석, 다음 연습 행동을 한곳에서 확인합니다.
                            </p>
                        </div>

                        <div className="flex flex-wrap gap-3">
                            <Link to="/upload" className={buttonVariantClassName("primary", "motion-cta")}>
                                새 영상 분석하기
                            </Link>
                            <Link to="/results" className={buttonVariantClassName("secondary", "motion-cta")}>
                                전체 결과 보기
                            </Link>
                            <Link to="/account" className={buttonVariantClassName("secondary", "motion-cta")}>
                                계정 설정
                            </Link>
                        </div>
                    </div>

                    {results === null ? (
                        <div className="rounded-2xl border border-white/10 bg-surface-primary p-9" role="status">
                            <p className="text-sm text-text-muted">대시보드를 불러오는 중입니다.</p>
                        </div>
                    ) : error ? (
                        <div className="rounded-2xl border border-red-400/30 bg-surface-primary p-9" role="alert">
                            <h2 className="mb-2 text-xl font-semibold">대시보드를 불러오지 못했습니다.</h2>
                            <p className="mb-5 text-sm text-text-secondary">{error}</p>
                            <button type="button" className={buttonVariantClassName("primary")} onClick={onRetry}>
                                다시 시도
                            </button>
                        </div>
                    ) : results.length === 0 ? (
                        <div className="rounded-2xl border border-white/10 bg-surface-primary p-9">
                            <h2 className="mb-2 text-xl font-semibold">첫 분석을 시작해보세요</h2>
                            <p className="mb-5 text-sm text-text-secondary">
                                아직 분석한 발표 영상이 없습니다. 첫 영상을 업로드하고 개선 포인트를 확인해보세요.
                            </p>
                            <Link to="/upload" className={buttonVariantClassName("primary", "motion-cta")}>
                                영상 업로드하기
                            </Link>
                        </div>
                    ) : (
                        <>
                            <div className="mb-6 rounded-2xl border border-primary-orange/30 bg-surface-secondary p-6">
                                <p className="mb-2 text-sm font-semibold text-primary-bright">다음 행동</p>
                                <p className="text-base text-text-primary">{getDashboardNextAction(results)}</p>
                            </div>

                            <div className="mb-6 grid grid-cols-1 gap-5 sm:grid-cols-3">
                                <article className="rounded-2xl border border-white/10 bg-surface-primary p-6">
                                    <p className="mb-2 text-sm text-text-muted">진행 또는 재개 필요</p>
                                    <strong className="text-3xl text-primary-bright">{inProgressCount}건</strong>
                                </article>
                                <article className="rounded-2xl border border-white/10 bg-surface-primary p-6">
                                    <p className="mb-2 text-sm text-text-muted">최근 조회 범위의 완료</p>
                                    <strong className="text-3xl text-text-primary">{completedCount}건</strong>
                                </article>
                                <article className="rounded-2xl border border-white/10 bg-surface-primary p-6">
                                    <p className="mb-2 text-sm text-text-muted">최근 완료 점수</p>
                                    <strong className="text-3xl text-text-primary">
                                        {latestScoredResult?.scoreSummary.totalScore ?? "-"}
                                    </strong>
                                </article>
                            </div>

                            <div className="mb-5 flex flex-wrap items-end justify-between gap-4">
                                <div>
                                    <p className="mb-2 text-sm font-semibold tracking-wide text-primary-bright">
                                        최근 분석
                                    </p>
                                    <h2 className="text-2xl font-semibold text-text-primary">최근 작업 확인</h2>
                                </div>
                                <Link to="/results" className={buttonVariantClassName("secondary", "motion-cta")}>
                                    전체 결과 보기
                                </Link>
                            </div>

                            <div className="grid grid-cols-1 gap-5 sm:grid-cols-3">
                                {results.slice(0, 3).map((result) => (
                                    <article
                                        key={result.jobId}
                                        className="rounded-2xl border border-white/10 bg-surface-primary p-6"
                                    >
                                        <p className="mb-1 text-xs font-semibold uppercase tracking-wide text-text-muted">
                                            {result.statusDescription || result.status}
                                        </p>
                                        <h3 className="mb-2 text-lg font-semibold text-text-primary">
                                            {getDashboardResultTitle(result)}
                                        </h3>
                                        <p className="text-sm text-text-secondary">
                                            총점{" "}
                                            {typeof result.scoreSummary?.totalScore === "number"
                                                ? result.scoreSummary.totalScore
                                                : "-"}
                                        </p>
                                        <Link
                                            to={`/results/${result.jobId}`}
                                            className="mt-3 inline-block text-sm font-semibold text-primary-bright hover:underline"
                                        >
                                            상세 보기
                                        </Link>
                                    </article>
                                ))}
                            </div>
                        </>
                    )}
                </div>
            </section>
        </div>
    );
}

// 0에서 target까지 이징을 넣어 세어 올라가는 숫자를 만듭니다. 페이지에 처음 들어왔을 때
// "이 화면은 정적인 이미지가 아니라 실제로 움직인다"는 것을 가장 직관적으로 보여주는
// 효과라, 히어로 지표(총점/응시율/WPM)에 사용합니다. start가 true가 되기 전에는 0에
// 머물러 있다가, delay 이후 카운트업을 시작합니다.
function useCountUp(target, { durationMs = 1400, start = false } = {}) {
    const [value, setValue] = useState(0);
    const prefersReducedMotion = useReducedMotion();

    useEffect(() => {
        if (!start || prefersReducedMotion) {
            return undefined;
        }

        let animationFrameId;
        const startTime = performance.now();

        function tick(now) {
            const progress = Math.min((now - startTime) / durationMs, 1);
            const eased = 1 - (1 - progress) ** 3;
            setValue(target * eased);

            if (progress < 1) {
                animationFrameId = requestAnimationFrame(tick);
            }
        }

        animationFrameId = requestAnimationFrame(tick);
        return () => cancelAnimationFrame(animationFrameId);
    }, [target, durationMs, start, prefersReducedMotion]);

    if (prefersReducedMotion) {
        return target;
    }

    return value;
}

function HeroMetric({ label, target, suffix, accent, delay }) {
    const prefersReducedMotion = useReducedMotion();
    const [started, setStarted] = useState(false);
    const displayValue = useCountUp(target, { start: started });

    useEffect(() => {
        const timer = window.setTimeout(() => setStarted(true), delay);
        return () => window.clearTimeout(timer);
    }, [delay]);

    return (
        <motion.div
            className="hero-metric"
            initial={prefersReducedMotion ? false : { opacity: 0, y: 14 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.45, delay: delay / 1000, ease: EASE_OUT }}
            style={{ "--metric-accent": accent }}
        >
            <span>{label}</span>
            <strong>
                {Math.round(displayValue)}
                {suffix}
            </strong>
        </motion.div>
    );
}

function HeroIllustration({ prefersReducedMotion }) {
    return (
        <motion.svg
            viewBox="0 0 400 500"
            className="hero-device"
            role="img"
            aria-label="분석 결과 화면을 표현한 일러스트"
            initial={prefersReducedMotion ? false : { opacity: 0, y: 34, rotateX: 8 }}
            animate={{ opacity: 1, y: 0, rotateX: 0 }}
            transition={{ duration: 0.8, delay: 0.12, ease: EASE_OUT }}
        >
            <rect x="0" y="0" width="400" height="500" rx="20" fill="#17130F" stroke="rgba(255,255,255,0.09)" strokeWidth="1.5" />
            <rect x="0" y="0" width="400" height="40" rx="20" fill="#211A14" />
            {[24, 42, 60].map((cx, index) => (
                <motion.circle
                    key={cx}
                    cx={cx}
                    cy="20"
                    r="5"
                    fill={["#F27424", "#F6A66B", "#4FC78A"][index]}
                    animate={prefersReducedMotion ? undefined : { opacity: [0.42, 1, 0.42] }}
                    transition={{
                        duration: 2.4,
                        repeat: Infinity,
                        delay: index * 0.28,
                        ease: "easeInOut",
                    }}
                />
            ))}
            <rect x="24" y="60" width="352" height="180" rx="14" fill="#2A2018" />
            <motion.rect
                x="24"
                y="60"
                width="352"
                height="180"
                rx="14"
                fill="url(#scanGradient)"
                animate={prefersReducedMotion ? undefined : { opacity: [0.18, 0.42, 0.18] }}
                transition={{ duration: 3.2, repeat: Infinity, ease: "easeInOut" }}
            />
            <motion.circle
                cx="200"
                cy="150"
                r="28"
                fill="rgba(247,243,238,0.12)"
                animate={prefersReducedMotion ? undefined : { scale: [1, 1.08, 1] }}
                transition={{ duration: 2.8, repeat: Infinity, ease: "easeInOut" }}
                style={{ transformOrigin: "200px 150px" }}
            />
            <motion.path
                d="M190 135l30 15-30 15z"
                fill="#F5EFE8"
                animate={prefersReducedMotion ? undefined : { x: [0, 3, 0] }}
                transition={{ duration: 2.6, repeat: Infinity, ease: "easeInOut" }}
            />
            <motion.rect
                x="24"
                y="258"
                width="164"
                height="70"
                rx="14"
                fill="#211A14"
                animate={prefersReducedMotion ? undefined : { y: [258, 252, 258] }}
                transition={{ duration: 4, repeat: Infinity, ease: "easeInOut" }}
            />
            <text x="40" y="285" fontSize="12" fill="#B7ADA4">총점</text>
            <text x="40" y="315" fontSize="26" fill="#F5EFE8">82</text>
            <motion.rect
                x="212"
                y="258"
                width="164"
                height="70"
                rx="14"
                fill="#211A14"
                animate={prefersReducedMotion ? undefined : { y: [258, 264, 258] }}
                transition={{ duration: 4.4, repeat: Infinity, ease: "easeInOut" }}
            />
            <text x="228" y="285" fontSize="12" fill="#B7ADA4">등급</text>
            <text x="228" y="315" fontSize="26" fill="#F5EFE8">B+</text>
            <rect x="24" y="344" width="352" height="120" rx="14" fill="#211A14" />
            <text x="40" y="368" fontSize="12" fill="#B7ADA4">회차별 총점 추이</text>
            <motion.polyline
                points="40 430 96 410 152 420 208 380 264 390 320 360"
                fill="none"
                stroke="#F27424"
                strokeWidth="3"
                strokeLinecap="round"
                strokeLinejoin="round"
                initial={prefersReducedMotion ? false : { pathLength: 0, opacity: 0 }}
                animate={{ pathLength: 1, opacity: 1 }}
                transition={{ duration: 1.4, delay: 0.58, ease: "easeInOut" }}
            />
            <motion.polyline
                points="40 442 96 430 152 432 208 408 264 414 320 398"
                fill="none"
                stroke="#72A5FF"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
                initial={prefersReducedMotion ? false : { pathLength: 0, opacity: 0 }}
                animate={{ pathLength: 1, opacity: 0.75 }}
                transition={{ duration: 1.2, delay: 0.78, ease: "easeInOut" }}
            />
            <defs>
                <linearGradient id="scanGradient" x1="24" x2="376" y1="60" y2="240">
                    <stop offset="0%" stopColor="#F27424" stopOpacity="0" />
                    <stop offset="50%" stopColor="#F27424" stopOpacity="0.45" />
                    <stop offset="100%" stopColor="#72A5FF" stopOpacity="0" />
                </linearGradient>
            </defs>
        </motion.svg>
    );
}

function HomePage() {
    const { isAuthenticated, user } = useAuth();
    const [openFaqIndex, setOpenFaqIndex] = useState(null);
    // null = 아직 불러오는 중, [] = 결과 없음. 로그인 사용자에게 최근 결과·진행 중인
    // 작업·다음 연습을 보여주는 개인 대시보드(P1-05)에 사용합니다.
    const [dashboardResults, setDashboardResults] = useState(null);
    const [dashboardError, setDashboardError] = useState("");
    const [dashboardReloadKey, setDashboardReloadKey] = useState(0);
    const prefersReducedMotion = useReducedMotion();
    const pageRef = useRef(null);
    const heroRef = useRef(null);
    const { scrollYProgress } = useScroll();
    const { scrollYProgress: heroScroll } = useScroll(
        isAuthenticated
            ? undefined
            : {
                target: heroRef,
                offset: ["start start", "end start"],
            }
    );
    const progressScale = useSpring(scrollYProgress, {
        stiffness: 120,
        damping: 28,
        mass: 0.3,
    });
    const heroTextY = useTransform(heroScroll, [0, 1], [0, -84]);
    const heroVisualY = useTransform(heroScroll, [0, 1], [0, 72]);
    const heroOpacity = useTransform(heroScroll, [0, 0.8], [1, 0.18]);

    // 로그아웃 상태에서는 대시보드 섹션 자체를 렌더링하지 않으므로(isAuthenticated로
    // 감싸져 있음), 여기서는 조회를 건너뛰기만 하면 되고 남은 이전 state를 굳이
    // 동기적으로 초기화할 필요가 없습니다.
    useEffect(() => {
        if (!isAuthenticated) {
            return undefined;
        }

        let cancelled = false;

        async function loadDashboardResults() {
            try {
                const response = await getResults({ page: 0, size: 10 });
                const content = response.data?.content;

                if (!cancelled) {
                    setDashboardResults(Array.isArray(content) ? content : []);
                    setDashboardError("");
                }
            } catch {
                if (!cancelled) {
                    setDashboardError("최근 분석 결과를 불러오지 못했습니다.");
                    setDashboardResults([]);
                }
            }
        }

        loadDashboardResults();

        return () => {
            cancelled = true;
        };
    }, [isAuthenticated, dashboardReloadKey]);

    function toggleFaq(index) {
        setOpenFaqIndex((current) => (current === index ? null : index));
    }

    function retryDashboard() {
        setDashboardResults(null);
        setDashboardError("");
        setDashboardReloadKey((previous) => previous + 1);
    }

    if (isAuthenticated) {
        return (
            <AuthenticatedDashboard
                user={user}
                results={dashboardResults}
                error={dashboardError}
                onRetry={retryDashboard}
            />
        );
    }

    return (
        <div className="landing-page" ref={pageRef}>
            <motion.div
                className="landing-scroll-progress"
                style={{ scaleX: prefersReducedMotion ? 1 : progressScale }}
                aria-hidden="true"
            />
            <section className="animated-hero-section" ref={heroRef}>
                <div className="hero-ambient-grid" aria-hidden="true" />
                <div className="hero-motion-ribbon ribbon-one" aria-hidden="true" />
                <div className="hero-motion-ribbon ribbon-two" aria-hidden="true" />
                <div className="hero-content-grid">
                    <motion.div
                        className="hero-copy"
                        style={prefersReducedMotion ? undefined : { y: heroTextY, opacity: heroOpacity }}
                        initial={prefersReducedMotion ? false : { opacity: 0, y: 18, filter: "blur(10px)" }}
                        animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
                        transition={{ duration: 0.7, ease: EASE_OUT }}
                    >
                        <motion.p
                            className="hero-eyebrow"
                            animate={prefersReducedMotion ? undefined : { backgroundPosition: ["0% 50%", "100% 50%", "0% 50%"] }}
                            transition={{ duration: 5, repeat: Infinity, ease: "linear" }}
                        >
                            AI 발표 코칭
                        </motion.p>
                        <h1 className="hero-title">
                            발표는 감이 아니라,
                            <br />
                            데이터로 완성됩니다
                        </h1>
                        <p className="hero-description-copy">
                            업로드한 발표 영상을 기반으로 자세, 시선, 제스처, 음성 속도,
                            필러 표현, 침묵 구간을 분석하고 맞춤형 피드백을 제공합니다.
                        </p>
                        <p className="hero-metric-caption">예시 결과 — 실제 분석 시 내 점수로 대체됩니다</p>
                        <div className="hero-metric-strip" aria-label="샘플 분석 지표">
                            {HERO_METRICS.map((metric, index) => (
                                <HeroMetric
                                    key={metric.label}
                                    label={metric.label}
                                    target={metric.target}
                                    suffix={metric.suffix}
                                    accent={metric.accent}
                                    delay={560 + index * 160}
                                />
                            ))}
                        </div>
                        <div className="hero-actions">
                            <>
                                <motion.div whileHover={prefersReducedMotion ? undefined : { y: -3 }} whileTap={{ scale: 0.98 }}>
                                    <Link to="/signup" className={buttonVariantClassName("primary", "motion-cta")}>
                                        로컬 테스트 시작
                                    </Link>
                                </motion.div>
                                <motion.div whileHover={prefersReducedMotion ? undefined : { y: -3 }} whileTap={{ scale: 0.98 }}>
                                    <Link to="/login" className={buttonVariantClassName("secondary", "motion-cta")}>
                                        로그인
                                    </Link>
                                </motion.div>
                            </>
                        </div>
                    </motion.div>

                    <motion.div
                        className="hero-visual-stage"
                        style={prefersReducedMotion ? undefined : { y: heroVisualY }}
                    >
                        <HeroIllustration prefersReducedMotion={prefersReducedMotion} />
                        <motion.div
                            className="hero-live-caption"
                            initial={prefersReducedMotion ? false : { opacity: 0, x: 24 }}
                            animate={{ opacity: 1, x: 0 }}
                            transition={{ duration: 0.7, delay: 0.42, ease: EASE_OUT }}
                        >
                            <span>실시간 분석</span>
                            <strong>표정 안정성 +12%</strong>
                        </motion.div>
                    </motion.div>
                </div>

                <motion.a
                    href="#features"
                    className="hero-scroll-hint"
                    initial={prefersReducedMotion ? false : { opacity: 0 }}
                    animate={
                        prefersReducedMotion
                            ? { opacity: 1 }
                            : { opacity: 1, y: [0, 10, 0] }
                    }
                    transition={
                        prefersReducedMotion
                            ? { duration: 0.5, delay: 1 }
                            : { opacity: { duration: 0.5, delay: 1 }, y: { duration: 1.8, repeat: Infinity, ease: "easeInOut", delay: 1 } }
                    }
                    aria-label="아래로 스크롤해서 더 알아보기"
                >
                    <span>스크롤해서 더 보기</span>
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                        <path d="M12 4v15" />
                        <path d="M5 12l7 7 7-7" />
                    </svg>
                </motion.a>
            </section>

            <AnimatedSection className="motion-section bg-background-primary px-6 py-24 text-text-primary sm:px-10 lg:px-16" id="features">
                <div className="mx-auto max-w-[1200px]">
                    <motion.div className="mb-14 max-w-[560px]" variants={fadeUp}>
                        <p className="mb-3 text-sm font-semibold tracking-wide text-primary-bright">핵심 기능</p>
                        <h2 className="text-3xl font-semibold leading-snug text-text-primary sm:text-4xl">
                            발표의 모든 순간을
                            <br />
                            세심하게 살핍니다
                        </h2>
                    </motion.div>

                    <div className="grid grid-cols-1 gap-6 sm:grid-cols-2">
                        {FEATURE_ITEMS.map((item, index) => (
                            <motion.article
                                className="motion-card rounded-2xl border border-white/10 bg-surface-primary p-9"
                                key={item.title}
                                variants={fadeUp}
                                whileHover={prefersReducedMotion ? undefined : { y: -10, scale: 1.015 }}
                                whileTap={{ scale: 0.99 }}
                                transition={{ duration: 0.32, delay: index * 0.02, ease: EASE_OUT }}
                            >
                                <motion.div
                                    className="mb-5 flex h-11 w-11 items-center justify-center rounded-xl"
                                    style={{ background: item.color }}
                                    animate={prefersReducedMotion ? undefined : { rotate: [0, -4, 4, 0] }}
                                    transition={{ duration: 5, repeat: Infinity, delay: index * 0.35, ease: "easeInOut" }}
                                >
                                    {item.icon}
                                </motion.div>
                                <h3 className="mb-2.5 text-xl font-semibold text-text-primary">{item.title}</h3>
                                <p className="text-sm leading-relaxed text-text-secondary">{item.description}</p>
                            </motion.article>
                        ))}
                    </div>
                </div>
            </AnimatedSection>

            <AnimatedSection className="motion-section bg-background-secondary px-6 py-24 text-text-primary sm:px-10 lg:px-16" id="analysis-detail">
                <div className="mx-auto max-w-[1200px]">
                    <motion.div className="mb-14 max-w-[560px]" variants={fadeUp}>
                        <p className="mb-3 text-sm font-semibold tracking-wide text-primary-bright">분석 항목 상세 소개</p>
                        <h2 className="text-3xl font-semibold leading-snug text-text-primary sm:text-4xl">
                            결과 상세 화면에서
                            <br />
                            확인하는 실제 지표
                        </h2>
                    </motion.div>

                    <div className="grid grid-cols-1 gap-6 sm:grid-cols-2">
                        {ANALYSIS_DETAIL_ITEMS.map((item, index) => (
                            <motion.article
                                className="motion-card motion-card-alt rounded-2xl border border-white/10 bg-surface-secondary p-9"
                                key={item.title}
                                variants={fadeUp}
                                whileHover={prefersReducedMotion ? undefined : { y: -8, rotateX: 2 }}
                                transition={{ duration: 0.32, delay: index * 0.02, ease: EASE_OUT }}
                            >
                                <span className="detail-index">0{index + 1}</span>
                                <h3 className="mb-2.5 text-xl font-semibold text-text-primary">{item.title}</h3>
                                <p className="text-sm leading-relaxed text-text-secondary">{item.description}</p>
                            </motion.article>
                        ))}
                    </div>
                </div>
            </AnimatedSection>

            <AnimatedSection className="motion-section bg-background-primary px-6 py-24 text-text-primary sm:px-10 lg:px-16" id="how-it-works">
                <div className="mx-auto max-w-[1200px]">
                    <motion.div className="mx-auto mb-14 max-w-[560px] text-center" variants={fadeUp}>
                        <p className="mb-3 text-sm font-semibold tracking-wide text-primary-bright">사용 방법</p>
                        <h2 className="text-3xl font-semibold leading-snug text-text-primary sm:text-4xl">3단계면 충분합니다</h2>
                    </motion.div>

                    <div className="mx-auto grid max-w-[1000px] grid-cols-1 gap-10 sm:grid-cols-3">
                        {HOW_IT_WORKS_STEPS.map((step, index) => (
                            <motion.div
                                className="process-step text-center"
                                key={step.title}
                                variants={fadeUp}
                                whileHover={prefersReducedMotion ? undefined : { y: -6 }}
                            >
                                <motion.div
                                    className="process-step-number mx-auto mb-5 flex h-14 w-14 items-center justify-center rounded-full bg-primary-orange text-2xl font-semibold text-warm-white"
                                    animate={prefersReducedMotion ? undefined : { boxShadow: [
                                        "0 0 0 rgba(242,116,36,0)",
                                        "0 0 34px rgba(242,116,36,0.42)",
                                        "0 0 0 rgba(242,116,36,0)",
                                    ] }}
                                    transition={{ duration: 3, repeat: Infinity, delay: index * 0.42, ease: "easeInOut" }}
                                >
                                    {index + 1}
                                </motion.div>
                                <h3 className="mb-2 text-lg font-semibold text-text-primary">{step.title}</h3>
                                <p className="text-sm leading-relaxed text-text-secondary">{step.description}</p>
                            </motion.div>
                        ))}
                    </div>
                </div>
            </AnimatedSection>

            <AnimatedSection className="motion-section bg-background-secondary px-6 py-24 text-text-primary sm:px-10 lg:px-16" id="faq">
                <div className="mx-auto max-w-[1200px]">
                    <motion.div className="mb-14 max-w-[560px]" variants={fadeUp}>
                        <p className="mb-3 text-sm font-semibold tracking-wide text-primary-bright">FAQ</p>
                        <h2 className="text-3xl font-semibold leading-snug text-text-primary sm:text-4xl">자주 묻는 질문</h2>
                    </motion.div>

                    <div className="max-w-[760px]">
                        {FAQ_ITEMS.map((item, index) => {
                            const isOpen = openFaqIndex === index;

                            return (
                                <motion.div className="faq-row border-b border-white/10 py-6" key={item.question} variants={fadeUp}>
                                    <button
                                        type="button"
                                        className="flex w-full items-center justify-between text-left text-base font-semibold text-text-primary"
                                        onClick={() => toggleFaq(index)}
                                        aria-expanded={isOpen}
                                    >
                                        <span>{item.question}</span>
                                        <motion.span
                                            className="text-xl font-normal text-primary-bright"
                                            animate={{ rotate: isOpen ? 180 : 0 }}
                                            transition={{ duration: 0.24, ease: "easeOut" }}
                                        >
                                            {isOpen ? "\u2212" : "+"}
                                        </motion.span>
                                    </button>
                                    {isOpen && (
                                        <motion.p
                                            className="mt-3.5 max-w-[600px] text-sm leading-relaxed text-text-secondary"
                                            initial={prefersReducedMotion ? false : { opacity: 0, height: 0, y: -8 }}
                                            animate={{ opacity: 1, height: "auto", y: 0 }}
                                            transition={{ duration: 0.24, ease: "easeOut" }}
                                        >
                                            {item.answer}
                                        </motion.p>
                                    )}
                                </motion.div>
                            );
                        })}
                    </div>
                </div>
            </AnimatedSection>

            <AnimatedSection className="motion-section final-cta-section bg-background-primary px-6 py-24 text-center text-text-primary sm:px-10 lg:px-16">
                <div className="mx-auto max-w-[1200px]">
                    <motion.h2 className="mb-8 text-3xl font-semibold sm:text-4xl" variants={fadeUp}>
                        지금, 데이터로 발표를 완성하세요
                    </motion.h2>
                    <motion.div variants={fadeUp} whileHover={prefersReducedMotion ? undefined : { y: -4, scale: 1.02 }} whileTap={{ scale: 0.98 }}>
                        <Link
                            to="/signup"
                            className="final-cta-link inline-flex items-center justify-center rounded-full bg-primary-deep px-8 py-4 text-base font-semibold text-warm-white transition-colors duration-200 hover:bg-primary-deep/85 active:bg-primary-deep/70"
                        >
                            테스트 계정 만들기
                        </Link>
                    </motion.div>
                </div>
            </AnimatedSection>
        </div>
    );
}

export default HomePage;
