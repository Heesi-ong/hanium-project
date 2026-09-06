import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import OpenAiFeedbackStatusSection from "./OpenAiFeedbackStatusSection";

describe("OpenAiFeedbackStatusSection", () => {
    it("uses pipeline OpenAI metadata when feedback metadata is a placeholder", () => {
        render(
            <OpenAiFeedbackStatusSection
                feedback={{
                    generationMode: "UNKNOWN",
                    model: "-",
                    realApiUsed: false,
                    fallbackReason: "-",
                }}
                pipeline={{
                    openAiGenerationMode: "REAL",
                    openAiModel: "gpt-4.1-mini",
                    openAiRealApiUsed: true,
                }}
            />
        );

        expect(screen.getAllByText("실제 OpenAI API").length).toBeGreaterThan(0);
        expect(screen.getByText("gpt-4.1-mini")).toBeInTheDocument();

        const realApiCard = screen.getByText("실제 API 응답 사용").closest("div");
        expect(within(realApiCard).getByText("예")).toBeInTheDocument();
    });

    it("keeps direct feedback metadata when it is meaningful", () => {
        render(
            <OpenAiFeedbackStatusSection
                feedback={{
                    generationMode: "FALLBACK",
                    model: "gpt-4o-mini",
                    realApiUsed: false,
                    fallbackReason: "OpenAI API timeout",
                }}
                pipeline={{
                    openAiGenerationMode: "REAL",
                    openAiModel: "gpt-4.1-mini",
                    openAiRealApiUsed: true,
                }}
            />
        );

        expect(screen.getAllByText("OpenAI 실패 후 Mock 대체").length).toBeGreaterThan(0);
        expect(screen.getByText("gpt-4o-mini")).toBeInTheDocument();
        expect(screen.getByText("OpenAI API timeout")).toBeInTheDocument();

        const realApiCard = screen.getByText("실제 API 응답 사용").closest("div");
        expect(within(realApiCard).getByText("아니오")).toBeInTheDocument();
    });

    it("explains that OpenAI feedback was skipped by user configuration", () => {
        render(
            <OpenAiFeedbackStatusSection
                feedback={{
                    generationMode: "SKIPPED",
                    model: null,
                    realApiUsed: false,
                    fallbackReason: "사용자 설정으로 OpenAI 피드백 생성이 비활성화되었습니다.",
                }}
                pipeline={{}}
            />
        );

        expect(screen.getAllByText("OpenAI 피드백 사용 안 함").length).toBeGreaterThan(0);
        expect(
            screen.getByText("사용자 설정에 따라 OpenAI 피드백 생성을 건너뛰었습니다.")
        ).toBeInTheDocument();
        expect(
            screen.getByText("사용자 설정으로 OpenAI 피드백 생성이 비활성화되었습니다.")
        ).toBeInTheDocument();
        expect(screen.getByText("미사용·대체 사유")).toBeInTheDocument();
    });

    it("does not present missing API usage metadata as a definite no", () => {
        render(
            <OpenAiFeedbackStatusSection
                feedback={{ generationMode: "UNKNOWN" }}
                pipeline={{}}
            />
        );

        const realApiItem = screen.getByText("실제 API 응답 사용").closest("div");
        expect(within(realApiItem).getByText("확인 불가")).toBeInTheDocument();
        expect(within(realApiItem).queryByText("아니오")).not.toBeInTheDocument();
        expect(
            screen.getByText("외부 OpenAI API 실행 여부를 결과 데이터에서 확인할 수 없습니다.")
        ).toBeInTheDocument();
    });

    it("distinguishes a fallback attempt from the response used in the final feedback", () => {
        render(
            <OpenAiFeedbackStatusSection
                feedback={{
                    generationMode: "FALLBACK",
                    realApiUsed: false,
                    fallbackReason: "OpenAI API timeout",
                }}
                pipeline={{}}
            />
        );

        expect(
            screen.getByText(/외부 OpenAI API 호출을 시도했지만 최종 피드백은 내부 대체 응답/)
        ).toBeInTheDocument();
        expect(screen.getByText("OpenAI API timeout")).toBeInTheDocument();
    });
});
