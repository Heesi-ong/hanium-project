import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UploadPage from "./UploadPage";

const analysisApiMock = vi.hoisted(() => ({
    getAnalysisProgress: vi.fn(),
    getAnalysisStatus: vi.fn(),
    runAnalysis: vi.fn(),
    uploadAnalysisVideo: vi.fn(),
}));

const toastMock = vi.hoisted(() => ({
    showToast: vi.fn(),
}));

vi.mock("../api/analysisApi", () => ({
    getAnalysisProgress: analysisApiMock.getAnalysisProgress,
    getAnalysisStatus: analysisApiMock.getAnalysisStatus,
    runAnalysis: analysisApiMock.runAnalysis,
    uploadAnalysisVideo: analysisApiMock.uploadAnalysisVideo,
}));

vi.mock("../context/ToastContext", () => ({
    useToast: () => ({
        showToast: toastMock.showToast,
    }),
}));

const createObjectURLMock = vi.fn();
const revokeObjectURLMock = vi.fn();

Object.defineProperty(window.URL, "createObjectURL", {
    configurable: true,
    value: createObjectURLMock,
});

Object.defineProperty(window.URL, "revokeObjectURL", {
    configurable: true,
    value: revokeObjectURLMock,
});

function renderUploadPage() {
    return render(
        <MemoryRouter>
            <UploadPage />
        </MemoryRouter>
    );
}

function getDropZone() {
    return screen
        .getByText(/클릭해서 발표 영상을 선택하세요/)
        .closest("label");
}

function createVideoFile(name = "presentation.mp4", content = "video") {
    return new File([content], name, { type: "video/mp4" });
}

function dropFile(dropZone, file) {
    fireEvent.drop(dropZone, {
        dataTransfer: {
            files: [file],
        },
    });
}

describe("UploadPage", () => {
    beforeEach(() => {
        analysisApiMock.getAnalysisProgress.mockReset();
        analysisApiMock.getAnalysisStatus.mockReset();
        analysisApiMock.runAnalysis.mockReset();
        analysisApiMock.uploadAnalysisVideo.mockReset();
        toastMock.showToast.mockReset();
        createObjectURLMock.mockReset();
        revokeObjectURLMock.mockReset();
        createObjectURLMock.mockImplementation((file) => `blob:${file.name}`);
    });

    it("accepts a dropped mp4 file and renders a local video preview", async () => {
        const file = createVideoFile();
        const { container } = renderUploadPage();

        dropFile(getDropZone(), file);

        expect(await screen.findByText("presentation.mp4")).toBeInTheDocument();
        expect(screen.getByText("0.00MB")).toBeInTheDocument();

        const previewVideo = container.querySelector("video");

        expect(previewVideo).toBeInTheDocument();
        expect(previewVideo.getAttribute("src")).toBe("blob:presentation.mp4");
        expect(createObjectURLMock).toHaveBeenCalledWith(file);
    });

    it("shows the existing extension error and does not render preview for unsupported dropped files", async () => {
        const { container } = renderUploadPage();

        dropFile(getDropZone(), new File(["not-video"], "memo.txt", {
            type: "text/plain",
        }));

        expect(
            await screen.findByText("mp4, mov, avi, mkv 형식의 영상 파일만 업로드할 수 있습니다.")
        ).toBeInTheDocument();
        expect(container.querySelector("video")).not.toBeInTheDocument();
        expect(createObjectURLMock).not.toHaveBeenCalled();
    });

    it("revokes the previous object URL when the selected file is replaced", async () => {
        const firstFile = createVideoFile("first.mp4");
        const secondFile = createVideoFile("second.mp4");
        renderUploadPage();
        const dropZone = getDropZone();

        dropFile(dropZone, firstFile);
        expect(await screen.findByText("first.mp4")).toBeInTheDocument();

        dropFile(dropZone, secondFile);
        expect(await screen.findByText("second.mp4")).toBeInTheDocument();

        await waitFor(() => {
            expect(revokeObjectURLMock).toHaveBeenCalledWith("blob:first.mp4");
        });
    });
});
