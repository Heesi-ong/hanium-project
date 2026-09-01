import {
    firstMeaningfulResultValue,
    formatGenerationModeLabel,
} from "./resultDetailFormatters";

function getVisualGenerationModeLabel(mode) {
    if (mode === "REAL") {
        return "실제 Video LLM";
    }

    if (mode === "FALLBACK") {
        return "Video LLM 실패 후 Mock 대체";
    }

    if (mode === "MOCK") {
        return "Mock Video LLM 분석";
    }

    if (mode === "SKIPPED") {
        return "Video LLM 분석 생략";
    }

    return "분석 방식 알 수 없음";
}

function getVisualGenerationModeClassName(mode) {
    if (mode === "REAL") {
        return "mini-badge success";
    }

    return "mini-badge muted";
}

// 실제 영상 분석(REAL)이 아니라 예시/대체 데이터로 채워진 경우, 사용자가 이 결과를
// 자신의 발표 영상에 기반한 것으로 오해하지 않도록 경고 문구를 반환합니다.
function getSampleWarning(mode) {
    if (mode === "MOCK") {
        return "※ 이 시각 분석은 실제 영상 분석이 아닌 예시(샘플) 데이터입니다. 업로드한 발표 영상의 내용에 기반하지 않습니다.";
    }

    if (mode === "FALLBACK") {
        return "※ 실제 영상 분석에 실패해 예시(샘플) 데이터로 대체되었습니다. 이 시각 분석 결과는 업로드한 발표 영상에 기반하지 않습니다.";
    }

    return "";
}

function hasText(value) {
    return typeof value === "string" && value.trim().length > 0;
}

// Video LLM 관찰 카테고리를 사용자에게 보여줄 순서와 한국어 라벨로 매핑합니다.
const OBSERVATION_CATEGORIES = [
    ["gesture", "제스처"],
    ["posture", "자세"],
];

