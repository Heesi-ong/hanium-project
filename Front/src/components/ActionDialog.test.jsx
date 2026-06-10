import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import ActionDialog from "./ActionDialog";

describe("ActionDialog", () => {
  it("exposes an accessible dialog and confirms a text value", () => {
    const onConfirm = vi.fn();
    render(
      <ActionDialog
        open
        title="새 대화 이름"
        initialValue="기존 이름"
        onCancel={vi.fn()}
        onConfirm={onConfirm}
      />,
    );

    expect(screen.getByRole("dialog", { name: "새 대화 이름" })).toBeInTheDocument();
    fireEvent.change(screen.getByRole("textbox", { name: "새 대화 이름" }), {
      target: { value: "변경 이름" },
    });
    fireEvent.click(screen.getByRole("button", { name: "확인" }));

    expect(onConfirm).toHaveBeenCalledWith("변경 이름");
  });
});
