import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

import AdminRecoveryPage from "./AdminRecoveryPage";

const apiMock = vi.hoisted(() => ({
    getAdminDeadLetterJobs: vi.fn(),
    getAdminStorageDeletionDeadLetters: vi.fn(),
    getAdminPasswordResetEmailDeadLetters: vi.fn(),
    requeueAdminDeadLetterJob: vi.fn(),
    requeueAdminStorageDeletionDeadLetter: vi.fn(),
    requeueAdminPasswordResetEmailDeadLetter: vi.fn(),
}));
const confirmMock = vi.hoisted(() => vi.fn());
const promptReasonMock = vi.hoisted(() => vi.fn());

vi.mock("../api/adminApi", () => apiMock);
vi.mock("../context/ConfirmContext", () => ({
    useConfirm: () => confirmMock,
    useReasonPrompt: () => promptReasonMock,
}));

function pagedResponse(content, { last = true, totalElements = content.length } = {}) {
    return { data: { content, last, totalElements } };
}

function renderPage() {
    return render(
        <MemoryRouter initialEntries={["/admin/recovery"]}>
            <AdminRecoveryPage />
        </MemoryRouter>
    );
}

describe("AdminRecoveryPage", () => {
    beforeEach(() => {
        Object.values(apiMock).forEach((mockFunction) => mockFunction.mockReset());
        confirmMock.mockReset();
        confirmMock.mockResolvedValue(true);
        promptReasonMock.mockReset();
        promptReasonMock.mockResolvedValue({ reason: "테스트 사유", incidentId: "INC-2001" });

        apiMock.getAdminDeadLetterJobs.mockResolvedValue(pagedResponse([{
            jobId: "analysis-dead-1",
            ownerId: 7,
            failReason: "엔진 반복 실패",
            retryCount: 3,
            completedAt: "2026-08-01T09:00:00",
        }]));
        apiMock.getAdminStorageDeletionDeadLetters.mockResolvedValue(pagedResponse([{
            id: 11,
            jobId: "storage-dead-1",
            reason: "RESULT_DELETED",
            attemptCount: 8,
            lastError: "MinIO unavailable",
            createdAt: "2026-08-01T09:05:00",
        }]));
        apiMock.getAdminPasswordResetEmailDeadLetters.mockResolvedValue(pagedResponse([{
            id: 21,
            userId: 9,
            maskedRecipientEmail: "us**@example.com",
            attemptCount: 5,
            lastError: "SMTP timeout",
            tokenExpiresAt: "2026-08-01T10:00:00",
        }]));
    });

    it("renders all three independent recovery queues", async () => {
        renderPage();

        expect(await screen.findByText("analysis-dead-1")).toBeInTheDocument();
        expect(screen.getByText("storage-dead-1")).toBeInTheDocument();
        expect(screen.getByText("us**@example.com")).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "재큐잉 전 확인 순서" })).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "복구 작업" })).toHaveAttribute("aria-current", "page");
        expect(screen.getAllByText("재시도 소진")).toHaveLength(3);
        expect(screen.getAllByRole("button", { name: "다시 큐에 넣기" })).toHaveLength(3);
    });

    it("keeps the recovery context and independent loading states visible while queues load", () => {
        const pendingRequest = new Promise(() => {});
        apiMock.getAdminDeadLetterJobs.mockReturnValue(pendingRequest);
        apiMock.getAdminStorageDeletionDeadLetters.mockReturnValue(pendingRequest);
        apiMock.getAdminPasswordResetEmailDeadLetters.mockReturnValue(pendingRequest);

        renderPage();

        expect(screen.getByRole("heading", { name: "복구 작업", level: 1 })).toBeInTheDocument();
        expect(screen.getByRole("navigation", { name: "관리자 메뉴" })).toBeInTheDocument();
        expect(screen.getByText("분석 작업 로딩 중")).toBeInTheDocument();
        expect(screen.getByText("스토리지 삭제 작업 로딩 중")).toBeInTheDocument();
        expect(screen.getByText("비밀번호 재설정 이메일 로딩 중")).toBeInTheDocument();
    });

    it("requeues each domain task through its matching API", async () => {
        apiMock.requeueAdminDeadLetterJob.mockResolvedValue({ success: true });
        apiMock.requeueAdminStorageDeletionDeadLetter.mockResolvedValue({ success: true });
        apiMock.requeueAdminPasswordResetEmailDeadLetter.mockResolvedValue({ success: true });
        renderPage();

        const analysisSection = (await screen.findByRole("heading", { name: "분석 작업" })).closest("section");
        const storageSection = screen.getByRole("heading", { name: "스토리지 삭제 작업" }).closest("section");
        const emailSection = screen.getByRole("heading", { name: "비밀번호 재설정 이메일" }).closest("section");

        fireEvent.click(within(analysisSection).getByRole("button", { name: "다시 큐에 넣기" }));
        fireEvent.click(within(storageSection).getByRole("button", { name: "다시 큐에 넣기" }));
        fireEvent.click(within(emailSection).getByRole("button", { name: "다시 큐에 넣기" }));

        await waitFor(() => {
            expect(apiMock.requeueAdminDeadLetterJob)
                .toHaveBeenCalledWith("analysis-dead-1", { reason: "테스트 사유", incidentId: "INC-2001" });
            expect(apiMock.requeueAdminStorageDeletionDeadLetter)
                .toHaveBeenCalledWith(11, { reason: "테스트 사유", incidentId: "INC-2001" });
            expect(apiMock.requeueAdminPasswordResetEmailDeadLetter)
                .toHaveBeenCalledWith(21, { reason: "테스트 사유", incidentId: "INC-2001" });
        });
        expect(screen.getByText("분석 작업 항목의 재큐잉을 접수했습니다. 실제 처리 상태를 다시 확인하세요.")).toBeInTheDocument();
        expect(screen.queryByText("analysis-dead-1")).not.toBeInTheDocument();
    });

    it("does not requeue a task when the reason prompt is cancelled", async () => {
        promptReasonMock.mockResolvedValue(null);
        renderPage();

        fireEvent.click((await screen.findAllByRole("button", { name: "다시 큐에 넣기" }))[0]);

        await waitFor(() => expect(promptReasonMock).toHaveBeenCalled());
        expect(apiMock.requeueAdminDeadLetterJob).not.toHaveBeenCalled();
    });

    it("keeps other recovery queues usable when one queue fails", async () => {
        apiMock.getAdminStorageDeletionDeadLetters.mockRejectedValue({ message: "스토리지 큐 조회 실패" });
        renderPage();

        expect(await screen.findByText("스토리지 큐 조회 실패")).toBeInTheDocument();
        const storageSection = screen.getByRole("heading", { name: "스토리지 삭제 작업" }).closest("section");
        expect(within(storageSection).getByText("스토리지 삭제 작업을(를) 표시할 수 없습니다.")).toBeInTheDocument();
        expect(within(storageSection).queryByText("재시도 소진 스토리지 삭제 작업이 없습니다.")).not.toBeInTheDocument();
        expect(within(storageSection).getByRole("button", { name: "다시 시도" })).toBeInTheDocument();
        expect(screen.getByText("analysis-dead-1")).toBeInTheDocument();
        expect(screen.getByText("us**@example.com")).toBeInTheDocument();
    });

    it("loads the next page inside only the selected recovery queue", async () => {
        apiMock.getAdminDeadLetterJobs
            .mockResolvedValueOnce(pagedResponse([{
                jobId: "analysis-dead-1",
                ownerId: 7,
                failReason: "엔진 반복 실패",
                retryCount: 3,
                completedAt: "2026-08-01T09:00:00",
            }], { last: false, totalElements: 2 }))
            .mockResolvedValueOnce(pagedResponse([{
                jobId: "analysis-dead-2",
                ownerId: 8,
                failReason: "타임아웃",
                retryCount: 3,
                completedAt: "2026-08-01T09:10:00",
            }], { totalElements: 2 }));
        renderPage();

        const analysisSection = (await screen.findByRole("heading", { name: "분석 작업" })).closest("section");
        fireEvent.click(within(analysisSection).getByRole("button", { name: "더 보기" }));

        expect(await within(analysisSection).findByText("analysis-dead-2")).toBeInTheDocument();
        expect(apiMock.getAdminDeadLetterJobs).toHaveBeenLastCalledWith({ page: 1 });
        expect(apiMock.getAdminStorageDeletionDeadLetters).toHaveBeenCalledTimes(1);
        expect(apiMock.getAdminPasswordResetEmailDeadLetters).toHaveBeenCalledTimes(1);
    });

    it("keeps a failed requeue item visible and reports the action error", async () => {
        apiMock.requeueAdminDeadLetterJob.mockRejectedValue({ message: "원인이 아직 해소되지 않았습니다" });
        renderPage();

        const analysisSection = (await screen.findByRole("heading", { name: "분석 작업" })).closest("section");
        fireEvent.click(within(analysisSection).getByRole("button", { name: "다시 큐에 넣기" }));

        expect(await within(analysisSection).findByText("원인이 아직 해소되지 않았습니다")).toBeInTheDocument();
        expect(within(analysisSection).getByText("analysis-dead-1")).toBeInTheDocument();
    });
});
