import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import AppRoutes from "./AppRoutes";

vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({
        isAuthenticated: false,
        isInitializing: false,
        logout: vi.fn(),
        user: null,
    }),
}));

function renderAppRoutes(path) {
    return render(
        <MemoryRouter initialEntries={[path]}>
            <AppRoutes />
        </MemoryRouter>
    );
}

describe("AppRoutes public policy pages", () => {
    it("renders privacy page without authentication", async () => {
        renderAppRoutes("/privacy");

        await waitFor(() => {
            expect(screen.getByRole("heading", { name: "개인정보처리방침" }))
                .toBeInTheDocument();
        });
        expect(screen.getByText(/OpenAI 및 NVIDIA API로 전송될 수 있습니다/))
            .toBeInTheDocument();
    });

    it("renders terms page without authentication", async () => {
        renderAppRoutes("/terms");

        await waitFor(() => {
            expect(screen.getByRole("heading", { name: "이용약관" }))
                .toBeInTheDocument();
        });
        expect(screen.getByText(/외부 AI API로 영상 또는 분석 데이터가 전송될 수 있다는 점/))
            .toBeInTheDocument();
    });

    it("renders forgot password page without authentication", async () => {
        renderAppRoutes("/forgot-password");

        await waitFor(() => {
            expect(screen.getByRole("heading", { name: "비밀번호 재설정" }))
                .toBeInTheDocument();
        });
    });

    it("renders reset password page without authentication", async () => {
        renderAppRoutes("/reset-password?token=abc");

        await waitFor(() => {
            expect(screen.getByRole("heading", { name: "새 비밀번호 설정" }))
                .toBeInTheDocument();
        });
    });
});
