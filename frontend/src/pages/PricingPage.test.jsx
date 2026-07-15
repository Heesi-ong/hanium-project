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
    it("renders the pricing placeholder notice", () => {
        renderPricingPage();

        expect(screen.getByRole("heading", { name: "요금제" }))
            .toBeInTheDocument();
        expect(screen.getByText("요금제는 아직 준비 중입니다. 실제 요금, 결제 수단, 등급별 제공 범위는 서비스 정식", { exact: false }))
            .toBeInTheDocument();
    });

    it("links to the upload page", () => {
        renderPricingPage();

        expect(screen.getByRole("link", { name: "지금 발표 분석 시작하기" }))
            .toHaveAttribute("href", "/upload");
    });
});
