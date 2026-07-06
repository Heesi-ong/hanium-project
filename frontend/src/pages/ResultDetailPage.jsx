import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import AnalysisMetricBarChart from "../components/chart/AnalysisMetricBarChart";
import EmotionDoughnutChart from "../components/chart/EmotionDoughnutChart";
import ResultScoreChart from "../components/chart/ResultScoreChart";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import AudioAnalysisSection from "../components/result-detail/AudioAnalysisSection";
import EmotionAnalysisSection from "../components/result-detail/EmotionAnalysisSection";
import FaceAnalysisSection from "../components/result-detail/FaceAnalysisSection";
import FeedbackSection from "../components/result-detail/FeedbackSection";
import FillerAnalysisSection from "../components/result-detail/FillerAnalysisSection";
import GestureAnalysisSection from "../components/result-detail/GestureAnalysisSection";
import OpenAiFeedbackStatusSection from "../components/result-detail/OpenAiFeedbackStatusSection";
import PipelineSection from "../components/result-detail/PipelineSection";
import PoseAnalysisSection from "../components/result-detail/PoseAnalysisSection";
import PracticePlanSection from "../components/result-detail/PracticePlanSection";
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

const POLLING_INTERVAL_MS = 1500;
const POLLING_TIMEOUT_MS = 120000;
const RATE_LIMIT_COOLDOWN_MS = 12000;

const RUNNING_STATUSES = [
    "BASIC_ANALYZING",
    "VIDEO_LLM_ANALYZING",
    "COMPACTING",
    "OPENAI_GENERATING",
    "MERGING_RESULT",
];

