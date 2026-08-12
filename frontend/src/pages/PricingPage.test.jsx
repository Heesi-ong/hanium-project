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
    it("renders the beta usage notice", () => {
        renderPricingPage();

        expect(screen.getByRole("heading", { name: "베타 이용 안내" }))
            .toBeInTheDocument();
        expect(screen.getByText("현재는 결제·유료 요금제가 없는 베타 서비스입니다.", { exact: false }))
            .toBeInTheDocument();
    });

    it("discloses analysis scope and limitations", () => {
        renderPricingPage();

        expect(screen.getByRole("heading", { name: "제공 범위와 한계" }))
            .toBeInTheDocument();
    });

    it("links to the upload page", () => {
        renderPricingPage();

        expect(screen.getByRole("link", { name: "지금 발표 분석 시작하기" }))
            .toHaveAttribute("href", "/upload");
    });
});
