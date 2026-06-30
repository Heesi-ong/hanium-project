package com.hanium.presentation.domain.analysis.type;

public enum AnalysisStep {

    UPLOAD("영상 업로드"),
    BASIC_ANALYSIS("기본 분석"),
    VIDEO_LLM_ANALYSIS("Video LLM 분석"),
    COMPACT_ANALYSIS("분석 결과 축약"),
    OPENAI_FEEDBACK("OpenAI 피드백 생성"),
    RESULT_MERGE("결과 병합"),
    RESULT_SAVE("결과 저장");

    private final String description;

    AnalysisStep(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}