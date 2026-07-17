import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { engineHealthCheck, healthCheck } from "../api/analysisApi";
import HomePage from "./HomePage";

const authMock = vi.hoisted(() => ({
    isAuthenticated: false,
}));

vi.mock("../api/analysisApi", () => ({
    healthCheck: vi.fn(),
    engineHealthCheck: vi.fn(),
}));

vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({
        isAuthenticated: authMock.isAuthenticated,
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
        healthCheck.mockReset();
        engineHealthCheck.mockReset();
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
        expect(engineHealthCheck).not.toHaveBeenCalled();
    });

    it("renders authenticated CTAs without showing signup links", () => {
        authMock.isAuthenticated = true;

        renderHomePage();

        expect(screen.getByRole("link", { name: "영상 업로드 시작" }))
            .toHaveAttribute("href", "/upload");
        expect(screen.getByRole("link", { name: "분석 결과 보기" }))
            .toHaveAttribute("href", "/results");
        expect(screen.getByRole("link", { name: "지금 업로드하기" }))
            .toHaveAttribute("href", "/upload");
        expect(screen.queryByRole("link", { name: "무료로 시작하기" }))
            .not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "무료 회원가입" }))
            .not.toBeInTheDocument();
        expect(healthCheck).not.toHaveBeenCalled();
        expect(engineHealthCheck).not.toHaveBeenCalled();
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
