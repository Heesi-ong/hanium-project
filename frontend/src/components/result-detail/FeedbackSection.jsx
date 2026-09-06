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

    if (mode === "FALLBACK") {
        return "mini-badge warning";
    }

    return "mini-badge muted";
}

function getFeedbackGenerationModeClassName(mode) {
    if (mode === "REAL") {
        return "mini-badge success";
    }

    if (mode === "FALLBACK") {
        return "mini-badge warning";
    }

    return "mini-badge muted";
}

function getGenerationModeToken(mode) {
    return ["REAL", "FALLBACK", "MOCK", "SKIPPED"].includes(mode)
        ? mode.toLowerCase()
        : "unknown";
}

function getFeedbackSourceDescription(mode) {
    if (mode === "REAL") {
        return "실제 OpenAI API가 생성한 응답 원문입니다.";
    }

    if (mode === "FALLBACK") {
        return "외부 AI 호출 실패 후 내부 대체 응답이 최종 피드백으로 사용됐습니다.";
    }

    if (mode === "MOCK") {
        return "외부 AI를 호출하지 않고 내부 Mock 로직으로 만든 테스트 피드백입니다.";
    }

    if (mode === "SKIPPED") {
        return "사용자 설정에 따라 외부 AI 피드백 생성을 건너뛴 결과입니다.";
    }

    return "피드백 생성 출처를 확인할 수 없습니다.";
}

function getVisualSourceDescription(mode) {
    if (mode === "REAL") {
        return "실제 Video LLM이 업로드 영상을 분석한 관찰입니다.";
    }

    if (mode === "FALLBACK") {
        return "실제 Video LLM 분석 실패 후 샘플 관찰로 대체됐습니다.";
    }

    if (mode === "MOCK") {
        return "외부 Video LLM을 호출하지 않은 샘플 관찰입니다.";
    }

    if (mode === "SKIPPED") {
        return "사용자 설정에 따라 Video LLM 분석을 건너뛰었습니다.";
    }

    return "시각 분석 출처를 확인할 수 없습니다.";
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
            <article
                className="detail-card result-feedback-card result-visual-feedback-card"
                aria-labelledby="visual-feedback-title"
            >
                <header className="result-feedback-card-header">
                    <div>
                        <span className="result-feedback-kicker">Visual observation</span>
                        <h2 id="visual-feedback-title">시각 분석 (Video LLM)</h2>
                    </div>
                    <span className="mini-badge muted">데이터 없음</span>
                </header>
                <div className="result-feedback-empty">
                    <strong>영상 분석 데이터가 아직 없습니다.</strong>
                    <p>Video LLM 결과가 제공되면 자세와 제스처 관찰이 표시됩니다.</p>
                </div>
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
    const observationCount = observationGroups.reduce(
        (sum, [, items]) => sum + items.length,
        0
    );

    return (
        <article
            className="detail-card result-feedback-card result-visual-feedback-card"
            aria-labelledby="visual-feedback-title"
        >
            <header className="result-feedback-card-header">
                <div>
                    <span className="result-feedback-kicker">Visual observation</span>
                    <h2 id="visual-feedback-title">시각 분석 (Video LLM)</h2>
                    <p>영상 구간별 자세와 제스처 관찰을 근거와 함께 확인하세요.</p>
                </div>
                <span className={getVisualGenerationModeClassName(generationMode)}>
                    {getVisualGenerationModeLabel(generationMode)}
                </span>
            </header>

            <div className={`result-feedback-source-note ${getGenerationModeToken(generationMode)}`}>
                <span className="result-feedback-source-symbol" aria-hidden="true">
                    {generationMode === "REAL" ? "✓" : "i"}
                </span>
                <div>
                    <strong>시각 분석 출처</strong>
                    <p>{getVisualSourceDescription(generationMode)}</p>
                </div>
            </div>

            {sampleWarning && (
                <p className="result-sample-warning" role="note">
                    {sampleWarning}
                </p>
            )}

            <div className="observation-groups result-observation-groups">
                <div className="result-observation-heading">
                    <h3>세부 관찰</h3>
                    <span>{observationCount}개 관찰</span>
                </div>
                {observationGroups.length === 0 ? (
                    <div className="result-feedback-empty compact">
                        <strong>표시할 세부 관찰 데이터가 없습니다.</strong>
                        <p>분석 결과에 포함된 자세·제스처 관찰만 표시합니다.</p>
                    </div>
                ) : (
                    observationGroups.map(([categoryLabel, items]) => (
                        <section className="observation-group" key={categoryLabel}>
                            <div className="result-observation-group-heading">
                                <h4>{categoryLabel}</h4>
                                <span>{items.length}개</span>
                            </div>
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
                        </section>
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
    const strengths = Array.isArray(feedback?.strengths)
        ? feedback.strengths
        : [];
    const improvements = Array.isArray(feedback?.improvements)
        ? feedback.improvements
        : [];

    return (
        <div className="result-feedback-layout">
            <article
                className="detail-card result-feedback-card result-feedback-overview-card"
                aria-labelledby="result-feedback-title"
            >
                <header className="result-feedback-card-header">
                    <div>
                        <span className="result-feedback-kicker">Coaching summary</span>
                        <h2 id="result-feedback-title">종합 피드백</h2>
                        <p>응답 출처를 먼저 확인한 뒤 강점과 다음 개선 행동을 읽어보세요.</p>
                    </div>
                    <span className={getFeedbackGenerationModeClassName(generationMode)}>
                        {formatGenerationModeLabel(generationMode)}
                    </span>
                </header>

                <div className={`result-feedback-source-note ${getGenerationModeToken(generationMode)}`}>
                    <span className="result-feedback-source-symbol" aria-hidden="true">
                        {generationMode === "REAL" ? "✓" : "i"}
                    </span>
                    <div>
                        <strong>피드백 출처</strong>
                        <p>{getFeedbackSourceDescription(generationMode)}</p>
                    </div>
                </div>

                <section className="feedback-block llm-raw-text-block result-feedback-raw">
                    <div className="result-feedback-block-heading">
                        <h3>응답 원문</h3>
                        <span>가공 없이 표시</span>
                    </div>
                    <pre
                        className="llm-raw-text"
                        tabIndex={0}
                        aria-label="종합 피드백 응답 원문"
                    >
                        {feedback?.overall || "표시할 종합 피드백이 없습니다."}
                    </pre>
                </section>

                <div className="feedback-columns result-feedback-columns">
                    <section className="result-feedback-signal strength">
                        <div className="result-feedback-signal-heading">
                            <span className="result-feedback-signal-icon" aria-hidden="true">+</span>
                            <h3>강점</h3>
                            <span>{strengths.length}개</span>
                        </div>
                        {strengths.length > 0 ? (
                            <ul>
                                {strengths.map((item, index) => (
                                    <li key={`${item}-${index}`}>{item}</li>
                                ))}
                            </ul>
                        ) : (
                            <p className="muted-text">표시할 강점이 없습니다.</p>
                        )}
                    </section>

                    <section className="result-feedback-signal improvement">
                        <div className="result-feedback-signal-heading">
                            <span className="result-feedback-signal-icon" aria-hidden="true">→</span>
                            <h3>개선점</h3>
                            <span>{improvements.length}개</span>
                        </div>
                        {improvements.length > 0 ? (
                            <ul>
                                {improvements.map((item, index) => (
                                    <li key={`${item}-${index}`}>{item}</li>
                                ))}
                            </ul>
                        ) : (
                            <p className="muted-text">표시할 개선점이 없습니다.</p>
                        )}
                    </section>
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
