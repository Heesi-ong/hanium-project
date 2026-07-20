import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import CollapsibleDetails from "./CollapsibleDetails";

// jsdom은 실제 브라우저와 달리 <summary> 클릭 시 <details>의 open을 자동으로
// 토글하지도, toggle 이벤트를 발생시키지도 않는다. 그래서 실제 브라우저가 하는
// 일(open 속성 변경 + toggle 이벤트 발생)을 테스트에서 직접 시뮬레이션한다.
function toggleDetails(container, open) {
    const details = container.querySelector("details");
    details.open = open;
    fireEvent(details, new Event("toggle"));
}

describe("CollapsibleDetails", () => {
    it("does not mount children until opened for the first time", () => {
        render(
            <CollapsibleDetails summary="자세히 보기">
                <p>지연 마운트되는 내용</p>
            </CollapsibleDetails>
        );

        expect(screen.queryByText("지연 마운트되는 내용")).not.toBeInTheDocument();
    });

    it("mounts children once the summary is opened", () => {
        const { container } = render(
            <CollapsibleDetails summary="자세히 보기">
                <p>지연 마운트되는 내용</p>
            </CollapsibleDetails>
        );

        toggleDetails(container, true);

        expect(screen.getByText("지연 마운트되는 내용")).toBeInTheDocument();
    });

    it("keeps children mounted after closing again", () => {
        const { container } = render(
            <CollapsibleDetails summary="자세히 보기">
                <p>지연 마운트되는 내용</p>
            </CollapsibleDetails>
        );

        toggleDetails(container, true);
        toggleDetails(container, false);

        expect(screen.getByText("지연 마운트되는 내용")).toBeInTheDocument();
    });

    it("applies role=heading with the given level when headingLevel is set", () => {
        render(
            <CollapsibleDetails summary="자세히 보기" headingLevel={3}>
                <p>내용</p>
            </CollapsibleDetails>
        );

        const summary = screen.getByText("자세히 보기");
        expect(summary).toHaveAttribute("role", "heading");
        expect(summary).toHaveAttribute("aria-level", "3");
    });
});
