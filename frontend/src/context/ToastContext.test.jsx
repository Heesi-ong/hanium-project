import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ToastProvider, useToast } from "./ToastContext";

function ToastProbe() {
    const { toasts, showToast, dismissToast } = useToast();

    return (
        <div>
            <button
                type="button"
                onClick={() => showToast("저장되었습니다.", "success")}
            >
                토스트 표시
            </button>
            {toasts.map((toast) => (
                <div key={toast.id}>
                    <span>{toast.message}</span>
                    <button type="button" onClick={() => dismissToast(toast.id)}>
                        닫기
                    </button>
                </div>
            ))}
        </div>
    );
}

describe("ToastContext", () => {
    afterEach(() => {
        vi.useRealTimers();
    });

    it("adds a toast and removes it automatically after 4000ms", () => {
        vi.useFakeTimers();

        render(
            <ToastProvider>
                <ToastProbe />
            </ToastProvider>
        );

        fireEvent.click(screen.getByRole("button", { name: "토스트 표시" }));

        expect(screen.getByText("저장되었습니다.")).toBeInTheDocument();

        act(() => {
            vi.advanceTimersByTime(4000);
        });

        expect(screen.queryByText("저장되었습니다.")).not.toBeInTheDocument();
    });

    it("dismisses a toast immediately", () => {
        vi.useFakeTimers();

        render(
            <ToastProvider>
                <ToastProbe />
            </ToastProvider>
        );

        fireEvent.click(screen.getByRole("button", { name: "토스트 표시" }));
        fireEvent.click(screen.getByRole("button", { name: "닫기" }));

        expect(screen.queryByText("저장되었습니다.")).not.toBeInTheDocument();
    });
});
