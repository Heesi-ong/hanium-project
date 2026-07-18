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
                    readiness: {
                        reachable: true,
                        authenticated: false,
                        ready: false,
                        message: "Analysis 엔진 내부 API 키 인증에 실패했습니다.",
                    },
                },
                videoLlmEngine: {
                    baseUrl: "http://video-llm-engine:8002",
                    health: {
                        status: "up",
                        reachable: true,
                    },
                    readiness: {
                        reachable: true,
                        authenticated: true,
                        ready: false,
                        response: {
                            mode: "FALLBACK",
                            realModelReady: false,
                            reason: "VIDEO_LLM_ENABLED=true but NVIDIA_API_KEY is missing; analysis will fall back to mock responses.",
                        },
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
        await screen.findByText("Analysis 엔진 내부 API 키 인증에 실패했습니다.");
        expect(screen.getAllByText("degraded")).toHaveLength(2);
        expect(screen.getByText("FALLBACK")).toBeInTheDocument();
        expect(screen.getByText("Real Model")).toBeInTheDocument();
        expect(screen.getAllByText("Reason")).toHaveLength(2);
        expect(screen.getByText(/NVIDIA_API_KEY is missing/)).toBeInTheDocument();
        expect(screen.getByText("Analysis 엔진 내부 API 키 인증에 실패했습니다.")).toBeInTheDocument();

        await waitFor(() => {
            expect(healthCheck).toHaveBeenCalledTimes(1);
            expect(engineHealthCheck).toHaveBeenCalledTimes(1);
        });
    });
});
