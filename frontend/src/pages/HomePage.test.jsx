import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";

import { engineHealthCheck, healthCheck } from "../api/analysisApi";
import HomePage from "./HomePage";

vi.mock("../api/analysisApi", () => ({
    healthCheck: vi.fn(),
    engineHealthCheck: vi.fn(),
}));

function renderHomePage() {
    return render(
        <MemoryRouter>
            <HomePage />
        </MemoryRouter>
    );
}

describe("HomePage", () => {
    it("renders the hero and new informational sections without loading status data", () => {
        renderHomePage();

        expect(screen.getByRole("heading", {
            name: /발표는 감이 아니라,\s*데이터로 개선합니다\./,
        })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "이용 방법" })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "분석 항목 상세 소개" })).toBeInTheDocument();
        expect(screen.queryByText("서버 및 엔진 상태")).not.toBeInTheDocument();
        expect(healthCheck).not.toHaveBeenCalled();
        expect(engineHealthCheck).not.toHaveBeenCalled();
    });
});
