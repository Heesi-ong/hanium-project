import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useJobStatusPolling } from "../hooks/useJobStatusPolling";
import AnalysisMetricBarChart from "../components/chart/AnalysisMetricBarChart";
import EmotionDoughnutChart from "../components/chart/EmotionDoughnutChart";
import ResultScoreChart from "../components/chart/ResultScoreChart";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import AnimatedSection from "../components/motion/AnimatedSection";
import AudioAnalysisSection from "../components/result-detail/AudioAnalysisSection";
import CoachChatSection from "../components/result-detail/CoachChatSection";
import EmotionAnalysisSection from "../components/result-detail/EmotionAnalysisSection";
import FaceAnalysisSection from "../components/result-detail/FaceAnalysisSection";
import FeedbackSection from "../components/result-detail/FeedbackSection";
import FillerAnalysisSection from "../components/result-detail/FillerAnalysisSection";
import GestureAnalysisSection from "../components/result-detail/GestureAnalysisSection";
import OpenAiFeedbackStatusSection from "../components/result-detail/OpenAiFeedbackStatusSection";
import PipelineSection from "../components/result-detail/PipelineSection";
import PoseAnalysisSection from "../components/result-detail/PoseAnalysisSection";
import PracticePlanSection from "../components/result-detail/PracticePlanSection";
import { formatDateTime } from "../components/result-detail/resultDetailFormatters";
import ResultSummaryOverview from "../components/result-detail/ResultSummaryOverview";
import SttSection from "../components/result-detail/SttSection";
import TimelineFeedbackSection from "../components/result-detail/TimelineFeedbackSection";
import VideoInfoSection from "../components/result-detail/VideoInfoSection";
import VideoPlayerSection from "../components/result-detail/VideoPlayerSection";
import StateMessage from "../components/StateMessage";
import StatusBadge from "../components/StatusBadge";
import {
    cancelAnalysis,
    deleteResult,
    getAnalysisStatus,
    getResult,
    retryAnalysis,
} from "../api/analysisApi";
import { ERROR_CODES, getErrorCode, getErrorMessage } from "../api/errorUtils";
import { STATUS_STEP_LABELS } from "../constants/analysisStatus";
import { useConfirm } from "../context/ConfirmContext";

const POLLING_INTERVAL_MS = 1500;
const POLLING_TIMEOUT_MS = 35 * 60 * 1000;
const RATE_LIMIT_COOLDOWN_MS = 12000;
const MAX_CONSECUTIVE_POLL_FAILURES = 5;
const EMPTY_OBJECT = {};
const EMPTY_ARRAY = [];

const RUNNING_STATUSES = [
    "QUEUED",
    "BASIC_ANALYZING",
    "VIDEO_LLM_ANALYZING",
    "COMPACTING",
    "OPENAI_GENERATING",
    "MERGING_RESULT",
];

