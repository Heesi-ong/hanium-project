import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import CoachChatSection from "./CoachChatSection";

const coachApiMock = vi.hoisted(() => ({
    getCoachMessages: vi.fn(),
    sendCoachMessage: vi.fn(),
}));

vi.mock("../../api/coachApi", () => ({
    getCoachMessages: coachApiMock.getCoachMessages,
    sendCoachMessage: coachApiMock.sendCoachMessage,
}));

describe("CoachChatSection", () => {
    beforeEach(() => {
        coachApiMock.getCoachMessages.mockReset();
        coachApiMock.sendCoachMessage.mockReset();
        window.HTMLElement.prototype.scrollIntoView = vi.fn();
    });

    it("does not fetch messages when the job is not completed", () => {
        render(<CoachChatSection jobId="job-1" isCompleted={false} />);

        expect(screen.getByText("분석이 완료된 후 이용할 수 있습니다.")).toBeInTheDocument();
        expect(coachApiMock.getCoachMessages).not.toHaveBeenCalled();
    });

    it("loads and displays existing conversation history", async () => {
        coachApiMock.getCoachMessages.mockResolvedValue({
            data: {
                messages: [
                    { role: "USER", content: "말이 너무 빠른가요?", generationMode: null },
                    { role: "ASSISTANT", content: "속도를 조금 늦춰보세요.", generationMode: "MOCK" },
                ],
            },
        });

        render(<CoachChatSection jobId="job-1" isCompleted />);

        await waitFor(() => {
            expect(screen.getByText("말이 너무 빠른가요?")).toBeInTheDocument();
            expect(screen.getByText("속도를 조금 늦춰보세요.")).toBeInTheDocument();
        });
        expect(coachApiMock.getCoachMessages).toHaveBeenCalledWith("job-1");
    });

    it("sends a message and renders the updated conversation", async () => {
        coachApiMock.getCoachMessages.mockResolvedValue({ data: { messages: [] } });
        coachApiMock.sendCoachMessage.mockResolvedValue({
            data: {
                messages: [
                    { role: "USER", content: "질문입니다.", generationMode: null },
                    { role: "ASSISTANT", content: "답변입니다.", generationMode: "MOCK" },
                ],
            },
        });

        render(<CoachChatSection jobId="job-1" isCompleted />);

        await waitFor(() => {
            expect(coachApiMock.getCoachMessages).toHaveBeenCalled();
        });

        fireEvent.change(
            screen.getByPlaceholderText("예: 말이 너무 빠른가요? 어떻게 개선할 수 있을까요?"),
            { target: { value: "질문입니다." } }
        );
        fireEvent.click(screen.getByRole("button", { name: "전송" }));

        await waitFor(() => {
            expect(coachApiMock.sendCoachMessage).toHaveBeenCalledWith("job-1", "질문입니다.");
            expect(screen.getByText("답변입니다.")).toBeInTheDocument();
        });
    });

    it("shows an error message when sending fails", async () => {
        coachApiMock.getCoachMessages.mockResolvedValue({ data: { messages: [] } });
        coachApiMock.sendCoachMessage.mockRejectedValue({
            message: "AI 코치 일일 메시지 한도를 초과했습니다. 내일 다시 시도해주세요.",
        });

        render(<CoachChatSection jobId="job-1" isCompleted />);

        await waitFor(() => {
            expect(coachApiMock.getCoachMessages).toHaveBeenCalled();
        });

        fireEvent.change(
            screen.getByPlaceholderText("예: 말이 너무 빠른가요? 어떻게 개선할 수 있을까요?"),
            { target: { value: "질문 3" } }
        );
        fireEvent.click(screen.getByRole("button", { name: "전송" }));

        await waitFor(() => {
            expect(
                screen.getByText("AI 코치 일일 메시지 한도를 초과했습니다. 내일 다시 시도해주세요.")
            ).toBeInTheDocument();
        });
    });
});
