import { Link, useSearchParams } from "react-router-dom";
import { useState } from "react";
import { confirmPasswordReset } from "../api/authApi";
import StateMessage from "../components/StateMessage";
import PasswordToggleButton from "../components/PasswordToggleButton";

const hintStyle = {
    display: "block",
    marginTop: 6,
    fontSize: 13,
    color: "#B7ADA4",
};

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
        <main className="mx-auto max-w-[1120px] px-6 pb-20 pt-12">
            <section className="page-section">
                <article className="upload-card">
                    <p className="eyebrow">New password</p>
                    <h2>새 비밀번호 설정</h2>
                    <p className="card-description">
                        새 비밀번호는 영문자와 숫자를 포함해 8자 이상이어야 합니다.
                    </p>

                    <form className="option-panel" onSubmit={handleSubmit}>
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
                                    required
                                    disabled={!token}
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

                        <StateMessage type="error">{error}</StateMessage>
                        <StateMessage type="success">{success}</StateMessage>

                        <div className="button-row">
                            <button
                                type="submit"
                                className="primary-button"
                                disabled={loading || !token}
                            >
                                {loading ? "변경 중..." : "비밀번호 변경"}
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

export default ResetPasswordPage;
