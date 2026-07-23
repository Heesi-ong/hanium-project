package com.hanium.presentation.presentation.dto.request;

public record VideoLlmReanalysisRequest(Boolean useOpenAi) {

    public boolean isUseOpenAi() {
        return useOpenAi == null || useOpenAi;
    }
}