function ResultDetailPage() {
    const { jobId } = useParams();
    const navigate = useNavigate();
    const confirm = useConfirm();

    const cooldownTimerRef = useRef(null);
    // VideoPlayerSection이 등록하는 영상 시크 함수. 시각 분석 관찰 항목을 클릭하면
    // 이 함수를 통해 영상을 해당 구간(startSec)으로 이동시킵니다.
    const videoSeekControllerRef = useRef(null);
    const handleSeekToObservation = useCallback((timestampSec) => {
        videoSeekControllerRef.current?.(timestampSec);
    }, []);

    const [resultData, setResultData] = useState(null);
    const [analysisStatus, setAnalysisStatus] = useState(null);
    const [loading, setLoading] = useState(true);
    const [retrying, setRetrying] = useState(false);
    const [cancelling, setCancelling] = useState(false);
    const [deleting, setDeleting] = useState(false);
    const [error, setError] = useState("");
    const [loadErrorCode, setLoadErrorCode] = useState("");
    const [rateLimitedUntil, setRateLimitedUntil] = useState(0);
    const [clockTick, setClockTick] = useState(() => Date.now());

    const result = resultData?.result || EMPTY_OBJECT;
    const dataIssue = resultData?.dataIssue || result.dataIssue || "";
    const dataIssueDescription =
        resultData?.dataIssueDescription ||
        result.dataIssueDescription ||
        "";
    const coachDisabledReason = dataIssue
        ? dataIssueDescription || "이 결과의 일부 데이터에 문제가 있어 AI 코치를 사용할 수 없습니다. 재분석이 필요할 수 있습니다."
        : "";

    const scoreSummary = result.scoreSummary || EMPTY_OBJECT;
    const basicAnalysis = result.basicAnalysis || EMPTY_OBJECT;
    const visualAnalysis = result.visualAnalysis || EMPTY_OBJECT;
    const feedback = result.feedback || EMPTY_OBJECT;
    const practicePlan = result.practicePlan || EMPTY_ARRAY;
    const timelineFeedback = result.timelineFeedback || EMPTY_ARRAY;
    const notableMoments = result.notableMoments || EMPTY_ARRAY;
    const pipeline = result.pipeline || EMPTY_OBJECT;

    const videoInfo = basicAnalysis.videoInfo || EMPTY_OBJECT;
    const frameInfo = basicAnalysis.frame || EMPTY_OBJECT;
    const poseInfo = basicAnalysis.pose || EMPTY_OBJECT;
    const gestureInfo = basicAnalysis.gesture || EMPTY_OBJECT;
    const audioInfo = basicAnalysis.audio || EMPTY_OBJECT;
    const fillerInfo = basicAnalysis.filler || EMPTY_OBJECT;
    const faceInfo = basicAnalysis.face || EMPTY_OBJECT;
    const emotionInfo = basicAnalysis.emotion || EMPTY_OBJECT;

    const sttInfo = audioInfo.stt || EMPTY_OBJECT;
    const audioExtractionInfo = audioInfo.audioExtraction || EMPTY_OBJECT;

    const sttSegments = Array.isArray(sttInfo.segments)
        ? sttInfo.segments
        : EMPTY_ARRAY;

    const fillerWords = Array.isArray(fillerInfo.fillerWords)
        ? fillerInfo.fillerWords
        : EMPTY_ARRAY;

    const poseFrameResults = Array.isArray(poseInfo.frameResults)
        ? poseInfo.frameResults
        : EMPTY_ARRAY;

    const gestureFrameResults = Array.isArray(gestureInfo.frameResults)
        ? gestureInfo.frameResults
        : EMPTY_ARRAY;

    const faceFrameResults = Array.isArray(faceInfo.frameResults)
        ? faceInfo.frameResults
        : EMPTY_ARRAY;

    const emotionFrameResults = Array.isArray(emotionInfo.frameResults)
        ? emotionInfo.frameResults
        : EMPTY_ARRAY;

    const emotionCounts = emotionInfo.emotionState?.emotionCounts || EMPTY_OBJECT;

    const currentStatus = analysisStatus?.status || result.status || null;
    const currentStatusDescription =
        analysisStatus?.statusDescription || currentStatus || "-";

    const isFailed = currentStatus === "FAILED";
    const isCancelled = currentStatus === "CANCELLED";
    const isCompleted = currentStatus === "COMPLETED";
    const isQueued = currentStatus === "QUEUED";
    const isRunning = RUNNING_STATUSES.includes(currentStatus);
    const isRateLimited = rateLimitedUntil > clockTick;

    const scoreItems = useMemo(
        () => [
            {
                label: "총점",
                value: scoreSummary.totalScore,
            },
            {
                label: "자세",
                value: scoreSummary.postureScore,
            },
            {
                label: "시선",
                value: scoreSummary.gazeScore,
            },
            {
                label: "음성",
                value: scoreSummary.speechScore,
            },
            {
                label: "제스처",
                value: scoreSummary.gestureScore,
            },
            {
                label: "표정",
                value: scoreSummary.expressionScore,
            },
        ],
        [scoreSummary]
    );

    const stopRateLimitCooldown = useCallback(() => {
        if (cooldownTimerRef.current) {
            clearTimeout(cooldownTimerRef.current);
            cooldownTimerRef.current = null;
        }
    }, []);

    const loadResult = useCallback(async () => {
        if (!jobId) {
            setError("조회할 jobId가 없습니다.");
            setLoading(false);
            return;
        }

        try {
            setLoading(true);
            setError("");
            setLoadErrorCode("");

            const response = await getResult(jobId);

            setResultData(response.data);

            if (response.data?.result?.status) {
                const status = response.data.result.status;
                setAnalysisStatus({
                    jobId,
                    status,
                    statusDescription: STATUS_STEP_LABELS[status] || status,
                    failReason: response.data.result.failReason || null,
                });
            }
        } catch (requestError) {
            const errorCode = getErrorCode(requestError);
            setLoadErrorCode(errorCode);

            if (errorCode === ERROR_CODES.ANALYSIS_JOB_ACCESS_DENIED) {
                setError("접근 권한이 없는 결과입니다.");
                return;
            }

            if (errorCode === ERROR_CODES.ANALYSIS_JOB_NOT_FOUND) {
                setError("삭제되었거나 존재하지 않는 결과입니다.");
                return;
            }

            setError(getErrorMessage(
                requestError,
                "분석 결과 상세 정보를 불러오는 중 오류가 발생했습니다."
            ));
        } finally {
            setLoading(false);
        }
    }, [jobId]);

    const fetchStatusOnce = useCallback(async (targetJobId) => {
        const response = await getAnalysisStatus(targetJobId);
        setAnalysisStatus(response.data);
        return response.data;
    }, []);

    const handlePollCompleted = useCallback(async () => {
        await loadResult();
    }, [loadResult]);

    const handlePollFailed = useCallback(async (statusData) => {
        await loadResult();
        setError(statusData.failReason || "분석 재시도가 실패했습니다.");
    }, [loadResult]);

    const handlePollCancelled = useCallback(async () => {
        await loadResult();
        setError("");
    }, [loadResult]);

    const handlePollTimeout = useCallback(() => {
        setError("분석 상태 확인 시간이 초과되었습니다. 잠시 후 다시 조회하세요.");
    }, []);

    const handlePollError = useCallback((requestError) => {
        setError(getErrorMessage(
            requestError,
            "분석 상태 확인 중 오류가 발생했습니다."
        ));
    }, []);

    // 이미 결과 상세를 보고 있는 화면이라, UploadPage(접수 직후 즉시 실패로 간주)와
    // 달리 잠깐의 네트워크 실패는 MAX_CONSECUTIVE_POLL_FAILURES번까지 참고 다음
    // 폴링 주기에 자동 재시도합니다.
    const { polling, startPolling: startStatusPolling, stopPolling } = useJobStatusPolling({
        intervalMs: POLLING_INTERVAL_MS,
        timeoutMs: POLLING_TIMEOUT_MS,
        maxConsecutiveFailures: MAX_CONSECUTIVE_POLL_FAILURES,
        fetchStatus: fetchStatusOnce,
        onCompleted: handlePollCompleted,
        onFailed: handlePollFailed,
        onCancelled: handlePollCancelled,
        onTimeout: handlePollTimeout,
        onPollError: handlePollError,
    });

    useEffect(() => {
        const loadTimer = window.setTimeout(() => {
            loadResult();
        }, 0);

        return () => {
            window.clearTimeout(loadTimer);
            stopPolling();
            stopRateLimitCooldown();
        };
    }, [loadResult, stopPolling, stopRateLimitCooldown]);

    useEffect(() => {
        if (jobId && isRunning && !polling) {
            const pollingStartTimer = window.setTimeout(() => {
                startStatusPolling(jobId);
            }, 0);

            return () => window.clearTimeout(pollingStartTimer);
        }
    }, [isRunning, jobId, polling, startStatusPolling]);

    function startRateLimitCooldown() {
        stopRateLimitCooldown();
        const now = Date.now();
        setClockTick(now);
        setRateLimitedUntil(now + RATE_LIMIT_COOLDOWN_MS);

        cooldownTimerRef.current = setTimeout(() => {
            setClockTick(Date.now());
            setRateLimitedUntil(0);
            cooldownTimerRef.current = null;
        }, RATE_LIMIT_COOLDOWN_MS);
    }

    function applyRateLimitMessage(requestError) {
        if (getErrorCode(requestError) !== ERROR_CODES.TOO_MANY_REQUESTS) {
            return false;
        }

        setError("요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
        startRateLimitCooldown();
        return true;
    }

    async function handleRetry() {
        if (!jobId) {
            return;
        }

        try {
            setRetrying(true);
            setError("");

            await retryAnalysis(jobId, {
                useVideoLlm: true,
                useOpenAi: true,
            });

            await fetchStatusOnce(jobId);
            startStatusPolling(jobId);
        } catch (requestError) {
            if (!applyRateLimitMessage(requestError)) {
                setError(getErrorMessage(
                    requestError,
                    "분석 재시도 중 오류가 발생했습니다."
                ));
            }

            if (getErrorCode(requestError) !== ERROR_CODES.TOO_MANY_REQUESTS) {
                try {
                    await fetchStatusOnce(jobId);
                } catch {
                    // 재시도 실패 후 상태 조회 실패는 기존 오류 메시지를 유지합니다.
                }
            }
        } finally {
            setRetrying(false);
        }
    }

    async function handleCancel() {
        if (!jobId) {
            return;
        }

        const confirmMessage = isQueued
            ? "대기 중인 분석을 취소하시겠습니까? 즉시 취소됩니다."
            : "진행 중인 분석을 취소하시겠습니까? 현재 실행 중인 단계가 끝난 뒤 취소됩니다.";

        const confirmed = await confirm(confirmMessage);

        if (!confirmed) {
            return;
        }

        try {
            setCancelling(true);
            setError("");

            await cancelAnalysis(jobId);
            await fetchStatusOnce(jobId);
            startStatusPolling(jobId);
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "분석 취소 요청 중 오류가 발생했습니다."
            ));

            try {
                await fetchStatusOnce(jobId);
            } catch {
                // 취소 요청 실패 후 상태 조회 실패는 기존 오류 메시지를 유지합니다.
            }
        } finally {
            setCancelling(false);
        }
    }

    async function handleDelete() {
        if (!jobId) {
            return;
        }

        const confirmed = await confirm(
            "이 분석 결과를 삭제하시겠습니까? 업로드 영상과 결과 JSON 파일도 함께 삭제됩니다."
        );

        if (!confirmed) {
            return;
        }

        try {
            stopPolling();
            setDeleting(true);
            setError("");

            await deleteResult(jobId);

            navigate("/results");
        } catch (requestError) {
            setError(getErrorMessage(
                requestError,
                "분석 결과 삭제 중 오류가 발생했습니다."
            ));
        } finally {
            setDeleting(false);
        }
    }

    function formatScore(value) {
        if (value === null || value === undefined) {
            return "-";
        }

        return value;
    }

    function getScoreClassName(value) {
        if (typeof value !== "number") {
            return "score-value muted";
        }

        if (value >= 85) {
            return "score-value excellent";
        }

        if (value >= 70) {
            return "score-value good";
        }

        if (value >= 50) {
            return "score-value normal";
        }

        return "score-value poor";
    }

    function getMetricLevelClassName(value) {
        if (typeof value !== "number") {
            return "metric-value muted";
        }

        if (value >= 85) {
            return "metric-value excellent";
        }

        if (value >= 70) {
            return "metric-value good";
        }

        if (value >= 50) {
            return "metric-value normal";
        }

        return "metric-value poor";
    }

    function renderMetricCard(label, value, helper) {
        return (
            <article className="metric-card">
                <span>{label}</span>
                <strong className={getMetricLevelClassName(value)}>
                    {formatScore(value)}
                </strong>
                {helper && <p>{helper}</p>}
            </article>
        );
    }

    if (
        !loading &&
        !resultData &&
        loadErrorCode === ERROR_CODES.ANALYSIS_JOB_ACCESS_DENIED
    ) {
        return (
            <section className="page-section">
                <PageHeader
                    eyebrow="Result Detail"
                    title="분석 결과 상세"
                    description={`현재 조회 대상 jobId: ${jobId}`}
                />

                <EmptyState
                    title="접근 권한이 없는 결과입니다."
                    description="본인이 업로드한 분석 결과만 조회할 수 있습니다."
                />

                <div className="button-row">
                    <Link to="/results" className="primary-button">
                        목록으로 이동
                    </Link>
                </div>
            </section>
        );
    }

    if (
        !loading &&
        !resultData &&
        loadErrorCode === ERROR_CODES.ANALYSIS_JOB_NOT_FOUND
    ) {
        return (
            <section className="page-section">
                <PageHeader
                    eyebrow="Result Detail"
                    title="분석 결과 상세"
                    description={`현재 조회 대상 jobId: ${jobId}`}
                />

                <EmptyState
                    title="삭제되었거나 존재하지 않는 결과입니다."
                    description="결과 목록에서 현재 접근 가능한 분석 결과를 다시 확인하세요."
                />

                <div className="button-row">
                    <Link to="/results" className="primary-button">
                        목록으로 이동
                    </Link>
                </div>
            </section>
        );
    }

    if (loading) {
        return (
            <section className="page-section">
                <PageHeader
                    eyebrow="Result Detail"
                    title="분석 결과 상세"
                    description="분석 결과를 불러오는 중입니다."
                />

                <EmptyState
                    loading
                    title="로딩 중"
                    description="잠시만 기다려 주세요."
                />
            </section>
        );
    }

    if (error && !resultData) {
        return (
            <section className="page-section">
                <PageHeader
                    eyebrow="Result Detail"
                    title="분석 결과 상세"
                    description={`현재 조회 대상 jobId: ${jobId}`}
                />

                <StateMessage type="error">{error}</StateMessage>

                <div className="button-row">
                    <button
                        type="button"
                        className="secondary-button"
                        onClick={loadResult}
                    >
                        다시 불러오기
                    </button>

                    <Link to="/results" className="primary-button">
                        목록으로 이동
                    </Link>
                </div>
            </section>
        );
    }

    return (
        <section className="page-section">
            <div className="print-only">
                <p>AI Presentation Coach — 분석 결과 리포트</p>
                <p>생성일: {new Date().toLocaleString("ko-KR")} · jobId: {jobId}</p>
            </div>

            <AnimatedSection className="detail-header-card">
                <div>
                    <p className="eyebrow">Result Detail</p>
                    <h1>분석 결과 상세</h1>
                    <p>
                        현재 조회 대상 jobId: <code>{jobId}</code>
                    </p>
                </div>

                <div className="detail-actions no-print">
                    <button
                        type="button"
                        className="secondary-button no-print"
                        onClick={() => window.print()}
                    >
                        인쇄 / PDF로 저장
                    </button>

                    <Link to="/results" className="secondary-button">
                        목록으로
                    </Link>

                    {(isFailed || isCancelled) && (
                        <button
                            type="button"
                            className="primary-button"
                            onClick={handleRetry}
                            disabled={retrying || polling || deleting || cancelling || isRateLimited}
                        >
                            {retrying || polling ? "재시도 진행 중..." : "분석 재시도"}
                        </button>
                    )}

                    {isRunning && (
                        <button
                            type="button"
                            className="secondary-button"
                            onClick={handleCancel}
                            disabled={retrying || deleting || cancelling || isRateLimited}
                        >
                            {cancelling
                                ? "취소 요청 중..."
                                : isQueued
                                    ? "대기 중 취소"
                                    : "분석 취소"}
                        </button>
                    )}

                    <button
                        type="button"
                        className="danger-button"
                        onClick={handleDelete}
                        disabled={retrying || polling || deleting || cancelling || isRunning}
                    >
                        {deleting ? "삭제 중..." : "삭제"}
                    </button>
                </div>
            </AnimatedSection>

            <StateMessage type="error">{error}</StateMessage>

            {dataIssue && (
                <p className="result-data-issue" role="alert">
                    ⚠ {dataIssueDescription || "이 결과의 일부 데이터에 문제가 있습니다."}
                </p>
            )}

            <StateMessage type="polling" messageKey={currentStatus}>
                {retrying || polling || cancelling || isRunning ? (
                    <>
                        분석 상태를 자동으로 확인하는 중입니다. 현재 상태:{" "}
                        <StatusBadge
                            status={currentStatus}
                            label={currentStatusDescription}
                        />
                        {analysisStatus?.startedAt && (
                            <>
                                {" "}
                                (접수 시각: {formatDateTime(analysisStatus.startedAt)})
                            </>
                        )}
                    </>
                ) : (
                    ""
                )}
            </StateMessage>

            <StateMessage type="success">
                {isCancelled
                    ? "분석이 취소되었습니다. 필요하면 다시 시도할 수 있습니다."
                    : isCompleted
                    ? "분석이 완료되었습니다. 최신 결과가 화면에 반영되었습니다."
                    : ""}
            </StateMessage>

            <AnimatedSection className="score-panel">
                <div className="score-panel-main">
                    <span className="score-panel-label">종합 등급</span>
                    <strong>{scoreSummary.level || "-"}</strong>
                    <p>
                        상태:{" "}
                        <StatusBadge
                            status={currentStatus}
                            label={currentStatusDescription}
                        />
                    </p>
                </div>

                <div className="score-grid">
                    {scoreItems.map((item) => (
                        <article className="score-card" key={item.label}>
                            <span>{item.label}</span>
                            <strong className={getScoreClassName(item.value)}>
                                {formatScore(item.value)}
                            </strong>
                        </article>
                    ))}
                </div>
            </AnimatedSection>

            <AnimatedSection>
                <ResultSummaryOverview
                    scoreSummary={scoreSummary}
                    videoInfo={videoInfo}
                    audioInfo={audioInfo}
                    fillerInfo={fillerInfo}
                    poseInfo={poseInfo}
                    faceInfo={faceInfo}
                    gestureInfo={gestureInfo}
                    emotionInfo={emotionInfo}
                />
            </AnimatedSection>

            <AnimatedSection>
                <ResultScoreChart scoreSummary={scoreSummary} />
            </AnimatedSection>

            <AnimatedSection className="detail-grid">
                <AnalysisMetricBarChart
                    poseInfo={poseInfo}
                    faceInfo={faceInfo}
                    gestureInfo={gestureInfo}
                    emotionInfo={emotionInfo}
                />

                <EmotionDoughnutChart emotionCounts={emotionCounts} />
            </AnimatedSection>

            <AnimatedSection className="no-print">
                <VideoPlayerSection
                    jobId={jobId}
                    notableMoments={notableMoments}
                    seekControllerRef={videoSeekControllerRef}
                />
            </AnimatedSection>

            <AnimatedSection>
                <VideoInfoSection videoInfo={videoInfo} frameInfo={frameInfo} />
            </AnimatedSection>

            <AnimatedSection>
                <OpenAiFeedbackStatusSection feedback={feedback} pipeline={pipeline} />
            </AnimatedSection>

            <AnimatedSection>
                <FeedbackSection
                    feedback={feedback}
                    visualAnalysis={visualAnalysis}
                    pipeline={pipeline}
                    onSeekToTime={handleSeekToObservation}
                />
            </AnimatedSection>

            <AnimatedSection className="no-print">
                <CoachChatSection
                    jobId={jobId}
                    isCompleted={isCompleted}
                    disabledReason={coachDisabledReason}
                />
            </AnimatedSection>

            <AnimatedSection>
                <PracticePlanSection practicePlan={practicePlan} />
            </AnimatedSection>

            <AnimatedSection>
                <TimelineFeedbackSection timelineFeedback={timelineFeedback} />
            </AnimatedSection>

            <AnimatedSection>
                <AudioAnalysisSection
                    audioInfo={audioInfo}
                    renderMetricCard={renderMetricCard}
                />
            </AnimatedSection>

            <AnimatedSection>
                <SttSection
                    sttInfo={sttInfo}
                    audioExtractionInfo={audioExtractionInfo}
                    sttSegments={sttSegments}
                />
            </AnimatedSection>

            <AnimatedSection>
                <FillerAnalysisSection
                    fillerInfo={fillerInfo}
                    fillerWords={fillerWords}
                    renderMetricCard={renderMetricCard}
                />
            </AnimatedSection>

            <AnimatedSection>
                <PoseAnalysisSection
                    poseInfo={poseInfo}
                    poseFrameResults={poseFrameResults}
                    renderMetricCard={renderMetricCard}
                />
            </AnimatedSection>

            <AnimatedSection>
                <GestureAnalysisSection
                    gestureInfo={gestureInfo}
                    gestureFrameResults={gestureFrameResults}
                    renderMetricCard={renderMetricCard}
                />
            </AnimatedSection>

            <AnimatedSection>
                <FaceAnalysisSection
                    faceInfo={faceInfo}
                    faceFrameResults={faceFrameResults}
                    renderMetricCard={renderMetricCard}
                />
            </AnimatedSection>

            <AnimatedSection>
                <EmotionAnalysisSection
                    emotionInfo={emotionInfo}
                    emotionCounts={emotionCounts}
                    emotionFrameResults={emotionFrameResults}
                    renderMetricCard={renderMetricCard}
                />
            </AnimatedSection>

            <AnimatedSection>
                <PipelineSection pipeline={pipeline} />
            </AnimatedSection>
        </section>
    );
}

export default ResultDetailPage;
