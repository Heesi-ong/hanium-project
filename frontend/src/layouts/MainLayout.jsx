import { Link, NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

function MainLayout() {
    const { isAuthenticated, user, logout } = useAuth();

    return (
        <div className="app-shell">
            <header className="app-header">
                <div className="app-header-inner">
                    <NavLink to="/" className="brand">
                        <span className="brand-mark">AI</span>
                        <span className="brand-text">Presentation Coach</span>
                    </NavLink>

                    <nav className="nav-menu">
                        <NavLink
                            to="/"
                            className={({ isActive }) =>
                                isActive ? "nav-link active" : "nav-link"
                            }
                        >
                            홈
                        </NavLink>

                        {isAuthenticated ? (
                            <>
                                <NavLink
                                    to="/upload"
                                    className={({ isActive }) =>
                                        isActive ? "nav-link active" : "nav-link"
                                    }
                                >
                                    영상 업로드
                                </NavLink>

                                <NavLink
                                    to="/results"
                                    className={({ isActive }) =>
                                        isActive ? "nav-link active" : "nav-link"
                                    }
                                >
                                    분석 결과
                                </NavLink>

                                <NavLink
                                    to="/status"
                                    className={({ isActive }) =>
                                        isActive ? "nav-link active" : "nav-link"
                                    }
                                >
                                    시스템 상태
                                </NavLink>

                                {/* 로그인한 이메일 표시부를 계정 설정 진입점으로 합쳤습니다.
                                    이메일을 누르면 /account(계정 설정)로 이동합니다. */}
                                <NavLink
                                    to="/account"
                                    className={({ isActive }) =>
                                        isActive
                                            ? "nav-link account-link active"
                                            : "nav-link account-link"
                                    }
                                    title="계정 설정"
                                >
                                    <svg
                                        className="account-icon"
                                        width="16"
                                        height="16"
                                        viewBox="0 0 24 24"
                                        aria-hidden="true"
                                        focusable="false"
                                    >
                                        <path
                                            fill="currentColor"
                                            d="M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10Zm0 2c-4.42 0-8 2.24-8 5v1h16v-1c0-2.76-3.58-5-8-5Z"
                                        />
                                    </svg>
                                    <span className="account-email">
                                        {user?.email || "사용자"}
                                    </span>
                                </NavLink>

                                <button
                                    type="button"
                                    className="nav-link"
                                    style={{
                                        border: 0,
                                        background: "transparent",
                                    }}
                                    onClick={logout}
                                >
                                    로그아웃
                                </button>
                            </>
                        ) : (
                            <>
                                <NavLink
                                    to="/login"
                                    className={({ isActive }) =>
                                        isActive ? "nav-link active" : "nav-link"
                                    }
                                >
                                    로그인
                                </NavLink>

                                <NavLink
                                    to="/signup"
                                    className={({ isActive }) =>
                                        isActive ? "nav-link active" : "nav-link"
                                    }
                                >
                                    회원가입
                                </NavLink>
                            </>
                        )}
                    </nav>
                </div>
            </header>

            <main className="app-main">
                <Outlet />
            </main>

            <footer className="app-footer">
                <Link to="/privacy">개인정보처리방침</Link>
                <span aria-hidden="true"> · </span>
                <Link to="/terms">이용약관</Link>
            </footer>
        </div>
    );
}

export default MainLayout;
