import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import AppRoutes from "./AppRoutes";

const authMock = vi.hoisted(() => ({
    isAuthenticated: false,
    isInitializing: false,
    user: null,
}));

beforeEach(() => {
    authMock.isAuthenticated = false;
    authMock.isInitializing = false;
    authMock.user = null;
});

vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({
        isAuthenticated: authMock.isAuthenticated,
        isInitializing: authMock.isInitializing,
        logout: vi.fn(),
        user: authMock.user,
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

// AdminRoute.test.jsx/ProtectedRoute.test.jsx는 가드 컴포넌트 자체를 합성
// 라우트로 격리해 검증한다. 여기서는 실제 AppRoutes 트리에서 /admin, /upload
// 같은 실제 경로가 정말로 그 가드로 감싸져 있는지(배선 자체)를 확인한다 —
// 예를 들어 나중에 누군가 실수로 <AdminRoute>...</AdminRoute> 밖으로 /admin을
// 옮겨도 격리된 가드 테스트만으로는 잡을 수 없는 종류의 회귀다.
describe("AppRoutes route guard wiring", () => {
    it("redirects unauthenticated users away from /upload to /login", async () => {
        authMock.isAuthenticated = false;
        authMock.isInitializing = false;
        authMock.user = null;

        renderAppRoutes("/upload");

        await waitFor(() => {
            expect(screen.getByRole("heading", { name: "로그인" })).toBeInTheDocument();
        });
    });

    it("redirects unauthenticated users away from /admin to /login", async () => {
        authMock.isAuthenticated = false;
        authMock.isInitializing = false;
        authMock.user = null;

        renderAppRoutes("/admin");

        await waitFor(() => {
            expect(screen.getByRole("heading", { name: "로그인" })).toBeInTheDocument();
        });
    });

    it("redirects authenticated non-admin users away from /admin to home", async () => {
        authMock.isAuthenticated = true;
        authMock.isInitializing = false;
        authMock.user = { id: 1, email: "user@example.com", admin: false };

        renderAppRoutes("/admin");

        // 리다이렉트 대상(HomePage)이 lazy(() => import(...)) 청크이고 로그인 사용자는
        // 공개 랜딩이 아니라 개인 대시보드를 렌더링한다. 전체 스위트를
        // 47개 파일과 함께 병렬로 돌릴 때 CPU 경합으로 청크 로드가 waitFor 기본
        // 타임아웃(1000ms)을 넘길 수 있다(단독 실행 시엔 항상 통과, 전체 스위트에서만
        // 간헐적으로 실패하는 것을 확인, 2026-08-01). 리다이렉트 로직 자체를 느슨하게
        // 검증하는 게 아니라 순수 타이밍 여유만 늘린다.
        await waitFor(
            () => {
                expect(
                    screen.getByRole("heading", { name: "다시 연습을 이어가세요" })
                ).toBeInTheDocument();
            },
            { timeout: 5000 }
        );
    });
});
