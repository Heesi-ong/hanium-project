import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import EmptyState from "../components/EmptyState";
import CollapsibleDetails from "../components/CollapsibleDetails";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import StatusBadge from "../components/StatusBadge";
import { useConfirm } from "../context/ConfirmContext";
import { useToast } from "../context/ToastContext";
import { useJobStatusPolling } from "../hooks/useJobStatusPolling";
import {
    cancelAnalysis,
    getAnalysisProgress,
    getAnalysisStatus,
    getServiceStatus,
    runAnalysis,
    uploadAnalysisVideo,
} from "../api/analysisApi";
import { ERROR_CODES, getErrorCode, getErrorMessage } from "../api/errorUtils";
import { STATUS_STEP_LABELS } from "../constants/analysisStatus";
import {
    clearActiveAnalysisJobId,
    readActiveAnalysisJobId,
    saveActiveAnalysisJobId,
} from "../utils/activeAnalysisStorage";

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
const TERMINAL_STATUSES = ["COMPLETED", "FAILED", "CANCELLED", "DEAD_LETTER"];

// 백엔드는 각 단계가 시작될 때 고정된 퍼센트(예: 기본분석 시작 시 10%)만 보내고,
// 그 단계가 끝날 때까지는 새 값을 보내지 않는다. 그래서 그대로 표시하면 오래 걸리는
// 단계에서 퍼센트가 한참 멈춰 있는 것처럼 보인다. 아래 두 맵은 각 단계에 머문 시간에
// 비례해 다음 마일스톤 직전까지만(최대 95%) 화면상 퍼센트를 부드럽게 올리기 위한
// 프론트 전용 추정치다. 실제 완료 신호는 항상 백엔드가 보내는 진짜 percent가 대신한다.
const PROGRESS_STEP_ESTIMATED_DURATION_MS = {
    BASIC_ANALYZING: 90000,
    VIDEO_LLM_ANALYZING: 15000,
    COMPACTING: 5000,
    OPENAI_GENERATING: 15000,
    MERGING_RESULT: 5000,
};

const PROGRESS_STEP_NEXT_MILESTONE = {
    BASIC_ANALYZING: 40,
    VIDEO_LLM_ANALYZING: 60,
    COMPACTING: 75,
    OPENAI_GENERATING: 90,
    MERGING_RESULT: 100,
};

function getDisplayPercent(progress) {
    if (!progress || typeof progress.percent !== "number") {
        return 0;
    }

    const estimatedDurationMs = PROGRESS_STEP_ESTIMATED_DURATION_MS[progress.status];
    const nextMilestone = PROGRESS_STEP_NEXT_MILESTONE[progress.status];

    if (!estimatedDurationMs || !nextMilestone || !progress.updatedAt) {
        return progress.percent;
    }

    const elapsedMs = Date.now() - new Date(progress.updatedAt).getTime();

    if (!Number.isFinite(elapsedMs) || elapsedMs <= 0) {
        return progress.percent;
    }

    const fraction = Math.min(elapsedMs / estimatedDurationMs, 0.95);
    const interpolated = progress.percent + (nextMilestone - progress.percent) * fraction;

    return Math.round(interpolated);
}

function createRecoveredUpload(jobId, statusData = {}) {
    return {
        jobId,
        status: statusData.status || "RECOVERING",
        statusDescription: statusData.statusDescription || "상태 확인 중",
        originalFileName: "이전에 업로드한 영상",
        fileSize: null,
        recovered: true,
    };
}

