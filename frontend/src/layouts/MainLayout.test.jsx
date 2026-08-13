import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import MainLayout from "./MainLayout";
import HomePage from "../pages/HomePage";

vi.mock("../api/analysisApi", () => ({
    healthCheck: vi.fn(),
    getServiceStatus: vi.fn(),
    getResults: vi.fn().mockResolvedValue({ data: { content: [], last: true } }),
}));

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
        expect(screen.queryByRole("link", { name: "서비스 상태" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "로그아웃" })).not.toBeInTheDocument();
        expect(screen.queryByText("user@example.com")).not.toBeInTheDocument();
        expect(screen.getByRole("link", { name: "테스트 데이터 처리 안내" }))
            .toHaveAttribute("href", "/privacy")
            .toHaveClass("text-text-secondary");
        expect(screen.getByRole("link", { name: "프로젝트 이용 안내" }))
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
        expect(screen.getByRole("link", { name: "서비스 상태" })).toHaveAttribute("href", "/status");
        // 로그인 이메일 표시부가 계정 설정(/account) 진입점으로 합쳐졌습니다.
        // 별도 "계정" 탭은 없어지고, 이메일을 누르면 /account로 이동합니다.
        expect(screen.queryByRole("link", { name: "계정" })).not.toBeInTheDocument();
        expect(screen.getByRole("link", { name: "user@example.com" })).toHaveAttribute("href", "/account");
        expect(screen.getByText("user@example.com")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "로그아웃" })).toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "로그인" })).not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "회원가입" })).not.toBeInTheDocument();
    });

    it("mobile menu is closed by default and opens/closes via the toggle button", () => {
        authMock.isAuthenticated = true;
        authMock.user = { email: "user@example.com" };

        renderMainLayout();

        const toggleButton = screen.getByRole("button", { name: "메뉴 열기" });
        expect(toggleButton).toHaveAttribute("aria-expanded", "false");
        expect(screen.queryByRole("navigation", { name: "모바일 메뉴" })).not.toBeInTheDocument();

        fireEvent.click(toggleButton);

        expect(screen.getByRole("button", { name: "메뉴 닫기" })).toHaveAttribute("aria-expanded", "true");
        const mobileNav = screen.getByRole("navigation", { name: "모바일 메뉴" });
        expect(mobileNav).toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: "메뉴 닫기" }));

        expect(screen.queryByRole("navigation", { name: "모바일 메뉴" })).not.toBeInTheDocument();
    });

    it("closes the mobile menu when a link inside it is clicked", () => {
        authMock.isAuthenticated = true;
        authMock.user = { email: "user@example.com" };

        renderMainLayout();

        fireEvent.click(screen.getByRole("button", { name: "메뉴 열기" }));
        const mobileNav = screen.getByRole("navigation", { name: "모바일 메뉴" });

        fireEvent.click(within(mobileNav).getByRole("link", { name: "분석 결과" }));

        expect(screen.queryByRole("navigation", { name: "모바일 메뉴" })).not.toBeInTheDocument();
    });

    it("closes the mobile menu on Escape key press", () => {
        authMock.isAuthenticated = true;
        authMock.user = { email: "user@example.com" };

        renderMainLayout();

        fireEvent.click(screen.getByRole("button", { name: "메뉴 열기" }));
        expect(screen.getByRole("navigation", { name: "모바일 메뉴" })).toBeInTheDocument();

        fireEvent.keyDown(window, { key: "Escape" });

        expect(screen.queryByRole("navigation", { name: "모바일 메뉴" })).not.toBeInTheDocument();
    });

    // HomePage가 한때 자체 footer를 렌더링해 이 전역 footer와 연속으로 두 번 표시된
    // 적이 있습니다(P1-05). 실제 HomePage를 "/"에 렌더링해 footer가 한 번만 나오는지
    // 확인합니다.
    it("renders exactly one footer when the real home page is shown", () => {
        authMock.isAuthenticated = false;
        authMock.user = null;

        render(
            <MemoryRouter initialEntries={["/"]}>
                <Routes>
                    <Route element={<MainLayout />}>
                        <Route path="/" element={<HomePage />} />
                    </Route>
                </Routes>
            </MemoryRouter>
        );

        expect(screen.getAllByRole("link", { name: "테스트 데이터 처리 안내" })).toHaveLength(1);
        expect(screen.getAllByRole("link", { name: "프로젝트 이용 안내" })).toHaveLength(1);
    });

    it("labels the pricing entry point as project usage guidance", () => {
        authMock.isAuthenticated = false;
        authMock.user = null;

        renderMainLayout();

        expect(screen.getByRole("link", { name: "프로젝트 안내" })).toHaveAttribute(
            "href",
            "/pricing"
        );
    });
});
