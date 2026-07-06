import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { AuthProvider, useAuth } from "./AuthContext";
import { ToastProvider } from "./ToastContext";
import ToastContainer from "../components/ToastContainer";

const apiMock = vi.hoisted(() => ({
    login: vi.fn(),
    logout: vi.fn(),
}));

vi.mock("../api/authApi", () => ({
    login: apiMock.login,
    logout: apiMock.logout,
}));

const AUTH_STORAGE_KEY = "presentationCoachAuth";

function AuthStatusProbe() {
    const { isAuthenticated, logout } = useAuth();

    return (
        <div>
            <span>{isAuthenticated ? "인증됨" : "로그아웃됨"}</span>
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
        apiMock.login.mockReset();
        apiMock.logout.mockReset();
        localStorage.setItem(
            AUTH_STORAGE_KEY,
            JSON.stringify({
                accessToken: "access-token",
                user: { email: "user@example.com" },
            })
        );
    });

    afterEach(() => {
        cleanup();
        localStorage.clear();
    });

    it("calls backend logout and clears local auth state when it succeeds", async () => {
        apiMock.logout.mockResolvedValue({ success: true });

        renderAuthProvider();

        expect(screen.getByText("인증됨")).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "로그아웃" }));

        await waitFor(() => {
            expect(apiMock.logout).toHaveBeenCalled();
            expect(screen.getByText("로그아웃됨")).toBeInTheDocument();
            expect(screen.getByText("로그아웃되었습니다.")).toBeInTheDocument();
        });
        expect(localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
    });

    it("clears local auth state even when backend logout fails", async () => {
        apiMock.logout.mockRejectedValue(new Error("network error"));

        renderAuthProvider();

        expect(screen.getByText("인증됨")).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "로그아웃" }));

        await waitFor(() => {
            expect(apiMock.logout).toHaveBeenCalled();
            expect(screen.getByText("로그아웃됨")).toBeInTheDocument();
        });
        expect(localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
    });

    it("does not show logout toast when silent option is enabled", async () => {
        apiMock.logout.mockResolvedValue({ success: true });

        renderAuthProvider();

        fireEvent.click(screen.getByRole("button", { name: "조용히 로그아웃" }));

        await waitFor(() => {
            expect(apiMock.logout).toHaveBeenCalled();
            expect(screen.getByText("로그아웃됨")).toBeInTheDocument();
        });
        expect(screen.queryByText("로그아웃되었습니다.")).not.toBeInTheDocument();
        expect(localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
    });
});
