import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import ForgotPasswordPage from "./ForgotPasswordPage";

const authApiMock = vi.hoisted(() => ({
    requestPasswordReset: vi.fn(),
}));

vi.mock("../api/authApi", () => ({
    requestPasswordReset: authApiMock.requestPasswordReset,
}));

function renderForgotPasswordPage() {
    return render(
        <MemoryRouter initialEntries={["/forgot-password"]}>
            <Routes>
                <Route path="/forgot-password" element={<ForgotPasswordPage />} />
            </Routes>
        </MemoryRouter>
    );
}

describe("ForgotPasswordPage", () => {
    beforeEach(() => {
        authApiMock.requestPasswordReset.mockReset();
    });

    it("centers the card and keeps its width bounded like the login page", () => {
        renderForgotPasswordPage();

        const card = screen
            .getByRole("heading", { name: "비밀번호 재설정" })
            .closest("article");

        expect(card).toHaveClass("w-full", "max-w-[520px]");
        expect(card?.parentElement).toHaveClass(
            "flex",
            "items-center",
            "justify-center",
            "min-h-[calc(100svh-200px)]"
        );
    });

    it("requests a password reset email", async () => {
        authApiMock.requestPasswordReset.mockResolvedValue({
            message: "입력한 이메일로 비밀번호 재설정 안내를 보냈습니다.",
        });

        renderForgotPasswordPage();

        fireEvent.change(screen.getByLabelText("이메일"), {
            target: { value: "user@example.com" },
        });
        fireEvent.click(screen.getByRole("button", { name: "재설정 안내 받기" }));

        await waitFor(() => {
            expect(authApiMock.requestPasswordReset).toHaveBeenCalledWith("user@example.com");
        });
        expect(await screen.findByText(/비밀번호 재설정 안내/)).toBeInTheDocument();
    });
});
