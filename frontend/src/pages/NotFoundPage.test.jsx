import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import NotFoundPage from "./NotFoundPage";

function renderNotFoundPage() {
    return render(
        <MemoryRouter>
            <NotFoundPage />
        </MemoryRouter>
    );
}

describe("NotFoundPage", () => {
    it("renders a not found message and a link back to home", () => {
        renderNotFoundPage();

        expect(screen.getByRole("heading", {
            name: "페이지를 찾을 수 없습니다",
        })).toBeInTheDocument();
        expect(screen.getByText("잘못된 주소로 접근했습니다.")).toBeInTheDocument();

        const homeLink = screen.getByRole("link", { name: "홈으로 돌아가기" });
        expect(homeLink).toHaveAttribute("href", "/");
    });
});
