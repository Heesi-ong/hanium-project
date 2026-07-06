import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { engineHealthCheck, healthCheck } from "../api/analysisApi";
import StatusPage from "./StatusPage";

vi.mock("../api/analysisApi", () => ({
    healthCheck: vi.fn(),
    engineHealthCheck: vi.fn(),
}));

describe("StatusPage", () => {
    beforeEach(() => {
        healthCheck.mockReset();
        engineHealthCheck.mockReset();

        healthCheck.mockResolvedValue({
            data: {
                status: "ok",
            },
        });

        engineHealthCheck.mockResolvedValue({
            data: {
                analysisEngine: {
                    baseUrl: "http://analysis-engine:8001",
                    health: {
                        status: "up",
                        reachable: true,
                    },
                },
                videoLlmEngine: {
                    baseUrl: "http://video-llm-engine:8002",
                    health: {
                        status: "down",
                        reachable: false,
                    },
                },
            },
        });
    });

    it("loads and renders backend and engine status cards", async () => {
        render(<StatusPage />);

        expect(screen.getByRole("heading", { name: "시스템 상태" })).toBeInTheDocument();

        expect(await screen.findByText("Spring Boot Backend")).toBeInTheDocument();
        expect(screen.getByText("Analysis Engine")).toBeInTheDocument();
        expect(screen.getByText("Video LLM Engine")).toBeInTheDocument();
        expect(screen.getByText("http://localhost:8080")).toBeInTheDocument();
        expect(screen.getByText("http://analysis-engine:8001")).toBeInTheDocument();
        expect(screen.getByText("http://video-llm-engine:8002")).toBeInTheDocument();

        await waitFor(() => {
            expect(healthCheck).toHaveBeenCalledTimes(1);
            expect(engineHealthCheck).toHaveBeenCalledTimes(1);
        });
    });
});
