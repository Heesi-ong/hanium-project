import { Link, useSearchParams } from "react-router-dom";
import { useState } from "react";
import { confirmPasswordReset } from "../api/authApi";
import StateMessage from "../components/StateMessage";
import PasswordToggleButton from "../components/PasswordToggleButton";
import AuthPageShell from "../components/auth/AuthPageShell";

function ResetPasswordPage() {
    const [searchParams] = useSearchParams();
    const token = searchParams.get("token") || "";

    const [newPassword, setNewPassword] = useState("");
    const [showPassword, setShowPassword] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState(token ? "" : "재설정 토큰이 없습니다.");
    const [success, setSuccess] = useState("");

    async function handleSubmit(event) {
        event.preventDefault();

        if (!token) {
            setError("재설정 토큰이 없습니다.");
            return;
        }

        try {
            setLoading(true);
            setError("");
            setSuccess("");

            await confirmPasswordReset({
                token,
                newPassword,
            });

            setSuccess("비밀번호가 재설정되었습니다. 새 비밀번호로 로그인해 주세요.");
        } catch (requestError) {
            setError(requestError.message || "비밀번호 재설정 중 오류가 발생했습니다.");
        } finally {
            setLoading(false);
        }
    }

    return (
        <AuthPageShell
            eyebrow="New password"
            title="새 비밀번호 설정"
            description="새 비밀번호는 영문자와 숫자를 포함해 8자 이상이어야 합니다."
            contextEyebrow="Secure handoff"
            contextTitle="재설정 링크에서 새 로그인 정보로 전환합니다"
            contextDescription={
                token
                    ? "링크에 재설정 토큰이 포함되어 있습니다. 새 비밀번호를 입력해 변경을 요청하세요."
                    : "새 비밀번호를 설정하려면 이메일로 전달된 재설정 링크의 토큰이 필요합니다."
            }
            contextPoints={[
                "링크에 포함된 재설정 토큰 확인",
                "비밀번호 변경 완료 후 로그인 화면으로 이동",
            ]}
        >
                <form className="mt-8 space-y-5" onSubmit={handleSubmit}>
                    <div
                        className={`flex items-center gap-3 rounded-xl border p-4 ${
                            token
                                ? "border-success/30 bg-success/10 text-success"
                                : "border-error/30 bg-error/10 text-error"
                        }`}
                        role="status"
                    >
                        <span className="inline-flex h-8 w-8 flex-none items-center justify-center rounded-lg border border-current font-black">
                            {token ? "✓" : "!"}
                        </span>
                        <span>
                            <small className="block text-xs font-bold uppercase tracking-[0.08em]">
                                재설정 링크 상태
                            </small>
                            <strong className="mt-1 block text-sm text-text-primary">
                                {token ? "토큰 포함됨" : "토큰 없음"}
                            </strong>
                        </span>
                    </div>

                    <label>
                        <span>
                            <strong>새 비밀번호</strong>
                            <input
                                type={showPassword ? "text" : "password"}
                                value={newPassword}
                                onChange={(event) => setNewPassword(event.target.value)}
                                autoComplete="new-password"
                                minLength={8}
                                className="text-input"
                                aria-describedby="reset-password-hint"
                                required
                                disabled={!token}
                            />
                            <small
                                id="reset-password-hint"
                                className="mt-2 block text-[13px] leading-5 text-text-secondary"
                            >
                                영문자와 숫자를 각각 1자 이상 포함해 8자 이상 입력해주세요.
                            </small>
                        </span>
                    </label>

                    <PasswordToggleButton
                        visible={showPassword}
                        onToggle={() => setShowPassword((prev) => !prev)}
                        disabled={!token}
                    />

                    <StateMessage type="error">{error}</StateMessage>
                    <StateMessage type="success">{success}</StateMessage>

                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                        <button
                            type="submit"
                            className="primary-button w-full"
                            disabled={loading || !token}
                        >
                            {loading ? "변경 중..." : "비밀번호 변경"}
                        </button>

                        <Link to="/login" className="secondary-button w-full">
                            로그인으로 이동
                        </Link>
                    </div>
                </form>
        </AuthPageShell>
    );
}

export default ResetPasswordPage;
