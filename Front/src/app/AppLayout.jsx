// 상단 내비게이션, 로그아웃, 반응형 메뉴를 감싸는 공통 앱 레이아웃이다.
import { useEffect, useState } from "react";
import { NavLink, useLocation } from "react-router-dom";

import { logout } from "../api/accountApi";
import StateMessage from "../components/StateMessage";
import { clearPresentationChatSession } from "../features/chat/presentationChatSession";

export default function AppLayout({ user, setUser, children }) {
  const location = useLocation();
  const [logoutError, setLogoutError] = useState("");
  const [navOpen, setNavOpen] = useState(false);
  const isResultRoute =
    location.pathname === "/results" || location.pathname.startsWith("/result/");
  const isHomeRoute = location.pathname === "/";

  const handleLogout = async () => {
    setLogoutError("");
    try {
      await logout();
      clearPresentationChatSession();
      setUser(null);
    } catch (requestError) {
      setLogoutError(requestError.message || "로그아웃하지 못했습니다.");
    }
  };

  useEffect(() => {
    setNavOpen(false);
  }, [location.pathname]);

  return (
    <div className={`app-container ${isHomeRoute ? "home-app-container" : ""}`}>
      <a className="skip-link" href="#main-content">
        본문 바로가기
      </a>

      <nav className={`navbar ${isHomeRoute ? "navbar-home" : ""}`} aria-label="주요 메뉴">
        <NavLink className="navbar-brand" to="/">
          <span>✦</span>
          SpeakInsight
        </NavLink>

        <button
          className="navbar-menu-button"
          aria-controls="primary-navigation"
          aria-expanded={navOpen}
          aria-label={navOpen ? "메뉴 닫기" : "메뉴 열기"}
          onClick={() => setNavOpen((open) => !open)}
          type="button"
        >
          {navOpen ? "닫기" : "메뉴"}
        </button>

        <div id="primary-navigation" className={`navbar-menu ${navOpen ? "open" : ""}`}>
          {isHomeRoute && (
            <div className="navbar-intro-links">
              <a href="#features" onClick={() => setNavOpen(false)}>
                주요 기능
              </a>
              <a href="#how" onClick={() => setNavOpen(false)}>
                이용 방법
              </a>
              <a href="#use-cases" onClick={() => setNavOpen(false)}>
                활용 분야
              </a>
            </div>
          )}

          <NavLink
            className={({ isActive }) => (isActive ? "navbar-link active" : "navbar-link")}
            to="/upload"
          >
            영상 분석
          </NavLink>
          <NavLink className={isResultRoute ? "navbar-link active" : "navbar-link"} to="/results">
            분석 이력
          </NavLink>
          <NavLink
            className={({ isActive }) => (isActive ? "navbar-link active" : "navbar-link")}
            to="/chat"
          >
            AI 코치
          </NavLink>
          {user && (
            <NavLink
              className={({ isActive }) => (isActive ? "navbar-link active" : "navbar-link")}
              to="/growth"
            >
              성장 추이
            </NavLink>
          )}
          {user?.role === "admin" && (
            <NavLink
              className={({ isActive }) => (isActive ? "navbar-link active" : "navbar-link")}
              to="/admin"
            >
              관리자
            </NavLink>
          )}

          <div className="navbar-account">
            {user ? (
              <>
                <NavLink className="navbar-link" to="/account">
                  {user.displayName}
                </NavLink>
                <button className="text-button" onClick={handleLogout} type="button">
                  로그아웃
                </button>
              </>
            ) : (
              <NavLink
                className={({ isActive }) => (isActive ? "navbar-link active" : "navbar-link")}
                to="/login"
              >
                로그인
              </NavLink>
            )}
          </div>

          {isHomeRoute && (
            <NavLink className="navbar-cta" to="/upload">
              무료로 분석 시작하기
            </NavLink>
          )}
        </div>
      </nav>

      {logoutError && (
        <StateMessage compact type="error" title="로그아웃하지 못했습니다.">
          {logoutError}
        </StateMessage>
      )}
      <div id="main-content" className="main-content" tabIndex="-1">
        {children}
      </div>
    </div>
  );
}
