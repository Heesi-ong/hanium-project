// 로그인 여부와 관리자 권한을 확인해 보호된 페이지 접근을 제어한다.
import React from "react";
import { Navigate, useLocation } from "react-router-dom";

export default function ProtectedRoute({ user, requiredRole, children }) {
  const location = useLocation();
  if (!user) {
    return (
      <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />
    );
  }
  if (requiredRole && user.role !== requiredRole) {
    return <Navigate to="/" replace />;
  }
  return children;
}
