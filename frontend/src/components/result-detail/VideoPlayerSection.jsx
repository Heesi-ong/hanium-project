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

    if (loading) {
        return <EmptyState title="영상을 불러오는 중입니다." />;
    }

    if (deleted) {
        return (
            <EmptyState title="원본 영상이 보존 기간 정책에 따라 삭제되어 더 이상 재생할 수 없습니다." />
        );
    }

    if (error) {
        return <StateMessage type="error">{error}</StateMessage>;
    }

    if (!videoUrl) {
        return null;
    }

    return (
        <article className="detail-card wide">
            <h2>업로드 영상</h2>
            <video
                ref={videoRef}
                controls
                preload="metadata"
                src={videoUrl}
                onLoadedMetadata={(event) => {
                    if (Number.isFinite(event.currentTarget.duration)) {
                        setMediaDurationSec(event.currentTarget.duration);
                    }
                }}
                onTimeUpdate={(event) => {
                    setCurrentTimeSec(event.currentTarget.currentTime || 0);
                }}
                style={{ width: "100%", borderRadius: 16 }}
            />
            {notableMoments.length > 0 && (
                <div className="notable-moment-list">
                    {notableMoments.map((moment) => (
                        <button
                            type="button"
                            className="secondary-button"
                            key={`${moment.category}-${moment.timestampSec}`}
                            onClick={() => handleSeekToMoment(moment.timestampSec)}
                        >
                            {formatTimestamp(moment.timestampSec)} · {moment.label}
                        </button>
                    ))}
                </div>
            )}
            <AnalysisTimelineSection
                durationSec={mediaDurationSec || durationSec}
                currentTimeSec={currentTimeSec}
                sttSegments={sttSegments}
                poseFrameResults={poseFrameResults}
                gestureFrameResults={gestureFrameResults}
                visualAnalysis={visualAnalysis}
                pipeline={pipeline}
                notableMoments={notableMoments}
                onSeekToTime={handleSeekToMoment}
            />
        </article>
    );
}

export default VideoPlayerSection;
