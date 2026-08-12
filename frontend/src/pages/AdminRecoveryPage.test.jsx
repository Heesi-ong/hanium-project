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

function pagedResponse(content) {
    return { data: { content, last: true, totalElements: content.length } };
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
        expect(screen.getAllByRole("button", { name: "다시 큐에 넣기" })).toHaveLength(3);
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
        expect(screen.getByText("analysis-dead-1")).toBeInTheDocument();
        expect(screen.getByText("us**@example.com")).toBeInTheDocument();
    });
});
