import { Link, Navigate, useNavigate } from "react-router-dom";
import { useState } from "react";
import { signup } from "../api/authApi";
import StateMessage from "../components/StateMessage";
import PasswordToggleButton from "../components/PasswordToggleButton";
import AuthPageShell from "../components/auth/AuthPageShell";
import { useAuth } from "../context/AuthContext";

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
        <AuthPageShell
            eyebrow="Create account"
            title="회원가입"
            description="이메일과 비밀번호를 등록한 뒤 로그인해서 분석 기능을 사용합니다."
            contextEyebrow="Start with context"
            contextTitle="첫 분석 전에 데이터 처리 기준부터 확인하세요"
            contextDescription="이 프로젝트는 로컬 테스트 환경에서 발표 영상을 분석하며, 가입 전에 관련 안내에 동의합니다."
            contextPoints={[
                "테스트 데이터 처리 및 이용 안내 확인",
                "계정별 분석 결과와 업로드 파일 접근 보호",
            ]}
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
                                autoComplete="new-password"
                                minLength={8}
                                className="text-input"
                                aria-describedby="signup-password-hint"
                                required
                            />
                            <small
                                id="signup-password-hint"
                                className="mt-2 block text-[13px] leading-5 text-text-secondary"
                            >
                                영문자와 숫자를 각각 1자 이상 포함해 8자 이상 입력해주세요.
                            </small>
                        </span>
                    </label>

                    <PasswordToggleButton
                        visible={showPassword}
                        onToggle={() => setShowPassword((prev) => !prev)}
                    />

                    <label className="terms-agreement rounded-xl border border-border-subtle bg-background-secondary/55 p-4">
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

                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                        <button
                            type="submit"
                            className="primary-button w-full"
                            disabled={loading || !agreedToTerms}
                        >
                            {loading ? "가입 중..." : "회원가입"}
                        </button>

                        <Link to="/login" className="secondary-button w-full">
                            로그인으로 이동
                        </Link>
                    </div>
                </form>
        </AuthPageShell>
    );
}

export default SignupPage;
