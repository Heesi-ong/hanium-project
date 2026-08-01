import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

import AdminUsersPage from "./AdminUsersPage";

const apiMock = vi.hoisted(() => ({
    getAdminUsers: vi.fn(),
    suspendAdminUser: vi.fn(),
    activateAdminUser: vi.fn(),
    forceWithdrawAdminUser: vi.fn(),
}));
const confirmMock = vi.hoisted(() => vi.fn());

vi.mock("../api/adminApi", () => apiMock);
vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({ user: { id: 1, email: "admin@example.com", admin: true } }),
}));
vi.mock("../context/ConfirmContext", () => ({
    useConfirm: () => confirmMock,
}));

const USERS_RESPONSE = {
    data: {
        content: [
            {
                id: 1,
                email: "admin@example.com",
                role: "ADMIN",
                status: "ACTIVE",
                createdAt: "2026-07-01T09:00:00",
                onboardingCompleted: true,
                analysisJobCount: 1,
            },
            {
                id: 2,
                email: "member@example.com",
                role: "USER",
                status: "ACTIVE",
                createdAt: "2026-07-02T09:00:00",
                onboardingCompleted: false,
                analysisJobCount: 2,
            },
        ],
        last: true,
    },
};

function renderPage() {
    return render(
        <MemoryRouter initialEntries={["/admin/users"]}>
            <AdminUsersPage />
        </MemoryRouter>
    );
}

describe("AdminUsersPage", () => {
    beforeEach(() => {
        Object.values(apiMock).forEach((mockFunction) => mockFunction.mockReset());
        apiMock.getAdminUsers.mockResolvedValue(USERS_RESPONSE);
        confirmMock.mockReset();
        confirmMock.mockResolvedValue(true);
    });

    it("renders users separately from recovery work", async () => {
        renderPage();

        expect(await screen.findByText("member@example.com")).toBeInTheDocument();
        expect(screen.getByText("admin@example.com")).toBeInTheDocument();
        expect(screen.getAllByRole("link", { name: "상세 보기" }))
            .toEqual(expect.arrayContaining([
                expect.objectContaining({ href: expect.stringContaining("/admin/users/2") }),
            ]));
        expect(screen.queryByText(/DEAD_LETTER/)).not.toBeInTheDocument();
    });

    it("suspends another user and protects the current admin row", async () => {
        apiMock.suspendAdminUser.mockResolvedValue({ success: true });
        renderPage();
        await screen.findByText("member@example.com");

        expect(screen.getAllByRole("button", { name: "정지" })).toHaveLength(1);
        fireEvent.click(screen.getByRole("button", { name: "정지" }));

        await waitFor(() => expect(apiMock.suspendAdminUser).toHaveBeenCalledWith(2));
        expect(await screen.findByRole("button", { name: "활성화" })).toBeInTheDocument();
    });

    it("force-withdraws a confirmed user and removes the row", async () => {
        apiMock.forceWithdrawAdminUser.mockResolvedValue({ success: true });
        renderPage();
        await screen.findByText("member@example.com");

        fireEvent.click(screen.getByRole("button", { name: "강제 탈퇴" }));

        await waitFor(() => expect(apiMock.forceWithdrawAdminUser).toHaveBeenCalledWith(2));
        expect(screen.queryByText("member@example.com")).not.toBeInTheDocument();
    });

    it("sends email, status, and role filters to the server", async () => {
        renderPage();
        await screen.findByText("member@example.com");

        fireEvent.change(screen.getByLabelText("이메일"), {
            target: { value: " member@example.com " },
        });
        fireEvent.change(screen.getByLabelText("상태"), {
            target: { value: "ACTIVE" },
        });
        fireEvent.change(screen.getByLabelText("권한"), {
            target: { value: "USER" },
        });
        fireEvent.click(screen.getByRole("button", { name: "검색" }));

        await waitFor(() => expect(apiMock.getAdminUsers).toHaveBeenLastCalledWith({
            page: 0,
            email: "member@example.com",
            status: "ACTIVE",
            role: "USER",
        }));
    });
});
