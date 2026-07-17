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
    getAdminDeadLetterJobs: vi.fn(),
    requeueAdminDeadLetterJob: vi.fn(),
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
    getAdminDeadLetterJobs: apiMock.getAdminDeadLetterJobs,
    requeueAdminDeadLetterJob: apiMock.requeueAdminDeadLetterJob,
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

const emptyDeadLetterResponse = {
    data: {
        content: [],
        totalElements: 0,
        totalPages: 0,
        number: 0,
        size: 20,
        numberOfElements: 0,
        first: true,
        last: true,
    },
};

const deadLetterResponse = {
    data: {
        content: [
            {
                jobId: "20260717090000-dead0001",
                ownerId: 2,
                status: "DEAD_LETTER",
                statusDescription: "재시도 소진(관리자 재처리 필요)",
                failReason: "엔진 반복 실패",
                retryCount: 3,
                createdAt: "2026-07-17T09:00:00",
                completedAt: "2026-07-17T09:10:00",
            },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
        numberOfElements: 1,
        first: true,
        last: true,
    },
};

describe("AdminDashboardPage", () => {
    beforeEach(() => {
        apiMock.getAdminStats.mockReset();
        apiMock.getAdminUsers.mockReset();
        apiMock.suspendAdminUser.mockReset();
        apiMock.activateAdminUser.mockReset();
        apiMock.forceWithdrawAdminUser.mockReset();
        apiMock.getAdminDeadLetterJobs.mockReset();
        apiMock.requeueAdminDeadLetterJob.mockReset();
        apiMock.getAdminDeadLetterJobs.mockResolvedValue(emptyDeadLetterResponse);
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

    it("renders the dead-letter queue table when there are exhausted jobs", async () => {
        apiMock.getAdminStats.mockResolvedValue(statsResponse);
        apiMock.getAdminUsers.mockResolvedValue(usersResponse);
        apiMock.getAdminDeadLetterJobs.mockResolvedValue(deadLetterResponse);

        renderAdminDashboardPage();

        expect(await screen.findByText("20260717090000-dead0001")).toBeInTheDocument();
        expect(screen.getByText("엔진 반복 실패")).toBeInTheDocument();
    });

    it("shows an empty state when there are no dead-letter jobs", async () => {
        apiMock.getAdminStats.mockResolvedValue(statsResponse);
        apiMock.getAdminUsers.mockResolvedValue(usersResponse);
        apiMock.getAdminDeadLetterJobs.mockResolvedValue(emptyDeadLetterResponse);

        renderAdminDashboardPage();

        expect(await screen.findByText("재시도 소진 작업이 없습니다.")).toBeInTheDocument();
    });

    it("requeues a dead-letter job after confirmation and removes it from the list", async () => {
        apiMock.getAdminStats.mockResolvedValue(statsResponse);
        apiMock.getAdminUsers.mockResolvedValue(usersResponse);
        apiMock.getAdminDeadLetterJobs.mockResolvedValue(deadLetterResponse);
        apiMock.requeueAdminDeadLetterJob.mockResolvedValue({ success: true });

        renderAdminDashboardPage();

        await screen.findByText("20260717090000-dead0001");

        fireEvent.click(screen.getByRole("button", { name: "다시 큐에 넣기" }));

        await waitFor(() => {
            expect(apiMock.requeueAdminDeadLetterJob).toHaveBeenCalledWith("20260717090000-dead0001");
        });
        await waitFor(() => {
            expect(screen.queryByText("20260717090000-dead0001")).not.toBeInTheDocument();
        });
        expect(await screen.findByText("재시도 소진 작업이 없습니다.")).toBeInTheDocument();
    });

    it("shows an error message when requeue fails", async () => {
        apiMock.getAdminStats.mockResolvedValue(statsResponse);
        apiMock.getAdminUsers.mockResolvedValue(usersResponse);
        apiMock.getAdminDeadLetterJobs.mockResolvedValue(deadLetterResponse);
        apiMock.requeueAdminDeadLetterJob.mockRejectedValue({ message: "재시도 소진(DEAD_LETTER) 상태의 분석 작업만 재처리할 수 있습니다." });

        renderAdminDashboardPage();

        await screen.findByText("20260717090000-dead0001");

        fireEvent.click(screen.getByRole("button", { name: "다시 큐에 넣기" }));

        expect(await screen.findByText("재시도 소진(DEAD_LETTER) 상태의 분석 작업만 재처리할 수 있습니다.")).toBeInTheDocument();
        expect(screen.getByText("20260717090000-dead0001")).toBeInTheDocument();
    });
});
