import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import StateMessage from "./StateMessage";

describe("StateMessage", () => {
    it.each(["error", "failure"])("renders %s messages as alerts", (type) => {
        render(<StateMessage type={type}>문제가 발생했습니다.</StateMessage>);

        expect(screen.getByRole("alert")).toHaveTextContent("문제가 발생했습니다.");
    });

    it.each(["success", "polling", "info"])(
        "renders %s messages as polite status updates",
        (type) => {
            render(<StateMessage type={type}>상태가 변경되었습니다.</StateMessage>);

            const message = screen.getByRole("status");
            expect(message).toHaveAttribute("aria-live", "polite");
            expect(message).toHaveTextContent("상태가 변경되었습니다.");
        }
    );

    it("renders nothing without children", () => {
        const { container } = render(<StateMessage type="info" />);

        expect(container).toBeEmptyDOMElement();
    });
});
