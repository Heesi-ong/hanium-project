import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import TimelineFeedbackSection from "./TimelineFeedbackSection";

describe("TimelineFeedbackSection", () => {
    it("renders feedback as an ordered coaching list with category, title, and recommendation", () => {
        render(
            <TimelineFeedbackSection
                timelineFeedback={[
                    { category: "속도", title: "말하기 속도가 빨라요", recommendation: "호흡을 크게 쉬어보세요." },
                ]}
            />
        );

        expect(screen.getByText("속도")).toBeInTheDocument();
        expect(screen.getByText("말하기 속도가 빨라요")).toBeInTheDocument();
        expect(screen.getByText("호흡을 크게 쉬어보세요.")).toBeInTheDocument();
        expect(screen.getByRole("list", { name: "발표 타임라인 피드백" })).toBeInTheDocument();
        expect(screen.getAllByRole("listitem")).toHaveLength(1);
        expect(screen.getByText("1개 피드백")).toBeInTheDocument();
    });

    it("falls back to default labels when fields are missing", () => {
        render(<TimelineFeedbackSection timelineFeedback={[{}]} />);

        expect(screen.getByText("feedback")).toBeInTheDocument();
        expect(screen.getByText("요약 정보가 없습니다.")).toBeInTheDocument();
        expect(screen.getByText("-")).toBeInTheDocument();
    });

    it("uses summary as the title when title is missing", () => {
        render(
            <TimelineFeedbackSection
                timelineFeedback={[{ category: "자세", summary: "어깨가 기울어져 있어요" }]}
            />
        );

        expect(screen.getByText("어깨가 기울어져 있어요")).toBeInTheDocument();
    });

    it("shows guidance when there is no timeline feedback", () => {
        render(<TimelineFeedbackSection timelineFeedback={[]} />);

        expect(screen.getByText("표시할 타임라인 피드백이 없습니다.")).toBeInTheDocument();
        expect(screen.getByText("0개 피드백")).toBeInTheDocument();
    });
});
