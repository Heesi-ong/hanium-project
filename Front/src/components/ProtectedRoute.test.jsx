import React from "react";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { describe, expect, it } from "vitest";

import ProtectedRoute from "./ProtectedRoute";

function LocationStateProbe() {
  const location = useLocation();
  return <div>{location.state?.from}</div>;
}

function renderRoute(user) {
  render(
    <MemoryRouter initialEntries={["/upload"]}>
      <Routes>
        <Route path="/login" element={<div>로그인 화면</div>} />
        <Route
          path="/upload"
          element={
            <ProtectedRoute user={user}>
              <div>분석 화면</div>
            </ProtectedRoute>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

describe("ProtectedRoute", () => {
  it("로그아웃 사용자를 로그인 화면으로 보낸다", () => {
    renderRoute(null);
    expect(screen.getByText("로그인 화면")).toBeInTheDocument();
  });

  it("로그인 사용자에게 보호 화면을 보여준다", () => {
    renderRoute({ id: 1 });
    expect(screen.getByText("분석 화면")).toBeInTheDocument();
  });

  it("로그인 이동 시 결과 기반 채팅의 검색 파라미터를 보존한다", () => {
    render(
      <MemoryRouter initialEntries={["/chat/result/job-1?question=test"]}>
        <Routes>
          <Route path="/login" element={<LocationStateProbe />} />
          <Route
            path="/chat/result/:resultId"
            element={
              <ProtectedRoute user={null}>
                <div>보호 화면</div>
              </ProtectedRoute>
            }
          />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText("/chat/result/job-1?question=test")).toBeInTheDocument();
  });

  it("일반 사용자를 관리자 경로에서 내보낸다", () => {
    render(
      <MemoryRouter initialEntries={["/admin"]}>
        <Routes>
          <Route path="/" element={<div>홈 화면</div>} />
          <Route
            path="/admin"
            element={
              <ProtectedRoute user={{ id: 1, role: "user" }} requiredRole="admin">
                <div>관리자 화면</div>
              </ProtectedRoute>
            }
          />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText("홈 화면")).toBeInTheDocument();
  });
});
