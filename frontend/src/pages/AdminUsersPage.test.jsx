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
const promptReasonMock = vi.hoisted(() => vi.fn());

vi.mock("../api/adminApi", () => apiMock);
vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({ user: { id: 1, email: "admin@example.com", admin: true } }),
}));
vi.mock("../context/ConfirmContext", () => ({
    useConfirm: () => confirmMock,
    useReasonPrompt: () => promptReasonMock,
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
        promptReasonMock.mockReset();
        promptReasonMock.mockResolvedValue({ reason: "테스트 사유", incidentId: "INC-2001" });
    });

    it("renders users separately from recovery work", async () => {
        renderPage();

        expect(await screen.findByText("member@example.com")).toBeInTheDocument();
        expect(screen.getByText("admin@example.com")).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "사용자 관리" })).toHaveAttribute("aria-current", "page");
        expect(screen.getByRole("table", { name: "관리자 사용자 목록" })).toBeInTheDocument();
        expect(screen.getByText("현재 로그인 계정")).toBeInTheDocument();
        expect(screen.getAllByText("2명 표시")).toHaveLength(2);
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

        await waitFor(() => expect(apiMock.suspendAdminUser).toHaveBeenCalledWith(2, {
            reason: "테스트 사유",
            incidentId: "INC-2001",
        }));
        expect(await screen.findByRole("button", { name: "활성화" })).toBeInTheDocument();
    });

    it("force-withdraws a confirmed user and removes the row", async () => {
        apiMock.forceWithdrawAdminUser.mockResolvedValue({ success: true });
        renderPage();
        await screen.findByText("member@example.com");

        fireEvent.click(screen.getByRole("button", { name: "강제 탈퇴" }));

        await waitFor(() => expect(apiMock.forceWithdrawAdminUser).toHaveBeenCalledWith(2, {
            reason: "테스트 사유",
            incidentId: "INC-2001",
        }));
        expect(screen.queryByText("member@example.com")).not.toBeInTheDocument();
    });

    it("does not suspend a user when the reason prompt is cancelled", async () => {
        promptReasonMock.mockResolvedValue(null);
        renderPage();
        await screen.findByText("member@example.com");

        fireEvent.click(screen.getByRole("button", { name: "정지" }));

        await waitFor(() => expect(promptReasonMock).toHaveBeenCalled());
        expect(apiMock.suspendAdminUser).not.toHaveBeenCalled();
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
        expect(screen.getByText("이메일: member@example.com")).toBeInTheDocument();
        expect(screen.getByText("상태: 활성")).toBeInTheDocument();
        expect(screen.getByText("권한: 일반 사용자")).toBeInTheDocument();
    });

    it("distinguishes a filtered empty result and clears the applied conditions", async () => {
        apiMock.getAdminUsers
            .mockResolvedValueOnce(USERS_RESPONSE)
            .mockResolvedValueOnce({ data: { content: [], last: true } })
            .mockResolvedValueOnce(USERS_RESPONSE);
        renderPage();
        await screen.findByText("member@example.com");

        fireEvent.change(screen.getByLabelText("이메일"), {
            target: { value: "nobody@example.com" },
        });
        fireEvent.click(screen.getByRole("button", { name: "검색" }));

        expect(await screen.findByText("검색 조건에 맞는 사용자가 없습니다.")).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "검색 조건 초기화" }));

        await waitFor(() => expect(apiMock.getAdminUsers).toHaveBeenLastCalledWith({ page: 0 }));
        expect(await screen.findByText("member@example.com")).toBeInTheDocument();
    });

    it("shows an initial load error separately and retries the list request", async () => {
        apiMock.getAdminUsers
            .mockRejectedValueOnce({ message: "사용자 조회 실패" })
            .mockResolvedValueOnce(USERS_RESPONSE);
        renderPage();

        expect(await screen.findByText("사용자 조회 실패")).toBeInTheDocument();
        expect(screen.getByText("사용자 목록을 표시할 수 없습니다.")).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

        expect(await screen.findByText("member@example.com")).toBeInTheDocument();
        expect(apiMock.getAdminUsers).toHaveBeenCalledTimes(2);
    });

    it("appends the next server page without changing the applied contract", async () => {
        apiMock.getAdminUsers
            .mockResolvedValueOnce({
                data: {
                    content: [USERS_RESPONSE.data.content[0]],
                    last: false,
                },
            })
            .mockResolvedValueOnce({
                data: {
                    content: [USERS_RESPONSE.data.content[1]],
                    last: true,
                },
            });
        renderPage();
        await screen.findByText("admin@example.com");

        fireEvent.click(screen.getByRole("button", { name: "더 보기" }));

        expect(await screen.findByText("member@example.com")).toBeInTheDocument();
        expect(apiMock.getAdminUsers).toHaveBeenLastCalledWith({ page: 1 });
    });
});
