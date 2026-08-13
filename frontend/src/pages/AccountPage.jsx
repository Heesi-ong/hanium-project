import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { changePassword, withdrawAccount } from "../api/authApi";
import { getErrorMessage } from "../api/errorUtils";
import StateMessage from "../components/StateMessage";
import PasswordToggleButton from "../components/PasswordToggleButton";
import { useAuth } from "../context/AuthContext";
import { useConfirm } from "../context/ConfirmContext";
import { useToast } from "../context/ToastContext";
import {
    getExperienceLevelLabel,
    getImprovementGoalLabel,
    getPurposeLabel,
} from "../constants/onboarding";

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
                    로그인 정보, 발표 분석 설정, 문의·데이터 권리 요청을 관리합니다.
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

                <div className="option-panel">
                    <strong>온보딩 설정</strong>

                    {user?.onboardingCompleted ? (
                        <>
                            <p className="card-description">
                                목적: {getPurposeLabel(user.purpose)} · 경험 수준: {getExperienceLevelLabel(user.experienceLevel)} · 개선 목표: {getImprovementGoalLabel(user.improvementGoal)}
                            </p>
                            <p className="card-description">
                                답변은 향후 추천 설정에 활용할 예정입니다.
                            </p>
                        </>
                    ) : (
                        <p className="card-description">
                            {user?.onboardingSkipped
                                ? "온보딩 질문에 아직 답변하지 않았습니다(건너뜀)."
                                : "온보딩 질문에 아직 답변하지 않았습니다."}
                        </p>
                    )}

                    <div className="button-row">
                        <button
                            type="button"
                            className="secondary-button"
                            onClick={() => navigate("/onboarding", { state: { from: "/account" } })}
                        >
                            {user?.onboardingCompleted ? "온보딩 설정 수정" : "온보딩 질문에 답변하기"}
                        </button>
                    </div>
                </div>

                <div className="option-panel">
                    <strong>테스트 데이터 관리</strong>
                    <p className="card-description">
                        시연이 끝나면 결과 삭제 또는 이 페이지 아래의 회원탈퇴를 이용해
                        업로드 영상과 분석 결과를 정리하세요. 세부 처리 방식은 아래 안내에서 확인할 수 있습니다.
                    </p>

                    <div className="button-row">
                        <Link to="/privacy" className="secondary-button">
                            테스트 데이터 처리 안내
                        </Link>
                        <Link to="/terms" className="secondary-button">
                            프로젝트 이용 안내
                        </Link>
                    </div>
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
                            className="danger-button"
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
