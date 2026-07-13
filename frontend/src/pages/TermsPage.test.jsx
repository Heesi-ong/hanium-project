import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import TermsPage from "./TermsPage";

function renderTermsPage() {
    return render(
        <MemoryRouter>
            <TermsPage />
        </MemoryRouter>
    );
}

describe("TermsPage", () => {
    it("renders business operator placeholders", () => {
        renderTermsPage();

        expect(screen.getByRole("heading", { name: "사업자 정보" }))
            .toBeInTheDocument();
        expect(screen.getByText("상호: [실제 서비스명/사업자명 입력 필요]"))
            .toBeInTheDocument();
        expect(screen.getByText("전화: [실제 연락처 입력 필요]"))
            .toBeInTheDocument();
    });
});
