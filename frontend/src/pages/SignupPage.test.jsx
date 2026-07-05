import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
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
    afterEach(() => {
        cleanup();
    });

    it("shows the password complexity hint next to the password field", () => {
        renderSignupPage();

        // 힌트가 label 안에 함께 들어가므로 라벨 텍스트는 정규식으로 매칭합니다.
        const passwordInput = screen.getByLabelText(/비밀번호/);
        const hint = screen.getByText(
            "영문자와 숫자를 각각 1자 이상 포함해 8자 이상 입력해주세요."
        );

        expect(passwordInput.parentElement).toContainElement(hint);
    });

    it("does not show the password complexity hint next to the email field", () => {
        renderSignupPage();

        const emailInput = screen.getByLabelText(/이메일/);
        const hint = screen.getByText(
            "영문자와 숫자를 각각 1자 이상 포함해 8자 이상 입력해주세요."
        );

        expect(emailInput.parentElement).not.toContainElement(hint);
    });
});
