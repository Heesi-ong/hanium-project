import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { getServiceStatus } from "../api/analysisApi";
import StatusPage from "./StatusPage";

vi.mock("../api/analysisApi", () => ({
    getServiceStatus: vi.fn(),
}));

const STATUS_RESPONSE = {
    timestamp: "2026-08-01T09:30:00",
    data: {
        overallStatus: "DEGRADED",
        backend: {
            status: "AVAILABLE",
            message: "서비스에 정상적으로 연결되었습니다.",
        },
        analysisEngine: {
            status: "AVAILABLE",
            message: "기본 분석 기능을 정상적으로 이용할 수 있습니다.",
        },
        videoLlmEngine: {
            status: "DEGRADED",
            message: "Video LLM 분석 기능 일부가 제한되어 있습니다.",
        },
        passwordReset: {
            status: "UNAVAILABLE",
            message: "현재 비밀번호 재설정 이메일을 보낼 수 없습니다.",
        },
    },
};

describe("StatusPage", () => {
    beforeEach(() => {
        getServiceStatus.mockReset();
        getServiceStatus.mockResolvedValue(STATUS_RESPONSE);
    });

    it("renders user-facing availability without internal diagnostics", async () => {
        render(<StatusPage />);

        expect(screen.getByRole("heading", { name: "서비스 상태" })).toBeInTheDocument();
        expect(await screen.findByText("기본 분석")).toBeInTheDocument();
        expect(screen.getByText("Video LLM 분석")).toBeInTheDocument();
        expect(screen.getByText("비밀번호 재설정")).toBeInTheDocument();

        const passwordResetCard = screen.getByText("비밀번호 재설정").closest("article");
        expect(within(passwordResetCard).getByText("이용 불가")).toBeInTheDocument();
        expect(within(passwordResetCard).getByText(
            "현재 비밀번호 재설정 이메일을 보낼 수 없습니다."
        )).toBeInTheDocument();

        expect(screen.queryByText(/http:\/\//)).not.toBeInTheDocument();
        expect(screen.queryByText(/authenticated/i)).not.toBeInTheDocument();
        expect(screen.queryByText(/NVIDIA_API_KEY/)).not.toBeInTheDocument();
        expect(screen.queryByText(/policy/i)).not.toBeInTheDocument();
        expect(getServiceStatus).toHaveBeenCalledTimes(1);
    });

    it("refreshes all component statuses on demand", async () => {
        render(<StatusPage />);
        await screen.findByText("기본 분석 기능을 정상적으로 이용할 수 있습니다.");

        getServiceStatus.mockResolvedValueOnce({
            ...STATUS_RESPONSE,
            data: {
                ...STATUS_RESPONSE.data,
                overallStatus: "AVAILABLE",
                videoLlmEngine: {
                    status: "AVAILABLE",
                    message: "Video LLM 분석 기능을 정상적으로 이용할 수 있습니다.",
                },
                passwordReset: {
                    status: "AVAILABLE",
                    message: "비밀번호 재설정 이메일을 정상적으로 이용할 수 있습니다.",
                },
            },
        });

        fireEvent.click(screen.getByRole("button", { name: "상태 새로고침" }));

        expect(await screen.findByText(
            "Video LLM 분석 기능을 정상적으로 이용할 수 있습니다."
        )).toBeInTheDocument();
        await waitFor(() => expect(getServiceStatus).toHaveBeenCalledTimes(2));
    });

    it("keeps the status layout visible when the request fails", async () => {
        getServiceStatus.mockRejectedValueOnce({ message: "상태 조회 실패" });

        render(<StatusPage />);

        expect(await screen.findByText("상태 조회 실패")).toBeInTheDocument();
        expect(screen.getByText("기본 분석")).toBeInTheDocument();
        expect(screen.getAllByText("확인 불가")).toHaveLength(5);
    });
});
