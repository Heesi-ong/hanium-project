import { Link } from "react-router-dom";
import { useState } from "react";
import { requestPasswordReset } from "../api/authApi";
import StateMessage from "../components/StateMessage";
import PageFadeIn from "../components/motion/PageFadeIn";

function ForgotPasswordPage() {
    const [email, setEmail] = useState("");
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState("");

    async function handleSubmit(event) {
        event.preventDefault();

        try {
            setLoading(true);
            setError("");
            setSuccess("");

            const response = await requestPasswordReset(email);
            setSuccess(response.message || "비밀번호 재설정 안내를 확인해 주세요.");
        } catch (requestError) {
            setError(requestError.message || "비밀번호 재설정 요청 중 오류가 발생했습니다.");
        } finally {
            setLoading(false);
        }
    }

    return (
        <PageFadeIn className="flex min-h-[calc(100svh-200px)] items-center justify-center py-6">
            <article className="upload-card w-full max-w-[520px]">
                <p className="eyebrow">Password reset</p>
                <h2>비밀번호 재설정</h2>
                <p className="card-description">
                    가입한 이메일을 입력하면 재설정 안내를 보냅니다.
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

                    <StateMessage type="error">{error}</StateMessage>
                    <StateMessage type="success">{success}</StateMessage>

                    <div className="button-row">
                        <button
                            type="submit"
                            className="primary-button"
                            disabled={loading}
                        >
                            {loading ? "요청 중..." : "재설정 안내 받기"}
                        </button>

                        <Link to="/login" className="secondary-button">
                            로그인으로 이동
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

export default ForgotPasswordPage;
