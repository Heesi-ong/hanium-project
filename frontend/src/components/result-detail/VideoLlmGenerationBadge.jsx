import { firstMeaningfulResultValue } from "./resultDetailFormatters";

function getVideoLlmGenerationModeLabel(mode) {
    if (mode === "REAL") {
        return "REAL";
    }

    if (mode === "FALLBACK") {
        return "FALLBACK";
    }

    if (mode === "MOCK") {
        return "MOCK";
    }

    if (mode === "SKIPPED") {
        return "SKIPPED";
    }

    return "UNKNOWN";
}

function getVideoLlmGenerationModeText(mode) {
    if (mode === "REAL") {
        return "실제 Video LLM";
    }

    if (mode === "FALLBACK") {
        return "실패 후 샘플 대체";
    }

    if (mode === "MOCK") {
        return "샘플 시각 분석";
    }

    if (mode === "SKIPPED") {
        return "시각 분석 생략";
    }

    return "방식 알 수 없음";
}

function getVideoLlmGenerationModeClassName(mode) {
    if (mode === "REAL") {
        return "ai-mode-badge real";
    }

    if (mode === "FALLBACK") {
        return "ai-mode-badge fallback";
    }

    if (mode === "MOCK") {
        return "ai-mode-badge mock";
    }

    if (mode === "SKIPPED") {
        return "ai-mode-badge skipped";
    }

    return "ai-mode-badge unknown";
}

function resolveVideoLlmGenerationMode(result) {
    return firstMeaningfulResultValue(
        result?.visualAnalysis?.model?.generationMode,
        result?.pipeline?.videoLlmGenerationMode,
        "UNKNOWN"
    );
}

function resolveVideoLlmModelText(result) {
    return firstMeaningfulResultValue(
        result?.visualAnalysis?.model?.name,
        result?.pipeline?.videoLlmAnalysis,
        "-"
    );
}

function VideoLlmGenerationBadge({ result }) {
    const generationMode = resolveVideoLlmGenerationMode(result);
    const model = resolveVideoLlmModelText(result);

    return (
        <div className="ai-mode-wrap">
            <span className={getVideoLlmGenerationModeClassName(generationMode)}>
                {getVideoLlmGenerationModeLabel(generationMode)}
            </span>

            <div className="ai-mode-meta">
                <strong>{getVideoLlmGenerationModeText(generationMode)}</strong>
                <span>Video LLM · {model}</span>
            </div>
        </div>
    );
}

export default VideoLlmGenerationBadge;
