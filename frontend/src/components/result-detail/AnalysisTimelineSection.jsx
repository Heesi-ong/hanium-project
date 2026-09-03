import { formatTimestamp } from "./resultDetailFormatters";
import "./analysisTimeline.css";

const AXIS_RATIOS = [0, 0.25, 0.5, 0.75, 1];

function clamp(value, min, max) {
    return Math.min(Math.max(value, min), max);
}

function toFiniteNumber(value) {
    return Number.isFinite(value) ? value : null;
}

function resolveDuration(durationSec, collections) {
    const candidates = [toFiniteNumber(durationSec) || 0];

    collections.flat().forEach((item) => {
        candidates.push(
            toFiniteNumber(item?.timestampSec) || 0,
            toFiniteNumber(item?.startSec) || 0,
            toFiniteNumber(item?.endSec) || 0,
            toFiniteNumber(item?.end) || 0
        );
    });

    return Math.max(...candidates, 1);
}

function formatGenerationMode(mode) {
    if (mode === "REAL") {
        return "실제 Video LLM";
    }
    if (mode === "FALLBACK") {
        return "Mock 대체";
    }
    if (mode === "MOCK") {
        return "Mock 분석";
    }
    return mode || "방식 확인 불가";
}

function createInterval(item, duration, fallbackLabel) {
    const start = clamp(toFiniteNumber(item?.startSec ?? item?.start) || 0, 0, duration);
    const rawEnd = toFiniteNumber(item?.endSec ?? item?.end);
    const end = clamp(rawEnd === null ? start : rawEnd, start, duration);
    const left = (start / duration) * 100;
    const width = Math.max(((end - start) / duration) * 100, 0.8);

    return {
        ...item,
        start,
        end,
        left,
        width: Math.min(width, 100 - left),
        displayLabel: item?.description || item?.text || item?.label || fallbackLabel,
    };
}

function createPoint(item, duration, fallbackLabel) {
    const timestamp = clamp(toFiniteNumber(item?.timestampSec) || 0, 0, duration);

    return {
        ...item,
        timestamp,
        left: (timestamp / duration) * 100,
        displayLabel: item?.label || fallbackLabel,
    };
}

function TimelineTrack({ track, duration, currentTimeSec, onSeekToTime }) {
    const playheadLeft = clamp((currentTimeSec / duration) * 100, 0, 100);

    return (
        <div className="analysis-timeline-row">
            <div className="analysis-timeline-label">
                <strong>{track.label}</strong>
                <span>{track.source}</span>
            </div>
            <div className={`analysis-timeline-lane ${track.className}`}>
                <span
                    className="analysis-timeline-playhead"
                    style={{ left: `${playheadLeft}%` }}
                    aria-hidden="true"
                />
                {track.kind === "interval"
                    ? track.items.map((item, index) => (
                        <button
                            type="button"
                            className="analysis-timeline-interval"
                            style={{
                                left: `${item.left}%`,
                                width: `${item.width}%`,
                            }}
                            key={`${track.key}-${item.start}-${item.end}-${index}`}
                            onClick={() => onSeekToTime?.(item.start)}
                            title={`${formatTimestamp(item.start)}–${formatTimestamp(item.end)} · ${item.displayLabel}`}
                            aria-label={`${track.label} ${formatTimestamp(item.start)} 구간으로 이동: ${item.displayLabel}`}
                        >
                            <span>{item.displayLabel}</span>
                        </button>
                    ))
                    : track.items.map((item, index) => (
                        <button
                            type="button"
                            className={`analysis-timeline-point ${item.active === false ? "is-muted" : ""}`}
                            style={{ left: `${item.left}%` }}
                            key={`${track.key}-${item.timestamp}-${index}`}
                            onClick={() => onSeekToTime?.(item.timestamp)}
                            title={`${formatTimestamp(item.timestamp)} · ${item.displayLabel}`}
                            aria-label={`${track.label} ${formatTimestamp(item.timestamp)} 지점으로 이동: ${item.displayLabel}`}
                        />
                    ))}
            </div>
        </div>
    );
}

