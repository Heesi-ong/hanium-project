import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

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
    it("renders public CTA buttons for unauthenticated users without loading status data", () => {
        authMock.isAuthenticated = false;

        renderHomePage();

        expect(screen.getByRole("heading", {
            name: /발표는 감이 아니라,\s*데이터로 개선합니다\./,
        })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "이용 방법" })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "분석 항목 상세 소개" })).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "무료로 시작하기" }))
            .toHaveAttribute("href", "/signup");
        expect(screen.getByRole("link", { name: "로그인" }))
            .toHaveAttribute("href", "/login");
        expect(screen.queryByRole("link", { name: "영상 업로드 시작" }))
            .not.toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "분석 결과 보기" }))
            .not.toBeInTheDocument();
        expect(screen.queryByText("서버 및 엔진 상태")).not.toBeInTheDocument();
        expect(healthCheck).not.toHaveBeenCalled();
        expect(engineHealthCheck).not.toHaveBeenCalled();
    });

    it("renders analysis CTA buttons for authenticated users", () => {
        authMock.isAuthenticated = true;

        renderHomePage();

        expect(screen.getByRole("heading", {
            name: /발표는 감이 아니라,\s*데이터로 개선합니다\./,
        })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "이용 방법" })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "분석 항목 상세 소개" })).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "영상 업로드 시작" }))
            .toHaveAttribute("href", "/upload");
        expect(screen.getByRole("link", { name: "분석 결과 보기" }))
            .toHaveAttribute("href", "/results");
        expect(screen.queryByRole("link", { name: "무료로 시작하기" }))
            .not.toBeInTheDocument();
        expect(healthCheck).not.toHaveBeenCalled();
        expect(engineHealthCheck).not.toHaveBeenCalled();
    });
});
