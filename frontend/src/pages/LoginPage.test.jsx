import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import LoginPage from "./LoginPage";

const authMock = vi.hoisted(() => ({
    login: vi.fn(),
    isAuthenticated: false,
}));

vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({
        isAuthenticated: authMock.isAuthenticated,
        login: authMock.login,
    }),
}));

function renderLoginPage() {
    return render(
        <MemoryRouter initialEntries={["/login"]}>
            <Routes>
                <Route path="/login" element={<LoginPage />} />
                <Route path="/" element={<div>홈</div>} />
                <Route path="/results/:jobId" element={<div>결과 상세</div>} />
                <Route path="/onboarding" element={<div>온보딩</div>} />
            </Routes>
        </MemoryRouter>
    );
}

describe("LoginPage", () => {
    beforeEach(() => {
        authMock.login.mockReset();
        authMock.isAuthenticated = false;
        sessionStorage.clear();
    });

    afterEach(() => {
        sessionStorage.clear();
    });

    it("shows a session expiration notice and returns to the stored path after login", async () => {
        sessionStorage.setItem("redirectAfterLogin", "/results/job-1");
        sessionStorage.setItem("sessionExpired", "true");
        authMock.login.mockResolvedValue({
            user: { email: "user@example.com" },
        });

        renderLoginPage();

        expect(screen.getByText("세션이 만료되어 다시 로그인해주세요.")).toBeInTheDocument();
        expect(sessionStorage.getItem("sessionExpired")).toBeNull();

        fireEvent.change(screen.getByLabelText("이메일"), {
            target: { value: "user@example.com" },
        });
        fireEvent.change(screen.getByLabelText("비밀번호"), {
            target: { value: "password123" },
        });
        fireEvent.click(screen.getByRole("button", { name: "로그인" }));

        await waitFor(() => {
            expect(screen.getByText("결과 상세")).toBeInTheDocument();
        });
        expect(sessionStorage.getItem("redirectAfterLogin")).toBeNull();
        expect(authMock.login).toHaveBeenCalledWith({
            email: "user@example.com",
            password: "password123",
        });
    });

    it("links to the forgot password page", () => {
        renderLoginPage();

        expect(screen.getByRole("link", { name: "비밀번호를 잊으셨나요?" }))
            .toHaveAttribute("href", "/forgot-password");
    });

    it("redirects to onboarding when the logged-in user has not completed it", async () => {
        authMock.login.mockResolvedValue({
            user: { email: "user@example.com", onboardingCompleted: false, onboardingSkipped: false },
        });

        renderLoginPage();

        fireEvent.change(screen.getByLabelText("이메일"), {
            target: { value: "user@example.com" },
        });
        fireEvent.change(screen.getByLabelText("비밀번호"), {
            target: { value: "password123" },
        });
        fireEvent.click(screen.getByRole("button", { name: "로그인" }));

        await waitFor(() => {
            expect(screen.getByText("온보딩")).toBeInTheDocument();
        });
    });

    // 2026-08-06 이전에는 "나중에 하기"를 서버가 기록하지 않아 onboardingSkipped가
    // 없었고, 로그인할 때마다 다시 온보딩으로 보내졌다(P1-02). 이미 건너뛴 사용자는
    // 다시 보내지 않아야 한다.
    it("does not redirect to onboarding when the user already skipped it", async () => {
        authMock.login.mockResolvedValue({
            user: { email: "user@example.com", onboardingCompleted: false, onboardingSkipped: true },
        });

        renderLoginPage();

        fireEvent.change(screen.getByLabelText("이메일"), {
            target: { value: "user@example.com" },
        });
        fireEvent.change(screen.getByLabelText("비밀번호"), {
            target: { value: "password123" },
        });
        fireEvent.click(screen.getByRole("button", { name: "로그인" }));

        await waitFor(() => {
            expect(screen.getByText("홈")).toBeInTheDocument();
        });
        expect(screen.queryByText("온보딩")).not.toBeInTheDocument();
    });
});
