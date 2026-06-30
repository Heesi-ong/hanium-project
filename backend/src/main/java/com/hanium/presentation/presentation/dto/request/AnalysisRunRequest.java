package com.hanium.presentation.presentation.dto.request;

public record AnalysisRunRequest(
        Boolean useVideoLlm,
        Boolean useOpenAi
) {

    public boolean isUseVideoLlm() {
        return useVideoLlm == null || useVideoLlm;
    }

    public boolean isUseOpenAi() {
        return useOpenAi == null || useOpenAi;
    }
}