import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import AccountPage from "./AccountPage";

const authMock = vi.hoisted(() => ({
    logout: vi.fn(),
    user: { email: "user@example.com" },
}));

const apiMock = vi.hoisted(() => ({
    withdrawAccount: vi.fn(),
}));

const toastMock = vi.hoisted(() => ({
    showToast: vi.fn(),
}));

const confirmMock = vi.hoisted(() => ({
    confirm: vi.fn(),
}));

vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({
        user: authMock.user,
        logout: authMock.logout,
    }),
}));

vi.mock("../api/authApi", () => ({
    withdrawAccount: apiMock.withdrawAccount,
}));

vi.mock("../context/ToastContext", () => ({
    useToast: () => ({
        showToast: toastMock.showToast,
    }),
}));

vi.mock("../context/ConfirmContext", () => ({
    useConfirm: () => confirmMock.confirm,
}));

function renderAccountPage() {
    return render(
        <MemoryRouter initialEntries={["/account"]}>
            <Routes>
                <Route path="/account" element={<AccountPage />} />
                <Route path="/login" element={<div>로그인 화면</div>} />
            </Routes>
        </MemoryRouter>
    );
}

describe("AccountPage", () => {
    beforeEach(() => {
        authMock.logout.mockReset();
        apiMock.withdrawAccount.mockReset();
        toastMock.showToast.mockReset();
        confirmMock.confirm.mockReset();
        confirmMock.confirm.mockResolvedValue(true);
    });

    afterEach(() => {
        cleanup();
        vi.restoreAllMocks();
    });

    it("does not call withdrawAccount when confirmation is cancelled", async () => {
        confirmMock.confirm.mockResolvedValue(false);

        renderAccountPage();

        fireEvent.change(screen.getByLabelText("비밀번호 확인"), {
            target: { value: "password123" },
        });
        fireEvent.click(screen.getByRole("button", { name: "회원탈퇴" }));

        await waitFor(() => {
            expect(confirmMock.confirm).toHaveBeenCalled();
        });
        expect(apiMock.withdrawAccount).not.toHaveBeenCalled();
        expect(authMock.logout).not.toHaveBeenCalled();
    });

    it("withdraws account, logs out, and redirects to login after confirmation", async () => {
        apiMock.withdrawAccount.mockResolvedValue({ success: true });

        renderAccountPage();

        fireEvent.change(screen.getByLabelText("비밀번호 확인"), {
            target: { value: "password123" },
        });
        fireEvent.click(screen.getByRole("button", { name: "회원탈퇴" }));

        await waitFor(() => {
            expect(apiMock.withdrawAccount).toHaveBeenCalledWith("password123");
            expect(authMock.logout).toHaveBeenCalledWith({ silent: true });
            expect(toastMock.showToast).toHaveBeenCalledWith(
                "회원탈퇴가 완료되었습니다.",
                "success"
            );
            expect(screen.getByText("로그인 화면")).toBeInTheDocument();
        });
    });

    it("shows error message when withdrawal fails", async () => {
        apiMock.withdrawAccount.mockRejectedValue({
            message: "비밀번호가 올바르지 않습니다.",
        });

        renderAccountPage();

        fireEvent.change(screen.getByLabelText("비밀번호 확인"), {
            target: { value: "wrongpass" },
        });
        fireEvent.click(screen.getByRole("button", { name: "회원탈퇴" }));

        expect(await screen.findByText("비밀번호가 올바르지 않습니다.")).toBeInTheDocument();
        expect(authMock.logout).not.toHaveBeenCalled();
    });
});
