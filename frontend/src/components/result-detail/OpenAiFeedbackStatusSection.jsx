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
    if (
        isMeaningfulResultValue(feedback?.generationMode)
        && typeof feedback?.realApiUsed === "boolean"
    ) {
        return feedback.realApiUsed;
    }

    if (typeof pipeline?.openAiRealApiUsed === "boolean") {
        return pipeline.openAiRealApiUsed;
    }

    if (typeof feedback?.realApiUsed === "boolean") {
        return feedback.realApiUsed;
    }

    return null;
}

function formatRealApiUsed(value) {
    if (value === true) {
        return "예";
    }

    if (value === false) {
        return "아니오";
    }

    return "확인 불가";
}

function getExecutionSummary(mode) {
    if (mode === "REAL") {
        return "외부 OpenAI API 응답이 최종 피드백에 사용됐습니다.";
    }

    if (mode === "FALLBACK") {
        return "외부 OpenAI API 호출을 시도했지만 최종 피드백은 내부 대체 응답입니다.";
    }

    if (mode === "MOCK") {
        return "외부 OpenAI API를 호출하지 않고 내부 Mock 응답을 사용했습니다.";
    }

    if (mode === "SKIPPED") {
        return "사용자 설정에 따라 외부 OpenAI 피드백 생성을 실행하지 않았습니다.";
    }

    return "외부 OpenAI API 실행 여부를 결과 데이터에서 확인할 수 없습니다.";
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
        <article
            className="detail-card wide result-generation-status-card"
            aria-labelledby="openai-generation-title"
        >
            <header className="result-feedback-card-header">
                <div>
                    <span className="result-feedback-kicker">Execution record</span>
                    <h2 id="openai-generation-title">AI 피드백 생성 상태</h2>
                    <p>최종 피드백에 실제 외부 API, 대체 응답, Mock 중 무엇이 사용됐는지 확인하세요.</p>
                </div>
                <span className={getGenerationModeClassName(generationMode)}>
                    {getGenerationModeLabel(generationMode)}
                </span>
            </header>

            <div className={`result-generation-callout ${String(generationMode).toLowerCase()}`}>
                <span aria-hidden="true">{generationMode === "REAL" ? "✓" : "i"}</span>
                <div>
                    <strong>이번 결과의 실행 기록</strong>
                    <p>{getExecutionSummary(generationMode)}</p>
                </div>
            </div>

            <dl className="result-generation-metadata">
                <div>
                    <dt>생성 방식</dt>
                    <dd>
                        <strong>{getGenerationModeLabel(generationMode)}</strong>
                        <span>{getGenerationModeDescription(generationMode)}</span>
                    </dd>
                </div>

                <div>
                    <dt>사용 모델</dt>
                    <dd>
                        <strong>{model}</strong>
                        <span>결과에 기록된 외부 API 또는 Mock 설정 모델명</span>
                    </dd>
                </div>

                <div>
                    <dt>실제 API 응답 사용</dt>
                    <dd>
                        <strong>{formatRealApiUsed(realApiUsed)}</strong>
                        <span>외부 OpenAI API 응답이 최종 피드백에 반영됐는지 여부</span>
                    </dd>
                </div>

                <div>
                    <dt>미사용·대체 사유</dt>
                    <dd>
                        <strong>{fallbackReason}</strong>
                        <span>결과 데이터에 저장된 원문 사유</span>
                    </dd>
                </div>
            </dl>
        </article>
    );
}

export default OpenAiFeedbackStatusSection;
