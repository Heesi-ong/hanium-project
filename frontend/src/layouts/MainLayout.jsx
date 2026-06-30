import { NavLink, Outlet } from "react-router-dom";

function MainLayout() {
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