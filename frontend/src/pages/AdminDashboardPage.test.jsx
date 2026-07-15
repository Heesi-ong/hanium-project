import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import AdminDashboardPage from "./AdminDashboardPage";

const apiMock = vi.hoisted(() => ({
    getAdminStats: vi.fn(),
    getAdminUsers: vi.fn(),
    suspendAdminUser: vi.fn(),
    activateAdminUser: vi.fn(),
    forceWithdrawAdminUser: vi.fn(),
}));

const authMock = vi.hoisted(() => ({
    user: { id: 1, email: "admin@example.com", admin: true },
}));

const confirmMock = vi.hoisted(() => ({
    confirm: vi.fn(),
}));

vi.mock("../api/adminApi", () => ({
    getAdminStats: apiMock.getAdminStats,
    getAdminUsers: apiMock.getAdminUsers,
    suspendAdminUser: apiMock.suspendAdminUser,
    activateAdminUser: apiMock.activateAdminUser,
    forceWithdrawAdminUser: apiMock.forceWithdrawAdminUser,
}));

vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({ user: authMock.user }),
}));

vi.mock("../context/ConfirmContext", () => ({
    useConfirm: () => confirmMock.confirm,
}));

function renderAdminDashboardPage() {
    return render(
        <MemoryRouter initialEntries={["/admin"]}>
            <Routes>
                <Route path="/admin" element={<AdminDashboardPage />} />
            </Routes>
        </MemoryRouter>
    );
}

const usersResponse = {
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
        totalElements: 2,
        totalPages: 1,
        number: 0,
        size: 20,
        numberOfElements: 2,
        first: true,
        last: true,
    },
};

const statsResponse = {
    data: {
        totalUsers: 2,
        adminUsers: 1,
        totalAnalysisJobs: 3,
        completedAnalysisJobs: 1,
    },
};

describe("AdminDashboardPage", () => {
    beforeEach(() => {
        apiMock.getAdminStats.mockReset();
        apiMock.getAdminUsers.mockReset();
        apiMock.suspendAdminUser.mockReset();
        apiMock.activateAdminUser.mockReset();
        apiMock.forceWithdrawAdminUser.mockReset();
        confirmMock.confirm.mockReset();
        confirmMock.confirm.mockResolvedValue(true);
    });

    afterEach(() => {
        cleanup();
        vi.restoreAllMocks();
    });

    it("renders aggregate stats and the user list", async () => {
        apiMock.getAdminStats.mockResolvedValue(statsResponse);
        apiMock.getAdminUsers.mockResolvedValue(usersResponse);

        renderAdminDashboardPage();

        await waitFor(() => {
            expect(screen.getByText("member@example.com")).toBeInTheDocument();
        });

        expect(screen.getByText("admin@example.com")).toBeInTheDocument();
        expect(screen.getByRole("cell", { name: "관리자" })).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "더 보기" })).not.toBeInTheDocument();
    });

    it("shows an error message when loading fails", async () => {
        apiMock.getAdminStats.mockRejectedValue({ message: "집계 통계를 불러오지 못했습니다." });
        apiMock.getAdminUsers.mockResolvedValue({ data: { content: [], last: true } });

        renderAdminDashboardPage();

        expect(await screen.findByText("집계 통계를 불러오지 못했습니다.")).toBeInTheDocument();
    });

    it("suspends another user after confirmation", async () => {
        apiMock.getAdminStats.mockResolvedValue(statsResponse);
        apiMock.getAdminUsers.mockResolvedValue(usersResponse);
        apiMock.suspendAdminUser.mockResolvedValue({ success: true });

        renderAdminDashboardPage();

        await screen.findByText("member@example.com");

        fireEvent.click(screen.getByRole("button", { name: "정지" }));

        await waitFor(() => {
            expect(apiMock.suspendAdminUser).toHaveBeenCalledWith(2);
        });
        expect(await screen.findByRole("button", { name: "활성화" })).toBeInTheDocument();
    });

    it("does not render a status action button for the current admin's own row", async () => {
        apiMock.getAdminStats.mockResolvedValue(statsResponse);
        apiMock.getAdminUsers.mockResolvedValue(usersResponse);

        renderAdminDashboardPage();

        await screen.findByText("member@example.com");

        expect(screen.getAllByRole("button", { name: "정지" })).toHaveLength(1);
    });

    it("force-withdraws another user after confirmation and removes the row", async () => {
        apiMock.getAdminStats.mockResolvedValue(statsResponse);
        apiMock.getAdminUsers.mockResolvedValue(usersResponse);
        apiMock.forceWithdrawAdminUser.mockResolvedValue({ success: true });

        renderAdminDashboardPage();

        await screen.findByText("member@example.com");

        fireEvent.click(screen.getByRole("button", { name: "강제 탈퇴" }));

        await waitFor(() => {
            expect(apiMock.forceWithdrawAdminUser).toHaveBeenCalledWith(2);
        });
        await waitFor(() => {
            expect(screen.queryByText("member@example.com")).not.toBeInTheDocument();
        });
    });
});
