package com.hanium.presentation.domain.analysis.type;

public enum AnalysisStatus {

    UPLOADED("업로드 완료"),
    BASIC_ANALYZING("기본 분석 중"),
    VIDEO_LLM_ANALYZING("Video LLM 분석 중"),
    COMPACTING("분석 결과 축약 중"),
    OPENAI_GENERATING("OpenAI 최종 피드백 생성 중"),
    MERGING_RESULT("최종 결과 병합 중"),
    COMPLETED("분석 완료"),
    FAILED("분석 실패"),
    CANCELLED("취소됨");

    private final String description;

    AnalysisStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
