import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import ResetPasswordPage from "./ResetPasswordPage";

const authApiMock = vi.hoisted(() => ({
    confirmPasswordReset: vi.fn(),
}));

vi.mock("../api/authApi", () => ({
    confirmPasswordReset: authApiMock.confirmPasswordReset,
}));

function renderResetPasswordPage(path = "/reset-password?token=reset-token") {
    return render(
        <MemoryRouter initialEntries={[path]}>
            <Routes>
                <Route path="/reset-password" element={<ResetPasswordPage />} />
            </Routes>
        </MemoryRouter>
    );
}

describe("ResetPasswordPage", () => {
    beforeEach(() => {
        authApiMock.confirmPasswordReset.mockReset();
    });

    it("confirms password reset with the token from the URL", async () => {
        authApiMock.confirmPasswordReset.mockResolvedValue({});

        renderResetPasswordPage();

        fireEvent.change(screen.getByLabelText(/새 비밀번호/), {
            target: { value: "newpassword123" },
        });
        fireEvent.click(screen.getByRole("button", { name: "비밀번호 변경" }));

        await waitFor(() => {
            expect(authApiMock.confirmPasswordReset).toHaveBeenCalledWith({
                token: "reset-token",
                newPassword: "newpassword123",
            });
        });
        expect(await screen.findByText(/비밀번호가 재설정되었습니다/)).toBeInTheDocument();
    });

    it("blocks submission when token is missing", () => {
        renderResetPasswordPage("/reset-password");

        expect(screen.getByText("재설정 토큰이 없습니다.")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "비밀번호 변경" })).toBeDisabled();
    });
});
