import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AuthProvider, useAuth } from "./AuthContext";
import { ToastProvider } from "./ToastContext";
import ToastContainer from "../components/ToastContainer";

const apiMock = vi.hoisted(() => ({
    fetchCurrentUser: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
}));

vi.mock("../api/authApi", () => ({
    fetchCurrentUser: apiMock.fetchCurrentUser,
    login: apiMock.login,
    logout: apiMock.logout,
}));

function AuthStatusProbe() {
    const { isAuthenticated, isInitializing, login, logout, user } = useAuth();

    return (
        <div>
            <span>{isInitializing ? "초기화 중" : "초기화 완료"}</span>
            <span>{isAuthenticated ? "인증됨" : "로그아웃됨"}</span>
            <span>{user?.email || "사용자 없음"}</span>
            <button
                type="button"
                onClick={() => login({ email: "user@example.com", password: "password123" })}
            >
                로그인
            </button>
            <button type="button" onClick={logout}>
                로그아웃
            </button>
            <button type="button" onClick={() => logout({ silent: true })}>
                조용히 로그아웃
            </button>
        </div>
    );
}

function renderAuthProvider() {
    return render(
        <ToastProvider>
            <AuthProvider>
                <AuthStatusProbe />
            </AuthProvider>
            <ToastContainer />
        </ToastProvider>
    );
}

describe("AuthContext logout", () => {
    beforeEach(() => {
        apiMock.fetchCurrentUser.mockReset();
        apiMock.login.mockReset();
        apiMock.logout.mockReset();
        apiMock.fetchCurrentUser.mockResolvedValue({
            success: true,
            data: { email: "user@example.com" },
        });
    });

    afterEach(() => {
        cleanup();
        localStorage.clear();
    });

    it("restores the current user from the session cookie on mount", async () => {
        renderAuthProvider();

        expect(screen.getByText("초기화 중")).toBeInTheDocument();

        await waitFor(() => {
            expect(apiMock.fetchCurrentUser).toHaveBeenCalled();
            expect(screen.getByText("초기화 완료")).toBeInTheDocument();
            expect(screen.getByText("인증됨")).toBeInTheDocument();
            expect(screen.getByText("user@example.com")).toBeInTheDocument();
        });
    });

    it("stays logged out when session restore fails", async () => {
        apiMock.fetchCurrentUser.mockRejectedValue({ status: 401 });

        renderAuthProvider();

        await waitFor(() => {
            expect(screen.getByText("초기화 완료")).toBeInTheDocument();
            expect(screen.getByText("로그아웃됨")).toBeInTheDocument();
        });
    });

    it("stores only the returned user in state after login", async () => {
        apiMock.fetchCurrentUser.mockRejectedValue({ status: 401 });
        apiMock.login.mockResolvedValue({
            success: true,
            data: {
                accessToken: "access-token",
                user: { email: "login@example.com" },
            },
        });

        renderAuthProvider();

        await waitFor(() => {
            expect(screen.getByText("초기화 완료")).toBeInTheDocument();
        });

        fireEvent.click(screen.getByRole("button", { name: "로그인" }));

        await waitFor(() => {
            expect(apiMock.login).toHaveBeenCalledWith({
                email: "user@example.com",
                password: "password123",
            });
            expect(screen.getByText("인증됨")).toBeInTheDocument();
            expect(screen.getByText("login@example.com")).toBeInTheDocument();
        });
        expect(localStorage.getItem("presentationCoachAuth")).toBeNull();
    });

    it("calls backend logout and clears local auth state when it succeeds", async () => {
        apiMock.logout.mockResolvedValue({ success: true });

        renderAuthProvider();

        await waitFor(() => {
            expect(screen.getByText("인증됨")).toBeInTheDocument();
        });
        fireEvent.click(screen.getByRole("button", { name: "로그아웃" }));

        await waitFor(() => {
            expect(apiMock.logout).toHaveBeenCalled();
            expect(screen.getByText("로그아웃됨")).toBeInTheDocument();
            expect(screen.getByText("로그아웃되었습니다.")).toBeInTheDocument();
        });
        expect(localStorage.getItem("presentationCoachAuth")).toBeNull();
    });

    it("clears local auth state even when backend logout fails", async () => {
        apiMock.logout.mockRejectedValue(new Error("network error"));

        renderAuthProvider();

        await waitFor(() => {
            expect(screen.getByText("인증됨")).toBeInTheDocument();
        });
        fireEvent.click(screen.getByRole("button", { name: "로그아웃" }));

        await waitFor(() => {
            expect(apiMock.logout).toHaveBeenCalled();
            expect(screen.getByText("로그아웃됨")).toBeInTheDocument();
        });
        expect(localStorage.getItem("presentationCoachAuth")).toBeNull();
    });

    it("does not show logout toast when silent option is enabled", async () => {
        apiMock.logout.mockResolvedValue({ success: true });

        renderAuthProvider();

        await waitFor(() => {
            expect(screen.getByText("인증됨")).toBeInTheDocument();
        });
        fireEvent.click(screen.getByRole("button", { name: "조용히 로그아웃" }));

        await waitFor(() => {
            expect(apiMock.logout).toHaveBeenCalled();
            expect(screen.getByText("로그아웃됨")).toBeInTheDocument();
        });
        expect(screen.queryByText("로그아웃되었습니다.")).not.toBeInTheDocument();
        expect(localStorage.getItem("presentationCoachAuth")).toBeNull();
    });
});
