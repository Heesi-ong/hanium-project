import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

import { buildOverlayFrameUrl } from "../../api/resultAssets";
import { formatTimestamp } from "./resultDetailFormatters";

// 1초 간격으로 추출한 장면 위에 MediaPipe가 검출한 어깨·팔·손목 골격과 어깨 균형선을
// 그린 이미지를 갤러리로 보여줍니다. 이미지는 결과 소유자만 접근 가능한 백엔드
// 엔드포인트(/api/results/{jobId}/frames/{fileName})에서 <img>로 직접 불러옵니다.

function FrameGallerySection({ jobId, frameGallery }) {
    const frames = Array.isArray(frameGallery) ? frameGallery : [];
    const [activeFrame, setActiveFrame] = useState(null);
    const closeButtonRef = useRef(null);
    const triggerButtonRef = useRef(null);

    useEffect(() => {
        if (!activeFrame) {
            return undefined;
        }

        const previousOverflow = document.body.style.overflow;
        document.body.style.overflow = "hidden";
        closeButtonRef.current?.focus();

        function handleKeyDown(event) {
            if (event.key === "Escape") {
                event.preventDefault();
                setActiveFrame(null);
            }

            if (event.key === "Tab") {
                event.preventDefault();
                closeButtonRef.current?.focus();
            }
        }

        document.addEventListener("keydown", handleKeyDown);

        return () => {
            document.removeEventListener("keydown", handleKeyDown);
            document.body.style.overflow = previousOverflow;
            triggerButtonRef.current?.focus();
        };
    }, [activeFrame]);

    if (!jobId || frames.length === 0) {
        return null;
    }

    const detectedCount = frames.filter((frame) => frame.poseDetected).length;
    const gestureCount = frames.filter((frame) => frame.gestureDetected).length;

    function openFrame(frame, imageUrl, triggerButton) {
        triggerButtonRef.current = triggerButton;
        setActiveFrame({ ...frame, imageUrl });
    }

    function closeFrame() {
        setActiveFrame(null);
    }

    return (
        <article
            className="detail-card wide result-evidence-card result-frame-gallery-card"
            aria-labelledby="frame-gallery-title"
        >
            <header className="result-evidence-card-header">
                <div>
                    <span className="result-evidence-kicker">Visual evidence</span>
                    <h2 id="frame-gallery-title">근거 프레임</h2>
                    <p>
                        MediaPipe 오버레이가 적용된 실제 분석 샘플입니다. 프레임을 선택하면
                        원본 비율로 확대됩니다.
                    </p>
                </div>
                <span className="result-evidence-count">전체 {frames.length}장</span>
            </header>

            <div className="frame-gallery-legend" aria-label="프레임 분석 요약">
                <span><b>{detectedCount}</b> 포즈 검출</span>
                <span><b>{gestureCount}</b> 제스처 검출</span>
                <span>선·점은 분석 엔진이 기록한 오버레이</span>
            </div>

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
                                aria-label={`${formatTimestamp(frame.timestampSec)} 지점 분석 프레임 확대, ${frame.poseDetected ? "포즈 검출" : "포즈 미검출"}${frame.gestureDetected ? ", 제스처 검출" : ""}`}
                                onClick={(event) =>
                                    openFrame(frame, imageUrl, event.currentTarget)
                                }
                            >
                                <img
                                    src={imageUrl}
                                    alt={`${formatTimestamp(frame.timestampSec)} 지점 분석 프레임`}
                                    loading="lazy"
                                />
                                <span className="frame-gallery-expand" aria-hidden="true">
                                    <svg viewBox="0 0 24 24">
                                        <path d="M9 5H5v4M15 5h4v4M9 19H5v-4M15 19h4v-4" />
                                    </svg>
                                </span>
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

            {activeFrame && createPortal(
                <div
                    className="frame-gallery-lightbox result-frame-gallery-lightbox"
                    role="dialog"
                    aria-modal="true"
                    aria-label="분석 프레임 확대 보기"
                    onMouseDown={(event) => {
                        if (event.target === event.currentTarget) {
                            closeFrame();
                        }
                    }}
                >
                    <figure>
                        <img
                            src={activeFrame.imageUrl}
                            alt={`${formatTimestamp(activeFrame.timestampSec)} 지점 분석 프레임 확대`}
                        />
                        <figcaption>
                            <div>
                                <strong>{formatTimestamp(activeFrame.timestampSec)}</strong>
                                <span>
                                    {activeFrame.poseDetected ? "포즈 검출됨" : "포즈 미검출"}
                                    {activeFrame.gestureDetected ? " · 제스처 검출됨" : ""}
                                </span>
                            </div>
                            <button
                                ref={closeButtonRef}
                                type="button"
                                className="secondary-button"
                                onClick={closeFrame}
                            >
                                닫기
                            </button>
                        </figcaption>
                    </figure>
                </div>,
                document.body
            )}
        </article>
    );
}

export default FrameGallerySection;
