import {
    firstMeaningfulResultValue,
    isMeaningfulResultValue,
} from "./resultDetailFormatters";

function getGenerationModeLabel(mode) {
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

    if (mode === "FAILED") {
        return "FAILED";
    }

    return "UNKNOWN";
}

function getGenerationModeText(mode) {
    if (mode === "REAL") {
        return "실제 OpenAI";
    }

    if (mode === "FALLBACK") {
        return "Fallback";
    }

    if (mode === "MOCK") {
        return "Mock";
    }

    if (mode === "SKIPPED") {
        return "사용 안 함";
    }

    if (mode === "FAILED") {
        return "생성 실패";
    }

    return "알 수 없음";
}

function getGenerationModeClassName(mode) {
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

    if (mode === "FAILED") {
        return "ai-mode-badge failed";
    }

    return "ai-mode-badge unknown";
}

function resolveRealApiUsed(feedback, pipeline) {
    if (isMeaningfulResultValue(feedback?.generationMode)) {
        return feedback?.realApiUsed === true;
    }

    if (typeof pipeline?.openAiRealApiUsed === "boolean") {
        return pipeline.openAiRealApiUsed;
    }

    return feedback?.realApiUsed === true;
}

function OpenAiGenerationBadge({ feedback, pipeline }) {
    const generationMode = firstMeaningfulResultValue(
        feedback?.generationMode,
        pipeline?.openAiGenerationMode,
        "UNKNOWN"
    );
    const model = firstMeaningfulResultValue(feedback?.model, pipeline?.openAiModel, "-");
    const realApiUsed = resolveRealApiUsed(feedback, pipeline);

    return (
        <div className="ai-mode-wrap">
      <span className={getGenerationModeClassName(generationMode)}>
        {getGenerationModeLabel(generationMode)}
      </span>

            <div className="ai-mode-meta">
                <strong>{getGenerationModeText(generationMode)}</strong>
                <span>
          {model}
                    {" · "}
                    API {realApiUsed ? "사용" : "미사용"}
        </span>
            </div>
        </div>
    );
}

export default OpenAiGenerationBadge;
