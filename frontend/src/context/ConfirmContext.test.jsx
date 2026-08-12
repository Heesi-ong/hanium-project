import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { ConfirmProvider, useReasonPrompt } from "./ConfirmContext";

function ReasonPromptProbe() {
    const promptReason = useReasonPrompt();

    return (
        <button
            type="button"
            onClick={async () => {
                const actionContext = await promptReason("이 계정을 정지합니다.");
                document.title = actionContext === null
                    ? "취소됨"
                    : `사유: ${actionContext.reason}, 참조: ${actionContext.incidentId || "없음"}`;
            }}
        >
            정지
        </button>
    );
}

function renderProbe() {
    return render(
        <ConfirmProvider>
            <ReasonPromptProbe />
        </ConfirmProvider>
    );
}

describe("useReasonPrompt", () => {
    it("shows the target/impact message passed to the prompt", () => {
        renderProbe();

        fireEvent.click(screen.getByRole("button", { name: "정지" }));

        expect(screen.getByText("이 계정을 정지합니다.")).toBeInTheDocument();
    });

    it("keeps the confirm button disabled until a non-blank reason is entered", () => {
        renderProbe();

        fireEvent.click(screen.getByRole("button", { name: "정지" }));

        const confirmButton = screen.getByRole("button", { name: "확인" });
        expect(confirmButton).toBeDisabled();

        fireEvent.change(screen.getByPlaceholderText("이 조치를 실행하는 이유를 입력해주세요."), {
            target: { value: "   " },
        });
        expect(confirmButton).toBeDisabled();

        fireEvent.change(screen.getByPlaceholderText("이 조치를 실행하는 이유를 입력해주세요."), {
            target: { value: "어뷰징 신고" },
        });
        expect(confirmButton).toBeEnabled();
    });

    it("resolves with the trimmed reason and optional incident id when confirmed", async () => {
        renderProbe();

        fireEvent.click(screen.getByRole("button", { name: "정지" }));
        fireEvent.change(screen.getByPlaceholderText("이 조치를 실행하는 이유를 입력해주세요."), {
            target: { value: "  어뷰징 신고  " },
        });
        fireEvent.change(screen.getByPlaceholderText("예: INC-2026-001 또는 문의 티켓 번호"), {
            target: { value: "  INC-2001  " },
        });
        fireEvent.click(screen.getByRole("button", { name: "확인" }));

        await waitFor(() => expect(document.title).toBe("사유: 어뷰징 신고, 참조: INC-2001"));
        expect(screen.queryByText("이 계정을 정지합니다.")).not.toBeInTheDocument();
    });

    it("omits a blank optional incident id", async () => {
        renderProbe();

        fireEvent.click(screen.getByRole("button", { name: "정지" }));
        fireEvent.change(screen.getByPlaceholderText("이 조치를 실행하는 이유를 입력해주세요."), {
            target: { value: "정책 위반" },
        });
        fireEvent.change(screen.getByPlaceholderText("예: INC-2026-001 또는 문의 티켓 번호"), {
            target: { value: "   " },
        });
        fireEvent.click(screen.getByRole("button", { name: "확인" }));

        await waitFor(() => expect(document.title).toBe("사유: 정책 위반, 참조: 없음"));
    });

    it("resolves with null when cancelled", async () => {
        renderProbe();

        fireEvent.click(screen.getByRole("button", { name: "정지" }));
        fireEvent.change(screen.getByPlaceholderText("이 조치를 실행하는 이유를 입력해주세요."), {
            target: { value: "어뷰징 신고" },
        });
        fireEvent.click(screen.getByRole("button", { name: "취소" }));

        await waitFor(() => expect(document.title).toBe("취소됨"));
    });

    it("throws when used outside ConfirmProvider", () => {
        const consoleErrorSpy = vi.spyOn(console, "error").mockImplementation(() => {});

        expect(() => render(<ReasonPromptProbe />)).toThrow(
            "useReasonPrompt must be used within ConfirmProvider"
        );

        consoleErrorSpy.mockRestore();
    });
});
