import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import VideoLlmGenerationBadge from "./VideoLlmGenerationBadge";

describe("VideoLlmGenerationBadge", () => {
    it("renders the visual analysis generation mode from the model payload", () => {
        render(
            <VideoLlmGenerationBadge
                result={{
                    visualAnalysis: {
                        model: {
                            name: "nvidia/nemotron",
                            generationMode: "REAL",
                        },
                    },
                }}
            />
        );

        expect(screen.getByText("REAL")).toBeInTheDocument();
        expect(screen.getByText("실제 Video LLM")).toBeInTheDocument();
        expect(screen.getByText("Video LLM · nvidia/nemotron")).toBeInTheDocument();
    });

    it("falls back to pipeline videoLlmGenerationMode for compact list payloads", () => {
        const result = {
            pipeline: {
                videoLlmGenerationMode: "SKIPPED",
                videoLlmAnalysis: "video-llm skipped: monthly budget exceeded",
            },
        };

        render(<VideoLlmGenerationBadge result={result} />);

        expect(screen.getByText("SKIPPED")).toBeInTheDocument();
        expect(screen.getByText("시각 분석 생략")).toBeInTheDocument();
        expect(
            screen.getByText("Video LLM · video-llm skipped: monthly budget exceeded")
        ).toBeInTheDocument();
    });

    it("ignores placeholder visual metadata when pipeline metadata is more specific", () => {
        render(
            <VideoLlmGenerationBadge
                result={{
                    visualAnalysis: {
                        model: {
                            name: "-",
                            generationMode: "UNKNOWN",
                        },
                    },
                    pipeline: {
                        videoLlmGenerationMode: "FALLBACK",
                        videoLlmAnalysis: "video-llm-engine fallback mock",
                    },
                }}
            />
        );

        expect(screen.getByText("FALLBACK")).toBeInTheDocument();
        expect(screen.getByText("실패 후 샘플 대체")).toBeInTheDocument();
        expect(
            screen.getByText("Video LLM · video-llm-engine fallback mock")
        ).toBeInTheDocument();
    });

    it("renders UNKNOWN when visual generation metadata is missing", () => {
        render(<VideoLlmGenerationBadge result={{}} />);

        expect(screen.getByText("UNKNOWN")).toBeInTheDocument();
        expect(screen.getByText("방식 알 수 없음")).toBeInTheDocument();
    });
});
