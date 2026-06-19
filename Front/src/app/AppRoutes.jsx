// 서비스의 URL 경로와 페이지 컴포넌트, 보호 라우트 권한을 정의한다.
import { Navigate, Route, Routes, useLocation } from "react-router-dom";

import ProtectedRoute from "../components/ProtectedRoute";
import AccountPage from "../pages/AccountPage";
import AdminPage from "../pages/AdminPage";
import AdminSystemPage from "../pages/AdminSystemPage";
import ChatPage from "../pages/ChatPage";
import GrowthPage from "../pages/GrowthPage";
import HomePage from "../pages/HomePage";
import LoginPage from "../pages/LoginPage";
import ResultDetailPage from "../pages/ResultDetailPage";
import ResultListPage from "../pages/ResultListPage";
import UploadPage from "../pages/UploadPage";
import { clearPresentationChatSession } from "../features/chat/presentationChatSession";

const protectedPage = (user, element, requiredRole) => (
  <ProtectedRoute user={user} requiredRole={requiredRole}>
    {element}
  </ProtectedRoute>
);

export default function AppRoutes({ user, setUser }) {
  const location = useLocation();
  const signOut = () => {
    clearPresentationChatSession();
    setUser(null);
  };

  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/upload" element={protectedPage(user, <UploadPage />)} />
      <Route path="/results" element={protectedPage(user, <ResultListPage />)} />
      <Route path="/result/:resultId" element={protectedPage(user, <ResultDetailPage />)} />
      <Route
        path="/login"
        element={
          user ? (
            <Navigate to={location.state?.from || "/upload"} replace />
          ) : (
            <LoginPage onAuthenticated={setUser} />
          )
        }
      />
      <Route path="/chat" element={protectedPage(user, <ChatPage user={user} />)} />
      <Route
        path="/chat/result/:resultId"
        element={protectedPage(user, <ChatPage user={user} />)}
      />
      <Route path="/growth" element={protectedPage(user, <GrowthPage />)} />
      <Route
        path="/account"
        element={protectedPage(
          user,
          <AccountPage user={user} onUserChange={setUser} onSignedOut={signOut} />,
        )}
      />
      <Route path="/admin" element={protectedPage(user, <AdminPage />, "admin")} />
      <Route path="/admin/system" element={protectedPage(user, <AdminSystemPage />, "admin")} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
