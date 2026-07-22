import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { changePassword, withdrawAccount } from "../api/authApi";
import { getErrorMessage } from "../api/errorUtils";
import StateMessage from "../components/StateMessage";
import PasswordToggleButton from "../components/PasswordToggleButton";
import { useAuth } from "../context/AuthContext";
import { useConfirm } from "../context/ConfirmContext";
import { useToast } from "../context/ToastContext";

const hintStyle = {
    display: "block",
    marginTop: 6,
    fontSize: 13,
    color: "#B7ADA4",
};

function AccountPage() {
    const navigate = useNavigate();
    const { user, logout } = useAuth();
    const { showToast } = useToast();
    const confirm = useConfirm();

    const [password, setPassword] = useState("");
    const [showPassword, setShowPassword] = useState(false);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");

    const [currentPassword, setCurrentPassword] = useState("");
    const [newPassword, setNewPassword] = useState("");
    const [confirmNewPassword, setConfirmNewPassword] = useState("");
    const [showPasswordChangeFields, setShowPasswordChangeFields] = useState(false);
    const [passwordChangeLoading, setPasswordChangeLoading] = useState(false);
    const [passwordChangeError, setPasswordChangeError] = useState("");

    async function handleChangePassword(event) {
        event.preventDefault();

        if (newPassword !== confirmNewPassword) {
            setPasswordChangeError("새 비밀번호가 서로 일치하지 않습니다.");
            return;
        }

        try {
            setPasswordChangeLoading(true);
            setPasswordChangeError("");

            await changePassword({ currentPassword, newPassword });

            setCurrentPassword("");
            setNewPassword("");
            setConfirmNewPassword("");
            setShowPasswordChangeFields(false);
            showToast("비밀번호가 변경되었습니다.", "success");
        } catch (requestError) {
            setPasswordChangeError(getErrorMessage(
                requestError,
                "비밀번호 변경 중 오류가 발생했습니다."
            ));
        } finally {
            setPasswordChangeLoading(false);
        }
    }

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

                <div className="option-panel">
                    <strong>비밀번호</strong>

                    {!showPasswordChangeFields ? (
                        <div className="button-row">
                            <button
                                type="button"
                                className="secondary-button"
                                onClick={() => setShowPasswordChangeFields(true)}
                            >
                                비밀번호 변경
                            </button>
                        </div>
                    ) : (
                        <form className="option-panel" onSubmit={handleChangePassword}>
                            <label>
                                <span>
                                    <strong>현재 비밀번호</strong>
                                    <input
                                        type="password"
                                        value={currentPassword}
                                        onChange={(event) => setCurrentPassword(event.target.value)}
                                        autoComplete="current-password"
                                        className="text-input"
                                        required
                                    />
                                </span>
                            </label>

                            <label>
                                <span>
                                    <strong>새 비밀번호</strong>
                                    <input
                                        type="password"
                                        value={newPassword}
                                        onChange={(event) => setNewPassword(event.target.value)}
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

                            <label>
                                <span>
                                    <strong>새 비밀번호 확인</strong>
                                    <input
                                        type="password"
                                        value={confirmNewPassword}
                                        onChange={(event) => setConfirmNewPassword(event.target.value)}
                                        autoComplete="new-password"
                                        minLength={8}
                                        className="text-input"
                                        required
                                    />
                                </span>
                            </label>

                            <StateMessage type="error">{passwordChangeError}</StateMessage>

                            <div className="button-row">
                                <button
                                    type="submit"
                                    className="primary-button"
                                    disabled={passwordChangeLoading}
                                >
                                    {passwordChangeLoading ? "변경 중..." : "비밀번호 변경 저장"}
                                </button>

                                <button
                                    type="button"
                                    className="secondary-button"
                                    onClick={() => {
                                        setShowPasswordChangeFields(false);
                                        setCurrentPassword("");
                                        setNewPassword("");
                                        setConfirmNewPassword("");
                                        setPasswordChangeError("");
                                    }}
                                    disabled={passwordChangeLoading}
                                >
                                    취소
                                </button>
                            </div>
                        </form>
                    )}
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
