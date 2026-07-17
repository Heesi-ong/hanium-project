package com.hanium.presentation.domain.analysis.type;

public enum AnalysisStatus {

    UPLOADED("업로드 완료"),
    QUEUED("분석 대기 중"),
    BASIC_ANALYZING("기본 분석 중"),
    VIDEO_LLM_ANALYZING("Video LLM 분석 중"),
    COMPACTING("분석 결과 축약 중"),
    OPENAI_GENERATING("OpenAI 최종 피드백 생성 중"),
    MERGING_RESULT("최종 결과 병합 중"),
    COMPLETED("분석 완료"),
    FAILED("분석 실패"),
    CANCELLED("취소됨"),
    // 재시도 가능 횟수를 모두 소진한 상태에서 다시 실패한 작업입니다. FAILED와 달리
    // 사용자가 /retry로 직접 재시도할 수 없고, 관리자가 검토 후 수동으로 재처리해야 합니다.
    DEAD_LETTER("재시도 소진(관리자 재처리 필요)");

    private final String description;

    AnalysisStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
