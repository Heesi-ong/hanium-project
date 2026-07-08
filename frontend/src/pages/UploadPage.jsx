import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import StatusBadge from "../components/StatusBadge";
import { useToast } from "../context/ToastContext";
import {
    getAnalysisProgress,
    getAnalysisStatus,
    runAnalysis,
    uploadAnalysisVideo,
} from "../api/analysisApi";
import { ERROR_CODES, getErrorCode, getErrorMessage } from "../api/errorUtils";

const MAX_FILE_SIZE_MB = 500;
const POLLING_INTERVAL_MS = 1500;
const POLLING_TIMEOUT_MS = 35 * 60 * 1000;
const PROGRESS_POLLING_INTERVAL_MS = 1000;
const RATE_LIMIT_COOLDOWN_MS = 12000;

const RUNNING_STATUSES = [
    "QUEUED",
    "BASIC_ANALYZING",
    "VIDEO_LLM_ANALYZING",
    "COMPACTING",
    "OPENAI_GENERATING",
    "MERGING_RESULT",
];

const STATUS_STEP_LABELS = {
    UPLOADED: "업로드 완료",
    QUEUED: "분석 대기 중",
    BASIC_ANALYZING: "기본 분석 중",
    VIDEO_LLM_ANALYZING: "Video LLM 분석 중",
    COMPACTING: "분석 결과 축약 중",
    OPENAI_GENERATING: "AI 피드백 생성 중",
    MERGING_RESULT: "최종 결과 병합 중",
    COMPLETED: "분석 완료",
    FAILED: "분석 실패",
    CANCELLED: "분석 취소됨",
};