function UploadPage() {
    const navigate = useNavigate();
    const location = useLocation();
    const { showToast } = useToast();
    const confirm = useConfirm();
    const progressTimerRef = useRef(null);
    const cooldownTimerRef = useRef(null);
    const fileInputRef = useRef(null);

    const [file, setFile] = useState(null);
    const [uploadedResult, setUploadedResult] = useState(null);
    const [analysisStatus, setAnalysisStatus] = useState(null);
    const [progress, setProgress] = useState(null);
    const [useVideoLlm, setUseVideoLlm] = useState(true);
    const [useOpenAi, setUseOpenAi] = useState(true);
    const [loading, setLoading] = useState(false);
    const [running, setRunning] = useState(false);
    const [cancelling, setCancelling] = useState(false);
    const [isDragActive, setIsDragActive] = useState(false);
    const [previewUrl, setPreviewUrl] = useState("");
    const [error, setError] = useState("");
    const [rateLimitedUntil, setRateLimitedUntil] = useState(0);
    const [capability, setCapability] = useState(null);
    const [uploadPercent, setUploadPercent] = useState(0);
    const practiceContext = location.state?.practiceContext || null;

    // 서비스 상태(/api/status)가 "이용 불가"라고 밝힌 기능을 업로드 화면이 기본 체크된
    // 채로 보여주면, 사용자가 실제로는 수행되지 않을 옵션을 선택했다고 오해할 수 있다.
    // 이용 불가 옵션은 자동으로 해제하고 선택할 수 없게 막는다.
    useEffect(() => {
        let cancelled = false;

        (async () => {
            try {
                const response = await getServiceStatus();
                if (!cancelled) {
                    setCapability(response.data);
                }
            } catch {
                // 상태 조회 실패는 업로드 자체를 막지 않는다 — 옵션은 기본값(전부 사용)을 유지한다.
            }
        })();

        return () => {
            cancelled = true;
        };
    }, []);

    const videoLlmUnavailable = capability?.videoLlmEngine?.status === "UNAVAILABLE";
    const aiFeedbackUnavailable = capability?.aiFeedback?.status === "UNAVAILABLE";

    // 상태를 effect로 강제 동기화하는 대신, "체크됐지만 지금은 이용 불가"인 경우를
    // 렌더링 시점에 파생값으로 계산한다. 사용자가 다시 체크를 만질 필요 없이 항상
    // 실제로 반영될 값만 checked/실행 인자로 흘려보낸다.
    const effectiveUseVideoLlm = useVideoLlm && !videoLlmUnavailable;
    const effectiveUseOpenAi = useOpenAi && !aiFeedbackUnavailable;
    const analysisOptionSummary = [
        "고급 분석 옵션",
        `Video LLM ${effectiveUseVideoLlm ? "사용" : "미사용"}`,
        `AI 피드백 ${effectiveUseOpenAi ? "사용" : "미사용"}`,
    ].join(" · ");

    const selectedFileSizeMb = file
        ? (file.size / 1024 / 1024).toFixed(2)
        : null;

    const currentStatus = analysisStatus?.status || uploadedResult?.status || null;

    const currentStatusDescription =
        analysisStatus?.statusDescription ||
        uploadedResult?.statusDescription ||
        "대기 중";

    const isRunningStatus = RUNNING_STATUSES.includes(currentStatus);
    const isQueuedStatus = currentStatus === "QUEUED";
    const isCompleted = currentStatus === "COMPLETED";
    const isFailed = ["FAILED", "DEAD_LETTER"].includes(currentStatus);
    const isCancelled = currentStatus === "CANCELLED";
    const isRateLimited = rateLimitedUntil > Date.now();

    // 일시적인 네트워크 장애(모바일 회선 순단 등)가 분석 실패처럼 보이지 않도록,
    // ResultDetailPage와 동일하게 연속 5회까지는 실패를 허용하고 다음 주기에
    // 자동으로 재시도합니다(2026-08-06 이전에는 1회 실패로 즉시 멈췄습니다).
    const { polling, startPolling: startStatusPolling, stopPolling } = useJobStatusPolling({
        intervalMs: POLLING_INTERVAL_MS,
        timeoutMs: POLLING_TIMEOUT_MS,
        maxConsecutiveFailures: 5,
        fetchStatus: fetchStatusOnce,
        onStatus: (statusData) => {
            setError("");
            setUploadedResult((currentResult) =>
                currentResult || createRecoveredUpload(statusData.jobId, statusData)
            );
        },
        onCompleted: async (statusData) => {
            stopProgressPolling();
            clearActiveAnalysisJobId();
            showToast("분석이 완료되었습니다.", "success");
            navigate(`/results/${statusData.jobId}`);
        },
        onFailed: (statusData) => {
            stopProgressPolling();
            clearActiveAnalysisJobId();
            setError(statusData.failReason || "분석이 실패했습니다.");
        },
        onCancelled: () => {
            stopProgressPolling();
            clearActiveAnalysisJobId();
            setError("분석이 취소되었습니다. 결과 상세 화면에서 다시 시도할 수 있습니다.");
        },
        onTimeout: () => {
            stopProgressPolling();
            setError("분석 상태 확인 시간이 초과되었습니다. 결과 목록에서 다시 확인하세요.");
        },
        onPollError: (requestError) => {
            stopProgressPolling();
            setError(getErrorMessage(
                requestError,
                "분석 상태 확인 중 오류가 발생했습니다."
            ));
        },
    });

    const isFileSelectionDisabled = loading || running || polling || cancelling;

    useEffect(() => {
        const storedJobId = readActiveAnalysisJobId();

        if (!storedJobId) {
            return undefined;
        }

        let cancelled = false;

        (async () => {
            setUploadedResult(createRecoveredUpload(storedJobId));

            try {
                const response = await getAnalysisStatus(storedJobId);

                if (cancelled) {
                    return;
                }

                const statusData = response.data;
                const terminalStatuses = ["FAILED", "CANCELLED", "DEAD_LETTER"];
                const recoverableStatuses = ["UPLOADED", ...RUNNING_STATUSES];

                if (statusData.status === "COMPLETED") {
                    clearActiveAnalysisJobId();
                    showToast("완료된 분석 결과로 이동합니다.", "success");
                    navigate(`/results/${storedJobId}`);
                    return;
                }

                if (
                    !recoverableStatuses.includes(statusData.status) &&
                    !terminalStatuses.includes(statusData.status)
                ) {
                    clearActiveAnalysisJobId();
                    setUploadedResult(null);
                    setError("이전에 진행하던 분석의 상태를 현재 화면에서 복구할 수 없습니다.");
                    return;
                }

                setUploadedResult(createRecoveredUpload(storedJobId, statusData));
                setAnalysisStatus(statusData);

                if (terminalStatuses.includes(statusData.status)) {
                    clearActiveAnalysisJobId();
                    setError(
                        statusData.failReason ||
                        "이전에 진행하던 분석이 종료되었습니다. 결과 상세 화면에서 확인하세요."
                    );
                    return;
                }

                if (RUNNING_STATUSES.includes(statusData.status)) {
                    startProgressPolling(storedJobId);
                    startStatusPolling(storedJobId);
                }
            } catch (requestError) {
                if (cancelled) {
                    return;
                }

                const errorCode = getErrorCode(requestError);

                if ([
                    ERROR_CODES.ANALYSIS_JOB_ACCESS_DENIED,
                    ERROR_CODES.ANALYSIS_JOB_NOT_FOUND,
                    ERROR_CODES.INVALID_INPUT_VALUE,
                ].includes(errorCode)) {
                    clearActiveAnalysisJobId();
                    setUploadedResult(null);
                } else {
                    // 최초 복구 조회만 일시적으로 실패해도 저장된 jobId를 기준으로 다음
                    // polling 주기에서 자동 재시도합니다. 성공 시 onStatus가 오류를 지웁니다.
                    startProgressPolling(storedJobId);
                    startStatusPolling(storedJobId);
                }

                setError(getErrorMessage(
                    requestError,
                    "이전에 진행하던 분석 상태를 확인하지 못했습니다. 잠시 후 다시 접속해주세요."
                ));
            }
        })();

        return () => {
            cancelled = true;
        };
        // startProgressPolling은 타이머 ref만 갱신하는 로컬 함수이며 이 최초 1회 복구
        // effect의 재실행 조건이 아닙니다.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [navigate, showToast, startStatusPolling]);

    useEffect(() => {
        return () => {
            stopPolling();
            stopProgressPolling();
            stopRateLimitCooldown();
        };
    }, [stopPolling]);

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
        clearActiveAnalysisJobId();
        setError("");
        setUploadedResult(null);
        setAnalysisStatus(null);
        setUploadPercent(0);

        // <input type="file">는 같은 경로의 파일을 다시 선택해도 네이티브 change 이벤트가
        // 발생하지 않는다(파일 목록이 실제로 바뀌지 않았다고 보기 때문). validateAndSetFile()이
        // 매번(성공이든 거부든) 이 함수를 먼저 호출하므로, 여기서 value를 비워두면 확장자/용량
        // 오류로 거부된 뒤 같은 파일을 다시 선택하는 경우도 항상 새 change 이벤트로 처리된다.
        if (fileInputRef.current) {
            fileInputRef.current.value = "";
        }
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

    async function uploadSelectedVideo() {
        if (!file) {
            setError("업로드할 영상 파일을 선택하세요.");
            return null;
        }

        try {
            stopPolling();
            setLoading(true);
            setError("");
            setAnalysisStatus(null);
            setUploadPercent(0);

            const response = await uploadAnalysisVideo(file, {
                practiceContext,
                onUploadProgress: (progressEvent) => {
                    if (!progressEvent.total) {
                        return;
                    }

                    setUploadPercent(
                        Math.round((progressEvent.loaded / progressEvent.total) * 100)
                    );
                },
            });

            setUploadedResult(response.data);
            saveActiveAnalysisJobId(response.data.jobId);
            setAnalysisStatus({
                jobId: response.data.jobId,
                status: response.data.status,
                statusDescription: response.data.statusDescription,
                failReason: null,
            });
            return response.data;
        } catch (requestError) {
            if (!applyRateLimitMessage(requestError)) {
                setError(getErrorMessage(
                    requestError,
                    "영상 업로드 중 오류가 발생했습니다."
                ));
            }
            return null;
        } finally {
            setLoading(false);
            setUploadPercent(0);
        }
    }

    async function startAnalysis(jobId) {
        if (!jobId) {
            setError("먼저 영상을 업로드해야 분석을 실행할 수 있습니다.");
            return false;
        }

        try {
            setRunning(true);
            setError("");
            setProgress(null);

            await runAnalysis(jobId, {
                useVideoLlm: effectiveUseVideoLlm,
                useOpenAi: effectiveUseOpenAi,
            });

            // 명령 응답을 받은 즉시 추적을 먼저 시작합니다. 이어지는 단건 상태 조회가
            // 일시적으로 실패해도 이미 접수된 장시간 작업을 놓치지 않습니다.
            startProgressPolling(jobId);
            startStatusPolling(jobId);
            await fetchStatusOnce(jobId);
            return true;
        } catch (requestError) {
            if (!applyRateLimitMessage(requestError)) {
                setError(getErrorMessage(
                    requestError,
                    "분석 실행 중 오류가 발생했습니다."
                ));
            }

            if (
                getErrorCode(requestError) !== ERROR_CODES.TOO_MANY_REQUESTS &&
                jobId
            ) {
                try {
                    const statusData = await fetchStatusOnce(jobId);

                    // /run 응답이 timeout이어도 서버에서는 작업을 접수했을 수 있습니다.
                    // 서버 상태가 실행 중이면 오류 화면에 멈추지 않고 추적을 복구합니다.
                    if (RUNNING_STATUSES.includes(statusData.status)) {
                        setError("");
                        showToast(
                            "분석 요청이 접수되어 상태 확인을 계속합니다.",
                            "info"
                        );
                        startProgressPolling(jobId);
                        startStatusPolling(jobId);
                    }
                } catch {
                    // 최초 오류 메시지를 유지하고 저장된 jobId로 다음 페이지 재진입 복구를 허용합니다.
                }
            }
            return false;
        } finally {
            setRunning(false);
        }
    }

    async function handleUploadAndRun() {
        const uploadResult = await uploadSelectedVideo();

        if (uploadResult?.jobId) {
            await startAnalysis(uploadResult.jobId);
        }
    }

    async function handleRunAnalysis() {
        await startAnalysis(uploadedResult?.jobId);
    }

    async function handleCancel() {
        if (!uploadedResult?.jobId) {
            return;
        }

        const confirmMessage = isQueuedStatus
            ? "대기 중인 분석을 취소하시겠습니까? 즉시 취소됩니다."
            : "진행 중인 분석을 취소하시겠습니까? 현재 실행 중인 단계가 끝난 뒤 취소됩니다.";

        const confirmed = await confirm(confirmMessage);

        if (!confirmed) {
            return;
        }

        try {
            setCancelling(true);
            setError("");

            await cancelAnalysis(uploadedResult.jobId);
            await fetchStatusOnce(uploadedResult.jobId);
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "분석 취소 요청 중 오류가 발생했습니다."
            ));

            try {
                await fetchStatusOnce(uploadedResult.jobId);
            } catch {
                // 취소 요청 실패 후 상태 조회 실패는 기존 오류 메시지를 유지합니다.
            }
        } finally {
            setCancelling(false);
        }
    }

    async function fetchStatusOnce(jobId) {
        const response = await getAnalysisStatus(jobId);
        setAnalysisStatus(response.data);

        if (TERMINAL_STATUSES.includes(response.data.status)) {
            stopProgressPolling();
        }

        return response.data;
    }

    function handleReset() {
        stopPolling();
        stopProgressPolling();
        clearActiveAnalysisJobId();
        setFile(null);
        setUploadedResult(null);
        setAnalysisStatus(null);
        setProgress(null);
        setUploadPercent(0);
        setError("");
        setUseVideoLlm(true);
        setUseOpenAi(true);

        // <input type="file">는 같은 경로의 파일을 다시 선택해도 네이티브 change 이벤트가
        // 발생하지 않는다(파일 목록이 실제로 바뀌지 않았다고 보기 때문). value를 직접
        // 비워야 이후 같은 파일을 다시 골랐을 때도 정상적으로 change가 발생한다.
        if (fileInputRef.current) {
            fileInputRef.current.value = "";
        }
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
                description="발표 영상을 업로드하면 기본 분석 엔진, Video LLM 엔진, AI 피드백 파이프라인을 통해 분석 결과를 생성합니다."
            />

            {practiceContext && (
                <div className="practice-context-banner" role="status">
                    <strong>{practiceContext.label} 집중 재연습</strong>
                    <span>
                        업로드한 결과를 기준 분석 {practiceContext.baselineJobId}와 자동 비교합니다.
                    </span>
                </div>
            )}

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
                            ref={fileInputRef}
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

                    <CollapsibleDetails
                        className="upload-options-details"
                        summary={analysisOptionSummary}
                        headingLevel={3}
                    >
                        <div className="upload-option-list">
                            <label className="option-row">
                            <input
                                type="checkbox"
                                checked={effectiveUseVideoLlm}
                                onChange={(event) => setUseVideoLlm(event.target.checked)}
                                disabled={
                                    loading ||
                                    running ||
                                    polling ||
                                    cancelling ||
                                    videoLlmUnavailable
                                }
                            />
                            <span>
                <strong>Video LLM 분석 사용</strong>
                <small>제스처와 자세를 영상 흐름 기반으로 판독합니다.</small>
                {videoLlmUnavailable && (
                    <small className="option-unavailable">
                        {capability?.videoLlmEngine?.message || "현재 이용할 수 없습니다."}
                    </small>
                )}
              </span>
                            </label>

                            <label className="option-row">
                            <input
                                type="checkbox"
                                checked={effectiveUseOpenAi}
                                onChange={(event) => setUseOpenAi(event.target.checked)}
                                disabled={
                                    loading ||
                                    running ||
                                    polling ||
                                    cancelling ||
                                    aiFeedbackUnavailable
                                }
                            />
                            <span>
                <strong>AI 피드백 사용</strong>
                <small>축약 분석 데이터를 바탕으로 코칭 피드백을 생성합니다.</small>
                {aiFeedbackUnavailable && (
                    <small className="option-unavailable">
                        {capability?.aiFeedback?.message || "현재 이용할 수 없습니다."}
                    </small>
                )}
              </span>
                            </label>
                        </div>
                    </CollapsibleDetails>

                    <StateMessage type="error">{error}</StateMessage>

                    {loading && (
                        <div className="progress-bar-wrap">
                            <div
                                className="progress-bar-track"
                                role="progressbar"
                                aria-valuenow={uploadPercent}
                                aria-valuemin={0}
                                aria-valuemax={100}
                                aria-label="업로드 진행률"
                            >
                                <div
                                    className="progress-bar-fill"
                                    style={{ width: `${uploadPercent}%` }}
                                />
                            </div>
                            <span className="progress-bar-label">
                                업로드 중 · {uploadPercent}%
                            </span>
                        </div>
                    )}

                    <div className="button-row">
                        <button
                            type="button"
                            className="primary-button"
                            onClick={handleUploadAndRun}
                            disabled={!file || loading || running || polling || cancelling || isRateLimited}
                        >
                            {loading
                                ? `업로드 중... ${uploadPercent}%`
                                : running || polling
                                    ? "분석 진행 중..."
                                    : "업로드하고 분석 시작"}
                        </button>

                        {(file || uploadedResult) && (
                            <button
                                type="button"
                                className="secondary-button"
                                onClick={handleReset}
                                disabled={loading || running || polling || cancelling}
                            >
                                초기화
                            </button>
                        )}
                    </div>
                </div>

                <div className="upload-card">
                    <h2>업로드 및 분석 상태</h2>

                    {!uploadedResult ? (
                        <EmptyState
                            title="아직 업로드된 영상이 없습니다."
                            description="영상을 업로드하면 접수 시각과 진행 상태가 이곳에 표시됩니다."
                        />
                    ) : (
                        <>
                            <div className="upload-result-box">
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
                                        {uploadedResult.fileSize == null
                                            ? "새로고침 전 업로드"
                                            : `${(uploadedResult.fileSize / 1024 / 1024).toFixed(2)}MB`}
                                    </strong>
                                </div>

                                {/* jobId는 일반 사용자에게 의미 있는 정보가 아니라 문의할 때만
                                    필요하므로 기본적으로 접어 둔다. 내부 저장 경로(storedFilePath)는
                                    운영 세부 정보라 아예 노출하지 않는다. */}
                                <details className="inquiry-id-details">
                                    <summary>문의용 ID</summary>
                                    <code>{uploadedResult.jobId}</code>
                                </details>
                            </div>

                            <div className="pipeline-box">
                                <h3>분석 진행 단계</h3>

                                {(running || polling) && (
                                    <div className="progress-bar-wrap">
                                        <div
                                            className="progress-bar-track"
                                            role="progressbar"
                                            aria-valuenow={getDisplayPercent(progress)}
                                            aria-valuemin={0}
                                            aria-valuemax={100}
                                            aria-label="분석 진행률"
                                        >
                                            <div
                                                className="progress-bar-fill"
                                                style={{ width: `${getDisplayPercent(progress)}%` }}
                                            />
                                        </div>
                                        <span className="progress-bar-label">
                                            {getDisplayPercent(progress)}% · {progress?.message || "진행률을 확인하는 중입니다."}
                                            {progress?.basicAnalysisStep?.stepNo != null &&
                                                progress?.basicAnalysisStep?.totalSteps != null &&
                                                ` (${progress.basicAnalysisStep.stepNo}/${progress.basicAnalysisStep.totalSteps} 단계)`}
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

                    {uploadedResult && (
                        <div className="button-row">
                            {currentStatus === "UPLOADED" && (
                                <button
                                    type="button"
                                    className="primary-button"
                                    onClick={handleRunAnalysis}
                                    disabled={running || loading || polling || isRateLimited}
                                >
                                    {running ? "분석 접수 중..." : "분석 다시 시작"}
                                </button>
                            )}

                            {isRunningStatus && (
                                <button
                                    type="button"
                                    className="secondary-button"
                                    onClick={handleCancel}
                                    disabled={cancelling || isRateLimited}
                                >
                                    {cancelling
                                        ? "취소 요청 중..."
                                        : isQueuedStatus
                                            ? "대기 중 취소"
                                            : "분석 취소"}
                                </button>
                            )}

                            {TERMINAL_STATUSES.includes(currentStatus) && (
                                <button
                                    type="button"
                                    className="secondary-button"
                                    onClick={() => navigate(`/results/${uploadedResult.jobId}`)}
                                >
                                    결과 페이지로 이동
                                </button>
                            )}
                        </div>
                    )}
                </div>
            </div>
        </section>
    );
}

export default UploadPage;
