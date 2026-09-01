import { Link, Navigate, useLocation, useNavigate } from "react-router-dom";
import { useEffect, useState } from "react";
import StateMessage from "../components/StateMessage";
import PasswordToggleButton from "../components/PasswordToggleButton";
import PageFadeIn from "../components/motion/PageFadeIn";
import { useAuth } from "../context/AuthContext";

function LoginPage() {
    const navigate = useNavigate();
    const location = useLocation();
    const { isAuthenticated, login } = useAuth();

    const [email, setEmail] = useState(location.state?.email || "");
    const [password, setPassword] = useState("");
    const [showPassword, setShowPassword] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [sessionExpired, setSessionExpired] = useState(
        sessionStorage.getItem("sessionExpired") === "true"
    );

    const redirectPath =
        location.state?.from?.pathname ||
        sessionStorage.getItem("redirectAfterLogin") ||
        "/";

    useEffect(() => {
        if (sessionExpired) {
            sessionStorage.removeItem("sessionExpired");
        }
    }, [sessionExpired]);

    if (isAuthenticated) {
        return <Navigate to={redirectPath} replace />;
    }

    async function handleSubmit(event) {
        event.preventDefault();

        try {
            setLoading(true);
            setError("");

            const result = await login({
                email,
                password,
            });

            sessionStorage.removeItem("redirectAfterLogin");
            sessionStorage.removeItem("sessionExpired");
            setSessionExpired(false);

            if (result.user?.onboardingCompleted === false && result.user?.onboardingSkipped === false) {
                navigate("/onboarding", { replace: true, state: { from: redirectPath } });
                return;
            }

            navigate(redirectPath, { replace: true });
        } catch (requestError) {
            setError(
                requestError.message ||
                "로그인 중 오류가 발생했습니다."
            );
        } finally {
            setLoading(false);
        }
    }

    return (
        <PageFadeIn className="flex min-h-[calc(100svh-200px)] items-center justify-center py-6">
            <article className="upload-card w-full max-w-[520px]">
                <p className="eyebrow">Sign in</p>
                <h2>로그인</h2>
                <p className="card-description">
                    가입한 이메일과 비밀번호로 발표 분석 서비스를 이용합니다.
                </p>

                <form className="option-panel" onSubmit={handleSubmit}>
                    <label>
                        <span>
                            <strong>이메일</strong>
                            <input
                                type="email"
                                value={email}
                                onChange={(event) => setEmail(event.target.value)}
                                autoComplete="email"
                                className="text-input"
                                required
                            />
                        </span>
                    </label>

                    <label>
                        <span>
                            <strong>비밀번호</strong>
                            <input
                                type={showPassword ? "text" : "password"}
                                value={password}
                                onChange={(event) => setPassword(event.target.value)}
                                autoComplete="current-password"
                                minLength={8}
                                className="text-input"
                                required
                            />
                        </span>
                    </label>

                    <PasswordToggleButton
                        visible={showPassword}
                        onToggle={() => setShowPassword((prev) => !prev)}
                    />

                    <p className="auth-policy-links" style={{ textAlign: "right" }}>
                        <Link to="/forgot-password">비밀번호를 잊으셨나요?</Link>
                    </p>

                    <StateMessage type="info">
                        {sessionExpired ? "세션이 만료되어 다시 로그인해주세요." : ""}
                    </StateMessage>
                    <StateMessage type="error">{error}</StateMessage>

                    <div className="button-row">
                        <button
                            type="submit"
                            className="primary-button"
                            disabled={loading}
                        >
                            {loading ? "로그인 중..." : "로그인"}
                        </button>

                        <Link to="/signup" className="secondary-button">
                            회원가입
                        </Link>
                    </div>
                </form>

                <p className="auth-policy-links">
                    <Link to="/privacy">테스트 데이터 처리 안내</Link>
                    <span aria-hidden="true"> · </span>
                    <Link to="/terms">프로젝트 이용 안내</Link>
                </p>
            </article>
        </PageFadeIn>
    );
}

export default LoginPage;
