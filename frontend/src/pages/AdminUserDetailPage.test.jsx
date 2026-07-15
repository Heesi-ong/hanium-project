import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import AdminUserDetailPage from "./AdminUserDetailPage";

const apiMock = vi.hoisted(() => ({
    getAdminUserResults: vi.fn(),
    deleteAdminResult: vi.fn(),
}));

const confirmMock = vi.hoisted(() => ({
    confirm: vi.fn(),
}));

vi.mock("../api/adminApi", () => ({
    getAdminUserResults: apiMock.getAdminUserResults,
    deleteAdminResult: apiMock.deleteAdminResult,
}));

vi.mock("../context/ConfirmContext", () => ({
    useConfirm: () => confirmMock.confirm,
}));

function renderAdminUserDetailPage(userId = "1") {
    return render(
        <MemoryRouter initialEntries={[`/admin/users/${userId}`]}>
            <Routes>
                <Route path="/admin/users/:userId" element={<AdminUserDetailPage />} />
            </Routes>
        </MemoryRouter>
    );
}

const singleResultResponse = {
    data: {
        content: [
            {
                jobId: "20260715090000-abcd1234",
                status: "COMPLETED",
                statusDescription: "분석 완료",
                fileName: "presentation.mp4",
                createdAt: "2026-07-15T09:00:00",
                scoreSummary: { totalScore: 88, level: "우수" },
                feedback: { generationMode: "MOCK", model: "-", realApiUsed: false },
            },
        ],
        last: true,
    },
};

describe("AdminUserDetailPage", () => {
    beforeEach(() => {
        apiMock.getAdminUserResults.mockReset();
        apiMock.deleteAdminResult.mockReset();
        confirmMock.confirm.mockReset();
        confirmMock.confirm.mockResolvedValue(true);
    });

    afterEach(() => {
        cleanup();
        vi.restoreAllMocks();
    });

    it("renders the user's analysis results", async () => {
        apiMock.getAdminUserResults.mockResolvedValue(singleResultResponse);

        renderAdminUserDetailPage("1");

        expect(await screen.findByText("presentation.mp4")).toBeInTheDocument();
        expect(apiMock.getAdminUserResults).toHaveBeenCalledWith("1", { page: 0 });
    });

    it("shows an empty state when the user has no results", async () => {
        apiMock.getAdminUserResults.mockResolvedValue({ data: { content: [], last: true } });

        renderAdminUserDetailPage("2");

        expect(await screen.findByText("표시할 분석 결과가 없습니다.")).toBeInTheDocument();
    });

    it("deletes a result after confirmation and removes it from the list", async () => {
        apiMock.getAdminUserResults.mockResolvedValue(singleResultResponse);
        apiMock.deleteAdminResult.mockResolvedValue({ success: true });

        renderAdminUserDetailPage("1");

        await screen.findByText("presentation.mp4");

        fireEvent.click(screen.getByRole("button", { name: "삭제" }));

        await waitFor(() => {
            expect(apiMock.deleteAdminResult).toHaveBeenCalledWith("20260715090000-abcd1234");
        });
        await waitFor(() => {
            expect(screen.queryByText("presentation.mp4")).not.toBeInTheDocument();
        });
    });
});
