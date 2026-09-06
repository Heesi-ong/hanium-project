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
        expect(screen.getByRole("navigation", { name: "관리자 메뉴" })).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "업무 개요" })).toHaveAttribute("aria-current", "page");
        expect(screen.getByRole("heading", { name: "서비스 데이터 스냅샷" })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "조치가 필요한 복구 대기열" })).toBeInTheDocument();
        expect(screen.getByText("총 6건")).toBeInTheDocument();
        expect(screen.getByText("전체 가입자")).toBeInTheDocument();
        expect(screen.getByText("분석 복구")).toBeInTheDocument();
        expect(screen.getByText("삭제 복구")).toBeInTheDocument();
        expect(screen.getByText("이메일 복구")).toBeInTheDocument();
        expect(screen.getByRole("link", { name: /분석 복구 3건/ })).toHaveAttribute("href", "/admin/recovery");
        expect(screen.getByRole("link", { name: /사용자 관리 열기/ })).toHaveAttribute("href", "/admin/users");
        expect(screen.getByRole("link", { name: /^복구 작업 열기$/ })).toHaveAttribute("href", "/admin/recovery");
        expect(screen.getByRole("link", { name: /감사로그 열기/ })).toHaveAttribute("href", "/admin/audit-logs");
        expect(screen.queryByText(/p95|CPU|메모리|지연/)).not.toBeInTheDocument();
    });

    it("keeps admin navigation and communicates the initial loading state", () => {
        apiMock.getAdminStats.mockReturnValue(new Promise(() => {}));
        apiMock.getAdminDeadLetterJobs.mockReturnValue(new Promise(() => {}));
        apiMock.getAdminStorageDeletionDeadLetters.mockReturnValue(new Promise(() => {}));
        apiMock.getAdminPasswordResetEmailDeadLetters.mockReturnValue(new Promise(() => {}));

        renderPage();

        expect(screen.getByRole("navigation", { name: "관리자 메뉴" })).toBeInTheDocument();
        expect(screen.getByText("운영 데이터를 불러오는 중입니다")).toBeInTheDocument();
        expect(screen.getByRole("button", { name: /새로고침 중/ })).toBeDisabled();
    });

    it("keeps successful sections visible when one recovery count fails", async () => {
        apiMock.getAdminStorageDeletionDeadLetters.mockRejectedValue({ message: "삭제 큐 조회 실패" });

        renderPage();

        expect(await screen.findByText("삭제 큐 조회 실패")).toBeInTheDocument();
        expect(screen.getByText("전체 가입자")).toBeInTheDocument();
        expect(screen.getByText("분석 복구")).toBeInTheDocument();
        expect(screen.getByText("이메일 복구")).toBeInTheDocument();
        expect(screen.getByText("일부 집계 확인 필요")).toBeInTheDocument();
        expect(screen.getByRole("link", { name: /삭제 복구 집계 확인 필요/ })).toBeInTheDocument();
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
