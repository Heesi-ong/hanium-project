import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import ProtectedRoute from "./ProtectedRoute";

const authMock = vi.hoisted(() => ({
    isAuthenticated: false,
    isInitializing: false,
}));

vi.mock("../context/AuthContext", () => ({
    useAuth: () => ({
        isAuthenticated: authMock.isAuthenticated,
        isInitializing: authMock.isInitializing,
    }),
}));

function renderProtectedRoute(initialPath = "/upload") {
    return render(
        <MemoryRouter initialEntries={[initialPath]}>
            <Routes>
                <Route element={<ProtectedRoute />}>
                    <Route path="/upload" element={<div>업로드 화면</div>} />
                </Route>
                <Route path="/login" element={<div>로그인 화면</div>} />
            </Routes>
        </MemoryRouter>
    );
}

describe("ProtectedRoute", () => {
    it("waits for auth initialization before redirecting", () => {
        authMock.isAuthenticated = false;
        authMock.isInitializing = true;

        renderProtectedRoute();

        expect(screen.queryByText("업로드 화면")).not.toBeInTheDocument();
        expect(screen.queryByText("로그인 화면")).not.toBeInTheDocument();
    });

    it("redirects unauthenticated users after initialization", () => {
        authMock.isAuthenticated = false;
        authMock.isInitializing = false;

        renderProtectedRoute();

        expect(screen.getByText("로그인 화면")).toBeInTheDocument();
    });

    it("renders protected content for authenticated users", () => {
        authMock.isAuthenticated = true;
        authMock.isInitializing = false;

        renderProtectedRoute();

        expect(screen.getByText("업로드 화면")).toBeInTheDocument();
    });
});
