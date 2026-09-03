import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { changePassword, withdrawAccount } from "../api/authApi";
import { getErrorMessage } from "../api/errorUtils";
import StateMessage from "../components/StateMessage";
import PasswordToggleButton from "../components/PasswordToggleButton";
import PageHeader from "../components/PageHeader";
import PageFadeIn from "../components/motion/PageFadeIn";
import { useAuth } from "../context/AuthContext";
import { useConfirm } from "../context/ConfirmContext";
import { useToast } from "../context/ToastContext";
import {
    getExperienceLevelLabel,
    getImprovementGoalLabel,
    getPurposeLabel,
} from "../constants/onboarding";

function AccountSectionIcon({ type }) {
    const paths = {
        identity: (
            <>
                <circle cx="12" cy="8" r="3.5" />
                <path d="M5.5 20c.4-4 2.7-6 6.5-6s6.1 2 6.5 6" />
            </>
        ),
        password: (
            <>
                <rect x="5" y="10" width="14" height="10" rx="3" />
                <path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v2" />
            </>
        ),
        practice: (
            <>
                <path d="M5 18V9M12 18V5M19 18v-6" />
                <path d="m4 6 5-3 5 4 6-4" />
            </>
        ),
        data: (
            <>
                <path d="M4 7h16M7 4h10l1 3H6l1-3Z" />
                <path d="m6 7 1 13h10l1-13M10 11v5M14 11v5" />
            </>
        ),
    };

    return (
        <span className="account-section-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none">
                <g stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                    {paths[type]}
                </g>
            </svg>
        </span>
    );
}

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
        <PageFadeIn className="account-page">
            <section className="account-hero">
                <PageHeader
                    eyebrow="Account Studio"
                    title="계정 설정"
                    description="로그인 정보와 발표 연습 설정을 확인하고, 테스트 데이터의 보관과 삭제를 관리합니다."
                />

                <aside className="account-profile-summary" aria-label="현재 계정 요약">
                    <span className="account-profile-mark" aria-hidden="true">
                        {(user?.email || "U").slice(0, 1).toUpperCase()}
                    </span>
                    <div>
                        <span>현재 로그인 계정</span>
                        <strong>{user?.email || "사용자"}</strong>
                        <small>
                            {user?.onboardingCompleted ? "연습 설정 완료" : "연습 설정 확인 필요"}
                        </small>
                    </div>
                </aside>
            </section>

            <div className="account-layout">
                <div className="account-settings-column">
                    <section className="account-panel" aria-labelledby="account-email-title">
                        <div className="account-panel-heading">
                            <AccountSectionIcon type="identity" />
                            <div>
                                <span className="account-panel-kicker">Identity</span>
                                <h2 id="account-email-title">로그인 정보</h2>
                            </div>
                        </div>
                        <dl className="account-detail-list">
                            <div>
                                <dt>이메일</dt>
                                <dd>{user?.email || "사용자"}</dd>
                            </div>
                        </dl>
                    </section>

                    <section className="account-panel" aria-labelledby="account-password-title">
                        <div className="account-panel-heading">
                            <AccountSectionIcon type="password" />
                            <div>
                                <span className="account-panel-kicker">Security</span>
                                <h2 id="account-password-title">비밀번호</h2>
                            </div>
                        </div>
                        <p className="account-panel-description">
                            현재 비밀번호를 확인한 뒤 새 비밀번호로 변경할 수 있습니다.
                        </p>

                        {!showPasswordChangeFields ? (
                            <div className="button-row account-panel-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                onClick={() => setShowPasswordChangeFields(true)}
                            >
                                비밀번호 변경
                            </button>
                            </div>
                        ) : (
                            <form className="account-inline-form" onSubmit={handleChangePassword}>
                                <label className="account-field">
                                    <strong>현재 비밀번호</strong>
                                    <input
                                        type="password"
                                        value={currentPassword}
                                        onChange={(event) => setCurrentPassword(event.target.value)}
                                        autoComplete="current-password"
                                        className="text-input"
                                        required
                                    />
                                </label>

                                <label className="account-field">
                                    <strong>새 비밀번호</strong>
                                    <input
                                        type="password"
                                        value={newPassword}
                                        onChange={(event) => setNewPassword(event.target.value)}
                                        autoComplete="new-password"
                                        minLength={8}
                                        className="text-input"
                                        required
                                        aria-describedby="new-password-hint"
                                    />
                                    <small className="account-field-hint" id="new-password-hint">
                                        영문자와 숫자를 각각 1자 이상 포함해 8자 이상 입력해주세요.
                                    </small>
                                </label>

                                <label className="account-field">
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
                                </label>

                                <StateMessage type="error">{passwordChangeError}</StateMessage>

                                <div className="button-row account-form-actions">
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
                    </section>

                    <section className="account-panel" aria-labelledby="account-practice-title">
                        <div className="account-panel-heading">
                            <AccountSectionIcon type="practice" />
                            <div>
                                <span className="account-panel-kicker">Practice profile</span>
                                <h2 id="account-practice-title">온보딩 설정</h2>
                            </div>
                        </div>

                        {user?.onboardingCompleted ? (
                            <>
                                <dl className="account-practice-summary">
                                    <div>
                                        <dt>목적</dt>
                                        <dd>{getPurposeLabel(user.purpose)}</dd>
                                    </div>
                                    <div>
                                        <dt>경험 수준</dt>
                                        <dd>{getExperienceLevelLabel(user.experienceLevel)}</dd>
                                    </div>
                                    <div>
                                        <dt>개선 목표</dt>
                                        <dd>{getImprovementGoalLabel(user.improvementGoal)}</dd>
                                    </div>
                                </dl>
                                <p className="account-panel-description">
                                    답변은 향후 추천 설정에 활용할 예정입니다.
                                </p>
                            </>
                        ) : (
                            <p className="account-panel-description">
                                {user?.onboardingSkipped
                                    ? "온보딩 질문에 아직 답변하지 않았습니다(건너뜀)."
                                    : "온보딩 질문에 아직 답변하지 않았습니다."}
                            </p>
                        )}

                        <div className="button-row account-panel-actions">
                            <button
                                type="button"
                                className="secondary-button"
                                onClick={() => navigate("/onboarding", { state: { from: "/account" } })}
                            >
                                {user?.onboardingCompleted ? "온보딩 설정 수정" : "온보딩 질문에 답변하기"}
                            </button>
                        </div>
                    </section>
                </div>

                <aside className="account-side-column" aria-label="데이터 및 계정 삭제">
                    <section className="account-panel account-data-panel" aria-labelledby="account-data-title">
                        <div className="account-panel-heading">
                            <AccountSectionIcon type="data" />
                            <div>
                                <span className="account-panel-kicker">Test data</span>
                                <h2 id="account-data-title">테스트 데이터 관리</h2>
                            </div>
                        </div>
                        <p className="account-panel-description">
                            시연이 끝나면 결과 삭제 또는 아래의 회원탈퇴를 이용해 업로드 영상과 분석 결과를 정리하세요.
                            세부 처리 방식은 안내에서 확인할 수 있습니다.
                        </p>

                        <div className="account-link-list">
                            <Link to="/privacy">
                                <span>테스트 데이터 처리 안내</span>
                                <span aria-hidden="true">↗</span>
                            </Link>
                            <Link to="/terms">
                                <span>프로젝트 이용 안내</span>
                                <span aria-hidden="true">↗</span>
                            </Link>
                        </div>
                    </section>

                    <form
                        className="account-panel account-danger-zone"
                        aria-labelledby="account-danger-title"
                        onSubmit={handleWithdraw}
                    >
                        <div className="account-danger-heading">
                            <span className="account-danger-symbol" aria-hidden="true">!</span>
                            <div>
                                <span className="account-panel-kicker">Danger zone</span>
                                <h2 id="account-danger-title">회원탈퇴와 데이터 삭제</h2>
                            </div>
                        </div>
                        <p className="account-panel-description">
                            탈퇴하면 업로드 영상과 분석 결과가 영구적으로 삭제되며 되돌릴 수 없습니다.
                        </p>

                        <label className="account-field">
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
                        </label>

                        <PasswordToggleButton
                            visible={showPassword}
                            onToggle={() => setShowPassword((prev) => !prev)}
                        />

                        <StateMessage type="error">{error}</StateMessage>

                        <div className="button-row account-panel-actions">
                            <button
                                type="submit"
                                className="danger-button"
                                disabled={loading}
                            >
                                {loading ? "처리 중..." : "회원탈퇴"}
                            </button>
                        </div>
                    </form>
                </aside>
            </div>
        </PageFadeIn>
    );
}

export default AccountPage;
