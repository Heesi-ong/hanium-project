import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import Badge from "./Badge";

describe("Badge", () => {
    it("renders children and defaults to the neutral tone", () => {
        render(<Badge>대기</Badge>);

        const badge = screen.getByText("대기");
        expect(badge).toHaveClass("bg-surface-secondary");
    });

    it("applies the success tone class when requested", () => {
        render(<Badge tone="success">완료</Badge>);

        const badge = screen.getByText("완료");
        expect(badge).toHaveClass("text-success");
    });

    it("applies the error tone class when requested", () => {
        render(<Badge tone="error">실패</Badge>);

        const badge = screen.getByText("실패");
        expect(badge).toHaveClass("text-error");
    });

    it("merges an additional className with the tone className", () => {
        render(<Badge className="ml-2">라벨</Badge>);

        const badge = screen.getByText("라벨");
        expect(badge).toHaveClass("ml-2", "bg-surface-secondary");
    });
});
