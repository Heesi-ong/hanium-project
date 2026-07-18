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
                visualAnalysis: {
                    model: {
                        name: "mock-video-llm",
                        generationMode: "MOCK",
                    },
                },
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
        expect(screen.getByText("샘플 시각 분석")).toBeInTheDocument();
        expect(screen.getByText("Video LLM · mock-video-llm")).toBeInTheDocument();
        expect(apiMock.getAdminUserResults).toHaveBeenCalledWith("1", { page: 0 });
    });

    it("shows an empty state when the user has no results", async () => {
        apiMock.getAdminUserResults.mockResolvedValue({ data: { content: [], last: true } });

        renderAdminUserDetailPage("2");

        expect(await screen.findByText("표시할 분석 결과가 없습니다.")).toBeInTheDocument();
    });

    it("shows data issue warnings for broken user results", async () => {
        apiMock.getAdminUserResults.mockResolvedValue({
            data: {
                content: [
                    {
                        ...singleResultResponse.data.content[0],
                        dataIssue: "RESULT_DATA_INCOMPLETE",
                        dataIssueDescription: "분석 결과 파일은 있지만 점수 또는 피드백 데이터가 불완전합니다.",
                    },
                ],
                last: true,
            },
        });

        renderAdminUserDetailPage("1");

        expect(await screen.findByRole("alert")).toHaveTextContent("불완전");
    });

    it("uses pipeline OpenAI metadata when result feedback metadata is a placeholder", async () => {
        apiMock.getAdminUserResults.mockResolvedValue({
            data: {
                content: [
                    {
                        ...singleResultResponse.data.content[0],
                        feedback: {
                            generationMode: "UNKNOWN",
                            model: "-",
                            realApiUsed: false,
                        },
                        pipeline: {
                            openAiGenerationMode: "REAL",
                            openAiModel: "gpt-4.1-mini",
                            openAiRealApiUsed: true,
                        },
                    },
                ],
                last: true,
            },
        });

        renderAdminUserDetailPage("1");

        expect(await screen.findByText("presentation.mp4")).toBeInTheDocument();
        expect(screen.getByText("실제 OpenAI")).toBeInTheDocument();
        expect(screen.getByText("gpt-4.1-mini · API 사용")).toBeInTheDocument();
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
