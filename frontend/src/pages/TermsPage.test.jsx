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
    it("renders the student project usage guide", () => {
        renderTermsPage();

        expect(screen.getByRole("heading", { name: "프로젝트 이용 안내" }))
            .toBeInTheDocument();
        expect(screen.getByText(/공개 상용 서비스의 이용약관이 아닙니다/))
            .toBeInTheDocument();
    });

    it("requires authorized videos and explains external AI", () => {
        renderTermsPage();

        expect(screen.getByText(/필요한 권한과 동의를 확보한 영상만/)).toBeInTheDocument();
        expect(screen.getByText(/mock 모드로만 테스트/)).toBeInTheDocument();
    });
});
