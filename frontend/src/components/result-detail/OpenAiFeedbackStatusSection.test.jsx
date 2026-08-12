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

        expect(screen.getByText("실제 OpenAI API")).toBeInTheDocument();
        expect(screen.getByText("gpt-4.1-mini")).toBeInTheDocument();

        const realApiCard = screen.getByText("실제 API 사용 여부").closest("article");
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

        expect(screen.getByText("OpenAI 실패 후 Mock 대체")).toBeInTheDocument();
        expect(screen.getByText("gpt-4o-mini")).toBeInTheDocument();
        expect(screen.getByText("OpenAI API timeout")).toBeInTheDocument();

        const realApiCard = screen.getByText("실제 API 사용 여부").closest("article");
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

        expect(screen.getByText("OpenAI 피드백 사용 안 함")).toBeInTheDocument();
        expect(
            screen.getByText("사용자 설정에 따라 OpenAI 피드백 생성을 건너뛰었습니다.")
        ).toBeInTheDocument();
        expect(
            screen.getByText("사용자 설정으로 OpenAI 피드백 생성이 비활성화되었습니다.")
        ).toBeInTheDocument();
        expect(screen.getByText("미사용·대체 사유")).toBeInTheDocument();
    });
});
