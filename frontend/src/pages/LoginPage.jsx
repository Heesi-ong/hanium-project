import { Link, Navigate, useLocation, useNavigate } from "react-router-dom";
import { useEffect, useState } from "react";
import StateMessage from "../components/StateMessage";
import { useAuth } from "../context/AuthContext";

const inputStyle = {
    width: "100%",
    minHeight: 46,
    marginTop: 8,
    padding: "0 14px",
    border: "1px solid rgba(43, 36, 32, 0.18)",
    borderRadius: 12,
    background: "#ffffff",
    color: "#2B2420",
};

function LoginPage() {
    const navigate = useNavigate();
    const location = useLocation();
    const { isAuthenticated, login } = useAuth();

    const [email, setEmail] = useState(location.state?.email || "");
    const [password, setPassword] = useState("");
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

            await login({
                email,
                password,
            });

            sessionStorage.removeItem("redirectAfterLogin");
            sessionStorage.removeItem("sessionExpired");
            setSessionExpired(false);
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
        <main className="app-main">
            <section className="page-section">
                <article className="upload-card">
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
                                    style={inputStyle}
                                    required
                                />
                            </span>
                        </label>

                        <label>
                            <span>
                                <strong>비밀번호</strong>
                                <input
                                    type="password"
                                    value={password}
                                    onChange={(event) => setPassword(event.target.value)}
                                    autoComplete="current-password"
                                    minLength={8}
                                    style={inputStyle}
                                    required
                                />
                            </span>
                        </label>

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
                        <Link to="/privacy">개인정보처리방침</Link>
                        <span aria-hidden="true"> · </span>
                        <Link to="/terms">이용약관</Link>
                    </p>
                </article>
            </section>
        </main>
    );
}

export default LoginPage;
