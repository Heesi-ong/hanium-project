import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { withdrawAccount } from "../api/authApi";
import { getErrorMessage } from "../api/errorUtils";
import StateMessage from "../components/StateMessage";
import PasswordToggleButton from "../components/PasswordToggleButton";
import { useAuth } from "../context/AuthContext";
import { useConfirm } from "../context/ConfirmContext";
import { useToast } from "../context/ToastContext";

function AccountPage() {
    const navigate = useNavigate();
    const { user, logout } = useAuth();
    const { showToast } = useToast();
    const confirm = useConfirm();

    const [password, setPassword] = useState("");
    const [showPassword, setShowPassword] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");

    async function handleWithdraw(event) {
        event.preventDefault();

        const confirmed = await confirm(
            "정말로 회원탈퇴하시겠습니까? 업로드한 영상과 분석 결과가 모두 영구적으로 삭제되며 되돌릴 수 없습니다."
        );

        if (!confirmed) {
            return;
        }

        try {
            setLoading(true);
            setError("");

            await withdrawAccount(password);
            await logout({ silent: true });
            showToast("회원탈퇴가 완료되었습니다.", "success");
            navigate("/login", { replace: true });
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "회원탈퇴 처리 중 오류가 발생했습니다."
            ));
        } finally {
            setLoading(false);
        }
    }

    return (
        <section className="page-section">
            <article className="upload-card">
                <p className="eyebrow">Account</p>
                <h2>계정</h2>
                <p className="card-description">
                    현재 로그인한 계정과 데이터 삭제 요청을 관리합니다.
                </p>

                <div className="option-panel">
                    <strong>이메일</strong>
                    <p className="card-description">
                        {user?.email || "사용자"}
                    </p>
                </div>

                <form className="option-panel" onSubmit={handleWithdraw}>
                    <label>
                        <span>
                            <strong>비밀번호 확인</strong>
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

                    <StateMessage type="error">{error}</StateMessage>

                    <div className="button-row">
                        <button
                            type="submit"
                            className="primary-button"
                            disabled={loading}
                        >
                            {loading ? "처리 중..." : "회원탈퇴"}
                        </button>
                    </div>
                </form>
            </article>
        </section>
    );
}

export default AccountPage;
