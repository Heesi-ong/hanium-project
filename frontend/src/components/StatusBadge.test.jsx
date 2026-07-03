import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import StatusBadge from "./StatusBadge";

describe("StatusBadge", () => {
    it.each([
        ["COMPLETED", "status-badge completed", "COMPLETED"],
        ["FAILED", "status-badge failed", "FAILED"],
        ["CANCELLED", "status-badge cancelled", "CANCELLED"],
        ["BASIC_ANALYZING", "status-badge running", "BASIC_ANALYZING"],
        ["VIDEO_LLM_ANALYZING", "status-badge running", "VIDEO_LLM_ANALYZING"],
        ["COMPACTING", "status-badge running", "COMPACTING"],
        ["OPENAI_GENERATING", "status-badge running", "OPENAI_GENERATING"],
        ["MERGING_RESULT", "status-badge running", "MERGING_RESULT"],
        ["UPLOADED", "status-badge uploaded", "UPLOADED"],
    ])("renders %s with %s", (status, expectedClassName, expectedText) => {
        render(<StatusBadge status={status} />);

        const badge = screen.getByText(expectedText);
        expect(badge).toHaveClass(...expectedClassName.split(" "));
    });

    it("uses label when provided", () => {
        render(<StatusBadge status="COMPLETED" label="완료" />);

        const badge = screen.getByText("완료");
        expect(badge).toHaveClass("status-badge", "completed");
    });

    it("renders fallback text and uploaded class for missing status", () => {
        render(<StatusBadge />);

        const badge = screen.getByText("-");
        expect(badge).toHaveClass("status-badge", "uploaded");
    });

    it("uses uploaded class for unknown status", () => {
        render(<StatusBadge status="UNKNOWN" />);

        const badge = screen.getByText("UNKNOWN");
        expect(badge).toHaveClass("status-badge", "uploaded");
    });
});
