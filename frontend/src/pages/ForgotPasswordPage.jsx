import { Link } from "react-router-dom";
import { useState } from "react";
import { requestPasswordReset } from "../api/authApi";
import StateMessage from "../components/StateMessage";
import AuthPageShell from "../components/auth/AuthPageShell";

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
        <AuthPageShell
            eyebrow="Password reset"
            title="비밀번호 재설정"
            description="가입한 이메일을 입력하면 재설정 안내를 보냅니다."
            contextEyebrow="Recovery flow"
            contextTitle="안내를 받은 뒤 안전하게 비밀번호를 바꾸세요"
            contextDescription="이메일로 재설정 안내를 요청하고, 전달된 링크에서 새 비밀번호를 설정합니다."
            contextPoints={[
                "가입한 이메일로 재설정 안내 요청",
                "전달된 링크의 토큰으로 새 비밀번호 설정",
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

                    <StateMessage type="error">{error}</StateMessage>
                    <StateMessage type="success">{success}</StateMessage>

                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                        <button
                            type="submit"
                            className="primary-button w-full"
                            disabled={loading}
                        >
                            {loading ? "요청 중..." : "재설정 안내 받기"}
                        </button>

                        <Link to="/login" className="secondary-button w-full">
                            로그인으로 이동
                        </Link>
                    </div>
                </form>
        </AuthPageShell>
    );
}

export default ForgotPasswordPage;
