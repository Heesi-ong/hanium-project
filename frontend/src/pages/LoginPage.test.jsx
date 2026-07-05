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
            accessToken: "token",
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
});
