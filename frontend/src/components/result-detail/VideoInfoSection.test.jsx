import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import VideoInfoSection from "./VideoInfoSection";

describe("VideoInfoSection", () => {
    it("separates source metadata from the sampled frame count", () => {
        render(
            <VideoInfoSection
                videoInfo={{
                    durationSec: 125.5,
                    fps: 29.97,
                    frameCount: 3762,
                    width: 1920,
                    height: 1080,
                    fileSize: 10485760,
                }}
                frameInfo={{ savedCount: 20 }}
            />
        );

        const section = screen.getByRole("article", { name: "영상 정보" });
        expect(within(section).getByText("125.50초")).toBeInTheDocument();
        expect(within(section).getByText("1920 × 1080")).toBeInTheDocument();
        expect(within(section).getByText("29.97 FPS")).toBeInTheDocument();
        expect(within(section).getByText("10.00MB")).toBeInTheDocument();
        expect(within(section).getByText("3762개")).toBeInTheDocument();
        expect(within(section).getByText("20개")).toBeInTheDocument();
    });

    it("keeps real zero values and does not invent missing sample counts", () => {
        render(
            <VideoInfoSection
                videoInfo={{
                    durationSec: 0,
                    fps: 0,
                    frameCount: 0,
                    width: 0,
                    height: 0,
                    fileSize: 0,
                }}
                frameInfo={{}}
            />
        );

        const section = screen.getByRole("article", { name: "영상 정보" });
        expect(within(section).getByText("0초")).toBeInTheDocument();
        expect(within(section).getByText("0 × 0")).toBeInTheDocument();
        expect(within(section).getByText("0 FPS")).toBeInTheDocument();
        expect(within(section).getByText("0.00MB")).toBeInTheDocument();
        expect(within(section).getByText("0개")).toBeInTheDocument();
        expect(within(section).getByText("-")).toBeInTheDocument();
        expect(within(section).queryByText("-개")).not.toBeInTheDocument();
    });
});
