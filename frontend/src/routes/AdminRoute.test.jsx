import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import AdminRoute from "./AdminRoute";

const authMock = vi.hoisted(() => ({
    isAuthenticated: false,
    isInitializing: false,
    user: null,
}));

vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({
        isAuthenticated: authMock.isAuthenticated,
        isInitializing: authMock.isInitializing,
        user: authMock.user,
    }),
}));

function renderAdminRoute(initialPath = "/admin") {
    return render(
        <MemoryRouter initialEntries={[initialPath]}>
            <Routes>
                <Route element={<AdminRoute />}>
                    <Route path="/admin" element={<div>관리자 대시보드</div>} />
                </Route>
                <Route path="/login" element={<div>로그인 화면</div>} />
                <Route path="/" element={<div>홈 화면</div>} />
            </Routes>
        </MemoryRouter>
    );
}

describe("AdminRoute", () => {
    it("waits for auth initialization before redirecting", () => {
        authMock.isAuthenticated = false;
        authMock.isInitializing = true;
        authMock.user = null;

        renderAdminRoute();

        expect(screen.queryByText("관리자 대시보드")).not.toBeInTheDocument();
        expect(screen.queryByText("로그인 화면")).not.toBeInTheDocument();
        expect(screen.queryByText("홈 화면")).not.toBeInTheDocument();
    });

    it("redirects unauthenticated users to login", () => {
        authMock.isAuthenticated = false;
        authMock.isInitializing = false;
        authMock.user = null;

        renderAdminRoute();

        expect(screen.getByText("로그인 화면")).toBeInTheDocument();
    });

    it("redirects authenticated non-admin users to home", () => {
        authMock.isAuthenticated = true;
        authMock.isInitializing = false;
        authMock.user = { id: 1, email: "user@example.com", admin: false };

        renderAdminRoute();

        expect(screen.getByText("홈 화면")).toBeInTheDocument();
    });

    it("renders admin content for authenticated admin users", () => {
        authMock.isAuthenticated = true;
        authMock.isInitializing = false;
        authMock.user = { id: 7, email: "admin@example.com", admin: true };

        renderAdminRoute();

        expect(screen.getByText("관리자 대시보드")).toBeInTheDocument();
    });
});
