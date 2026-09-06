import { formatFileSize, formatNumber } from "./resultDetailFormatters";

function formatMetric(value, suffix = "") {
    const formatted = formatNumber(value);
    return formatted === "-" ? formatted : `${formatted}${suffix}`;
}

function VideoInfoSection({ videoInfo, frameInfo }) {
    const hasResolution =
        typeof videoInfo?.width === "number" &&
        typeof videoInfo?.height === "number";

    const metrics = [
        {
            label: "영상 길이",
            value: formatMetric(videoInfo?.durationSec, "초"),
            helper: "분석 대상 발표의 전체 재생 시간",
        },
        {
            label: "해상도",
            value: hasResolution
                ? `${videoInfo.width} × ${videoInfo.height}`
                : "-",
            helper: "업로드된 원본 영상의 화면 크기",
        },
        {
            label: "초당 프레임",
            value: formatMetric(videoInfo?.fps, " FPS"),
            helper: "원본 영상에 기록된 프레임 속도",
        },
        {
            label: "파일 크기",
            value: formatFileSize(videoInfo?.fileSize),
            helper: "업로드된 원본 영상의 저장 용량",
        },
        {
            label: "전체 프레임",
            value: formatMetric(videoInfo?.frameCount, "개"),
            helper: "원본 영상에 포함된 총 프레임 수",
        },
        {
            label: "분석 샘플",
            value: formatMetric(frameInfo?.savedCount, "개"),
            helper: "자세·제스처 분석에 사용한 추출 프레임",
        },
    ];

    return (
        <article
            className="detail-card wide result-evidence-card result-video-info-card"
            aria-labelledby="result-video-info-title"
        >
            <header className="result-evidence-card-header">
                <div>
                    <span className="result-evidence-kicker">Source profile</span>
                    <h2 id="result-video-info-title">영상 정보</h2>
                    <p>
                        원본 영상과 실제 분석에 사용된 프레임 정보를 구분해 확인하세요.
                    </p>
                </div>
                <span className="result-evidence-count" aria-label="영상 정보 6개 항목">
                    6개 항목
                </span>
            </header>

            <div className="result-video-info-grid">
                {metrics.map((metric, index) => (
                    <div className="result-video-info-metric" key={metric.label}>
                        <span className="result-video-info-index" aria-hidden="true">
                            {String(index + 1).padStart(2, "0")}
                        </span>
                        <div>
                            <span className="result-video-info-label">{metric.label}</span>
                            <strong>{metric.value}</strong>
                            <p>{metric.helper}</p>
                        </div>
                    </div>
                ))}
            </div>
        </article>
    );
}

export default VideoInfoSection;