function UploadPage() {
    const navigate = useNavigate();
    const { showToast } = useToast();
    const pollingTimerRef = useRef(null);
    const pollingStartedAtRef = useRef(null);
    const progressTimerRef = useRef(null);
    const cooldownTimerRef = useRef(null);

    const [file, setFile] = useState(null);
    const [uploadedResult, setUploadedResult] = useState(null);
    const [analysisStatus, setAnalysisStatus] = useState(null);
    const [progress, setProgress] = useState(null);
    const [useVideoLlm, setUseVideoLlm] = useState(true);
    const [useOpenAi, setUseOpenAi] = useState(true);
    const [loading, setLoading] = useState(false);
    const [running, setRunning] = useState(false);
    const [polling, setPolling] = useState(false);
    const [isDragActive, setIsDragActive] = useState(false);
    const [previewUrl, setPreviewUrl] = useState("");
    const [error, setError] = useState("");
    const [rateLimitedUntil, setRateLimitedUntil] = useState(0);

    const selectedFileSizeMb = file
        ? (file.size / 1024 / 1024).toFixed(2)
        : null;

    const currentStatus = analysisStatus?.status || uploadedResult?.status || null;

    const currentStatusDescription =
        analysisStatus?.statusDescription ||
        uploadedResult?.statusDescription ||
        "대기 중";

    const isRunningStatus = RUNNING_STATUSES.includes(currentStatus);
    const isCompleted = currentStatus === "COMPLETED";
    const isFailed = currentStatus === "FAILED";
    const isCancelled = currentStatus === "CANCELLED";
    const isRateLimited = rateLimitedUntil > Date.now();
    const isFileSelectionDisabled = loading || running || polling;

    useEffect(() => {
        return () => {
            stopPolling();
            stopProgressPolling();
            stopRateLimitCooldown();
        };
    }, []);

    useEffect(() => {
        if (!file) {
            // eslint-disable-next-line react-hooks/set-state-in-effect -- objectURL 생성/해제는 effect의 부수효과이며 setPreviewUrl은 그 결과를 반영할 뿐이므로 렌더링 중 계산으로 대체할 수 없음
            setPreviewUrl("");
            return undefined;
        }

        const objectUrl = URL.createObjectURL(file);
        setPreviewUrl(objectUrl);

        return () => {
            URL.revokeObjectURL(objectUrl);
        };
    }, [file]);

    function stopPolling() {
        if (pollingTimerRef.current) {
            clearInterval(pollingTimerRef.current);
            pollingTimerRef.current = null;
        }

        pollingStartedAtRef.current = null;
        setPolling(false);
    }

    function stopProgressPolling() {
        if (progressTimerRef.current) {
            clearInterval(progressTimerRef.current);
            progressTimerRef.current = null;
        }
    }

    function startRateLimitCooldown() {
        stopRateLimitCooldown();
        setRateLimitedUntil(Date.now() + RATE_LIMIT_COOLDOWN_MS);

        cooldownTimerRef.current = setTimeout(() => {
            setRateLimitedUntil(0);
            cooldownTimerRef.current = null;
        }, RATE_LIMIT_COOLDOWN_MS);
    }

    function stopRateLimitCooldown() {
        if (cooldownTimerRef.current) {
            clearTimeout(cooldownTimerRef.current);
            cooldownTimerRef.current = null;
        }
    }

    function applyRateLimitMessage(requestError) {
        if (getErrorCode(requestError) !== ERROR_CODES.TOO_MANY_REQUESTS) {
            return false;
        }

        setError("요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
        startRateLimitCooldown();
        return true;
    }

    // /run은 분석 작업을 접수한 뒤 백그라운드 파이프라인으로 넘깁니다.
    // 접수 직후부터 진행률 API를 같이 조회해 QUEUED/분석 중 상태를 화면에 보여줍니다.
    function startProgressPolling(jobId) {
        stopProgressPolling();

        progressTimerRef.current = setInterval(async () => {
            try {
                const response = await getAnalysisProgress(jobId);
                setProgress(response.data);
            } catch {
                // 진행률 조회 실패는 분석 자체의 실패가 아니므로 조용히 무시합니다.
            }
        }, PROGRESS_POLLING_INTERVAL_MS);
    }

    function resetSelectedFileState() {
        stopPolling();
        setError("");
        setUploadedResult(null);
        setAnalysisStatus(null);
    }

    function validateAndSetFile(selectedFile) {
        resetSelectedFileState();

        if (!selectedFile) {
            setFile(null);
            return false;
        }

        const extension = selectedFile.name
            .slice(selectedFile.name.lastIndexOf("."))
            .toLowerCase();

        const allowedExtensions = [".mp4", ".mov", ".avi", ".mkv"];

        if (!allowedExtensions.includes(extension)) {
            setFile(null);
            setError("mp4, mov, avi, mkv 형식의 영상 파일만 업로드할 수 있습니다.");
            return false;
        }

        const fileSizeMb = selectedFile.size / 1024 / 1024;

        if (fileSizeMb > MAX_FILE_SIZE_MB) {
            setFile(null);
            setError(`파일 크기는 최대 ${MAX_FILE_SIZE_MB}MB까지 업로드할 수 있습니다.`);
            return false;
        }

        setFile(selectedFile);
        return true;
    }

    function handleFileChange(event) {
        validateAndSetFile(event.target.files?.[0]);
    }

    function preventFileDropDefault(event) {
        event.preventDefault();
        event.stopPropagation();
    }

    function handleDragEnter(event) {
        preventFileDropDefault(event);

        if (isFileSelectionDisabled) {
            return;
        }

        setIsDragActive(true);
    }

    function handleDragOver(event) {
        preventFileDropDefault(event);

        if (event.dataTransfer) {
            event.dataTransfer.dropEffect = isFileSelectionDisabled ? "none" : "copy";
        }
    }

    function handleDragLeave(event) {
        preventFileDropDefault(event);
        setIsDragActive(false);
    }

    function handleDrop(event) {
        preventFileDropDefault(event);
        setIsDragActive(false);

        if (isFileSelectionDisabled) {
            return;
        }

        validateAndSetFile(event.dataTransfer.files?.[0]);
    }

    async function handleUpload() {
        if (!file) {
            setError("업로드할 영상 파일을 선택하세요.");
            return;
        }

        try {
            stopPolling();
            setLoading(true);
            setError("");
            setAnalysisStatus(null);

            const response = await uploadAnalysisVideo(file);

            setUploadedResult(response.data);
            setAnalysisStatus({
                jobId: response.data.jobId,
                status: response.data.status,
                statusDescription: response.data.statusDescription,
                failReason: null,
            });
        } catch (requestError) {
            if (!applyRateLimitMessage(requestError)) {
                setError(getErrorMessage(
                    requestError,
                    "영상 업로드 중 오류가 발생했습니다."
                ));
            }
        } finally {
            setLoading(false);
        }
    }

    async function handleRunAnalysis() {
        if (!uploadedResult?.jobId) {
            setError("먼저 영상을 업로드해야 분석을 실행할 수 있습니다.");
            return;
        }

        try {
            setRunning(true);
            setError("");
            setProgress(null);

            startProgressPolling(uploadedResult.jobId);

            await runAnalysis(uploadedResult.jobId, {
                useVideoLlm,
                useOpenAi,
            });

            await fetchStatusOnce(uploadedResult.jobId);
            startStatusPolling(uploadedResult.jobId);
        } catch (requestError) {
            if (!applyRateLimitMessage(requestError)) {
                setError(getErrorMessage(
                    requestError,
                    "분석 실행 중 오류가 발생했습니다."
                ));
            }

            if (
                getErrorCode(requestError) !== ERROR_CODES.TOO_MANY_REQUESTS &&
                uploadedResult?.jobId
            ) {
                await fetchStatusOnce(uploadedResult.jobId);
            }
        } finally {
            setRunning(false);
            stopProgressPolling();
        }
    }

    async function fetchStatusOnce(jobId) {
        const response = await getAnalysisStatus(jobId);
        setAnalysisStatus(response.data);
        return response.data;
    }

    function startStatusPolling(jobId) {
        stopPolling();

        pollingStartedAtRef.current = Date.now();
        setPolling(true);

        pollingTimerRef.current = setInterval(async () => {
            try {
                const elapsedMs = Date.now() - pollingStartedAtRef.current;

                if (elapsedMs > POLLING_TIMEOUT_MS) {
                    stopPolling();
                    setError("분석 상태 확인 시간이 초과되었습니다. 결과 목록에서 다시 확인하세요.");
                    return;
                }

                const statusData = await fetchStatusOnce(jobId);

                if (statusData.status === "COMPLETED") {
                    stopPolling();
                    showToast("분석이 완료되었습니다.", "success");
                    navigate(`/results/${jobId}`);
                    return;
                }

                if (statusData.status === "FAILED") {
                    stopPolling();
                    setError(statusData.failReason || "분석이 실패했습니다.");
                    return;
                }

                if (statusData.status === "CANCELLED") {
                    stopPolling();
                    setError("분석이 취소되었습니다. 결과 상세 화면에서 다시 시도할 수 있습니다.");
                }
            } catch (requestError) {
                stopPolling();
                setError(getErrorMessage(
                    requestError,
                    "분석 상태 확인 중 오류가 발생했습니다."
                ));
            }
        }, POLLING_INTERVAL_MS);
    }

    function handleReset() {
        stopPolling();
        setFile(null);
        setUploadedResult(null);
        setAnalysisStatus(null);
        setProgress(null);
        setError("");
        setUseVideoLlm(true);
        setUseOpenAi(true);
    }

    function getStepClassName(stepStatus) {
        if (currentStatus === stepStatus) {
            return "pipeline-step active";
        }

        if (isCompleted) {
            return "pipeline-step done";
        }

        const currentIndex = getStepIndex(currentStatus);
        const stepIndex = getStepIndex(stepStatus);

        if (currentIndex > stepIndex) {
            return "pipeline-step done";
        }

        if ((isFailed || isCancelled) && currentIndex >= stepIndex) {
            return "pipeline-step failed";
        }

        return "pipeline-step";
    }

    function getStepIndex(status) {
        const order = [
            "UPLOADED",
            "QUEUED",
            "BASIC_ANALYZING",
            "VIDEO_LLM_ANALYZING",
            "COMPACTING",
            "OPENAI_GENERATING",
            "MERGING_RESULT",
            "COMPLETED",
        ];

        return order.indexOf(status);
    }

    return (
        <section className="page-section">
            <PageHeader
                eyebrow="Upload"
                title="발표 영상 업로드"
                description="발표 영상을 업로드하면 기본 분석 엔진, Video LLM 엔진, OpenAI 피드백 파이프라인을 통해 분석 결과를 생성합니다."
            />

            <div className="upload-grid">
                <div className="upload-card">
                    <h2>영상 파일 선택</h2>
                    <p className="card-description">
                        지원 형식: mp4, mov, avi, mkv · 최대 {MAX_FILE_SIZE_MB}MB
                    </p>

                    <label
                        className={
                            isDragActive
                                ? "file-drop-zone drag-active"
                                : "file-drop-zone"
                        }
                        onDragEnter={handleDragEnter}
                        onDragOver={handleDragOver}
                        onDragLeave={handleDragLeave}
                        onDrop={handleDrop}
                    >
                        <input
                            type="file"
                            accept=".mp4,.mov,.avi,.mkv,video/mp4,video/quicktime"
                            onChange={handleFileChange}
                            disabled={isFileSelectionDisabled}
                        />

                        <span className="file-drop-title">
              {file ? file.name : "클릭해서 발표 영상을 선택하세요"}
            </span>

                        <span className="file-drop-subtitle">
              {file
                  ? `${selectedFileSizeMb}MB`
                  : "파일을 선택하거나 이 영역에 끌어다 놓으면 업로드 준비 상태가 됩니다."}
            </span>
                    </label>

                    {previewUrl && (
                        <div className="upload-video-preview">
                            <video
                                controls
                                preload="metadata"
                                src={previewUrl}
                                style={{ width: "100%", borderRadius: 16 }}
                            />
                        </div>
                    )}

                    <div className="option-panel">
                        <h3>분석 옵션</h3>

                        <label className="option-row">
                            <input
                                type="checkbox"
                                checked={useVideoLlm}
                                onChange={(event) => setUseVideoLlm(event.target.checked)}
                                disabled={running || polling}
                            />
                            <span>
                <strong>Video LLM 분석 사용</strong>
                <small>시선, 표정, 제스처, 자세를 영상 흐름 기반으로 판독합니다.</small>
              </span>
                        </label>

                        <label className="option-row">
                            <input
                                type="checkbox"
                                checked={useOpenAi}
                                onChange={(event) => setUseOpenAi(event.target.checked)}
                                disabled={running || polling}
                            />
                            <span>
                <strong>OpenAI 피드백 사용</strong>
                <small>축약 분석 데이터를 바탕으로 코칭 피드백을 생성합니다.</small>
              </span>
                        </label>
                    </div>

                    <StateMessage type="error">{error}</StateMessage>

                    <div className="button-row">
                        <button
                            type="button"
                            className="primary-button"
                            onClick={handleUpload}
                            disabled={!file || loading || running || polling || isRateLimited}
                        >
                            {loading ? "업로드 중..." : "영상 업로드"}
                        </button>

                        <button
                            type="button"
                            className="secondary-button"
                            onClick={handleReset}
                            disabled={loading || running || polling}
                        >
                            초기화
                        </button>
                    </div>
                </div>

                <div className="upload-card">
                    <h2>업로드 및 분석 상태</h2>

                    {!uploadedResult ? (
                        <EmptyState
                            title="아직 업로드된 영상이 없습니다."
                            description="영상을 업로드하면 jobId와 저장 경로가 이곳에 표시됩니다."
                        />
                    ) : (
                        <>
                            <div className="upload-result-box">
                                <div className="result-row">
                                    <span>Job ID</span>
                                    <strong>{uploadedResult.jobId}</strong>
                                </div>

                                <div className="result-row">
                                    <span>현재 상태</span>
                                    <strong>
                                        <StatusBadge
                                            status={currentStatus}
                                            label={currentStatusDescription}
                                        />
                                    </strong>
                                </div>

                                <div className="result-row">
                                    <span>파일명</span>
                                    <strong>{uploadedResult.originalFileName}</strong>
                                </div>

                                <div className="result-row">
                                    <span>파일 크기</span>
                                    <strong>
                                        {(uploadedResult.fileSize / 1024 / 1024).toFixed(2)}MB
                                    </strong>
                                </div>

                                <div className="stored-path">
                                    <span>저장 경로</span>
                                    <code>{uploadedResult.storedFilePath}</code>
                                </div>
                            </div>

                            <div className="pipeline-box">
                                <h3>분석 진행 단계</h3>

                                {(running || polling) && (
                                    <div className="progress-bar-wrap">
                                        <div
                                            className="progress-bar-track"
                                            role="progressbar"
                                            aria-valuenow={progress?.percent ?? 0}
                                            aria-valuemin={0}
                                            aria-valuemax={100}
                                            aria-label="분석 진행률"
                                        >
                                            <div
                                                className="progress-bar-fill"
                                                style={{ width: `${progress?.percent ?? 0}%` }}
                                            />
                                        </div>
                                        <span className="progress-bar-label">
                                            {progress?.percent ?? 0}% · {progress?.message || "진행률을 확인하는 중입니다."}
                                        </span>
                                    </div>
                                )}

                                <div className="pipeline-steps">
                                    {Object.entries(STATUS_STEP_LABELS).map(([status, label]) => (
                                        <div
                                            className={getStepClassName(status)}
                                            key={status}
                                        >
                                            <span />
                                            <strong>{label}</strong>
                                        </div>
                                    ))}
                                </div>

                                <StateMessage type="polling">
                                    {running || polling || isRunningStatus
                                        ? "분석 상태를 자동으로 확인하는 중입니다."
                                        : ""}
                                </StateMessage>

                                <StateMessage type="success">
                                    {isCompleted
                                        ? "분석이 완료되었습니다. 결과 상세 페이지로 이동할 수 있습니다."
                                        : ""}
                                </StateMessage>

                                <StateMessage type="failure">
                                    {isFailed
                                        ? "분석이 실패했습니다. 결과 상세 페이지에서 재시도할 수 있습니다."
                                        : ""}
                                </StateMessage>
                            </div>
                        </>
                    )}

                    <div className="button-row">
                        <button
                            type="button"
                            className="primary-button"
                            onClick={handleRunAnalysis}
                            disabled={!uploadedResult || running || loading || polling || isCompleted || isRateLimited}
                        >
                            {running || polling ? "분석 진행 중..." : "분석 실행"}
                        </button>

                        <button
                            type="button"
                            className="secondary-button"
                            onClick={() =>
                                uploadedResult?.jobId && navigate(`/results/${uploadedResult.jobId}`)
                            }
                            disabled={!uploadedResult || running || loading}
                        >
                            결과 페이지로 이동
                        </button>
                    </div>
                </div>
            </div>
        </section>
    );
}

export default UploadPage;
