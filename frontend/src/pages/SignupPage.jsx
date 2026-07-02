import { Link, Navigate, useNavigate } from "react-router-dom";
import { useState } from "react";
import { signup } from "../api/authApi";
import StateMessage from "../components/StateMessage";
import { useAuth } from "../context/AuthContext";

const inputStyle = {
    width: "100%",
    minHeight: 46,
    marginTop: 8,
    padding: "0 14px",
    border: "1px solid #cbd5e1",
    borderRadius: 12,
    background: "#ffffff",
    color: "#0f172a",
};

function SignupPage() {
    const navigate = useNavigate();
    const { isAuthenticated } = useAuth();

    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState("");

    if (isAuthenticated) {
        return <Navigate to="/" replace />;
    }

    async function handleSubmit(event) {
        event.preventDefault();

        try {
            setLoading(true);
            setError("");
            setSuccess("");

            await signup({
                email,
                password,
            });

            setSuccess("회원가입이 완료되었습니다. 로그인해 주세요.");
            navigate("/login", {
                replace: true,
                state: {
                    email,
                },
            });
        } catch (requestError) {
            setError(
                requestError.message ||
                "회원가입 중 오류가 발생했습니다."
            );
        } finally {
            setLoading(false);
        }
    }

    return (
        <main className="app-main">
            <section className="page-section">
                <article className="upload-card">
                    <p className="eyebrow">Create account</p>
                    <h2>회원가입</h2>
                    <p className="card-description">
                        이메일과 비밀번호를 등록한 뒤 로그인해서 분석 기능을 사용합니다.
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
                                    autoComplete="new-password"
                                    minLength={8}
                                    style={inputStyle}
                                    required
                                />
                            </span>
                        </label>

                        <StateMessage type="error">{error}</StateMessage>
                        <StateMessage type="success">{success}</StateMessage>

                        <div className="button-row">
                            <button
                                type="submit"
                                className="primary-button"
                                disabled={loading}
                            >
                                {loading ? "가입 중..." : "회원가입"}
                            </button>

                            <Link to="/login" className="secondary-button">
                                로그인으로 이동
                            </Link>
                        </div>
                    </form>
                </article>
            </section>
        </main>
    );
}

export default SignupPage;
