import { useState } from "react";
import { Link } from "react-router-dom";
import { motion, useReducedMotion } from "motion/react";
import { useAuth } from "../context/AuthContext";
import { buttonVariantClassName } from "../components/ui/Button";
import "./HomePage.css";

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
            "표정 점수, 표현력 원점수, 표정 다양성 점수와 주요 표정 상태 집계를 함께 보여줍니다.",
    },
    {
        title: "음성·발화",
        description:
            "WPM, 단어 수, 발화·침묵 시간, 침묵 비율, 필러 표현과 STT transcript를 확인합니다.",
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

function HeroIllustration() {
    return (
        <svg
            viewBox="0 0 400 500"
            className="w-full max-w-[340px] h-auto"
            role="img"
            aria-label="분석 결과 화면을 표현한 일러스트"
        >
            <rect x="0" y="0" width="400" height="500" rx="20" fill="#17130F" stroke="rgba(255,255,255,0.09)" strokeWidth="1.5" />
            <rect x="0" y="0" width="400" height="40" rx="20" fill="#211A14" />
            <circle cx="24" cy="20" r="5" fill="#F27424" />
            <circle cx="42" cy="20" r="5" fill="#F6A66B" />
            <circle cx="60" cy="20" r="5" fill="#4FC78A" />
            <rect x="24" y="60" width="352" height="180" rx="14" fill="#2A2018" />
            <circle cx="200" cy="150" r="28" fill="rgba(247,243,238,0.12)" />
            <path d="M190 135l30 15-30 15z" fill="#F5EFE8" />
            <rect x="24" y="258" width="164" height="70" rx="14" fill="#211A14" />
            <text x="40" y="285" fontSize="12" fill="#B7ADA4">총점</text>
            <text x="40" y="315" fontSize="26" fill="#F5EFE8">82</text>
            <rect x="212" y="258" width="164" height="70" rx="14" fill="#211A14" />
            <text x="228" y="285" fontSize="12" fill="#B7ADA4">등급</text>
            <text x="228" y="315" fontSize="26" fill="#F5EFE8">B+</text>
            <rect x="24" y="344" width="352" height="120" rx="14" fill="#211A14" />
            <text x="40" y="368" fontSize="12" fill="#B7ADA4">회차별 총점 추이</text>
            <polyline
                points="40 430 96 410 152 420 208 380 264 390 320 360"
                fill="none"
                stroke="#F27424"
                strokeWidth="3"
                strokeLinecap="round"
                strokeLinejoin="round"
            />
        </svg>
    );
}

function HomePage() {
    const { isAuthenticated } = useAuth();
    const [openFaqIndex, setOpenFaqIndex] = useState(null);
    const prefersReducedMotion = useReducedMotion();

    function toggleFaq(index) {
        setOpenFaqIndex((current) => (current === index ? null : index));
    }

    return (
        <div className="landing-page">
            <section className="relative overflow-hidden bg-background-primary px-6 py-24 text-text-primary sm:px-10 lg:px-16 lg:py-32">
                <div
                    className="pointer-events-none absolute inset-0"
                    style={{
                        background:
                            "radial-gradient(circle at 50% 30%, rgba(200,90,25,0.25), transparent 55%)",
                    }}
                    aria-hidden="true"
                />
                <div className="relative mx-auto grid max-w-[1200px] items-center gap-12 lg:grid-cols-[1.1fr_0.9fr]">
                    <motion.div
                        initial={prefersReducedMotion ? false : { opacity: 0, y: 16 }}
                        animate={{ opacity: 1, y: 0 }}
                        transition={{ duration: 0.5, ease: "easeOut" }}
                    >
                        <p className="mb-6 inline-flex items-center gap-2 rounded-full bg-primary-orange/15 px-4 py-2 text-sm font-semibold text-soft-orange">
                            AI 발표 코칭
                        </p>
                        <h1 className="mb-6 text-[40px] font-semibold leading-[1.2] tracking-tight sm:text-[54px] lg:text-[64px]">
                            발표는 감이 아니라,
                            <br />
                            데이터로 완성됩니다
                        </h1>
                        <p className="mb-9 max-w-[480px] text-lg leading-relaxed text-text-secondary">
                            업로드한 발표 영상을 기반으로 자세, 시선, 제스처, 음성 속도,
                            필러 표현, 침묵 구간을 분석하고 맞춤형 피드백을 제공합니다.
                        </p>
                        <div className="flex flex-wrap items-center gap-4">
                            {isAuthenticated ? (
                                <>
                                    <Link to="/upload" className={buttonVariantClassName("primary")}>
                                        영상 업로드 시작
                                    </Link>
                                    <Link to="/results" className={buttonVariantClassName("secondary")}>
                                        분석 결과 보기
                                    </Link>
                                </>
                            ) : (
                                <>
                                    <Link to="/signup" className={buttonVariantClassName("primary")}>
                                        무료로 시작하기
                                    </Link>
                                    <Link to="/login" className={buttonVariantClassName("secondary")}>
                                        로그인
                                    </Link>
                                </>
                            )}
                        </div>
                    </motion.div>

                    <motion.div
                        className="flex justify-center"
                        initial={prefersReducedMotion ? false : { opacity: 0 }}
                        animate={{ opacity: 1 }}
                        transition={{ duration: 0.6, delay: 0.15, ease: "easeOut" }}
                    >
                        <HeroIllustration />
                    </motion.div>
                </div>
            </section>

            <section className="bg-background-primary px-6 py-24 text-text-primary sm:px-10 lg:px-16" id="features">
                <div className="mx-auto max-w-[1200px]">
                    <div className="mb-14 max-w-[560px]">
                        <p className="mb-3 text-sm font-semibold tracking-wide text-primary-bright">핵심 기능</p>
                        <h2 className="text-3xl font-semibold leading-snug text-text-primary sm:text-4xl">
                            발표의 모든 순간을
                            <br />
                            세심하게 살핍니다
                        </h2>
                    </div>

                    <div className="grid grid-cols-1 gap-6 sm:grid-cols-2">
                        {FEATURE_ITEMS.map((item, index) => (
                            <motion.article
                                className="rounded-2xl border border-white/10 bg-surface-primary p-9 transition-transform duration-200 hover:-translate-y-1"
                                key={item.title}
                                initial={prefersReducedMotion ? false : { opacity: 0, y: 20 }}
                                whileInView={{ opacity: 1, y: 0 }}
                                viewport={{ once: true, amount: 0.2 }}
                                transition={{ duration: 0.4, delay: index * 0.05, ease: "easeOut" }}
                            >
                                <div
                                    className="mb-5 flex h-11 w-11 items-center justify-center rounded-xl"
                                    style={{ background: item.color }}
                                >
                                    {item.icon}
                                </div>
                                <h3 className="mb-2.5 text-xl font-semibold text-text-primary">{item.title}</h3>
                                <p className="text-sm leading-relaxed text-text-secondary">{item.description}</p>
                            </motion.article>
                        ))}
                    </div>
                </div>
            </section>

            <section className="bg-background-secondary px-6 py-24 text-text-primary sm:px-10 lg:px-16" id="analysis-detail">
                <div className="mx-auto max-w-[1200px]">
                    <div className="mb-14 max-w-[560px]">
                        <p className="mb-3 text-sm font-semibold tracking-wide text-primary-bright">분석 항목 상세 소개</p>
                        <h2 className="text-3xl font-semibold leading-snug text-text-primary sm:text-4xl">
                            결과 상세 화면에서
                            <br />
                            확인하는 실제 지표
                        </h2>
                    </div>

                    <div className="grid grid-cols-1 gap-6 sm:grid-cols-2">
                        {ANALYSIS_DETAIL_ITEMS.map((item, index) => (
                            <motion.article
                                className="rounded-2xl border border-white/10 bg-surface-secondary p-9 transition-transform duration-200 hover:-translate-y-1"
                                key={item.title}
                                initial={prefersReducedMotion ? false : { opacity: 0, y: 20 }}
                                whileInView={{ opacity: 1, y: 0 }}
                                viewport={{ once: true, amount: 0.2 }}
                                transition={{ duration: 0.4, delay: index * 0.05, ease: "easeOut" }}
                            >
                                <h3 className="mb-2.5 text-xl font-semibold text-text-primary">{item.title}</h3>
                                <p className="text-sm leading-relaxed text-text-secondary">{item.description}</p>
                            </motion.article>
                        ))}
                    </div>
                </div>
            </section>

            <section className="bg-background-primary px-6 py-24 text-text-primary sm:px-10 lg:px-16" id="how-it-works">
                <div className="mx-auto max-w-[1200px]">
                    <div className="mx-auto mb-14 max-w-[560px] text-center">
                        <p className="mb-3 text-sm font-semibold tracking-wide text-primary-bright">사용 방법</p>
                        <h2 className="text-3xl font-semibold leading-snug text-text-primary sm:text-4xl">3단계면 충분합니다</h2>
                    </div>

                    <div className="mx-auto grid max-w-[1000px] grid-cols-1 gap-10 sm:grid-cols-3">
                        {HOW_IT_WORKS_STEPS.map((step, index) => (
                            <motion.div
                                className="text-center"
                                key={step.title}
                                initial={prefersReducedMotion ? false : { opacity: 0, y: 20 }}
                                whileInView={{ opacity: 1, y: 0 }}
                                viewport={{ once: true, amount: 0.2 }}
                                transition={{ duration: 0.4, delay: index * 0.05, ease: "easeOut" }}
                            >
                                <div className="mx-auto mb-5 flex h-14 w-14 items-center justify-center rounded-full bg-primary-orange text-2xl font-semibold text-warm-white">
                                    {index + 1}
                                </div>
                                <h3 className="mb-2 text-lg font-semibold text-text-primary">{step.title}</h3>
                                <p className="text-sm leading-relaxed text-text-secondary">{step.description}</p>
                            </motion.div>
                        ))}
                    </div>
                </div>
            </section>

            <section className="bg-background-secondary px-6 py-24 text-text-primary sm:px-10 lg:px-16" id="faq">
                <div className="mx-auto max-w-[1200px]">
                    <div className="mb-14 max-w-[560px]">
                        <p className="mb-3 text-sm font-semibold tracking-wide text-primary-bright">FAQ</p>
                        <h2 className="text-3xl font-semibold leading-snug text-text-primary sm:text-4xl">자주 묻는 질문</h2>
                    </div>

                    <div className="max-w-[760px]">
                        {FAQ_ITEMS.map((item, index) => {
                            const isOpen = openFaqIndex === index;

                            return (
                                <div className="border-b border-white/10 py-6" key={item.question}>
                                    <button
                                        type="button"
                                        className="flex w-full items-center justify-between text-left text-base font-semibold text-text-primary"
                                        onClick={() => toggleFaq(index)}
                                        aria-expanded={isOpen}
                                    >
                                        <span>{item.question}</span>
                                        <span className="text-xl font-normal text-primary-bright">{isOpen ? "\u2212" : "+"}</span>
                                    </button>
                                    {isOpen && (
                                        <motion.p
                                            className="mt-3.5 max-w-[600px] text-sm leading-relaxed text-text-secondary"
                                            initial={prefersReducedMotion ? false : { opacity: 0, y: -4 }}
                                            animate={{ opacity: 1, y: 0 }}
                                            transition={{ duration: 0.2, ease: "easeOut" }}
                                        >
                                            {item.answer}
                                        </motion.p>
                                    )}
                                </div>
                            );
                        })}
                    </div>
                </div>
            </section>

            <section className="bg-background-primary px-6 py-24 text-center text-text-primary sm:px-10 lg:px-16">
                <div className="mx-auto max-w-[1200px]">
                    <h2 className="mb-8 text-3xl font-semibold sm:text-4xl">지금, 데이터로 발표를 완성하세요</h2>
                    {isAuthenticated ? (
                        <Link
                            to="/upload"
                            className="inline-flex items-center justify-center rounded-full bg-primary-orange px-8 py-4 text-base font-semibold text-warm-white transition-colors duration-200 hover:bg-primary-bright active:bg-primary-deep"
                        >
                            지금 업로드하기
                        </Link>
                    ) : (
                        <Link
                            to="/signup"
                            className="inline-flex items-center justify-center rounded-full bg-primary-orange px-8 py-4 text-base font-semibold text-warm-white transition-colors duration-200 hover:bg-primary-bright active:bg-primary-deep"
                        >
                            무료 회원가입
                        </Link>
                    )}
                </div>
            </section>

            <footer className="border-t border-white/10 bg-background-primary px-6 py-14 text-text-primary sm:px-10 lg:px-16">
                <div className="mx-auto max-w-[1200px]">
                    <div className="mb-10 flex flex-wrap items-start justify-between gap-8">
                        <div>
                            <p className="mb-2.5 text-2xl italic text-text-primary">AI Presentation Coach</p>
                            <p className="max-w-[280px] text-sm leading-relaxed text-text-muted">
                                누구나 자신 있게 발표할 수 있도록, 데이터로 돕는 AI 발표 분석 서비스입니다.
                            </p>
                        </div>

                        <div className="flex flex-col gap-2.5 text-sm">
                            <span className="mb-1 text-xs font-semibold text-text-primary">서비스</span>
                            <a className="text-text-secondary hover:text-primary-bright" href="#features">기능</a>
                            <a className="text-text-secondary hover:text-primary-bright" href="#how-it-works">사용 방법</a>
                            <a className="text-text-secondary hover:text-primary-bright" href="#faq">FAQ</a>
                        </div>
                    </div>

                    <div className="border-t border-white/10 pt-6 text-xs text-text-muted">
                        &copy; {new Date().getFullYear()} AI Presentation Coach. All rights reserved.
                    </div>
                </div>
            </footer>
        </div>
    );
}

export default HomePage;
