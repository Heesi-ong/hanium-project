import { useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { completeOnboarding } from "../api/onboardingApi";
import StateMessage from "../components/StateMessage";
import { useAuth } from "../context/AuthContext";

const PURPOSE_OPTIONS = [
    { value: "INTERVIEW", label: "면접 준비" },
    { value: "PRESENTATION", label: "발표/프레젠테이션" },
    { value: "LECTURE", label: "강의/교육" },
    { value: "OTHER", label: "기타" },
];

const EXPERIENCE_LEVEL_OPTIONS = [
    { value: "BEGINNER", label: "입문" },
    { value: "INTERMEDIATE", label: "중급" },
    { value: "ADVANCED", label: "숙련" },
];

const IMPROVEMENT_GOAL_OPTIONS = [
    { value: "VOICE_TONE", label: "목소리 톤" },
    { value: "PACE", label: "말하기 속도" },
    { value: "EYE_CONTACT", label: "시선 처리" },
    { value: "POSTURE", label: "자세" },
    { value: "CONTENT_STRUCTURE", label: "내용 구성" },
    { value: "OTHER", label: "기타" },
];

function OnboardingPage() {
    const navigate = useNavigate();
    const location = useLocation();
    const { updateUser } = useAuth();

    const redirectPath = location.state?.from || "/";

    const [purpose, setPurpose] = useState(PURPOSE_OPTIONS[0].value);
    const [experienceLevel, setExperienceLevel] = useState(EXPERIENCE_LEVEL_OPTIONS[0].value);
    const [improvementGoal, setImprovementGoal] = useState(IMPROVEMENT_GOAL_OPTIONS[0].value);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");

    async function handleSubmit(event) {
        event.preventDefault();

        try {
            setLoading(true);
            setError("");

            await completeOnboarding({ purpose, experienceLevel, improvementGoal });
            updateUser({ onboardingCompleted: true });
            navigate(redirectPath, { replace: true });
        } catch (requestError) {
            setError(requestError.message || "온보딩 정보 저장 중 오류가 발생했습니다.");
        } finally {
            setLoading(false);
        }
    }

    function handleSkip() {
        navigate(redirectPath, { replace: true });
    }

    return (
        <main className="mx-auto max-w-[1120px] px-6 pb-20 pt-12">
            <section className="page-section">
                <article className="upload-card">
                    <p className="eyebrow">Get started</p>
                    <h2>서비스를 어떻게 이용하실 건가요?</h2>
                    <p className="card-description">
                        답변을 바탕으로 발표 분석 피드백을 더 맞춤화할 수 있습니다. 언제든 건너뛸 수 있습니다.
                    </p>

                    <form className="option-panel" onSubmit={handleSubmit}>
                        <label>
                            <span>
                                <strong>주로 어떤 목적으로 사용하시나요?</strong>
                                <select
                                    value={purpose}
                                    onChange={(event) => setPurpose(event.target.value)}
                                    className="sort-select"
                                >
                                    {PURPOSE_OPTIONS.map((option) => (
                                        <option key={option.value} value={option.value}>
                                            {option.label}
                                        </option>
                                    ))}
                                </select>
                            </span>
                        </label>

                        <label>
                            <span>
                                <strong>발표 경험 수준은 어느 정도인가요?</strong>
                                <select
                                    value={experienceLevel}
                                    onChange={(event) => setExperienceLevel(event.target.value)}
                                    className="sort-select"
                                >
                                    {EXPERIENCE_LEVEL_OPTIONS.map((option) => (
                                        <option key={option.value} value={option.value}>
                                            {option.label}
                                        </option>
                                    ))}
                                </select>
                            </span>
                        </label>

                        <label>
                            <span>
                                <strong>가장 개선하고 싶은 부분은 무엇인가요?</strong>
                                <select
                                    value={improvementGoal}
                                    onChange={(event) => setImprovementGoal(event.target.value)}
                                    className="sort-select"
                                >
                                    {IMPROVEMENT_GOAL_OPTIONS.map((option) => (
                                        <option key={option.value} value={option.value}>
                                            {option.label}
                                        </option>
                                    ))}
                                </select>
                            </span>
                        </label>

                        <StateMessage type="error">{error}</StateMessage>

                        <div className="button-row">
                            <button
                                type="submit"
                                className="primary-button"
                                disabled={loading}
                            >
                                {loading ? "저장 중..." : "저장하고 시작하기"}
                            </button>

                            <button
                                type="button"
                                className="secondary-button"
                                onClick={handleSkip}
                                disabled={loading}
                            >
                                나중에 하기
                            </button>
                        </div>
                    </form>
                </article>
            </section>
        </main>
    );
}

export default OnboardingPage;
