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
    changePassword: vi.fn(),
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
    changePassword: apiMock.changePassword,
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
        apiMock.changePassword.mockReset();
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

    it("hides the password change form until the toggle button is clicked", () => {
        renderAccountPage();

        expect(screen.queryByLabelText("현재 비밀번호")).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: "비밀번호 변경" }));

        expect(screen.getByLabelText("현재 비밀번호")).toBeInTheDocument();
    });

    it("changes the password and resets the form on success", async () => {
        apiMock.changePassword.mockResolvedValue({ success: true });

        renderAccountPage();

        fireEvent.click(screen.getByRole("button", { name: "비밀번호 변경" }));

        fireEvent.change(screen.getByLabelText("현재 비밀번호"), {
            target: { value: "oldPassword123" },
        });
        fireEvent.change(screen.getByLabelText(/^새 비밀번호(?!\s*확인)/), {
            target: { value: "newPassword456" },
        });
        fireEvent.change(screen.getByLabelText("새 비밀번호 확인"), {
            target: { value: "newPassword456" },
        });

        fireEvent.click(screen.getByRole("button", { name: "비밀번호 변경 저장" }));

        await waitFor(() => {
            expect(apiMock.changePassword).toHaveBeenCalledWith({
                currentPassword: "oldPassword123",
                newPassword: "newPassword456",
            });
            expect(toastMock.showToast).toHaveBeenCalledWith(
                "비밀번호가 변경되었습니다.",
                "success"
            );
        });

        // 성공하면 폼이 다시 접혀야 합니다(입력값도 초기화된 채로).
        expect(screen.queryByLabelText("현재 비밀번호")).not.toBeInTheDocument();
    });

    it("shows a client-side error and does not call the API when the new password confirmation does not match", async () => {
        renderAccountPage();

        fireEvent.click(screen.getByRole("button", { name: "비밀번호 변경" }));

        fireEvent.change(screen.getByLabelText("현재 비밀번호"), {
            target: { value: "oldPassword123" },
        });
        fireEvent.change(screen.getByLabelText(/^새 비밀번호(?!\s*확인)/), {
            target: { value: "newPassword456" },
        });
        fireEvent.change(screen.getByLabelText("새 비밀번호 확인"), {
            target: { value: "somethingElse789" },
        });

        fireEvent.click(screen.getByRole("button", { name: "비밀번호 변경 저장" }));

        expect(
            await screen.findByText("새 비밀번호가 서로 일치하지 않습니다.")
        ).toBeInTheDocument();
        expect(apiMock.changePassword).not.toHaveBeenCalled();
    });

    it("shows the server error message when changing the password fails", async () => {
        apiMock.changePassword.mockRejectedValue({
            message: "비밀번호가 올바르지 않습니다.",
        });

        renderAccountPage();

        fireEvent.click(screen.getByRole("button", { name: "비밀번호 변경" }));

        fireEvent.change(screen.getByLabelText("현재 비밀번호"), {
            target: { value: "wrongCurrentPassword" },
        });
        fireEvent.change(screen.getByLabelText(/^새 비밀번호(?!\s*확인)/), {
            target: { value: "newPassword456" },
        });
        fireEvent.change(screen.getByLabelText("새 비밀번호 확인"), {
            target: { value: "newPassword456" },
        });

        fireEvent.click(screen.getByRole("button", { name: "비밀번호 변경 저장" }));

        expect(await screen.findByText("비밀번호가 올바르지 않습니다.")).toBeInTheDocument();
        // 실패했으므로 폼은 계속 열려 있어야 합니다.
        expect(screen.getByLabelText("현재 비밀번호")).toBeInTheDocument();
    });

    it("clears the form and hides it when cancel is clicked", () => {
        renderAccountPage();

        fireEvent.click(screen.getByRole("button", { name: "비밀번호 변경" }));
        fireEvent.change(screen.getByLabelText("현재 비밀번호"), {
            target: { value: "oldPassword123" },
        });

        fireEvent.click(screen.getByRole("button", { name: "취소" }));

        expect(screen.queryByLabelText("현재 비밀번호")).not.toBeInTheDocument();
    });
});
