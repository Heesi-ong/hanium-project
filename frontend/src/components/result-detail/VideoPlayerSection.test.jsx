import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getVideoAccessToken } from "../../api/analysisApi";
import VideoPlayerSection from "./VideoPlayerSection";

vi.mock("../../api/analysisApi", () => ({
    getVideoAccessToken: vi.fn(),
}));

const playMock = vi.fn().mockResolvedValue(undefined);

Object.defineProperty(window.HTMLMediaElement.prototype, "play", {
    configurable: true,
    value: playMock,
});

describe("VideoPlayerSection", () => {
    beforeEach(() => {
        getVideoAccessToken.mockReset();
        playMock.mockClear();
    });

    it("renders video with an access token URL", async () => {
        const token = "video-token/with?reserved=value";
        const jobId = "20260707090000-test-job";
        getVideoAccessToken.mockResolvedValue({
            data: {
                token,
                expiresInSeconds: 300,
            },
        });

        const { container } = render(<VideoPlayerSection jobId={jobId} />);

        expect(
            await screen.findByRole("heading", { name: "업로드 영상" })
        ).toBeInTheDocument();

        const video = container.querySelector("video");

        expect(video).toBeInTheDocument();
        expect(video.getAttribute("src")).toBe(
            `/api/results/${jobId}/video?access=${encodeURIComponent(token)}`
        );
    });

    it("shows a retention message when the original video no longer exists", async () => {
        getVideoAccessToken.mockRejectedValue({
            error: "FILE_NOT_FOUND",
            message: "파일을 찾을 수 없습니다.",
        });

        render(<VideoPlayerSection jobId="20260707090000-deleted-job" />);

        expect(
            await screen.findByText(
                "원본 영상이 보존 기간 정책에 따라 삭제되어 더 이상 재생할 수 없습니다."
            )
        ).toBeInTheDocument();
    });

    it("shows a generic error message for other failures", async () => {
        getVideoAccessToken.mockRejectedValue({
            error: "NETWORK_ERROR",
            message: "서버와 통신할 수 없습니다.",
        });

        render(<VideoPlayerSection jobId="20260707090000-error-job" />);

        expect(
            await screen.findByText("서버와 통신할 수 없습니다.")
        ).toBeInTheDocument();
    });

    it("seeks to a notable moment and starts playback", async () => {
        const jobId = "20260707090000-moment-job";
        getVideoAccessToken.mockResolvedValue({
            data: {
                token: "video-token",
                expiresInSeconds: 300,
            },
        });

        const { container } = render(
            <VideoPlayerSection
                jobId={jobId}
                notableMoments={[
                    {
                        category: "posture",
                        label: "자세 균형이 가장 흔들린 순간",
                        timestampSec: 83,
                        value: 37,
                    },
                    {
                        category: "gesture",
                        label: "제스처가 가장 활발했던 순간",
                        timestampSec: 125,
                        value: 7,
                    },
                ]}
            />
        );

        const postureMomentButton = await screen.findByRole("button", {
            name: "01:23 · 자세 균형이 가장 흔들린 순간",
        });
        const video = container.querySelector("video");

        fireEvent.click(postureMomentButton);

        expect(video.currentTime).toBe(83);
        expect(playMock).toHaveBeenCalledTimes(1);
    });
});