function AnalysisTimelineSection({
    durationSec,
    currentTimeSec = 0,
    sttSegments = [],
    poseFrameResults = [],
    gestureFrameResults = [],
    visualAnalysis = {},
    pipeline = {},
    notableMoments = [],
    onSeekToTime,
}) {
    const observations = visualAnalysis?.observations || {};
    const videoLlmItems = [
        ...(Array.isArray(observations.posture) ? observations.posture : []),
        ...(Array.isArray(observations.gesture) ? observations.gesture : []),
    ];
    const collections = [
        sttSegments,
        poseFrameResults,
        gestureFrameResults,
        videoLlmItems,
        notableMoments,
    ];
    const duration = resolveDuration(durationSec, collections);
    const generationMode = visualAnalysis?.model?.generationMode
        || pipeline?.videoLlmGenerationMode
        || "UNKNOWN";

    const tracks = [
        {
            key: "speech",
            label: "발화",
            source: "정량 · STT",
            kind: "interval",
            className: "speech",
            items: sttSegments.map((item) => createInterval(item, duration, "발화 구간")),
        },
        {
            key: "posture",
            label: "자세",
            source: "정량 · MediaPipe",
            kind: "point",
            className: "posture",
            items: poseFrameResults.map((item) => createPoint({
                ...item,
                active: item?.poseDetected !== false,
                label: item?.poseDetected === false
                    ? "자세 미검출"
                    : `어깨 균형 ${item?.shoulderBalanceScore ?? "-"}점`,
            }, duration, "자세 분석")),
        },
        {
            key: "gesture",
            label: "제스처",
            source: "정량 · MediaPipe",
            kind: "point",
            className: "gesture",
            items: gestureFrameResults.map((item) => createPoint({
                ...item,
                active: item?.gestureDetected === true,
                label: item?.gestureDetected
                    ? "제스처 감지"
                    : "제스처 없음",
            }, duration, "제스처 분석")),
        },
        {
            key: "video-llm",
            label: "AI 관찰",
            source: formatGenerationMode(generationMode),
            kind: "interval",
            className: generationMode === "REAL" ? "video-llm real" : "video-llm fallback",
            items: videoLlmItems.map((item) => createInterval(item, duration, "Video LLM 관찰")),
        },
        {
            key: "moments",
            label: "주요 순간",
            source: "정량 · 자동 선정",
            kind: "point",
            className: "moments",
            items: notableMoments.map((item) => createPoint(item, duration, "주요 순간")),
        },
    ].filter((track) => track.items.length > 0);

    if (tracks.length === 0) {
        return null;
    }

    const sampleMode = generationMode === "FALLBACK" || generationMode === "MOCK";

    return (
        <section className="analysis-timeline" aria-labelledby="analysis-timeline-title">
            <div className="analysis-timeline-header">
                <div>
                    <h3 id="analysis-timeline-title">영상 동기화 분석 타임라인</h3>
                    <p>막대나 점을 선택하면 영상이 해당 시점으로 이동합니다.</p>
                </div>
                <div className="analysis-timeline-legend" aria-label="분석 출처 범례">
                    <span className="timeline-source-badge quantitative">정량 분석</span>
                    {videoLlmItems.length > 0 && (
                        <span className={`timeline-source-badge ${sampleMode ? "sample" : "real"}`}>
                            {formatGenerationMode(generationMode)}
                        </span>
                    )}
                </div>
            </div>

            {sampleMode && videoLlmItems.length > 0 && (
                <p className="analysis-timeline-warning" role="note">
                    AI 관찰 트랙은 실제 영상 분석이 아닌 예시 데이터이므로 정량 트랙과 구분해 확인하세요.
                </p>
            )}

            <div className="analysis-timeline-scroll">
                <div className="analysis-timeline-axis" aria-hidden="true">
                    <span />
                    <div>
                        {AXIS_RATIOS.map((ratio) => (
                            <span key={ratio} style={{ left: `${ratio * 100}%` }}>
                                {formatTimestamp(duration * ratio)}
                            </span>
                        ))}
                    </div>
                </div>

                {tracks.map((track) => (
                    <TimelineTrack
                        key={track.key}
                        track={track}
                        duration={duration}
                        currentTimeSec={currentTimeSec}
                        onSeekToTime={onSeekToTime}
                    />
                ))}
            </div>
        </section>
    );
}

export default AnalysisTimelineSection;
