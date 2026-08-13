import { Link, Navigate, useNavigate } from "react-router-dom";
import { useState } from "react";
import { signup } from "../api/authApi";
import StateMessage from "../components/StateMessage";
import PasswordToggleButton from "../components/PasswordToggleButton";
import PageFadeIn from "../components/motion/PageFadeIn";
import { useAuth } from "../context/AuthContext";

const hintStyle = {
    display: "block",
    marginTop: 6,
    fontSize: 13,
    color: "#B7ADA4",
};

function SignupPage() {
    const navigate = useNavigate();
    const { isAuthenticated } = useAuth();

    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [showPassword, setShowPassword] = useState(false);
    const [agreedToTerms, setAgreedToTerms] = useState(false);
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

            if (!agreedToTerms) {
                setError("테스트 데이터 처리 안내 및 프로젝트 이용 안내에 동의해야 계정을 만들 수 있습니다.");
                return;
            }

            await signup({
                email,
                password,
                agreedToTerms: true,
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
        <main className="mx-auto max-w-[1120px] px-6 pb-20 pt-12">
            <PageFadeIn className="page-section">
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
                                    autoComplete="new-password"
                                    minLength={8}
                                    className="text-input"
                                    required
                                />
                                <small style={hintStyle}>
                                    영문자와 숫자를 각각 1자 이상 포함해 8자 이상 입력해주세요.
                                </small>
                            </span>
                        </label>

                        <PasswordToggleButton
                            visible={showPassword}
                            onToggle={() => setShowPassword((prev) => !prev)}
                        />

                        <label className="terms-agreement">
                            <input
                                type="checkbox"
                                checked={agreedToTerms}
                                onChange={(event) => setAgreedToTerms(event.target.checked)}
                            />
                            <span>
                                <Link to="/privacy" target="_blank" rel="noreferrer">
                                    테스트 데이터 처리 안내
                                </Link>
                                {" 및 "}
                                <Link to="/terms" target="_blank" rel="noreferrer">
                                    프로젝트 이용 안내
                                </Link>
                                에 동의합니다.
                            </span>
                        </label>

                        <StateMessage type="error">{error}</StateMessage>
                        <StateMessage type="success">{success}</StateMessage>

                        <div className="button-row">
                            <button
                                type="submit"
                                className="primary-button"
                                disabled={loading || !agreedToTerms}
                            >
                                {loading ? "가입 중..." : "회원가입"}
                            </button>

                            <Link to="/login" className="secondary-button">
                                로그인으로 이동
                            </Link>
                        </div>
                    </form>
                </article>
            </PageFadeIn>
        </main>
    );
}

export default SignupPage;
