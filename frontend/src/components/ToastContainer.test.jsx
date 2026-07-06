import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import ToastContainer from "./ToastContainer";
import { ToastProvider, useToast } from "../context/ToastContext";

function ToastControls() {
    const { showToast } = useToast();

    return (
        <div>
            <button
                type="button"
                onClick={() => showToast("작업이 완료되었습니다.", "success")}
            >
                성공 토스트
            </button>
            <button
                type="button"
                onClick={() => showToast("작업에 실패했습니다.", "error")}
            >
                에러 토스트
            </button>
        </div>
    );
}

function renderToastContainer() {
    return render(
        <ToastProvider>
            <ToastControls />
            <ToastContainer />
        </ToastProvider>
    );
}

describe("ToastContainer", () => {
    it("renders non-error toasts as polite status updates", () => {
        renderToastContainer();

        fireEvent.click(screen.getByRole("button", { name: "성공 토스트" }));

        const toast = screen.getByRole("status");
        expect(toast).toHaveAttribute("aria-live", "polite");
        expect(toast).toHaveTextContent("작업이 완료되었습니다.");
    });

    it("renders error toasts as alerts and supports dismissal", () => {
        renderToastContainer();

        fireEvent.click(screen.getByRole("button", { name: "에러 토스트" }));

        expect(screen.getByRole("alert")).toHaveTextContent("작업에 실패했습니다.");

        fireEvent.click(screen.getByRole("button", { name: "알림 닫기" }));

        expect(screen.queryByText("작업에 실패했습니다.")).not.toBeInTheDocument();
    });
});
