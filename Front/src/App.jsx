import { BrowserRouter, Routes, Route, NavLink, Navigate, useLocation } from "react-router-dom";
import { useEffect, useState } from "react";

import "./App.css";

import { getMe, logout } from "./api/accountApi";
import UploadPage from "./pages/UploadPage";
import ResultListPage from "./pages/ResultListPage";
import ResultDetailPage from "./pages/ResultDetailPage";
import LoginPage from "./pages/LoginPage";
import ChatPage from "./pages/ChatPage";
import HomePage from "./pages/HomePage";
import GrowthPage from "./pages/GrowthPage";
import AccountPage from "./pages/AccountPage";
import ProtectedRoute from "./components/ProtectedRoute";

function AppContent() {
  const location = useLocation();
  const [user, setUser] = useState(null);
  const [authLoading, setAuthLoading] = useState(true);
  const isResultRoute =
    location.pathname === "/results" || location.pathname.startsWith("/result/");
  const isHomeRoute = location.pathname === "/";

  useEffect(() => {
    getMe()
      .then((result) => setUser(result.user))
      .catch(() => setUser(null))
      .finally(() => setAuthLoading(false));
  }, []);

  const handleLogout = async () => {
    await logout();
    setUser(null);
  };

  return (
    <div className={`app-container ${isHomeRoute ? "home-app-container" : ""}`}>
      <nav className={`navbar ${isHomeRoute ? "navbar-home" : ""}`}>
        <NavLink className="navbar-brand" to="/">
          <span>✦</span>
          SpeakInsight
        </NavLink>

        {isHomeRoute && (
          <div className="navbar-intro-links">
            <a href="#features">주요 기능</a>
            <a href="#how">이용 방법</a>
            <a href="#use-cases">활용 분야</a>
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

        <div className="navbar-account">
          {user ? (
            <>
              <NavLink className="navbar-link" to="/account">
                {user.displayName}
              </NavLink>
              <button className="text-button" onClick={handleLogout}>
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
      </nav>

      {!authLoading && (
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route
            path="/upload"
            element={
              <ProtectedRoute user={user}>
                <UploadPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/results"
            element={
              <ProtectedRoute user={user}>
                <ResultListPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/result/:resultId"
            element={
              <ProtectedRoute user={user}>
                <ResultDetailPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/login"
            element={
              user ? (
                <Navigate to={location.state?.from || "/chat"} replace />
              ) : (
                <LoginPage onAuthenticated={setUser} />
              )
            }
          />
          <Route
            path="/chat"
            element={
              <ProtectedRoute user={user}>
                <ChatPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/growth"
            element={
              <ProtectedRoute user={user}>
                <GrowthPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/account"
            element={
              <ProtectedRoute user={user}>
                <AccountPage user={user} onUserChange={setUser} onSignedOut={() => setUser(null)} />
              </ProtectedRoute>
            }
          />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      )}
    </div>
  );
}

function App() {
  return (
    <BrowserRouter>
      <AppContent />
    </BrowserRouter>
  );
}

export default App;
