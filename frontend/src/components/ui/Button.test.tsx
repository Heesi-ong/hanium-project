import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import Button from "./Button";

describe("Button", () => {
    it("renders children and defaults to the primary variant", () => {
        render(<Button>저장</Button>);

        const button = screen.getByRole("button", { name: "저장" });
        expect(button).toHaveClass("bg-primary-deep");
    });

    it("applies the secondary variant class when requested", () => {
        render(<Button variant="secondary">취소</Button>);

        const button = screen.getByRole("button", { name: "취소" });
        expect(button).toHaveClass("bg-surface-secondary");
    });

    it("merges an additional className with the variant className", () => {
        render(<Button className="w-full">전체 너비</Button>);

        const button = screen.getByRole("button", { name: "전체 너비" });
        expect(button).toHaveClass("w-full", "bg-primary-deep");
    });

    it("forwards native button attributes such as disabled", () => {
        render(<Button disabled>비활성</Button>);

        expect(screen.getByRole("button", { name: "비활성" })).toBeDisabled();
    });
});
