import { useState } from "react";

import { buildOverlayFrameUrl } from "../../api/resultAssets";
import { formatTimestamp } from "./resultDetailFormatters";

// 1초 간격으로 추출한 장면 위에 MediaPipe가 검출한 어깨·팔·손목 골격과 어깨 균형선을
// 그린 이미지를 갤러리로 보여줍니다. 이미지는 결과 소유자만 접근 가능한 백엔드
// 엔드포인트(/api/results/{jobId}/frames/{fileName})에서 <img>로 직접 불러옵니다.

function FrameGallerySection({ jobId, frameGallery }) {
    const frames = Array.isArray(frameGallery) ? frameGallery : [];
    const [activeFrame, setActiveFrame] = useState(null);

    if (!jobId || frames.length === 0) {
        return null;
    }

    const detectedCount = frames.filter((frame) => frame.poseDetected).length;

    return (
        <article className="detail-card wide">
            <h2>분석 프레임 미리보기</h2>

            <p className="muted-text">
                초록/파랑 선은 어깨·팔꿈치·손목을 이은 골격, 어깨를 잇는 선은 좌우 균형
                상태(초록=균형, 주황=기울어짐)를 나타냅니다. 상단 라벨은 제스처 활성
                여부입니다. 전체 {frames.length}장 중 {detectedCount}장에서 자세가
                검출됐습니다.
            </p>

            <ul className="frame-gallery-grid">
                {frames.map((frame, index) => {
                    const imageUrl = buildOverlayFrameUrl(jobId, frame.fileName);

                    return (
                        <li
                            className="frame-gallery-item"
                            key={frame.fileName ?? index}
                        >
                            <button
                                type="button"
                                className="frame-gallery-thumb"
                                onClick={() =>
                                    setActiveFrame({ ...frame, imageUrl })
                                }
                            >
                                <img
                                    src={imageUrl}
                                    alt={`${formatTimestamp(frame.timestampSec)} 지점 분석 프레임`}
                                    loading="lazy"
                                />
                            </button>

                            <div className="frame-gallery-meta">
                                <span>{formatTimestamp(frame.timestampSec)}</span>
                                <span
                                    className={`mini-badge ${frame.poseDetected ? "success" : "muted"}`}
                                >
                                    {frame.poseDetected ? "포즈 검출" : "미검출"}
                                </span>
                                {frame.gestureDetected && (
                                    <span className="mini-badge success">제스처</span>
                                )}
                            </div>
                        </li>
                    );
                })}
            </ul>

            {activeFrame && (
                <div
                    className="frame-gallery-lightbox"
                    role="dialog"
                    aria-modal="true"
                    aria-label="분석 프레임 확대 보기"
                    onClick={() => setActiveFrame(null)}
                >
                    <figure onClick={(event) => event.stopPropagation()}>
                        <img
                            src={activeFrame.imageUrl}
                            alt={`${formatTimestamp(activeFrame.timestampSec)} 지점 분석 프레임 확대`}
                        />
                        <figcaption>
                            <span>
                                {formatTimestamp(activeFrame.timestampSec)} ·{" "}
                                {activeFrame.poseDetected
                                    ? "포즈 검출됨"
                                    : "포즈 미검출"}
                            </span>
                            <button
                                type="button"
                                className="secondary-button"
                                onClick={() => setActiveFrame(null)}
                            >
                                닫기
                            </button>
                        </figcaption>
                    </figure>
                </div>
            )}
        </article>
    );
}

export default FrameGallerySection;
