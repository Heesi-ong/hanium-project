import { Link, NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../context/AuthContext";

function navLinkClassName({ isActive }) {
    const base = "rounded-lg px-3.5 py-2.5 text-sm font-bold transition-colors duration-150";

    if (isActive) {
        return `${base} bg-primary-orange text-warm-white`;
    }

    return `${base} text-text-secondary hover:bg-primary-orange/15 hover:text-text-primary`;
}

function accountLinkClassName({ isActive }) {
    const border = isActive ? "border-transparent" : "border-white/15";
    return `${navLinkClassName({ isActive })} inline-flex items-center gap-1.5 max-w-[220px] border ${border}`;
}

function MainLayout() {
    const { isAuthenticated, user, logout } = useAuth();

    return (
        <div className="min-h-screen bg-background-primary text-text-primary">
            <header className="sticky top-0 z-20 border-b border-white/10 bg-background-primary/90 backdrop-blur-md">
                <div className="mx-auto flex h-[72px] max-w-[1120px] items-center justify-between px-6">
                    <NavLink to="/" className="inline-flex items-center gap-2.5 font-extrabold text-text-primary">
                        <span className="inline-flex h-[34px] w-[34px] items-center justify-center rounded-xl bg-primary-orange text-sm text-warm-white">
                            AI
                        </span>
                        <span className="text-lg tracking-tight text-text-primary">Presentation Coach</span>
                    </NavLink>

                    <nav className="flex items-center gap-2">
                        <NavLink to="/" className={navLinkClassName}>
                            홈
                        </NavLink>

                        <NavLink to="/pricing" className={navLinkClassName}>
                            요금제
                        </NavLink>

                        {isAuthenticated ? (
                            <>
                                <NavLink to="/upload" className={navLinkClassName}>
                                    영상 업로드
                                </NavLink>

                                <NavLink to="/results" className={navLinkClassName}>
                                    분석 결과
                                </NavLink>

                                <NavLink to="/status" className={navLinkClassName}>
                                    시스템 상태
                                </NavLink>

                                {user?.admin && (
                                    <NavLink to="/admin" className={navLinkClassName}>
                                        관리자
                                    </NavLink>
                                )}

                                {/* 로그인한 이메일 표시부를 계정 설정 진입점으로 합쳤습니다.
                                    이메일을 누르면 /account(계정 설정)로 이동합니다. */}
                                <NavLink to="/account" className={accountLinkClassName} title="계정 설정">
                                    <svg
                                        className="h-4 w-4 flex-shrink-0"
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
                                    <span className="overflow-hidden text-ellipsis whitespace-nowrap">
                                        {user?.email || "사용자"}
                                    </span>
                                </NavLink>

                                <button
                                    type="button"
                                    className="rounded-lg border-0 bg-transparent px-3.5 py-2.5 text-sm font-bold text-text-secondary transition-colors duration-150 hover:bg-primary-orange/15 hover:text-text-primary"
                                    onClick={logout}
                                >
                                    로그아웃
                                </button>
                            </>
                        ) : (
                            <>
                                <NavLink to="/login" className={navLinkClassName}>
                                    로그인
                                </NavLink>

                                <NavLink to="/signup" className={navLinkClassName}>
                                    회원가입
                                </NavLink>
                            </>
                        )}
                    </nav>
                </div>
            </header>

            <main className="mx-auto max-w-[1120px] px-6 pb-20 pt-12">
                <Outlet />
            </main>

            <footer className="px-6 py-6 text-center text-sm text-text-muted">
                <Link to="/privacy" className="text-text-secondary hover:text-primary-bright">
                    개인정보처리방침
                </Link>
                <span aria-hidden="true"> · </span>
                <Link to="/terms" className="text-text-secondary hover:text-primary-bright">
                    이용약관
                </Link>
            </footer>
        </div>
    );
}

export default MainLayout;
