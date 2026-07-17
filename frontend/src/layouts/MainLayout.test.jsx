import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import MainLayout from "./MainLayout";

const authMock = vi.hoisted(() => ({
    isAuthenticated: false,
    logout: vi.fn(),
    user: null,
}));

vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({
        isAuthenticated: authMock.isAuthenticated,
        logout: authMock.logout,
        user: authMock.user,
    }),
}));

function renderMainLayout(initialPath = "/") {
    return render(
        <MemoryRouter initialEntries={[initialPath]}>
            <Routes>
                <Route element={<MainLayout />}>
                    <Route path="/" element={<div>홈 화면</div>} />
                    <Route path="/upload" element={<div>업로드 화면</div>} />
                    <Route path="/results" element={<div>결과 화면</div>} />
                    <Route path="/account" element={<div>계정 화면</div>} />
                    <Route path="/status" element={<div>상태 화면</div>} />
                </Route>
            </Routes>
        </MemoryRouter>
    );
}

describe("MainLayout", () => {
    it("shows only public navigation links for unauthenticated users", () => {
        authMock.isAuthenticated = false;
        authMock.user = null;

        renderMainLayout();

        expect(screen.getByRole("link", { name: "홈" }))
            .toHaveAttribute("href", "/")
            .toHaveClass("bg-primary-deep");
        expect(screen.getByRole("link", { name: "로그인" })).toHaveAttribute("href", "/login");
        expect(screen.getByRole("link", { name: "회원가입" })).toHaveAttribute("href", "/signup");
        expect(screen.queryByRole("link", { name: "영상 업로드" })).not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "분석 결과" })).not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "계정" })).not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "시스템 상태" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "로그아웃" })).not.toBeInTheDocument();
        expect(screen.queryByText("user@example.com")).not.toBeInTheDocument();
        expect(screen.getByRole("link", { name: "개인정보처리방침" }))
            .toHaveAttribute("href", "/privacy")
            .toHaveClass("text-text-secondary");
        expect(screen.getByRole("link", { name: "이용약관" }))
            .toHaveAttribute("href", "/terms")
            .toHaveClass("text-text-secondary");
    });

    it("shows authenticated navigation links and user controls for authenticated users", () => {
        authMock.isAuthenticated = true;
        authMock.user = { email: "user@example.com" };

        renderMainLayout();

        expect(screen.getByRole("link", { name: "홈" })).toHaveAttribute("href", "/");
        expect(screen.getByRole("link", { name: "영상 업로드" })).toHaveAttribute("href", "/upload");
        expect(screen.getByRole("link", { name: "분석 결과" })).toHaveAttribute("href", "/results");
        expect(screen.getByRole("link", { name: "시스템 상태" })).toHaveAttribute("href", "/status");
        // 로그인 이메일 표시부가 계정 설정(/account) 진입점으로 합쳐졌습니다.
        // 별도 "계정" 탭은 없어지고, 이메일을 누르면 /account로 이동합니다.
        expect(screen.queryByRole("link", { name: "계정" })).not.toBeInTheDocument();
        expect(screen.getByRole("link", { name: "user@example.com" })).toHaveAttribute("href", "/account");
        expect(screen.getByText("user@example.com")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "로그아웃" })).toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "로그인" })).not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "회원가입" })).not.toBeInTheDocument();
    });
});
