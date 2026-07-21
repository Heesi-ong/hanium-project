import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import UploadPage from "./UploadPage";

const analysisApiMock = vi.hoisted(() => ({
    cancelAnalysis: vi.fn(),
    getAnalysisProgress: vi.fn(),
    getAnalysisStatus: vi.fn(),
    runAnalysis: vi.fn(),
    uploadAnalysisVideo: vi.fn(),
}));

const toastMock = vi.hoisted(() => ({
    showToast: vi.fn(),
}));

const confirmMock = vi.hoisted(() => ({
    confirm: vi.fn(),
}));

vi.mock("../api/analysisApi", () => ({
    cancelAnalysis: analysisApiMock.cancelAnalysis,
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

vi.mock("../context/ConfirmContext", () => ({
    useConfirm: () => confirmMock.confirm,
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
        analysisApiMock.cancelAnalysis.mockReset();
        analysisApiMock.getAnalysisProgress.mockReset();
        analysisApiMock.getAnalysisStatus.mockReset();
        analysisApiMock.runAnalysis.mockReset();
        analysisApiMock.uploadAnalysisVideo.mockReset();
        toastMock.showToast.mockReset();
        confirmMock.confirm.mockReset();
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

    it("resets the native file input's value when the reset button is clicked", async () => {
        // 브라우저는 JS로 <input type="file">의 value에 빈 문자열 외의 값을 대입하는 것을
        // 막기 때문에(jsdom도 동일), "선택 후 값이 채워졌다가 초기화로 비워진다"를 값 자체로
        // 검증할 수는 없다. 대신 실제 코드가 하는 일 — value setter가 ""로 호출되는 것 —을
        // 직접 스파이로 검증한다. 이 대입이 있어야 브라우저가 같은 파일을 다시 선택했을 때도
        // change 이벤트를 발생시킨다(대입이 없으면 파일 목록이 안 바뀐 것으로 보고 무시한다).
        const valueSetterSpy = vi.spyOn(window.HTMLInputElement.prototype, "value", "set");

        try {
            const file = createVideoFile();
            const { container } = renderUploadPage();
            const fileInput = container.querySelector('input[type="file"]');

            fireEvent.change(fileInput, { target: { files: [file] } });
            expect(await screen.findByText("presentation.mp4")).toBeInTheDocument();

            valueSetterSpy.mockClear();
            fireEvent.click(screen.getByText("초기화"));

            expect(valueSetterSpy).toHaveBeenCalledWith("");
        } finally {
            valueSetterSpy.mockRestore();
        }
    });

    it("resets the native file input's value even when the selected file is rejected", async () => {
        // 확장자/용량 오류로 거부된 뒤에도 value가 그대로면, 같은(잘못된) 파일을 다시
        // 고르거나 문제를 고친 같은 이름의 파일을 다시 선택했을 때 change 이벤트가
        // 발생하지 않아 아무 반응도 일어나지 않는다.
        const valueSetterSpy = vi.spyOn(window.HTMLInputElement.prototype, "value", "set");

        try {
            const { container } = renderUploadPage();
            const fileInput = container.querySelector('input[type="file"]');
            const rejectedFile = new File(["not-video"], "memo.txt", { type: "text/plain" });

            valueSetterSpy.mockClear();
            fireEvent.change(fileInput, { target: { files: [rejectedFile] } });

            expect(
                await screen.findByText("mp4, mov, avi, mkv 형식의 영상 파일만 업로드할 수 있습니다.")
            ).toBeInTheDocument();
            expect(valueSetterSpy).toHaveBeenCalledWith("");
        } finally {
            valueSetterSpy.mockRestore();
        }
    });

    it("shows a queued cancel button and cancels the job immediately", async () => {
        const jobId = "job-cancel-test";

        analysisApiMock.uploadAnalysisVideo.mockResolvedValue({
            data: {
                jobId,
                status: "UPLOADED",
                statusDescription: "업로드 완료",
                originalFileName: "presentation.mp4",
                storedFilePath: "/storage/uploads/job-cancel-test/presentation.mp4",
                fileSize: 1024,
            },
        });

        analysisApiMock.runAnalysis.mockResolvedValue({ data: {} });

        analysisApiMock.getAnalysisStatus
            .mockResolvedValueOnce({
                data: {
                    jobId,
                    status: "QUEUED",
                    statusDescription: "분석 대기 중",
                    failReason: null,
                },
            })
            .mockResolvedValue({
                data: {
                    jobId,
                    status: "CANCELLED",
                    statusDescription: "취소됨",
                    failReason: null,
                },
            });

        analysisApiMock.getAnalysisProgress.mockResolvedValue({
            data: { percent: 0, message: "대기 중" },
        });

        analysisApiMock.cancelAnalysis.mockResolvedValue({
            data: {
                jobId,
                status: "CANCELLED",
                statusDescription: "취소됨",
            },
        });

        confirmMock.confirm.mockResolvedValue(true);

        renderUploadPage();

        dropFile(getDropZone(), createVideoFile());
        fireEvent.click(await screen.findByRole("button", { name: "영상 업로드" }));

        await screen.findByText(jobId);

        fireEvent.click(await screen.findByRole("button", { name: "분석 실행" }));

        const cancelButton = await screen.findByRole("button", { name: "대기 중 취소" });
        fireEvent.click(cancelButton);

        expect(confirmMock.confirm).toHaveBeenCalled();

        await waitFor(() => {
            expect(analysisApiMock.cancelAnalysis).toHaveBeenCalledWith(jobId);
        });

        expect(await screen.findByText("취소됨")).toBeInTheDocument();
    });

    it("disables navigating to the result page while analysis is still polling", async () => {
        const jobId = "job-polling-nav-test";

        analysisApiMock.uploadAnalysisVideo.mockResolvedValue({
            data: {
                jobId,
                status: "UPLOADED",
                statusDescription: "업로드 완료",
                originalFileName: "presentation.mp4",
                storedFilePath: "/storage/uploads/job-polling-nav-test/presentation.mp4",
                fileSize: 1024,
            },
        });

        analysisApiMock.runAnalysis.mockResolvedValue({ data: {} });

        // 분석이 QUEUED에서 계속 머물러 있는 상황을 흉내냅니다(아직 완료되지 않음).
        analysisApiMock.getAnalysisStatus.mockResolvedValue({
            data: {
                jobId,
                status: "QUEUED",
                statusDescription: "분석 대기 중",
                failReason: null,
            },
        });

        analysisApiMock.getAnalysisProgress.mockResolvedValue({
            data: { percent: 0, message: "대기 중" },
        });

        renderUploadPage();

        dropFile(getDropZone(), createVideoFile());
        fireEvent.click(await screen.findByRole("button", { name: "영상 업로드" }));

        await screen.findByText(jobId);

        fireEvent.click(await screen.findByRole("button", { name: "분석 실행" }));

        // 아직 분석 결과 파일이 없는데도 상세 페이지로 이동하면 진행률 UI 대신 깨진
        // 에러 화면을 보게 되므로, 폴링 중에는 이 버튼이 비활성화돼 있어야 합니다.
        const goToResultButton = await screen.findByRole("button", { name: "결과 페이지로 이동" });
        await waitFor(() => {
            expect(goToResultButton).toBeDisabled();
        });
    });
});
