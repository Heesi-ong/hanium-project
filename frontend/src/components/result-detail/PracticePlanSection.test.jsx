import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import PracticePlanSection from "./PracticePlanSection";

describe("PracticePlanSection", () => {
    it("renders the server-provided practice sequence without changing its content", () => {
        render(
            <PracticePlanSection
                practicePlan={[
                    {
                        title: "핵심 문장 앞에서 멈추기",
                        description: "핵심 수치를 말하기 전에 1초간 멈춥니다.",
                        duration: "3분",
                    },
                    {
                        title: "시선 전환 연습",
                        description: "화면에서 청중으로 시선을 되돌립니다.",
                        duration: "5분",
                    },
                ]}
            />
        );

        expect(screen.getByText("2단계")).toBeInTheDocument();
        const list = screen.getByRole("list");
        expect(within(list).getAllByRole("listitem")).toHaveLength(2);
        expect(screen.getByText("핵심 문장 앞에서 멈추기")).toBeInTheDocument();
        expect(screen.getByText("핵심 수치를 말하기 전에 1초간 멈춥니다.")).toBeInTheDocument();
        expect(screen.getByText("3분")).toBeInTheDocument();
    });

    it("shows an explicit empty state when no plan is available", () => {
        render(<PracticePlanSection practicePlan={[]} />);

        expect(screen.getByText("계획 없음")).toBeInTheDocument();
        expect(screen.getByText("표시할 연습 계획이 없습니다.")).toBeInTheDocument();
        expect(
            screen.getByText("분석 결과에 연습 항목이 포함되면 단계별로 표시됩니다.")
        ).toBeInTheDocument();
    });
});
