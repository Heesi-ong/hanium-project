import { Link, Navigate, useLocation, useNavigate } from "react-router-dom";
import { useEffect, useState } from "react";
import StateMessage from "../components/StateMessage";
import PasswordToggleButton from "../components/PasswordToggleButton";
import AuthPageShell from "../components/auth/AuthPageShell";
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
        <AuthPageShell
            eyebrow="Sign in"
            title="로그인"
            description="가입한 이메일과 비밀번호로 발표 분석 서비스를 이용합니다."
            contextEyebrow="Resume your practice"
            contextTitle="연습하던 흐름으로 자연스럽게 돌아가세요"
            contextDescription="로그인 후 진행 중인 분석이나 이전에 열어보던 결과로 연결됩니다."
            contextPoints={[
                "로그인 전 요청한 화면으로 복귀",
                "온보딩 완료 여부에 맞는 다음 단계 안내",
            ]}
            footer={(
                <p className="flex flex-wrap gap-x-2 gap-y-1 text-sm text-text-secondary">
                    <Link className="rounded-md hover:text-primary-bright" to="/privacy">
                        테스트 데이터 처리 안내
                    </Link>
                    <span aria-hidden="true">·</span>
                    <Link className="rounded-md hover:text-primary-bright" to="/terms">
                        프로젝트 이용 안내
                    </Link>
                </p>
            )}
        >
                <form className="mt-8 space-y-5" onSubmit={handleSubmit}>
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

                    <p className="text-right">
                        <Link
                            className="inline-flex min-h-11 items-center rounded-lg px-1 text-sm text-text-secondary hover:text-primary-bright"
                            to="/forgot-password"
                        >
                            비밀번호를 잊으셨나요?
                        </Link>
                    </p>

                    <StateMessage type="info">
                        {sessionExpired ? "세션이 만료되어 다시 로그인해주세요." : ""}
                    </StateMessage>
                    <StateMessage type="error">{error}</StateMessage>

                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                        <button
                            type="submit"
                            className="primary-button w-full"
                            disabled={loading}
                        >
                            {loading ? "로그인 중..." : "로그인"}
                        </button>

                        <Link to="/signup" className="secondary-button w-full">
                            회원가입
                        </Link>
                    </div>
                </form>
        </AuthPageShell>
    );
}

export default LoginPage;
