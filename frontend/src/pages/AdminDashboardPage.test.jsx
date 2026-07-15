import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import AdminDashboardPage from "./AdminDashboardPage";

const apiMock = vi.hoisted(() => ({
    getAdminStats: vi.fn(),
    getAdminUsers: vi.fn(),
}));

vi.mock("../api/adminApi", () => ({
    getAdminStats: apiMock.getAdminStats,
    getAdminUsers: apiMock.getAdminUsers,
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

describe("AdminDashboardPage", () => {
    beforeEach(() => {
        apiMock.getAdminStats.mockReset();
        apiMock.getAdminUsers.mockReset();
    });

    afterEach(() => {
        cleanup();
        vi.restoreAllMocks();
    });

    it("renders aggregate stats and the user list", async () => {
        apiMock.getAdminStats.mockResolvedValue({
            data: {
                totalUsers: 2,
                adminUsers: 1,
                totalAnalysisJobs: 3,
                completedAnalysisJobs: 1,
            },
        });
        apiMock.getAdminUsers.mockResolvedValue({
            data: {
                content: [
                    {
                        id: 1,
                        email: "admin@example.com",
                        role: "ADMIN",
                        createdAt: "2026-07-01T09:00:00",
                        onboardingCompleted: true,
                        analysisJobCount: 1,
                    },
                    {
                        id: 2,
                        email: "member@example.com",
                        role: "USER",
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
        });

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
});
