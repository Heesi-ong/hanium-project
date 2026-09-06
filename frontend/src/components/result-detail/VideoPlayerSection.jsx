import { useCallback, useEffect, useRef, useState } from "react";
import { getVideoAccessToken } from "../../api/analysisApi";
import { API_BASE_URL } from "../../api/apiClient";
import {
    ERROR_CODES,
    getErrorCode,
    getErrorMessage,
} from "../../api/errorUtils";
import EmptyState from "../EmptyState";
import StateMessage from "../StateMessage";
import AnalysisTimelineSection from "./AnalysisTimelineSection";
import { formatTimestamp } from "./resultDetailFormatters";

function VideoPlayerSection({
    jobId,
    durationSec,
    notableMoments = [],
    sttSegments = [],
    poseFrameResults = [],
    gestureFrameResults = [],
    visualAnalysis = {},
    pipeline = {},
    seekControllerRef,
}) {
    const videoRef = useRef(null);
    const [loading, setLoading] = useState(true);
    const [videoUrl, setVideoUrl] = useState("");
    const [error, setError] = useState("");
    const [deleted, setDeleted] = useState(false);
    const [currentTimeSec, setCurrentTimeSec] = useState(0);
    const [mediaDurationSec, setMediaDurationSec] = useState(0);
    const displayedDuration = mediaDurationSec || durationSec;

    useEffect(() => {
        let ignore = false;

        async function loadVideoAccessToken() {
            if (!jobId) {
                setLoading(false);
                setError("조회할 jobId가 없습니다.");
                return;
            }

            try {
                setLoading(true);
                setVideoUrl("");
                setError("");
                setDeleted(false);

                const response = await getVideoAccessToken(jobId);
                const token = response.data?.token;

                if (!token) {
                    throw new Error("영상 재생 토큰을 찾을 수 없습니다.");
                }

                if (!ignore) {
                    setVideoUrl(
                        `${API_BASE_URL}/api/results/${jobId}/video?access=${encodeURIComponent(token)}`
                    );
                }
            } catch (requestError) {
                if (ignore) {
                    return;
                }

                if (getErrorCode(requestError) === ERROR_CODES.FILE_NOT_FOUND) {
                    setDeleted(true);
                    return;
                }

                setError(
                    getErrorMessage(
                        requestError,
                        "영상을 불러오는 중 오류가 발생했습니다."
                    )
                );
            } finally {
                if (!ignore) {
                    setLoading(false);
                }
            }
        }

        loadVideoAccessToken();

        return () => {
            ignore = true;
        };
    }, [jobId]);

    const handleSeekToMoment = useCallback((timestampSec) => {
        if (!videoRef.current || typeof timestampSec !== "number") {
            return;
        }

        videoRef.current.currentTime = timestampSec;

        const playResult = videoRef.current.play();
        if (playResult?.catch) {
            playResult.catch(() => {});
        }
    }, []);

    // 부모(ResultDetailPage)가 시각 분석 관찰 항목 클릭 시 이 영상을 해당 구간으로
    // 이동시킬 수 있도록, 시크 함수를 공유 ref에 등록합니다.
    useEffect(() => {
        if (!seekControllerRef) {
            return undefined;
        }

        seekControllerRef.current = handleSeekToMoment;
        return () => {
            if (seekControllerRef.current === handleSeekToMoment) {
                seekControllerRef.current = null;
            }
        };
    }, [seekControllerRef, handleSeekToMoment]);

    return (
        <article className="detail-card wide result-video-card" aria-labelledby="result-video-title">
            <header className="result-video-header">
                <div>
                    <span className="result-video-kicker">Evidence player</span>
                    <h2 id="result-video-title">발표 영상과 분석 신호</h2>
                    <p>
                        영상을 재생하며 발화·자세·제스처·AI 관찰이 나타난 시점을 함께 확인하세요.
                    </p>
                </div>
                <span className="protected-video-badge">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path d="M7.5 10V7.5a4.5 4.5 0 0 1 9 0V10m-10 0h11a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 17.5 20h-11A1.5 1.5 0 0 1 5 18.5v-7A1.5 1.5 0 0 1 6.5 10Z" />
                    </svg>
                    보호된 영상
                </span>
            </header>

            {loading && (
                <div className="result-video-state">
                    <EmptyState loading title="영상을 불러오는 중입니다." />
                </div>
            )}

            {!loading && deleted && (
                <div className="result-video-state">
                    <EmptyState
                        title="원본 영상이 보존 기간 정책에 따라 삭제되어 더 이상 재생할 수 없습니다."
                        description="분석 결과와 피드백은 계속 확인할 수 있습니다."
                    />
                </div>
            )}

            {!loading && !deleted && error && (
                <div className="result-video-state">
                    <StateMessage type="error">{error}</StateMessage>
                </div>
            )}

            {!loading && !deleted && !error && videoUrl && (
                <>
                    <div className="result-video-stage">
                        <video
                            ref={videoRef}
                            controls
                            preload="metadata"
                            src={videoUrl}
                            aria-label="업로드한 발표 영상"
                            onLoadedMetadata={(event) => {
                                if (Number.isFinite(event.currentTarget.duration)) {
                                    setMediaDurationSec(event.currentTarget.duration);
                                }
                            }}
                            onTimeUpdate={(event) => {
                                setCurrentTimeSec(event.currentTarget.currentTime || 0);
                            }}
                        />
                        <div className="result-video-stage-meta" aria-label="영상 재생 정보">
                            <span>현재 위치 {formatTimestamp(currentTimeSec)}</span>
                            <span>전체 {formatTimestamp(displayedDuration)}</span>
                        </div>
                    </div>

                    {notableMoments.length > 0 && (
                        <section className="notable-moments" aria-labelledby="notable-moments-title">
                            <div className="notable-moments-header">
                                <div>
                                    <h3 id="notable-moments-title">주요 순간 바로가기</h3>
                                    <p>자동 선정된 구간을 선택하면 영상이 해당 시점부터 재생됩니다.</p>
                                </div>
                                <span>{notableMoments.length}개 구간</span>
                            </div>
                            <div className="notable-moment-list">
                                {notableMoments.map((moment) => (
                                    <button
                                        type="button"
                                        className="notable-moment-button"
                                        key={`${moment.category}-${moment.timestampSec}`}
                                        onClick={() => handleSeekToMoment(moment.timestampSec)}
                                        aria-label={`${formatTimestamp(moment.timestampSec)} · ${moment.label}`}
                                    >
                                        <strong>{formatTimestamp(moment.timestampSec)}</strong>
                                        <span>{moment.label}</span>
                                    </button>
                                ))}
                            </div>
                        </section>
                    )}

                    <AnalysisTimelineSection
                        durationSec={displayedDuration}
                        currentTimeSec={currentTimeSec}
                        sttSegments={sttSegments}
                        poseFrameResults={poseFrameResults}
                        gestureFrameResults={gestureFrameResults}
                        visualAnalysis={visualAnalysis}
                        pipeline={pipeline}
                        notableMoments={notableMoments}
                        onSeekToTime={handleSeekToMoment}
                    />
                </>
            )}
        </article>
    );
}

export default VideoPlayerSection;
