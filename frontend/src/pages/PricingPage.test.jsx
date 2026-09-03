import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import PricingPage from "./PricingPage";

function renderPricingPage() {
    return render(
        <MemoryRouter>
            <PricingPage />
        </MemoryRouter>
    );
}

describe("PricingPage", () => {
    it("renders the local project test guide", () => {
        renderPricingPage();

        expect(screen.getByRole("heading", { name: "프로젝트 테스트 안내", level: 1 }))
            .toBeInTheDocument();
        expect(screen.getByText(/결제·요금제·공개 회원 모집 없이/)).toBeInTheDocument();
        expect(screen.getByRole("navigation", { name: "프로젝트 테스트 안내 목차" }))
            .toBeInTheDocument();
    });

    it("distinguishes local analysis and optional external AI", () => {
        renderPricingPage();

        expect(screen.getByRole("heading", { name: "분석 모드", level: 2 }))
            .toBeInTheDocument();
        expect(screen.getByText(/mock\/fallback 결과와 실제 호출 결과/)).toBeInTheDocument();
    });

    it("links to the upload page", () => {
        renderPricingPage();

        expect(screen.getByRole("link", { name: "로컬 테스트 시작하기" }))
            .toHaveAttribute("href", "/upload");
    });
});
