import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import OpenAiGenerationBadge from "./OpenAiGenerationBadge";

describe("OpenAiGenerationBadge", () => {
    it("uses pipeline OpenAI metadata when feedback metadata is a placeholder", () => {
        render(
            <OpenAiGenerationBadge
                feedback={{
                    generationMode: "UNKNOWN",
                    model: "-",
                    realApiUsed: false,
                }}
                pipeline={{
                    openAiGenerationMode: "REAL",
                    openAiModel: "gpt-4.1-mini",
                    openAiRealApiUsed: true,
                }}
            />
        );

        expect(screen.getByText("REAL")).toBeInTheDocument();
        expect(screen.getByText("실제 OpenAI")).toBeInTheDocument();
        expect(screen.getByText("gpt-4.1-mini · API 사용")).toBeInTheDocument();
    });

    it("keeps direct feedback metadata when it is meaningful", () => {
        render(
            <OpenAiGenerationBadge
                feedback={{
                    generationMode: "MOCK",
                    model: "mock-feedback",
                    realApiUsed: false,
                }}
                pipeline={{
                    openAiGenerationMode: "REAL",
                    openAiModel: "gpt-4.1-mini",
                    openAiRealApiUsed: true,
                }}
            />
        );

        expect(screen.getByText("MOCK")).toBeInTheDocument();
        expect(screen.getByText("Mock")).toBeInTheDocument();
        expect(screen.getByText("mock-feedback · API 미사용")).toBeInTheDocument();
    });
});
