import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import SignupPage from "./SignupPage";

const authMock = vi.hoisted(() => ({
    isAuthenticated: false,
}));

const authApiMock = vi.hoisted(() => ({
    signup: vi.fn(),
}));

vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({
        isAuthenticated: authMock.isAuthenticated,
    }),
}));

vi.mock("../api/authApi", () => ({
    signup: authApiMock.signup,
}));

function renderSignupPage() {
    return render(
        <MemoryRouter initialEntries={["/signup"]}>
            <Routes>
                <Route path="/signup" element={<SignupPage />} />
                <Route path="/" element={<div>홈</div>} />
                <Route path="/login" element={<div>로그인</div>} />
            </Routes>
        </MemoryRouter>
    );
}

describe("SignupPage", () => {
    beforeEach(() => {
        authApiMock.signup.mockReset();
    });

    afterEach(() => {
        cleanup();
    });

    it("centers the signup card and keeps its width bounded like the login page", () => {
        renderSignupPage();

        const heading = screen.getByRole("heading", { name: "회원가입", level: 1 });
        const card = heading.closest("article");
        const shell = card?.parentElement;

        expect(card).toHaveClass("bg-surface-primary", "lg:order-2");
        expect(shell).toHaveClass("grid", "w-full", "max-w-[1080px]");
        expect(shell?.parentElement).toHaveClass("min-h-[calc(100svh-200px)]");
        expect(screen.getByText("첫 분석 전에 데이터 처리 기준부터 확인하세요"))
            .toBeInTheDocument();
    });

    it("shows the password complexity hint next to the password field", () => {
        renderSignupPage();

        // 힌트가 label 안에 함께 들어가므로 라벨 텍스트는 정규식으로 매칭합니다.
        const passwordInput = screen.getByLabelText(/비밀번호/);
        const hint = screen.getByText(
            "영문자와 숫자를 각각 1자 이상 포함해 8자 이상 입력해주세요."
        );

        expect(passwordInput.parentElement).toContainElement(hint);
        expect(passwordInput).toHaveAttribute("aria-describedby", "signup-password-hint");
    });

    it("does not show the password complexity hint next to the email field", () => {
        renderSignupPage();

        const emailInput = screen.getByLabelText(/이메일/);
        const hint = screen.getByText(
            "영문자와 숫자를 각각 1자 이상 포함해 8자 이상 입력해주세요."
        );

        expect(emailInput.parentElement).not.toContainElement(hint);
    });

    it("blocks signup until the user agrees to privacy policy and terms", () => {
        renderSignupPage();

        expect(screen.getByRole("button", { name: "회원가입" })).toBeDisabled();
        expect(screen.getByRole("link", { name: "테스트 데이터 처리 안내" })).toHaveAttribute(
            "href",
            "/privacy"
        );
        expect(screen.getByRole("link", { name: "프로젝트 이용 안내" })).toHaveAttribute(
            "href",
            "/terms"
        );

        fireEvent.change(screen.getByLabelText(/이메일/), {
            target: { value: "new@example.com" },
        });
        fireEvent.change(screen.getByLabelText(/비밀번호/), {
            target: { value: "password123" },
        });
        fireEvent.click(screen.getByRole("button", { name: "회원가입" }));

        expect(authApiMock.signup).not.toHaveBeenCalled();
    });

    it("sends agreedToTerms when the user checks the agreement", async () => {
        authApiMock.signup.mockResolvedValue({
            success: true,
        });

        renderSignupPage();

        fireEvent.change(screen.getByLabelText(/이메일/), {
            target: { value: "new@example.com" },
        });
        fireEvent.change(screen.getByLabelText(/비밀번호/), {
            target: { value: "password123" },
        });
        fireEvent.click(
            screen.getByLabelText(/테스트 데이터 처리 안내 및 프로젝트 이용 안내에 동의합니다/)
        );
        fireEvent.click(screen.getByRole("button", { name: "회원가입" }));

        await waitFor(() => {
            expect(authApiMock.signup).toHaveBeenCalledWith({
                email: "new@example.com",
                password: "password123",
                agreedToTerms: true,
            });
        });
    });
});
