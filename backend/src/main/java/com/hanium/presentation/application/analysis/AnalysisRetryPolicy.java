package com.hanium.presentation.application.analysis;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;

/** 재시도 요청에서 명시되지 않은 외부 AI 옵션을 최초 실행 값으로 보존합니다. */
final class AnalysisRetryPolicy {

    RetryOptions resolve(
            AnalysisJob analysisJob,
            Boolean useVideoLlmOverride,
            Boolean useOpenAiOverride
    ) {
        return new RetryOptions(
                useVideoLlmOverride != null
                        ? useVideoLlmOverride
                        : analysisJob.isUseVideoLlm(),
                useOpenAiOverride != null
                        ? useOpenAiOverride
                        : analysisJob.isUseOpenAi()
        );
    }

    record RetryOptions(boolean useVideoLlm, boolean useOpenAi) {
    }
}
