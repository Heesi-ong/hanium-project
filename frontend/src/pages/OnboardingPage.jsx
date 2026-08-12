import { useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { completeOnboarding, skipOnboarding } from "../api/onboardingApi";
import StateMessage from "../components/StateMessage";
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
        <main className="mx-auto max-w-[1120px] px-6 pb-20 pt-12">
            <section className="page-section">
                <article className="upload-card">
                    <p className="eyebrow">Get started</p>
                    <h2>서비스를 어떻게 이용하실 건가요?</h2>
                    <p className="card-description">
                        답변은 향후 추천 설정에 활용할 예정입니다. 언제든 건너뛸 수 있고, 계정 설정에서 다시 확인하거나 수정할 수 있습니다.
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
                                disabled={loading || skipping}
                            >
                                {loading ? "저장 중..." : "저장하고 시작하기"}
                            </button>

                            <button
                                type="button"
                                className="secondary-button"
                                onClick={handleSkip}
                                disabled={loading || skipping}
                            >
                                {skipButtonLabel}
                            </button>
                        </div>
                    </form>
                </article>
            </section>
        </main>
    );
}

export default OnboardingPage;
