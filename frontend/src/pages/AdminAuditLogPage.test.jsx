import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import AdminAuditLogPage from "./AdminAuditLogPage";

const apiMock = vi.hoisted(() => ({
    getAdminAuditLogs: vi.fn(),
}));

vi.mock("../api/adminApi", () => ({
    getAdminAuditLogs: apiMock.getAdminAuditLogs,
}));

function renderAdminAuditLogPage() {
    return render(
        <MemoryRouter initialEntries={["/admin/audit-logs"]}>
            <Routes>
                <Route path="/admin/audit-logs" element={<AdminAuditLogPage />} />
            </Routes>
        </MemoryRouter>
    );
}

describe("AdminAuditLogPage", () => {
    beforeEach(() => {
        apiMock.getAdminAuditLogs.mockReset();
    });

    afterEach(() => {
        cleanup();
        vi.restoreAllMocks();
    });

    it("renders audit log entries with translated labels", async () => {
        apiMock.getAdminAuditLogs.mockResolvedValue({
            data: {
                content: [
                    {
                        id: 1,
                        adminEmail: "admin@example.com",
                        action: "SUSPEND_USER",
                        targetType: "USER",
                        targetId: "42",
                        detail: null,
                        reason: "어뷰징 신고 접수",
                        requestId: "req-abc-123",
                        incidentId: "INC-2001",
                        createdAt: "2026-07-15T09:00:00",
                    },
                ],
                last: true,
            },
        });

        renderAdminAuditLogPage();

        expect(await screen.findByText("admin@example.com")).toBeInTheDocument();
        expect(screen.getByRole("cell", { name: "계정 정지" })).toBeInTheDocument();
        expect(screen.getByRole("cell", { name: "사용자" })).toBeInTheDocument();
        // P2-03: 파괴적 조치 사유와 상관 ID가 감사로그 목록에 그대로 보여야 한다.
        expect(screen.getByRole("cell", { name: "어뷰징 신고 접수" })).toBeInTheDocument();
        expect(screen.getByRole("cell", { name: "INC-2001" })).toBeInTheDocument();
        expect(screen.getByRole("cell", { name: "req-abc-123" })).toBeInTheDocument();
    });

    it("shows an empty state when there are no audit logs", async () => {
        apiMock.getAdminAuditLogs.mockResolvedValue({ data: { content: [], last: true } });

        renderAdminAuditLogPage();

        expect(await screen.findByText("표시할 감사로그가 없습니다.")).toBeInTheDocument();
    });

    it("sends administrator, action, target, and date filters to the server", async () => {
        apiMock.getAdminAuditLogs.mockResolvedValue({ data: { content: [], last: true } });
        renderAdminAuditLogPage();
        await screen.findByText("표시할 감사로그가 없습니다.");

        fireEvent.change(screen.getByLabelText("관리자 이메일"), {
            target: { value: " admin@example.com " },
        });
        fireEvent.change(screen.getByLabelText("작업"), {
            target: { value: "REQUEUE_STORAGE_DELETION_TASK" },
        });
        fireEvent.change(screen.getByLabelText("대상 유형"), {
            target: { value: "STORAGE_DELETION_TASK" },
        });
        fireEvent.change(screen.getByLabelText("대상 ID"), {
            target: { value: " 77 " },
        });
        fireEvent.change(screen.getByLabelText("시작 시각"), {
            target: { value: "2026-08-01T00:00" },
        });
        fireEvent.change(screen.getByLabelText("종료 시각"), {
            target: { value: "2026-08-02T00:00" },
        });
        fireEvent.click(screen.getByRole("button", { name: "필터 적용" }));

        await waitFor(() => expect(apiMock.getAdminAuditLogs).toHaveBeenLastCalledWith({
            page: 0,
            adminEmail: "admin@example.com",
            action: "REQUEUE_STORAGE_DELETION_TASK",
            targetType: "STORAGE_DELETION_TASK",
            targetId: "77",
            from: "2026-08-01T00:00",
            to: "2026-08-02T00:00",
        }));
        expect(screen.getByText("관리자 이메일: admin@example.com")).toBeInTheDocument();
        expect(screen.getByText("작업: 스토리지 삭제 재큐잉")).toBeInTheDocument();
        expect(screen.getByText("대상 유형: 스토리지 삭제 작업")).toBeInTheDocument();
    });

    it("distinguishes a filtered empty result and clears the applied conditions", async () => {
        apiMock.getAdminAuditLogs
            .mockResolvedValueOnce({
                data: {
                    content: [{ id: 1, adminEmail: "admin@example.com", action: "SUSPEND_USER", targetType: "USER", targetId: "42", createdAt: "2026-07-15T09:00:00" }],
                    last: true,
                },
            })
            .mockResolvedValueOnce({ data: { content: [], last: true } });
        renderAdminAuditLogPage();
        await screen.findByText("admin@example.com");

        fireEvent.change(screen.getByLabelText("관리자 이메일"), {
            target: { value: "nobody@example.com" },
        });
        fireEvent.click(screen.getByRole("button", { name: "필터 적용" }));

        expect(await screen.findByText("검색 조건에 맞는 감사로그가 없습니다.")).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "검색 조건 초기화" }));

        await waitFor(() => expect(apiMock.getAdminAuditLogs).toHaveBeenLastCalledWith({ page: 0 }));
    });

    it("shows an initial load error separately and retries the list request", async () => {
        apiMock.getAdminAuditLogs
            .mockRejectedValueOnce({ message: "감사로그 조회 실패" })
            .mockResolvedValueOnce({ data: { content: [], last: true } });
        renderAdminAuditLogPage();

        expect(await screen.findByText("감사로그 조회 실패")).toBeInTheDocument();
        expect(screen.getByText("감사로그를 표시할 수 없습니다.")).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

        expect(await screen.findByText("표시할 감사로그가 없습니다.")).toBeInTheDocument();
        expect(apiMock.getAdminAuditLogs).toHaveBeenCalledTimes(2);
    });

    it("appends the next server page without changing the applied contract", async () => {
        apiMock.getAdminAuditLogs
            .mockResolvedValueOnce({
                data: {
                    content: [{ id: 1, adminEmail: "admin@example.com", action: "SUSPEND_USER", targetType: "USER", targetId: "42", createdAt: "2026-07-15T09:00:00" }],
                    last: false,
                },
            })
            .mockResolvedValueOnce({
                data: {
                    content: [{ id: 2, adminEmail: "second@example.com", action: "ACTIVATE_USER", targetType: "USER", targetId: "43", createdAt: "2026-07-16T09:00:00" }],
                    last: true,
                },
            });
        renderAdminAuditLogPage();
        await screen.findByText("admin@example.com");

        fireEvent.click(screen.getByRole("button", { name: "더 보기" }));

        expect(await screen.findByText("second@example.com")).toBeInTheDocument();
        expect(apiMock.getAdminAuditLogs).toHaveBeenLastCalledWith({ page: 1 });
    });
});
