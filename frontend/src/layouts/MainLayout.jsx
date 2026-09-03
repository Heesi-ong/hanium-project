import { useEffect, useState } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router-dom";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useAuth } from "../context/AuthContext";
import { EASE_OUT } from "../components/motion/animationVariants";

function navLinkClassName({ isActive }) {
    const base = "flex-shrink-0 whitespace-nowrap rounded-lg px-3.5 py-2.5 text-sm font-bold transition-colors duration-150";

    if (isActive) {
        return `${base} bg-primary-deep text-warm-white`;
    }

    return `${base} text-text-secondary hover:bg-primary-orange/15 hover:text-text-primary`;
}

function mobileNavLinkClassName({ isActive }) {
    const base = "block w-full rounded-lg px-3.5 py-2.5 text-left text-sm font-bold transition-colors duration-150";

    if (isActive) {
        return `${base} bg-primary-deep text-warm-white`;
    }

    return `${base} text-text-secondary hover:bg-primary-orange/15 hover:text-text-primary`;
}

function accountLinkClassName({ isActive }) {
    const border = isActive ? "border-transparent" : "border-white/15";
    return `${navLinkClassName({ isActive })} inline-flex items-center gap-1.5 max-w-[220px] border ${border}`;
}

function mobileAccountLinkClassName({ isActive }) {
    const border = isActive ? "border-transparent" : "border-white/15";
    return `${mobileNavLinkClassName({ isActive })} inline-flex items-center gap-1.5 border ${border}`;
}

// 데스크톱 가로 nav와 모바일 접힘 메뉴가 같은 링크 목록을 공유하도록 한 곳에 모았습니다.
// onNavigate는 모바일 메뉴에서 링크를 눌렀을 때 메뉴를 닫는 용도로, 데스크톱에서는
// 아무 동작도 하지 않는 빈 함수를 넘깁니다.
function NavLinks({ isAuthenticated, user, logout, variant, onNavigate }) {
    const isMobile = variant === "mobile";
    const linkClassName = isMobile ? mobileNavLinkClassName : navLinkClassName;
    const accountClassName = isMobile ? mobileAccountLinkClassName : accountLinkClassName;

    return (
        <>
            <NavLink to="/" className={linkClassName} onClick={onNavigate}>
                홈
            </NavLink>

            <NavLink to="/pricing" className={linkClassName} onClick={onNavigate}>
                프로젝트 안내
            </NavLink>

            {isAuthenticated ? (
                <>
                    <NavLink to="/upload" className={linkClassName} onClick={onNavigate}>
                        영상 업로드
                    </NavLink>

                    <NavLink to="/results" className={linkClassName} onClick={onNavigate}>
                        분석 결과
                    </NavLink>

                    <NavLink to="/status" className={linkClassName} onClick={onNavigate}>
                        서비스 상태
                    </NavLink>

                    {user?.admin && (
                        <NavLink to="/admin" className={linkClassName} onClick={onNavigate}>
                            관리자
                        </NavLink>
                    )}

                    {/* 로그인한 이메일 표시부를 계정 설정 진입점으로 합쳤습니다.
                        이메일을 누르면 /account(계정 설정)로 이동합니다. */}
                    <NavLink to="/account" className={accountClassName} title="계정 설정" onClick={onNavigate}>
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
                        className={
                            isMobile
                                ? "block w-full rounded-lg border-0 bg-transparent px-3.5 py-2.5 text-left text-sm font-bold text-text-secondary transition-colors duration-150 hover:bg-primary-orange/15 hover:text-text-primary"
                                : "flex-shrink-0 whitespace-nowrap rounded-lg border-0 bg-transparent px-3.5 py-2.5 text-sm font-bold text-text-secondary transition-colors duration-150 hover:bg-primary-orange/15 hover:text-text-primary"
                        }
                        onClick={() => {
                            onNavigate?.();
                            logout();
                        }}
                    >
                        로그아웃
                    </button>
                </>
            ) : (
                <>
                    <NavLink to="/login" className={linkClassName} onClick={onNavigate}>
                        로그인
                    </NavLink>

                    <NavLink to="/signup" className={linkClassName} onClick={onNavigate}>
                        회원가입
                    </NavLink>
                </>
            )}
        </>
    );
}

