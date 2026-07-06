import { useEffect, useState } from "react";
import { getVideoAccessToken } from "../../api/analysisApi";
import { API_BASE_URL } from "../../api/apiClient";
import {
    ERROR_CODES,
    getErrorCode,
    getErrorMessage,
} from "../../api/errorUtils";
import EmptyState from "../EmptyState";
import StateMessage from "../StateMessage";

function VideoPlayerSection({ jobId }) {
    const [loading, setLoading] = useState(true);
    const [videoUrl, setVideoUrl] = useState("");
    const [error, setError] = useState("");
    const [deleted, setDeleted] = useState(false);

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
                controls
                preload="metadata"
                src={videoUrl}
                style={{ width: "100%", borderRadius: 16 }}
            />
        </article>
    );
}

export default VideoPlayerSection;
