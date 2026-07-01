import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import StateMessage from "../components/StateMessage";
import StatusBadge from "../components/StatusBadge";
import {
    deleteResult,
    getAnalysisStatus,
    getResult,
    retryAnalysis,
} from "../api/analysisApi";

const POLLING_INTERVAL_MS = 1500;
const POLLING_TIMEOUT_MS = 120000;

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

    const [resultData, setResultData] = useState(null);
    const [analysisStatus, setAnalysisStatus] = useState(null);
    const [loading, setLoading] = useState(true);
    const [retrying, setRetrying] = useState(false);
    const [polling, setPolling] = useState(false);
    const [deleting, setDeleting] = useState(false);
    const [error, setError] = useState("");

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
    const audioInfo = basicAnalysis.audio || {};
    const fillerInfo = basicAnalysis.filler || {};
    const faceInfo = basicAnalysis.face || {};

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

    const faceFrameResults = Array.isArray(faceInfo.frameResults)
        ? faceInfo.frameResults
        : [];

    const currentStatus = analysisStatus?.status || result.status || null;
    const currentStatusDescription =
        analysisStatus?.statusDescription || currentStatus || "-";

    const isFailed = currentStatus === "FAILED";
    const isCompleted = currentStatus === "COMPLETED";
    const isRunning = RUNNING_STATUSES.includes(currentStatus);

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
        ],
        [scoreSummary]
    );

    useEffect(() => {
        loadResult();

        return () => {
            stopPolling();
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
            setError(
                requestError.message ||
                "분석 결과 상세 정보를 불러오는 중 오류가 발생했습니다."
            );
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
                }
            } catch (requestError) {
                stopPolling();
                setError(
                    requestError.message ||
                    "분석 상태 확인 중 오류가 발생했습니다."
                );
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
            setError(
                requestError.message ||
                "분석 재시도 중 오류가 발생했습니다."
            );

            try {
                await fetchStatusOnce(jobId);
            } catch {
                // 재시도 실패 후 상태 조회 실패는 기존 오류 메시지를 유지합니다.
            }
        } finally {
            setRetrying(false);
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
            setError(
                requestError.message ||
                "분석 결과 삭제 중 오류가 발생했습니다."
            );
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

    function formatNumber(value, digits = 2) {
        if (value === null || value === undefined || value === "") {
            return "-";
        }

        if (typeof value !== "number") {
            return value;
        }

        return Number.isInteger(value) ? value : value.toFixed(digits);
    }

    function formatPercent(value) {
        if (value === null || value === undefined || typeof value !== "number") {
            return "-";
        }

        return `${Math.round(value * 100)}%`;
    }

    function formatFileSize(fileSize) {
        if (!fileSize && fileSize !== 0) {
            return "-";
        }

        return `${(fileSize / 1024 / 1024).toFixed(2)}MB`;
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

    function formatObjectValue(value) {
        if (value === null || value === undefined) {
            return "-";
        }

        if (typeof value === "object") {
            return JSON.stringify(value, null, 2);
        }

        return String(value);
    }

    function renderKeyValueSection(title, data) {
        const entries = Object.entries(data || {});

        return (
            <article className="detail-card">
                <h2>{title}</h2>

                {entries.length === 0 ? (
                    <p className="muted-text">표시할 데이터가 없습니다.</p>
                ) : (
                    <div className="key-value-list">
                        {entries.map(([key, value]) => (
                            <div className="key-value-item" key={key}>
                                <span>{key}</span>

                                {typeof value === "object" && value !== null ? (
                                    <pre>{formatObjectValue(value)}</pre>
                                ) : (
                                    <strong>{formatObjectValue(value)}</strong>
                                )}
                            </div>
                        ))}
                    </div>
                )}
            </article>
        );
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

    function formatEyeContactLevel(level) {
        if (level === "good") {
            return "좋음";
        }

        if (level === "normal") {
            return "보통";
        }

        if (level === "weak") {
            return "약함";
        }

        if (level === "poor") {
            return "부족";
        }

        return "알 수 없음";
    }

    function formatGazeDirection(direction) {
        if (direction === "left") {
            return "왼쪽";
        }

        if (direction === "right") {
            return "오른쪽";
        }

        if (direction === "center") {
            return "중앙";
        }

        return "알 수 없음";
    }

    function formatAnalysisMethod(method) {
        if (method === "duration_based_estimation") {
            return "영상 길이 기반 추정";
        }

        if (method === "audio_extracted_duration_based_estimation") {
            return "오디오 추출 + 길이 기반 추정";
        }

        if (method === "stt_based_analysis") {
            return "STT 기반 분석";
        }

        if (method === "stt_based_filler_detection") {
            return "STT 기반 필러 탐지";
        }

        if (method === "faster_whisper") {
            return "faster-whisper";
        }

        if (!method) {
            return "-";
        }

        return method;
    }

    function formatSttSuccess(success) {
        if (success === true) {
            return "성공";
        }

        if (success === false) {
            return "실패";
        }

        return "-";
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

                    {isFailed && (
                        <button
                            type="button"
                            className="primary-button"
                            onClick={handleRetry}
                            disabled={retrying || polling || deleting}
                        >
                            {retrying || polling ? "재시도 진행 중..." : "분석 재시도"}
                        </button>
                    )}

                    <button
                        type="button"
                        className="danger-button"
                        onClick={handleDelete}
                        disabled={retrying || polling || deleting || isRunning}
                    >
                        {deleting ? "삭제 중..." : "삭제"}
                    </button>
                </div>
            </div>

            <StateMessage type="error">{error}</StateMessage>

            <StateMessage type="polling">
                {retrying || polling || isRunning
                    ? (
                        <>
                            분석 상태를 자동으로 확인하는 중입니다. 현재 상태:{" "}
                            <StatusBadge
                                status={currentStatus}
                                label={currentStatusDescription}
                            />
                        </>
                    )
                    : ""}
            </StateMessage>

            <StateMessage type="success">
                {isCompleted
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

            <article className="detail-card wide">
                <h2>영상 및 프레임 정보</h2>

                <div className="metric-grid">
                    <article className="metric-card">
                        <span>영상 길이</span>
                        <strong>{formatNumber(videoInfo.durationSec)}초</strong>
                        <p>전체 발표 영상 길이입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>FPS</span>
                        <strong>{formatNumber(videoInfo.fps)}</strong>
                        <p>초당 프레임 수입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>해상도</span>
                        <strong>
                            {videoInfo.width && videoInfo.height
                                ? `${videoInfo.width} × ${videoInfo.height}`
                                : "-"}
                        </strong>
                        <p>업로드된 영상의 가로·세로 크기입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>파일 크기</span>
                        <strong>{formatFileSize(videoInfo.fileSize)}</strong>
                        <p>업로드된 영상 파일 크기입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>추출 프레임</span>
                        <strong>{frameInfo.savedCount ?? 0}개</strong>
                        <p>자세·얼굴 분석에 사용된 샘플 프레임 수입니다.</p>
                    </article>
                </div>
            </article>

            <article className="detail-card wide">
                <h2>음성 분석 요약</h2>

                <div className="metric-grid">
                    {renderMetricCard(
                        "음성 점수",
                        audioInfo.speechScore,
                        "말하기 속도 점수와 침묵 점수를 합산한 음성 평가 점수입니다."
                    )}

                    {renderMetricCard(
                        "말하기 속도 점수",
                        audioInfo.speechSpeedScore,
                        "WPM이 적정 범위에 가까울수록 높은 점수입니다."
                    )}

                    {renderMetricCard(
                        "침묵 점수",
                        audioInfo.silenceScore,
                        "전체 길이 대비 침묵 비율이 낮을수록 높은 점수입니다."
                    )}

                    <article className="metric-card">
                        <span>WPM</span>
                        <strong>{audioInfo.speechSpeedWpm ?? 0}</strong>
                        <p>STT 발화 구간과 단어 수를 기준으로 계산한 분당 단어 수입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>단어 수</span>
                        <strong>{audioInfo.estimatedWordCount ?? 0}개</strong>
                        <p>STT transcript 기준 단어 수입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>발화 시간</span>
                        <strong>{formatNumber(audioInfo.estimatedSpeechDurationSec)}초</strong>
                        <p>STT segment 기준 실제 발화 시간 합계입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>침묵 시간</span>
                        <strong>{formatNumber(audioInfo.totalSilenceTime)}초</strong>
                        <p>STT segment 사이 공백으로 계산한 침묵 시간입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>침묵 횟수</span>
                        <strong>{audioInfo.silenceCount ?? 0}회</strong>
                        <p>1초 이상 발화 공백이 발생한 횟수입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>침묵 비율</span>
                        <strong>{formatPercent(audioInfo.silenceRatio)}</strong>
                        <p>전체 발표 시간 대비 침묵 시간의 비율입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>분석 방식</span>
                        <strong>{formatAnalysisMethod(audioInfo.analysisMethod)}</strong>
                        <p>현재 음성 분석에 사용된 계산 방식입니다.</p>
                    </article>
                </div>

                {audioInfo.note && (
                    <p className="muted-text">{audioInfo.note}</p>
                )}
            </article>

            <article className="detail-card wide">
                <h2>STT 변환 결과</h2>

                <div className="metric-grid">
                    <article className="metric-card">
                        <span>STT 상태</span>
                        <strong>{formatSttSuccess(sttInfo.success)}</strong>
                        <p>음성 파일을 텍스트로 변환했는지 여부입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>STT 모델</span>
                        <strong>{sttInfo.modelSize || "-"}</strong>
                        <p>faster-whisper 모델 크기입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>언어</span>
                        <strong>{sttInfo.language || "-"}</strong>
                        <p>Whisper가 감지한 음성 언어입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>언어 확률</span>
                        <strong>{formatPercent(sttInfo.languageProbability)}</strong>
                        <p>감지 언어에 대한 모델의 추정 확률입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>Segment 수</span>
                        <strong>{sttInfo.segmentCount ?? 0}개</strong>
                        <p>STT가 나눈 발화 구간 수입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>오디오 추출</span>
                        <strong>{audioExtractionInfo.success ? "성공" : "실패"}</strong>
                        <p>영상에서 wav 오디오를 분리했는지 여부입니다.</p>
                    </article>
                </div>

                {audioExtractionInfo.audioPath && (
                    <div className="key-value-list">
                        <div className="key-value-item">
                            <span>audio.wav 저장 경로</span>
                            <strong>{audioExtractionInfo.audioPath}</strong>
                        </div>
                    </div>
                )}

                {sttInfo.error && (
                    <StateMessage type="error">{sttInfo.error}</StateMessage>
                )}

                <div className="feedback-block">
                    <h3>Transcript</h3>
                    <p>{sttInfo.transcript || "표시할 STT 변환 텍스트가 없습니다."}</p>
                </div>

                {sttSegments.length > 0 ? (
                    <div className="pose-frame-table-wrap">
                        <h3>STT Segment</h3>

                        <table className="pose-frame-table">
                            <thead>
                            <tr>
                                <th>순서</th>
                                <th>시작</th>
                                <th>끝</th>
                                <th>길이</th>
                                <th>텍스트</th>
                            </tr>
                            </thead>

                            <tbody>
                            {sttSegments.map((segment, index) => (
                                <tr key={`${segment.start}-${segment.end}-${index}`}>
                                    <td>{index + 1}</td>
                                    <td>{formatNumber(segment.start)}초</td>
                                    <td>{formatNumber(segment.end)}초</td>
                                    <td>{formatNumber(segment.duration)}초</td>
                                    <td>{segment.text || "-"}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                ) : (
                    <p className="muted-text">표시할 STT segment가 없습니다.</p>
                )}
            </article>

            <article className="detail-card wide">
                <h2>필러 분석 요약</h2>

                <div className="metric-grid">
                    {renderMetricCard(
                        "필러 점수",
                        fillerInfo.fillerScore,
                        "전체 단어 수 대비 필러 비율이 낮을수록 높은 점수입니다."
                    )}

                    <article className="metric-card">
                        <span>필러 수</span>
                        <strong>{fillerInfo.fillerCount ?? 0}개</strong>
                        <p>STT transcript에서 감지한 필러 표현 수입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>필러 비율</span>
                        <strong>{formatPercent(fillerInfo.fillerRatio)}</strong>
                        <p>전체 단어 수 대비 필러 표현의 비율입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>분석 방식</span>
                        <strong>{formatAnalysisMethod(fillerInfo.analysisMethod)}</strong>
                        <p>현재 필러 분석에 사용된 계산 방식입니다.</p>
                    </article>
                </div>

                {fillerInfo.note && (
                    <p className="muted-text">{fillerInfo.note}</p>
                )}

                {fillerWords.length > 0 ? (
                    <div className="pose-frame-table-wrap">
                        <h3>감지된 필러 표현</h3>

                        <table className="pose-frame-table">
                            <thead>
                            <tr>
                                <th>순서</th>
                                <th>필러 표현</th>
                                <th>횟수</th>
                            </tr>
                            </thead>

                            <tbody>
                            {fillerWords.map((item, index) => (
                                <tr key={`${item.word}-${index}`}>
                                    <td>{index + 1}</td>
                                    <td>{item.word}</td>
                                    <td>{item.count}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                ) : (
                    <p className="muted-text">감지된 필러 표현이 없습니다.</p>
                )}
            </article>

            <article className="detail-card wide">
                <h2>자세 분석 요약</h2>

                <div className="metric-grid">
                    {renderMetricCard(
                        "자세 점수",
                        poseInfo.postureScore,
                        "검출률과 어깨 균형 점수를 합산한 자세 평가 점수입니다."
                    )}

                    {renderMetricCard(
                        "어깨 균형 점수",
                        poseInfo.shoulderBalanceScore,
                        "좌우 어깨 높이 차이를 기반으로 계산한 균형 점수입니다."
                    )}

                    <article className="metric-card">
                        <span>자세 검출률</span>
                        <strong>{formatPercent(poseInfo.detectionRate)}</strong>
                        <p>추출된 프레임 중 포즈가 감지된 비율입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>검출 프레임</span>
                        <strong>
                            {poseInfo.detectedFrameCount ?? 0} / {poseInfo.totalFrameCount ?? 0}
                        </strong>
                        <p>포즈가 감지된 프레임 수와 전체 프레임 수입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>평균 어깨 차이</span>
                        <strong>{formatNumber(poseInfo.averageShoulderDiff, 4)}</strong>
                        <p>좌우 어깨 y좌표 차이의 평균값입니다.</p>
                    </article>
                </div>

                {poseFrameResults.length > 0 ? (
                    <div className="pose-frame-table-wrap">
                        <h3>프레임별 자세 분석</h3>

                        <table className="pose-frame-table">
                            <thead>
                            <tr>
                                <th>순서</th>
                                <th>시간</th>
                                <th>검출</th>
                                <th>어깨 차이</th>
                                <th>어깨 균형 점수</th>
                            </tr>
                            </thead>

                            <tbody>
                            {poseFrameResults.map((frameResult, index) => (
                                <tr key={`${frameResult.sequence}-${index}`}>
                                    <td>{frameResult.sequence ?? index + 1}</td>
                                    <td>{formatNumber(frameResult.timestampSec)}초</td>
                                    <td>
                                        {frameResult.poseDetected ? (
                                            <span className="mini-badge success">검출</span>
                                        ) : (
                                            <span className="mini-badge muted">미검출</span>
                                        )}
                                    </td>
                                    <td>{formatNumber(frameResult.shoulderDiff, 4)}</td>
                                    <td>{frameResult.shoulderBalanceScore ?? 0}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                ) : (
                    <p className="muted-text">표시할 프레임별 자세 분석 결과가 없습니다.</p>
                )}
            </article>

            <article className="detail-card wide">
                <h2>얼굴/시선 분석 요약</h2>

                <div className="metric-grid">
                    {renderMetricCard(
                        "시선 점수",
                        faceInfo.gazeScore,
                        "코끝 위치와 양쪽 눈 중심의 차이를 기반으로 계산한 시선 안정성 점수입니다."
                    )}

                    <article className="metric-card">
                        <span>얼굴 검출률</span>
                        <strong>{formatPercent(faceInfo.detectionRate)}</strong>
                        <p>추출된 프레임 중 얼굴이 감지된 비율입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>검출 프레임</span>
                        <strong>
                            {faceInfo.detectedFrameCount ?? 0} / {faceInfo.totalFrameCount ?? 0}
                        </strong>
                        <p>얼굴이 감지된 프레임 수와 전체 프레임 수입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>평균 코 오프셋</span>
                        <strong>{formatNumber(faceInfo.averageNoseOffset, 4)}</strong>
                        <p>눈 중심 대비 코끝 위치 차이의 평균값입니다.</p>
                    </article>

                    <article className="metric-card">
                        <span>아이컨택 수준</span>
                        <strong>{formatEyeContactLevel(faceInfo.eyeContactLevel)}</strong>
                        <p>시선 점수를 기준으로 환산한 발표 시선 안정성입니다.</p>
                    </article>
                </div>

                {faceFrameResults.length > 0 ? (
                    <div className="pose-frame-table-wrap">
                        <h3>프레임별 얼굴/시선 분석</h3>

                        <table className="pose-frame-table">
                            <thead>
                            <tr>
                                <th>순서</th>
                                <th>시간</th>
                                <th>검출</th>
                                <th>시선 방향</th>
                                <th>코 오프셋</th>
                                <th>시선 점수</th>
                            </tr>
                            </thead>

                            <tbody>
                            {faceFrameResults.map((frameResult, index) => (
                                <tr key={`${frameResult.sequence}-${index}`}>
                                    <td>{frameResult.sequence ?? index + 1}</td>
                                    <td>{formatNumber(frameResult.timestampSec)}초</td>
                                    <td>
                                        {frameResult.faceDetected ? (
                                            <span className="mini-badge success">검출</span>
                                        ) : (
                                            <span className="mini-badge muted">미검출</span>
                                        )}
                                    </td>
                                    <td>{formatGazeDirection(frameResult.gazeDirection)}</td>
                                    <td>{formatNumber(frameResult.absNoseOffset, 4)}</td>
                                    <td>{frameResult.gazeScore ?? 0}</td>
                                </tr>
                            ))}
                            </tbody>
                        </table>
                    </div>
                ) : (
                    <p className="muted-text">표시할 프레임별 얼굴/시선 분석 결과가 없습니다.</p>
                )}
            </article>

            <div className="detail-grid">
                <article className="detail-card wide">
                    <h2>종합 피드백</h2>

                    <div className="feedback-block">
                        <h3>전체 평가</h3>
                        <p>{feedback.overall || "표시할 종합 피드백이 없습니다."}</p>
                    </div>

                    <div className="feedback-columns">
                        <div>
                            <h3>강점</h3>
                            {Array.isArray(feedback.strengths) &&
                            feedback.strengths.length > 0 ? (
                                <ul>
                                    {feedback.strengths.map((item, index) => (
                                        <li key={`${item}-${index}`}>{item}</li>
                                    ))}
                                </ul>
                            ) : (
                                <p className="muted-text">표시할 강점이 없습니다.</p>
                            )}
                        </div>

                        <div>
                            <h3>개선점</h3>
                            {Array.isArray(feedback.improvements) &&
                            feedback.improvements.length > 0 ? (
                                <ul>
                                    {feedback.improvements.map((item, index) => (
                                        <li key={`${item}-${index}`}>{item}</li>
                                    ))}
                                </ul>
                            ) : (
                                <p className="muted-text">표시할 개선점이 없습니다.</p>
                            )}
                        </div>
                    </div>
                </article>

                {renderKeyValueSection("시각 분석", visualAnalysis)}
            </div>

            <article className="detail-card">
                <h2>연습 계획</h2>

                {Array.isArray(practicePlan) && practicePlan.length > 0 ? (
                    <div className="practice-list">
                        {practicePlan.map((item, index) => (
                            <div className="practice-item" key={`${item.title}-${index}`}>
                                <span>{index + 1}</span>

                                <div>
                                    <h3>{item.title || "연습 항목"}</h3>
                                    <p>{item.description || "-"}</p>
                                    {item.duration && <strong>{item.duration}</strong>}
                                </div>
                            </div>
                        ))}
                    </div>
                ) : (
                    <p className="muted-text">표시할 연습 계획이 없습니다.</p>
                )}
            </article>

            <article className="detail-card">
                <h2>타임라인 피드백</h2>

                {Array.isArray(timelineFeedback) && timelineFeedback.length > 0 ? (
                    <div className="timeline-list">
                        {timelineFeedback.map((item, index) => (
                            <div className="timeline-item" key={`${item.category}-${index}`}>
                                <span>{item.category || "feedback"}</span>
                                <h3>{item.summary || "요약 정보가 없습니다."}</h3>
                                <p>{item.recommendation || "-"}</p>
                            </div>
                        ))}
                    </div>
                ) : (
                    <p className="muted-text">표시할 타임라인 피드백이 없습니다.</p>
                )}
            </article>

            {renderKeyValueSection("파이프라인 정보", pipeline)}
        </section>
    );
}

export default ResultDetailPage;