import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import PipelineSection from "./PipelineSection";

function openPipelineDetails(container) {
    const details = container.querySelector("details");
    details.open = true;
    fireEvent(details, new Event("toggle"));
}

describe("PipelineSection", () => {
    it("lists scalar pipeline entries inside the collapsible detail block", () => {
        const { container } = render(
            <PipelineSection pipeline={{ openAiGenerationMode: "REAL", totalDurationMs: 1234 }} />
        );

        openPipelineDetails(container);

        expect(screen.getByText("openAiGenerationMode")).toBeInTheDocument();
        expect(screen.getByText("REAL")).toBeInTheDocument();
        expect(screen.getByText("totalDurationMs")).toBeInTheDocument();
        expect(screen.getByText("1234")).toBeInTheDocument();
        expect(screen.getByText("2개 항목")).toBeInTheDocument();
        expect(screen.getAllByRole("term")).toHaveLength(2);
    });

    it("renders nested object entries as formatted JSON", () => {
        const { container } = render(
            <PipelineSection pipeline={{ steps: { frameExtraction: "ok" } }} />
        );

        openPipelineDetails(container);

        expect(screen.getByText("steps")).toBeInTheDocument();
        expect(screen.getByText(/"frameExtraction": "ok"/)).toBeInTheDocument();
        expect(screen.getByLabelText("steps 상세 값")).toHaveAttribute("tabindex", "0");
    });

    it("shows guidance when there is no pipeline information", () => {
        render(<PipelineSection pipeline={{}} />);

        expect(screen.getByText("표시할 파이프라인 정보가 없습니다.")).toBeInTheDocument();
        expect(screen.getByText("0개 항목")).toBeInTheDocument();
    });

    it("shows guidance when pipeline is not provided", () => {
        render(<PipelineSection />);

        expect(screen.getByText("표시할 파이프라인 정보가 없습니다.")).toBeInTheDocument();
    });
});
