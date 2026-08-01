import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

import AdminDashboardPage from "./AdminDashboardPage";

const apiMock = vi.hoisted(() => ({
    getAdminStats: vi.fn(),
    getAdminDeadLetterJobs: vi.fn(),
    getAdminStorageDeletionDeadLetters: vi.fn(),
    getAdminPasswordResetEmailDeadLetters: vi.fn(),
}));

vi.mock("../api/adminApi", () => apiMock);

function pagedResponse(totalElements) {
    return {
        data: {
            content: [],
            totalElements,
            last: true,
        },
    };
}

function renderPage() {
    return render(
        <MemoryRouter initialEntries={["/admin"]}>
            <AdminDashboardPage />
        </MemoryRouter>
    );
}

describe("AdminDashboardPage", () => {
    beforeEach(() => {
        Object.values(apiMock).forEach((mockFunction) => mockFunction.mockReset());
        apiMock.getAdminStats.mockResolvedValue({
            data: {
                totalUsers: 12,
                adminUsers: 2,
                totalAnalysisJobs: 30,
                completedAnalysisJobs: 24,
            },
        });
        apiMock.getAdminDeadLetterJobs.mockResolvedValue(pagedResponse(3));
        apiMock.getAdminStorageDeletionDeadLetters.mockResolvedValue(pagedResponse(1));
        apiMock.getAdminPasswordResetEmailDeadLetters.mockResolvedValue(pagedResponse(2));
    });

    it("renders business aggregates and three actionable recovery counts", async () => {
        renderPage();

        expect(await screen.findByRole("heading", { name: "관리자 업무 개요" })).toBeInTheDocument();
        expect(screen.getByText("전체 가입자")).toBeInTheDocument();
        expect(screen.getByText("분석 복구")).toBeInTheDocument();
        expect(screen.getByText("삭제 복구")).toBeInTheDocument();
        expect(screen.getByText("이메일 복구")).toBeInTheDocument();
        expect(screen.getByRole("link", { name: /사용자 관리 열기/ })).toHaveAttribute("href", "/admin/users");
        expect(screen.getByRole("link", { name: /복구 작업 열기/ })).toHaveAttribute("href", "/admin/recovery");
        expect(screen.queryByText(/p95|CPU|메모리|지연/)).not.toBeInTheDocument();
    });

    it("keeps successful sections visible when one recovery count fails", async () => {
        apiMock.getAdminStorageDeletionDeadLetters.mockRejectedValue({ message: "삭제 큐 조회 실패" });

        renderPage();

        expect(await screen.findByText("삭제 큐 조회 실패")).toBeInTheDocument();
        expect(screen.getByText("전체 가입자")).toBeInTheDocument();
        expect(screen.getByText("분석 복구")).toBeInTheDocument();
        expect(screen.getByText("이메일 복구")).toBeInTheDocument();
    });

    it("refreshes all overview sources on demand", async () => {
        renderPage();
        await screen.findByText("전체 가입자");

        fireEvent.click(screen.getByRole("button", { name: "개요 새로고침" }));

        await waitFor(() => expect(apiMock.getAdminStats).toHaveBeenCalledTimes(2));
        expect(apiMock.getAdminDeadLetterJobs).toHaveBeenCalledTimes(2);
        expect(apiMock.getAdminStorageDeletionDeadLetters).toHaveBeenCalledTimes(2);
        expect(apiMock.getAdminPasswordResetEmailDeadLetters).toHaveBeenCalledTimes(2);
    });
});
