import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import CoachChatSection from "./CoachChatSection";

const coachApiMock = vi.hoisted(() => ({
    getCoachMessages: vi.fn(),
    sendCoachMessage: vi.fn(),
    resetCoachConversation: vi.fn(),
}));

const confirmMock = vi.hoisted(() => ({
    confirm: vi.fn(),
}));

vi.mock("../../api/coachApi", () => ({
    getCoachMessages: coachApiMock.getCoachMessages,
    sendCoachMessage: coachApiMock.sendCoachMessage,
    resetCoachConversation: coachApiMock.resetCoachConversation,
}));

vi.mock("../../context/ConfirmContext", () => ({
    useConfirm: () => confirmMock.confirm,
}));

describe("CoachChatSection", () => {
    beforeEach(() => {
        coachApiMock.getCoachMessages.mockReset();
        coachApiMock.sendCoachMessage.mockReset();
        coachApiMock.resetCoachConversation.mockReset();
        confirmMock.confirm.mockReset();
        confirmMock.confirm.mockResolvedValue(true);
        window.HTMLElement.prototype.scrollIntoView = vi.fn();
    });

    it("does not fetch messages when the job is not completed", () => {
        render(<CoachChatSection jobId="job-1" isCompleted={false} />);

        expect(screen.getByText("분석이 완료된 후 이용할 수 있습니다.")).toBeInTheDocument();
        expect(coachApiMock.getCoachMessages).not.toHaveBeenCalled();
    });

    it("does not fetch messages when the completed result has a data issue", () => {
        render(
            <CoachChatSection
                jobId="job-1"
                isCompleted
                disabledReason="분석은 완료됐지만 결과 파일을 찾을 수 없습니다. 관리자에게 문의하세요."
            />
        );

        expect(
            screen.getByText("분석은 완료됐지만 결과 파일을 찾을 수 없습니다. 관리자에게 문의하세요.")
        ).toBeInTheDocument();
        expect(coachApiMock.getCoachMessages).not.toHaveBeenCalled();
        expect(
            screen.queryByPlaceholderText("예: 말이 너무 빠른가요? 어떻게 개선할 수 있을까요?")
        ).not.toBeInTheDocument();
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

    it("shows the daily usage summary when it is included in the history response", async () => {
        coachApiMock.getCoachMessages.mockResolvedValue({
            data: {
                messages: [],
                dailyUsage: { used: 1, capacity: 5, remaining: 4 },
            },
        });

        render(<CoachChatSection jobId="job-1" isCompleted />);

        expect(
            await screen.findByText(/오늘 5회 중 1회 사용했습니다\. \(남은 횟수: 4회\)/)
        ).toBeInTheDocument();
    });

    it("disables the input and shows a notice once the daily usage is exhausted", async () => {
        coachApiMock.getCoachMessages.mockResolvedValue({
            data: {
                messages: [],
                dailyUsage: { used: 5, capacity: 5, remaining: 0 },
            },
        });

        render(<CoachChatSection jobId="job-1" isCompleted />);

        expect(
            await screen.findByText("오늘 사용 가능한 AI 코치 메시지를 모두 사용했습니다. 내일 다시 시도해주세요.")
        ).toBeInTheDocument();
        expect(
            screen.getByPlaceholderText("예: 말이 너무 빠른가요? 어떻게 개선할 수 있을까요?")
        ).toBeDisabled();
        expect(screen.getByRole("button", { name: "전송" })).toBeDisabled();
    });

    it("updates the daily usage summary after sending a message", async () => {
        coachApiMock.getCoachMessages.mockResolvedValue({
            data: {
                messages: [],
                dailyUsage: { used: 0, capacity: 5, remaining: 5 },
            },
        });
        coachApiMock.sendCoachMessage.mockResolvedValue({
            data: {
                messages: [
                    { role: "USER", content: "질문입니다.", generationMode: null },
                    { role: "ASSISTANT", content: "답변입니다.", generationMode: "MOCK" },
                ],
                dailyUsage: { used: 1, capacity: 5, remaining: 4 },
            },
        });

        render(<CoachChatSection jobId="job-1" isCompleted />);

        await screen.findByText(/오늘 5회 중 0회 사용했습니다\. \(남은 횟수: 5회\)/);

        fireEvent.change(
            screen.getByPlaceholderText("예: 말이 너무 빠른가요? 어떻게 개선할 수 있을까요?"),
            { target: { value: "질문입니다." } }
        );
        fireEvent.click(screen.getByRole("button", { name: "전송" }));

        expect(
            await screen.findByText(/오늘 5회 중 1회 사용했습니다\. \(남은 횟수: 4회\)/)
        ).toBeInTheDocument();
    });

    it("does not show the reset button when there is no conversation yet", async () => {
        coachApiMock.getCoachMessages.mockResolvedValue({ data: { messages: [] } });

        render(<CoachChatSection jobId="job-1" isCompleted />);

        await waitFor(() => {
            expect(coachApiMock.getCoachMessages).toHaveBeenCalled();
        });

        expect(screen.queryByRole("button", { name: "대화 초기화" })).not.toBeInTheDocument();
    });

    it("asks for confirmation and clears the conversation when reset is confirmed", async () => {
        coachApiMock.getCoachMessages.mockResolvedValue({
            data: {
                messages: [
                    { role: "USER", content: "질문입니다.", generationMode: null },
                    { role: "ASSISTANT", content: "답변입니다.", generationMode: "MOCK" },
                ],
            },
        });
        coachApiMock.resetCoachConversation.mockResolvedValue({ success: true });

        render(<CoachChatSection jobId="job-1" isCompleted />);

        await waitFor(() => {
            expect(screen.getByText("답변입니다.")).toBeInTheDocument();
        });

        fireEvent.click(screen.getByRole("button", { name: "대화 초기화" }));

        await waitFor(() => {
            expect(confirmMock.confirm).toHaveBeenCalled();
            expect(coachApiMock.resetCoachConversation).toHaveBeenCalledWith("job-1");
        });

        expect(screen.queryByText("답변입니다.")).not.toBeInTheDocument();
        expect(screen.getByText("아직 대화가 없습니다. 궁금한 점을 물어보세요.")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "대화 초기화" })).not.toBeInTheDocument();
    });

    it("does not reset the conversation when confirmation is cancelled", async () => {
        coachApiMock.getCoachMessages.mockResolvedValue({
            data: {
                messages: [
                    { role: "USER", content: "질문입니다.", generationMode: null },
                ],
            },
        });
        confirmMock.confirm.mockResolvedValue(false);

        render(<CoachChatSection jobId="job-1" isCompleted />);

        await waitFor(() => {
            expect(screen.getByText("질문입니다.")).toBeInTheDocument();
        });

        fireEvent.click(screen.getByRole("button", { name: "대화 초기화" }));

        await waitFor(() => {
            expect(confirmMock.confirm).toHaveBeenCalled();
        });
        expect(coachApiMock.resetCoachConversation).not.toHaveBeenCalled();
        expect(screen.getByText("질문입니다.")).toBeInTheDocument();
    });

    it("shows an error message when resetting the conversation fails", async () => {
        coachApiMock.getCoachMessages.mockResolvedValue({
            data: {
                messages: [
                    { role: "USER", content: "질문입니다.", generationMode: null },
                ],
            },
        });
        coachApiMock.resetCoachConversation.mockRejectedValue({
            message: "대화 초기화 중 오류가 발생했습니다.",
        });

        render(<CoachChatSection jobId="job-1" isCompleted />);

        await waitFor(() => {
            expect(screen.getByText("질문입니다.")).toBeInTheDocument();
        });

        fireEvent.click(screen.getByRole("button", { name: "대화 초기화" }));

        await waitFor(() => {
            expect(screen.getByText("대화 초기화 중 오류가 발생했습니다.")).toBeInTheDocument();
        });
        expect(screen.getByText("질문입니다.")).toBeInTheDocument();
    });
});
