import React from "react";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import StateMessage from "./StateMessage";

describe("StateMessage", () => {
  it("uses alert semantics for errors", () => {
    render(<StateMessage type="error">요청에 실패했습니다.</StateMessage>);

    expect(screen.getByRole("alert")).toHaveTextContent("요청에 실패했습니다.");
  });

  it("renders an action", () => {
    render(<StateMessage actions={<button>다시 시도</button>}>내용</StateMessage>);

    expect(screen.getByRole("button", { name: "다시 시도" })).toBeInTheDocument();
  });
});