function MenuIcon({ open }) {
    if (open) {
        return (
            <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
                <path
                    fill="currentColor"
                    d="M6.4 4.98 4.98 6.4 10.59 12l-5.61 5.6 1.41 1.42L12 13.4l5.6 5.61 1.41-1.41L13.4 12l5.61-5.6-1.41-1.41L12 10.59Z"
                />
            </svg>
        );
    }

    return (
        <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path fill="currentColor" d="M3 6h18v2H3zm0 5h18v2H3zm0 5h18v2H3z" />
        </svg>
    );
}

function MainLayout() {
    const { isAuthenticated, user, logout } = useAuth();
    const prefersReducedMotion = useReducedMotion();
    const location = useLocation();
    const [isMenuOpen, setIsMenuOpen] = useState(false);
    const [previousPathname, setPreviousPathname] = useState(location.pathname);
    const isHomePage = location.pathname === "/";

    // 라우트가 바뀌면(링크 클릭 등으로) 모바일 메뉴를 항상 닫아, 다음 페이지에 이전
    // 페이지에서 열어둔 메뉴가 그대로 남지 않게 합니다. 이펙트 대신 렌더링 중에 바로
    // 조정하는 방식(React 공식 문서의 "prop이 바뀌면 state 조정하기" 패턴)을 씁니다 —
    // 이펙트에서 setState를 호출하면 한 프레임 더 지연되어 깜빡임이 생길 수 있습니다.
    if (location.pathname !== previousPathname) {
        setPreviousPathname(location.pathname);
        setIsMenuOpen(false);
    }

    // Esc로도 닫을 수 있게 합니다(모바일 메뉴가 열려 있을 때만 리스너를 붙입니다).
    useEffect(() => {
        if (!isMenuOpen) {
            return undefined;
        }

        function handleKeyDown(event) {
            if (event.key === "Escape") {
                setIsMenuOpen(false);
            }
        }

        window.addEventListener("keydown", handleKeyDown);
        return () => window.removeEventListener("keydown", handleKeyDown);
    }, [isMenuOpen]);

    return (
        <div className="app-shell bg-background-primary text-text-primary">
            <div className="app-shell-ambient" aria-hidden="true" />
            <motion.header
                className="app-shell-header sticky top-0 z-40 border-b border-border-subtle bg-background-primary/90 backdrop-blur-md"
                initial={prefersReducedMotion ? false : { opacity: 0, y: -18 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.42, ease: EASE_OUT }}
            >
                <div className="mx-auto flex h-[72px] max-w-[1120px] items-center justify-between px-6">
                    <NavLink to="/" className="inline-flex min-h-11 items-center gap-2.5 rounded-xl font-extrabold text-text-primary transition-transform duration-200 hover:-translate-y-0.5">
                        <motion.span
                            className="inline-flex h-[34px] w-[34px] items-center justify-center rounded-xl bg-primary-deep text-sm text-warm-white"
                            animate={prefersReducedMotion ? undefined : { boxShadow: [
                                "0 0 0 rgba(242,116,36,0)",
                                "0 0 24px rgba(242,116,36,0.34)",
                                "0 0 0 rgba(242,116,36,0)",
                            ] }}
                            transition={{ duration: 3.2, repeat: Infinity, ease: "easeInOut" }}
                        >
                            AI
                        </motion.span>
                        <span className="text-lg tracking-tight text-text-primary">Presentation Coach</span>
                    </NavLink>

                    {/* 로그인 상태에서는 링크 수가 많아 좁은 화면(lg 미만)에서 가로 폭을
                        넘치거나 라벨이 세로로 쪼개져 표시되므로, lg 이상에서만 가로 nav를
                        보여주고 그 아래 화면 폭에서는 햄버거 메뉴로 접습니다. */}
                    <nav className="hidden items-center gap-2 lg:flex" aria-label="주요 메뉴">
                        <NavLinks
                            isAuthenticated={isAuthenticated}
                            user={user}
                            logout={logout}
                            variant="desktop"
                            onNavigate={undefined}
                        />
                    </nav>

                    <button
                        type="button"
                        className="inline-flex h-11 w-11 appearance-none items-center justify-center rounded-control border border-border-subtle bg-surface-primary text-text-secondary shadow-sm transition-colors duration-150 hover:border-border-emphasis hover:bg-surface-secondary hover:text-text-primary lg:hidden"
                        aria-expanded={isMenuOpen}
                        aria-controls="mobile-nav-menu"
                        aria-label={isMenuOpen ? "메뉴 닫기" : "메뉴 열기"}
                        onClick={() => setIsMenuOpen((previous) => !previous)}
                    >
                        <MenuIcon open={isMenuOpen} />
                    </button>
                </div>

                {/* 닫힐 때 AnimatePresence의 exit 애니메이션까지 넣으면 언마운트가 비동기로
                    지연돼(테스트 환경뿐 아니라 실제로도) 방금 닫았는데 잠깐 남아있는 것처럼
                    보일 수 있어, 열릴 때만 살짝 나타나는 진입 애니메이션만 적용하고 닫을 때는
                    즉시 사라지게 합니다. */}
                {isMenuOpen && (
                    <motion.nav
                        id="mobile-nav-menu"
                        aria-label="모바일 메뉴"
                        className="flex flex-col gap-1 border-t border-white/10 px-6 py-3 lg:hidden"
                        initial={prefersReducedMotion ? false : { opacity: 0, y: -8 }}
                        animate={{ opacity: 1, y: 0 }}
                        transition={{ duration: 0.18, ease: EASE_OUT }}
                    >
                        <NavLinks
                            isAuthenticated={isAuthenticated}
                            user={user}
                            logout={logout}
                            variant="mobile"
                            onNavigate={() => setIsMenuOpen(false)}
                        />
                    </motion.nav>
                )}
            </motion.header>

            <main
                className={`app-shell-main ${isHomePage ? "app-shell-main--full" : "app-shell-main--contained"}`}
                data-layout={isHomePage ? "full-bleed" : "contained"}
            >
                <AnimatePresence>
                    <motion.div
                        className="min-w-0"
                        key={location.pathname}
                        initial={prefersReducedMotion ? false : { opacity: 0, y: 16 }}
                        animate={{ opacity: 1, y: 0 }}
                        exit={prefersReducedMotion ? undefined : { opacity: 0, y: -12 }}
                        transition={{ duration: 0.32, ease: EASE_OUT }}
                    >
                        <Outlet />
                    </motion.div>
                </AnimatePresence>
            </main>

            {/* 이전에는 홈 페이지가 자체 footer를 하나 더 렌더링해 이 전역 footer와
                연속으로 두 번 표시됐습니다(P1-05). 이제 footer는 이 한 곳에만 있습니다. */}
            <footer className="app-shell-footer border-t border-border-subtle bg-background-primary/90 px-6 py-10 text-text-primary backdrop-blur-sm sm:px-10 lg:px-16">
                <div className="mx-auto flex max-w-[1120px] flex-col gap-6 sm:flex-row sm:items-center sm:justify-between">
                    <div>
                        <p className="text-base italic text-text-primary">AI Presentation Coach</p>
                        <p className="mt-1 text-xs text-text-muted">
                            학생 프로젝트 · 로컬 테스트 전용
                        </p>
                    </div>

                    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
                        <Link to="/privacy" className="text-text-secondary hover:text-primary-bright">
                            테스트 데이터 처리 안내
                        </Link>
                        <Link to="/terms" className="text-text-secondary hover:text-primary-bright">
                            프로젝트 이용 안내
                        </Link>
                    </div>
                </div>
            </footer>
        </div>
    );
}

export default MainLayout;
