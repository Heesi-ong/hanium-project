// 인증 상태를 초기화하고 라우터, 공통 레이아웃, 세션 만료 처리를 연결한다.
import { BrowserRouter } from "react-router-dom";
import { useCallback, useEffect, useState } from "react";

import "./App.css";

import { getMe } from "./api/accountApi";
import { API_UNAUTHORIZED_EVENT } from "./api/apiClient";
import AppLayout from "./app/AppLayout";
import AppRoutes from "./app/AppRoutes";
import StateMessage from "./components/StateMessage";

function AppContent() {
  const [user, setUser] = useState(null);
  const [authLoading, setAuthLoading] = useState(true);
  const [authError, setAuthError] = useState("");

  const loadCurrentUser = useCallback((signal) => {
    setAuthLoading(true);
    setAuthError("");
    getMe(signal)
      .then((result) => setUser(result.user))
      .catch((requestError) => {
        if (requestError.name === "AbortError") return;
        if (requestError.status === 401) {
          setUser(null);
          return;
        }
        setAuthError(requestError.message || "사용자 정보를 확인하지 못했습니다.");
      })
      .finally(() => setAuthLoading(false));
  }, []);

  useEffect(() => {
    const handleUnauthorized = () => setUser(null);
    window.addEventListener(API_UNAUTHORIZED_EVENT, handleUnauthorized);
    const controller = new AbortController();
    loadCurrentUser(controller.signal);
    return () => {
      controller.abort();
      window.removeEventListener(API_UNAUTHORIZED_EVENT, handleUnauthorized);
    };
  }, [loadCurrentUser]);

  const retryAuth = useCallback(() => {
    const controller = new AbortController();
    loadCurrentUser(controller.signal);
  }, [loadCurrentUser]);

  if (authLoading) {
    return (
      <div className="app-container">
        <StateMessage title="사용자 정보를 확인하는 중입니다." />
      </div>
    );
  }

  if (authError) {
    return (
      <div className="app-container">
        <main className="page">
          <StateMessage
            type="error"
            title="사용자 정보를 확인하지 못했습니다."
            actions={
              <button className="button" onClick={retryAuth}>
                다시 시도
              </button>
            }
          >
            {authError}
          </StateMessage>
        </main>
      </div>
    );
  }

  return (
    <AppLayout user={user} setUser={setUser}>
      <AppRoutes user={user} setUser={setUser} />
    </AppLayout>
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
