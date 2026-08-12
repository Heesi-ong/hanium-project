import {
    firstMeaningfulResultValue,
    isMeaningfulResultValue,
} from "./resultDetailFormatters";

function getGenerationModeLabel(mode) {
    if (mode === "REAL") {
        return "실제 OpenAI API";
    }

    if (mode === "FALLBACK") {
        return "OpenAI 실패 후 Mock 대체";
    }

    if (mode === "MOCK") {
        return "Mock 피드백";
    }

    if (mode === "SKIPPED") {
        return "OpenAI 피드백 사용 안 함";
    }

    return "알 수 없음";
}

function getGenerationModeDescription(mode) {
    if (mode === "REAL") {
        return "실제 OpenAI API 호출을 통해 생성된 피드백입니다.";
    }

    if (mode === "FALLBACK") {
        return "실제 OpenAI API 호출을 시도했지만 실패하여 Mock 피드백으로 대체되었습니다.";
    }

    if (mode === "MOCK") {
        return "OpenAI API를 호출하지 않고 내부 Mock 로직으로 생성된 피드백입니다.";
    }

    if (mode === "SKIPPED") {
        return "사용자 설정에 따라 OpenAI 피드백 생성을 건너뛰었습니다.";
    }

    return "피드백 생성 방식을 확인할 수 없습니다.";
}

function getGenerationModeClassName(mode) {
    if (mode === "REAL") {
        return "mini-badge success";
    }

    if (mode === "FALLBACK") {
        return "mini-badge warning";
    }

    if (mode === "MOCK") {
        return "mini-badge muted";
    }

    if (mode === "SKIPPED") {
        return "mini-badge muted";
    }

    return "mini-badge muted";
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

function OpenAiFeedbackStatusSection({ feedback, pipeline }) {
    const generationMode = firstMeaningfulResultValue(
        feedback?.generationMode,
        pipeline?.openAiGenerationMode,
        "UNKNOWN"
    );
    const model = firstMeaningfulResultValue(feedback?.model, pipeline?.openAiModel, "-");
    const realApiUsed = resolveRealApiUsed(feedback, pipeline);
    const fallbackReason = firstMeaningfulResultValue(
        feedback?.fallbackReason,
        pipeline?.openAiFallbackReason,
        "-"
    );

    return (
        <article className="detail-card wide">
            <h2>AI 피드백 생성 상태</h2>

            <div className="metric-grid">
                <article className="metric-card">
                    <span>생성 방식</span>
                    <strong>
            <span className={getGenerationModeClassName(generationMode)}>
              {getGenerationModeLabel(generationMode)}
            </span>
                    </strong>
                    <p>{getGenerationModeDescription(generationMode)}</p>
                </article>

                <article className="metric-card">
                    <span>사용 모델</span>
                    <strong>{model}</strong>
                    <p>OpenAI 실제 호출 또는 Mock 설정에서 참조하는 모델명입니다.</p>
                </article>

                <article className="metric-card">
                    <span>실제 API 사용 여부</span>
                    <strong>{realApiUsed ? "예" : "아니오"}</strong>
                    <p>실제 OpenAI API 호출 결과가 최종 피드백에 사용되었는지 여부입니다.</p>
                </article>

                <article className="metric-card">
                    <span>미사용·대체 사유</span>
                    <strong>{fallbackReason}</strong>
                    <p>OpenAI를 사용하지 않았거나 대체 피드백이 사용된 이유입니다.</p>
                </article>
            </div>
        </article>
    );
}

export default OpenAiFeedbackStatusSection;
