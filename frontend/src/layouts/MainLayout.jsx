import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

function MainLayout() {
    const { user, logout } = useAuth();

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
                            to="/account"
                            className={({ isActive }) =>
                                isActive ? "nav-link active" : "nav-link"
                            }
                        >
                            계정
                        </NavLink>

                        <NavLink
                            to="/status"
                            className={({ isActive }) =>
                                isActive ? "nav-link active" : "nav-link"
                            }
                        >
                            시스템 상태
                        </NavLink>

                        <span className="nav-link">
                            {user?.email || "사용자"}
                        </span>

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
                    </nav>
                </div>
            </header>

            <main className="app-main">
                <Outlet />
            </main>
        </div>
    );
}

export default MainLayout;