// 초 단위 값을 m:ss 형태로 표시합니다(예: 75 -> "1:15"). 숫자가 아니면 빈 문자열.
function formatSeconds(value) {
    if (typeof value !== "number" || Number.isNaN(value)) {
        return "";
    }

    const total = Math.max(0, Math.round(value));
    const minutes = Math.floor(total / 60);
    const seconds = total % 60;
    return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

// 한 관찰 항목의 구간을 "0:12–0:18" 또는 "0:12" 형태로 표시합니다.
function formatObservationRange(item) {
    const start = formatSeconds(item?.startSec);
    const end = formatSeconds(item?.endSec);
    if (start && end && start !== end) {
        return `${start}–${end}`;
    }
    return start || end || "";
}

// 신뢰도(0~1)를 백분율 문자열로. 범위를 벗어나거나 숫자가 아니면 빈 문자열.
function formatConfidence(value) {
    if (typeof value !== "number" || Number.isNaN(value) || value < 0 || value > 1) {
        return "";
    }
    return `${Math.round(value * 100)}%`;
}

// observations 객체에서 항목이 하나라도 있는 카테고리만 [라벨, 항목목록]으로 추립니다.
function getObservationGroups(observations) {
    if (!observations || typeof observations !== "object") {
        return [];
    }

    return OBSERVATION_CATEGORIES.map(([key, label]) => {
        const items = observations[key];
        return [label, Array.isArray(items) ? items : []];
    }).filter(([, items]) => items.length > 0);
}

function VisualAnalysisBox({ visualAnalysis, pipeline, onSeekToTime }) {
    if (!visualAnalysis) {
        return (
            <article className="detail-card">
                <h2>시각 분석 (Video LLM)</h2>
                <p className="muted-text">영상 분석 데이터가 아직 없습니다.</p>
            </article>
        );
    }

    const generationMode = firstMeaningfulResultValue(
        visualAnalysis?.model?.generationMode,
        pipeline?.videoLlmGenerationMode,
        "UNKNOWN"
    );
    const sampleWarning = getSampleWarning(generationMode);
    const observationGroups = getObservationGroups(visualAnalysis?.observations);

    return (
        <article className="detail-card">
            <h2>시각 분석 (Video LLM)</h2>

            <div className="key-value-list">
                <div className="key-value-item">
                    <span>생성 방식</span>
                    <strong>
                        <span className={getVisualGenerationModeClassName(generationMode)}>
                            {getVisualGenerationModeLabel(generationMode)}
                        </span>
                    </strong>
                </div>

                {sampleWarning && (
                    <p className="muted-text" role="note">
                        {sampleWarning}
                    </p>
                )}
            </div>

            <div className="observation-groups">
                <h3>세부 관찰</h3>
                {observationGroups.length === 0 ? (
                    <p className="muted-text">표시할 세부 관찰 데이터가 없습니다.</p>
                ) : (
                    observationGroups.map(([categoryLabel, items]) => (
                                <div className="observation-group" key={categoryLabel}>
                                    <h4>{categoryLabel}</h4>
                                    <ul className="observation-list">
                                        {items.map((item, index) => {
                                            const range = formatObservationRange(item);
                                            const confidence = formatConfidence(item?.confidence);
                                            return (
                                                <li
                                                    className="observation-item"
                                                    key={`${categoryLabel}-${index}`}
                                                >
                                                    <div className="observation-meta">
                                                        {range &&
                                                            (onSeekToTime &&
                                                            typeof item?.startSec === "number" ? (
                                                                <button
                                                                    type="button"
                                                                    className="observation-time observation-seek"
                                                                    onClick={() =>
                                                                        onSeekToTime(item.startSec)
                                                                    }
                                                                    aria-label={`영상을 ${range} 구간으로 이동`}
                                                                >
                                                                    {range}
                                                                </button>
                                                            ) : (
                                                                <span className="observation-time">
                                                                    {range}
                                                                </span>
                                                            ))}
                                                        {hasText(item?.label) && (
                                                            <span className="mini-badge muted">
                                                                {item.label.trim()}
                                                            </span>
                                                        )}
                                                        {confidence && (
                                                            <span className="observation-confidence">
                                                                신뢰도 {confidence}
                                                            </span>
                                                        )}
                                                    </div>
                                                    {hasText(item?.description) && (
                                                        <p className="observation-description">
                                                            {item.description.trim()}
                                                        </p>
                                                    )}
                                                </li>
                                            );
                                        })}
                                    </ul>
                                </div>
                    ))
                )}
            </div>
        </article>
    );
}

function FeedbackSection({ feedback, visualAnalysis, pipeline, onSeekToTime }) {
    const generationMode = firstMeaningfulResultValue(
        feedback?.generationMode,
        pipeline?.openAiGenerationMode,
        "UNKNOWN"
    );

    return (
        <div className="detail-grid">
            <article className="detail-card wide">
                <h2>종합 피드백</h2>

                <div className="feedback-block llm-raw-text-block">
                    <h3>
                        AI 응답 원문
                        <span className="mini-badge muted">
                            {formatGenerationModeLabel(generationMode)}
                        </span>
                    </h3>
                    <p className="muted-text">
                        AI가 실제로 생성해 전달한 텍스트를 가공 없이 그대로 보여줍니다.
                    </p>
                    <pre className="llm-raw-text" tabIndex={0}>
                        {feedback?.overall || "표시할 종합 피드백이 없습니다."}
                    </pre>
                </div>

                <div className="feedback-columns">
                    <div>
                        <h3>강점</h3>
                        {Array.isArray(feedback?.strengths) &&
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
                        {Array.isArray(feedback?.improvements) &&
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

            <VisualAnalysisBox
                visualAnalysis={visualAnalysis}
                pipeline={pipeline}
                onSeekToTime={onSeekToTime}
            />
        </div>
    );
}

export default FeedbackSection;
