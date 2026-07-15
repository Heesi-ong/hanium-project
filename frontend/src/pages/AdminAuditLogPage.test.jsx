import { cleanup, render, screen } from "@testing-library/react";
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
                        createdAt: "2026-07-15T09:00:00",
                    },
                ],
                last: true,
            },
        });

        renderAdminAuditLogPage();

        expect(await screen.findByText("admin@example.com")).toBeInTheDocument();
        expect(screen.getByText("계정 정지")).toBeInTheDocument();
        expect(screen.getByText("사용자")).toBeInTheDocument();
    });

    it("shows an empty state when there are no audit logs", async () => {
        apiMock.getAdminAuditLogs.mockResolvedValue({ data: { content: [], last: true } });

        renderAdminAuditLogPage();

        expect(await screen.findByText("표시할 감사로그가 없습니다.")).toBeInTheDocument();
    });
});
