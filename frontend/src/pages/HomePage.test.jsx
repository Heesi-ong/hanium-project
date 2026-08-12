import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { getResults, getServiceStatus, healthCheck } from "../api/analysisApi";
import HomePage from "./HomePage";

const authMock = vi.hoisted(() => ({
    isAuthenticated: false,
    user: null,
}));

vi.mock("../api/analysisApi", () => ({
    healthCheck: vi.fn(),
    getServiceStatus: vi.fn(),
    getResults: vi.fn(),
}));

vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({
        isAuthenticated: authMock.isAuthenticated,
        user: authMock.user,
    }),
}));

function renderHomePage() {
    return render(
        <MemoryRouter>
            <HomePage />
        </MemoryRouter>
    );
}

describe("HomePage", () => {
    beforeEach(() => {
        authMock.isAuthenticated = false;
        authMock.user = null;
        healthCheck.mockReset();
        getServiceStatus.mockReset();
        getResults.mockReset();
        getResults.mockResolvedValue({ data: { content: [], last: true } });
    });

    it("renders the public landing page and unauthenticated CTAs without loading status data", () => {
        renderHomePage();

        expect(screen.getByRole("heading", {
            name: /발표는 감이 아니라,\s*데이터로 완성됩니다/,
        })).toBeInTheDocument();
        expect(screen.getByText("핵심 기능")).toBeInTheDocument();
        expect(screen.getByText("분석 항목 상세 소개")).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "3단계면 충분합니다" })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "자주 묻는 질문" })).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "무료로 시작하기" }))
            .toHaveAttribute("href", "/signup")
            .toHaveClass("bg-primary-deep");
        expect(screen.getByRole("link", { name: "무료 회원가입" }))
            .toHaveAttribute("href", "/signup")
            .toHaveClass("bg-primary-deep");
        expect(screen.queryByRole("link", { name: "영상 업로드 시작" }))
            .not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "지금 업로드하기" }))
            .not.toBeInTheDocument();
        expect(healthCheck).not.toHaveBeenCalled();
        expect(getServiceStatus).not.toHaveBeenCalled();
    });

    it("renders the personal dashboard as the authenticated user's first screen", async () => {
        authMock.isAuthenticated = true;
        authMock.user = { email: "user@example.com" };

        renderHomePage();

        expect(screen.getByRole("heading", { name: "다시 연습을 이어가세요" }))
            .toBeInTheDocument();
        expect(screen.getByText(/user@example.com 계정의/)).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "새 영상 분석하기" }))
            .toHaveAttribute("href", "/upload");
        expect(screen.getByRole("link", { name: "전체 결과 보기" }))
            .toHaveAttribute("href", "/results");
        expect(screen.getByRole("link", { name: "계정 설정" }))
            .toHaveAttribute("href", "/account");
        expect(screen.queryByRole("link", { name: "무료로 시작하기" }))
            .not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "무료 회원가입" }))
            .not.toBeInTheDocument();
        expect(screen.queryByText("예시 결과 — 실제 분석 시 내 점수로 대체됩니다"))
            .not.toBeInTheDocument();
        expect(healthCheck).not.toHaveBeenCalled();
        expect(getServiceStatus).not.toHaveBeenCalled();

        await waitFor(() => expect(getResults).toHaveBeenCalledWith({ page: 0, size: 10 }));
    });

    it("marks the hero score strip as an example, not a real result", () => {
        renderHomePage();

        expect(screen.getByText("예시 결과 — 실제 분석 시 내 점수로 대체됩니다"))
            .toBeInTheDocument();
    });

    it("shows an upload prompt on the personal dashboard when the user has no results yet", async () => {
        authMock.isAuthenticated = true;
        getResults.mockResolvedValue({ data: { content: [], last: true } });

        renderHomePage();

        expect(await screen.findByText("첫 분석을 시작해보세요")).toBeInTheDocument();
        expect(
            await screen.findByText(
                "아직 분석한 발표 영상이 없습니다. 첫 영상을 업로드하고 개선 포인트를 확인해보세요."
            )
        ).toBeInTheDocument();
    });

    it("shows recent results and a next-action hint on the personal dashboard", async () => {
        authMock.isAuthenticated = true;
        getResults.mockResolvedValue({
            data: {
                content: [
                    {
                        jobId: "job-home-dashboard",
                        status: "COMPLETED",
                        statusDescription: "분석 완료",
                        fileName: "practice.mp4",
                        createdAt: "2026-08-01T09:00:00",
                        scoreSummary: { totalScore: 85 },
                        feedback: { improvements: ["시선을 카메라에 더 오래 고정해보세요."] },
                    },
                ],
                last: true,
            },
        });

        renderHomePage();

        expect(await screen.findByText("practice.mp4")).toBeInTheDocument();
        expect(screen.getByText("총점 85")).toBeInTheDocument();
        expect(
            screen.getByText("다음 연습 포인트: 시선을 카메라에 더 오래 고정해보세요.")
        ).toBeInTheDocument();
        expect(screen.getByText("최근 완료 점수")).toBeInTheDocument();
        expect(screen.getByText("85")).toBeInTheDocument();
    });

    it("prioritizes an in-progress job as the next action while keeping recent completed results", async () => {
        authMock.isAuthenticated = true;
        getResults.mockResolvedValue({
            data: {
                content: [
                    {
                        jobId: "job-running",
                        status: "BASIC_ANALYZING",
                        statusDescription: "기본 분석 중",
                        fileName: "running.mp4",
                    },
                    {
                        jobId: "job-completed",
                        status: "COMPLETED",
                        statusDescription: "분석 완료",
                        fileName: "completed.mp4",
                        scoreSummary: { totalScore: 81 },
                        feedback: { improvements: ["발화 속도를 조금 늦춰보세요."] },
                    },
                ],
                last: true,
            },
        });

        renderHomePage();

        expect(
            await screen.findByText(
                "현재 진행 중인 분석이 1건 있습니다. 결과 목록에서 현재 단계를 확인해보세요."
            )
        ).toBeInTheDocument();
        expect(screen.getByText("running.mp4")).toBeInTheDocument();
        expect(screen.getByText("completed.mp4")).toBeInTheDocument();
    });

    it("treats an uploaded-only job as an action that needs to be resumed", async () => {
        authMock.isAuthenticated = true;
        getResults.mockResolvedValue({
            data: {
                content: [
                    {
                        jobId: "job-uploaded",
                        status: "UPLOADED",
                        statusDescription: "업로드 완료",
                        fileName: "resume.mp4",
                    },
                ],
                last: true,
            },
        });

        renderHomePage();

        expect(
            await screen.findByText(
                "업로드를 마친 분석이 1건 있습니다. 영상 업로드 화면에서 분석을 이어서 시작하세요."
            )
        ).toBeInTheDocument();
        expect(screen.getByText("진행 또는 재개 필요")).toBeInTheDocument();
        expect(screen.getByText("1건")).toBeInTheDocument();
    });

    it("retries the dashboard request after an initial failure", async () => {
        authMock.isAuthenticated = true;
        getResults
            .mockRejectedValueOnce({ message: "temporary failure" })
            .mockResolvedValueOnce({ data: { content: [], last: true } });

        renderHomePage();

        expect(
            await screen.findByText("대시보드를 불러오지 못했습니다.")
        ).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));

        expect(await screen.findByText("첫 분석을 시작해보세요")).toBeInTheDocument();
        expect(getResults).toHaveBeenCalledTimes(2);
    });

    it("does not fetch dashboard results for unauthenticated visitors", () => {
        renderHomePage();

        expect(getResults).not.toHaveBeenCalled();
        expect(screen.queryByText("다시 연습을 이어가세요")).not.toBeInTheDocument();
    });

    it("toggles FAQ answers as a single-open accordion", () => {
        renderHomePage();

        const uploadQuestion = screen.getByRole("button", {
            name: /어떤 영상 파일을 업로드할 수 있나요/,
        });
        const retentionQuestion = screen.getByRole("button", {
            name: /업로드한 영상은 계속 보관되나요/,
        });

        expect(screen.queryByText(/mp4, mov, avi, mkv 형식을 지원하며/))
            .not.toBeInTheDocument();
        expect(screen.queryByText(/분석이 완료된 원본 영상은 30일간 보관/))
            .not.toBeInTheDocument();

        fireEvent.click(uploadQuestion);

        expect(screen.getByText(/mp4, mov, avi, mkv 형식을 지원하며/))
            .toBeInTheDocument();
        expect(screen.queryByText(/분석이 완료된 원본 영상은 30일간 보관/))
            .not.toBeInTheDocument();

        fireEvent.click(retentionQuestion);

        expect(screen.queryByText(/mp4, mov, avi, mkv 형식을 지원하며/))
            .not.toBeInTheDocument();
        expect(screen.getByText(/분석이 완료된 원본 영상은 30일간 보관/))
            .toBeInTheDocument();

        fireEvent.click(retentionQuestion);

        expect(screen.queryByText(/분석이 완료된 원본 영상은 30일간 보관/))
            .not.toBeInTheDocument();
    });
});
