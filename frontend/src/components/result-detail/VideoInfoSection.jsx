import { formatFileSize, formatNumber } from "./resultDetailFormatters";

function VideoInfoSection({ videoInfo, frameInfo }) {
    return (
        <article className="detail-card wide">
            <h2>영상 및 프레임 정보</h2>

            <div className="metric-grid">
                <article className="metric-card">
                    <span>영상 길이</span>
                    <strong>{formatNumber(videoInfo?.durationSec)}초</strong>
                    <p>전체 발표 영상 길이입니다.</p>
                </article>

                <article className="metric-card">
                    <span>FPS</span>
                    <strong>{formatNumber(videoInfo?.fps)}</strong>
                    <p>초당 프레임 수입니다.</p>
                </article>

                <article className="metric-card">
                    <span>해상도</span>
                    <strong>
                        {videoInfo?.width && videoInfo?.height
                            ? `${videoInfo.width} × ${videoInfo.height}`
                            : "-"}
                    </strong>
                    <p>업로드된 영상의 가로·세로 크기입니다.</p>
                </article>

                <article className="metric-card">
                    <span>파일 크기</span>
                    <strong>{formatFileSize(videoInfo?.fileSize)}</strong>
                    <p>업로드된 영상 파일 크기입니다.</p>
                </article>

                <article className="metric-card">
                    <span>추출 프레임</span>
                    <strong>{frameInfo?.savedCount ?? 0}개</strong>
                    <p>자세·제스처 분석에 사용된 샘플 프레임 수입니다.</p>
                </article>
            </div>
        </article>
    );
}

export default VideoInfoSection;