function ResultDetailPage() {
    const { jobId } = useParams();
    const navigate = useNavigate();

    const pollingTimerRef = useRef(null);
    const pollingStartedAtRef = useRef(null);
    const cooldownTimerRef = useRef(null);

    const [resultData, setResultData] = useState(null);
    const [analysisStatus, setAnalysisStatus] = useState(null);
    const [loading, setLoading] = useState(true);
    const [retrying, setRetrying] = useState(false);
    const [cancelling, setCancelling] = useState(false);
    const [polling, setPolling] = useState(false);
    const [deleting, setDeleting] = useState(false);
    const [error, setError] = useState("");
    const [loadErrorCode, setLoadErrorCode] = useState("");
    const [rateLimitedUntil, setRateLimitedUntil] = useState(0);

    const result = resultData?.result || {};

    const scoreSummary = result.scoreSummary || {};
    const basicAnalysis = result.basicAnalysis || {};
    const visualAnalysis = result.visualAnalysis || {};
    const feedback = result.feedback || {};
    const practicePlan = result.practicePlan || [];
    const timelineFeedback = result.timelineFeedback || [];
    const pipeline = result.pipeline || {};

    const videoInfo = basicAnalysis.videoInfo || {};
    const frameInfo = basicAnalysis.frame || {};
    const poseInfo = basicAnalysis.pose || {};
    const gestureInfo = basicAnalysis.gesture || {};
    const audioInfo = basicAnalysis.audio || {};
    const fillerInfo = basicAnalysis.filler || {};
    const faceInfo = basicAnalysis.face || {};
    const emotionInfo = basicAnalysis.emotion || {};

    const sttInfo = audioInfo.stt || {};
    const audioExtractionInfo = audioInfo.audioExtraction || {};

    const sttSegments = Array.isArray(sttInfo.segments)
        ? sttInfo.segments
        : [];

    const fillerWords = Array.isArray(fillerInfo.fillerWords)
        ? fillerInfo.fillerWords
        : [];

    const poseFrameResults = Array.isArray(poseInfo.frameResults)
        ? poseInfo.frameResults
        : [];

    const gestureFrameResults = Array.isArray(gestureInfo.frameResults)
        ? gestureInfo.frameResults
        : [];

    const faceFrameResults = Array.isArray(faceInfo.frameResults)
        ? faceInfo.frameResults
        : [];

    const emotionFrameResults = Array.isArray(emotionInfo.frameResults)
        ? emotionInfo.frameResults
        : [];

    const emotionCounts = emotionInfo.emotionState?.emotionCounts || {};

    const currentStatus = analysisStatus?.status || result.status || null;
    const currentStatusDescription =
        analysisStatus?.statusDescription || currentStatus || "-";

    const isFailed = currentStatus === "FAILED";
    const isCancelled = currentStatus === "CANCELLED";
    const isCompleted = currentStatus === "COMPLETED";
    const isRunning = RUNNING_STATUSES.includes(currentStatus);
    const isRateLimited = rateLimitedUntil > Date.now();

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

    useEffect(() => {
        loadResult();

        return () => {
            stopPolling();
            stopRateLimitCooldown();
        };
    }, [jobId]);

    async function loadResult() {
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
                setAnalysisStatus({
                    jobId,
                    status: response.data.result.status,
                    statusDescription: response.data.result.status,
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
    }

    async function fetchStatusOnce(targetJobId) {
        const response = await getAnalysisStatus(targetJobId);
        setAnalysisStatus(response.data);
        return response.data;
    }

    function startStatusPolling(targetJobId) {
        stopPolling();

        pollingStartedAtRef.current = Date.now();
        setPolling(true);

        pollingTimerRef.current = setInterval(async () => {
            try {
                const elapsedMs = Date.now() - pollingStartedAtRef.current;

                if (elapsedMs > POLLING_TIMEOUT_MS) {
                    stopPolling();
                    setError("분석 상태 확인 시간이 초과되었습니다. 잠시 후 다시 조회하세요.");
                    return;
                }

                const statusData = await fetchStatusOnce(targetJobId);

                if (statusData.status === "COMPLETED") {
                    stopPolling();
                    await loadResult();
                    return;
                }

                if (statusData.status === "FAILED") {
                    stopPolling();
                    await loadResult();
                    setError(statusData.failReason || "분석 재시도가 실패했습니다.");
                    return;
                }

                if (statusData.status === "CANCELLED") {
                    stopPolling();
                    await loadResult();
                    setError("");
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

    function stopPolling() {
        if (pollingTimerRef.current) {
            clearInterval(pollingTimerRef.current);
            pollingTimerRef.current = null;
        }

        pollingStartedAtRef.current = null;
        setPolling(false);
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

        const confirmed = window.confirm(
            "진행 중인 분석을 취소하시겠습니까? 현재 실행 중인 단계가 끝난 뒤 취소됩니다."
        );

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

        const confirmed = window.confirm(
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
            <div className="detail-header-card">
                <div>
                    <p className="eyebrow">Result Detail</p>
                    <h1>분석 결과 상세</h1>
                    <p>
                        현재 조회 대상 jobId: <code>{jobId}</code>
                    </p>
                </div>

                <div className="detail-actions">
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
                            {cancelling ? "취소 요청 중..." : "분석 취소"}
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
            </div>

            <StateMessage type="error">{error}</StateMessage>

            <StateMessage type="polling">
                {retrying || polling || cancelling || isRunning ? (
                    <>
                        분석 상태를 자동으로 확인하는 중입니다. 현재 상태:{" "}
                        <StatusBadge
                            status={currentStatus}
                            label={currentStatusDescription}
                        />
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

            <div className="score-panel">
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
            </div>

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

            <ResultScoreChart scoreSummary={scoreSummary} />

            <div className="detail-grid">
                <AnalysisMetricBarChart
                    poseInfo={poseInfo}
                    faceInfo={faceInfo}
                    gestureInfo={gestureInfo}
                    emotionInfo={emotionInfo}
                />

                <EmotionDoughnutChart emotionCounts={emotionCounts} />
            </div>

            <VideoPlayerSection jobId={jobId} />

            <VideoInfoSection videoInfo={videoInfo} frameInfo={frameInfo} />

            <OpenAiFeedbackStatusSection feedback={feedback} />

            <FeedbackSection
                feedback={feedback}
                visualAnalysis={visualAnalysis}
            />

            <PracticePlanSection practicePlan={practicePlan} />

            <TimelineFeedbackSection timelineFeedback={timelineFeedback} />

            <AudioAnalysisSection
                audioInfo={audioInfo}
                renderMetricCard={renderMetricCard}
            />

            <SttSection
                sttInfo={sttInfo}
                audioExtractionInfo={audioExtractionInfo}
                sttSegments={sttSegments}
            />

            <FillerAnalysisSection
                fillerInfo={fillerInfo}
                fillerWords={fillerWords}
                renderMetricCard={renderMetricCard}
            />

            <PoseAnalysisSection
                poseInfo={poseInfo}
                poseFrameResults={poseFrameResults}
                renderMetricCard={renderMetricCard}
            />

            <GestureAnalysisSection
                gestureInfo={gestureInfo}
                gestureFrameResults={gestureFrameResults}
                renderMetricCard={renderMetricCard}
            />

            <FaceAnalysisSection
                faceInfo={faceInfo}
                faceFrameResults={faceFrameResults}
                renderMetricCard={renderMetricCard}
            />

            <EmotionAnalysisSection
                emotionInfo={emotionInfo}
                emotionCounts={emotionCounts}
                emotionFrameResults={emotionFrameResults}
                renderMetricCard={renderMetricCard}
            />

            <PipelineSection pipeline={pipeline} />
        </section>
    );
}

export default ResultDetailPage;
