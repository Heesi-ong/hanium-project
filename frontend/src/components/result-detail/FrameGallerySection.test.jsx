import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import FrameGallerySection from "./FrameGallerySection";

const FRAMES = [
    {
        sequence: 1,
        timestampSec: 1,
        poseDetected: true,
        gestureDetected: false,
        fileName: "frame_001.jpg",
    },
    {
        sequence: 2,
        timestampSec: 62,
        poseDetected: false,
        gestureDetected: false,
        fileName: "frame_002.jpg",
    },
];

describe("FrameGallerySection", () => {
    it("renders one thumbnail per frame pointing at the owner-scoped endpoint", () => {
        render(<FrameGallerySection jobId="20260101120000-abcdef12" frameGallery={FRAMES} />);

        const images = screen.getAllByRole("img");
        expect(images).toHaveLength(2);
        expect(images[0].getAttribute("src")).toContain(
            "/api/results/20260101120000-abcdef12/frames/frame_001.jpg"
        );
        expect(images[0].getAttribute("src")).not.toContain("framePath");
        expect(screen.getAllByText(/포즈 검출/).length).toBeGreaterThan(0);
        expect(screen.getByText("미검출")).toBeInTheDocument();
        expect(screen.getByText("01:02")).toBeInTheDocument();
        expect(screen.getByLabelText(/00:01 지점 분석 프레임 확대/))
            .toBeInTheDocument();
    });

    it("opens a lightbox when a thumbnail is clicked", () => {
        render(<FrameGallerySection jobId="20260101120000-abcdef12" frameGallery={FRAMES} />);

        fireEvent.click(screen.getAllByRole("button")[0]);

        expect(screen.getByRole("dialog", { name: "분석 프레임 확대 보기" })).toBeInTheDocument();
        expect(screen.getByText(/포즈 검출됨/)).toBeInTheDocument();
    });

    it("closes with Escape and returns focus to the opened thumbnail", async () => {
        render(<FrameGallerySection jobId="20260101120000-abcdef12" frameGallery={FRAMES} />);

        const firstThumbnail = screen.getByLabelText(/00:01 지점 분석 프레임 확대/);
        fireEvent.click(firstThumbnail);

        const closeButton = screen.getByRole("button", { name: "닫기" });
        await waitFor(() => expect(closeButton).toHaveFocus());
        expect(document.body.style.overflow).toBe("hidden");

        fireEvent.keyDown(document, { key: "Tab" });
        expect(closeButton).toHaveFocus();

        fireEvent.keyDown(document, { key: "Escape" });

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        await waitFor(() => expect(firstThumbnail).toHaveFocus());
        expect(document.body.style.overflow).toBe("");
    });

    it("renders nothing without a jobId or frames", () => {
        const { container: noJob } = render(<FrameGallerySection jobId="" frameGallery={FRAMES} />);
        expect(noJob).toBeEmptyDOMElement();

        const { container: noFrames } = render(
            <FrameGallerySection jobId="20260101120000-abcdef12" frameGallery={[]} />
        );
        expect(noFrames).toBeEmptyDOMElement();
    });
});
