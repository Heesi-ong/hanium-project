package com.hanium.presentation.domain.result.type;

public enum FeedbackCategory {

    OVERALL("종합 평가"),
    POSTURE("자세"),
    EYE_CONTACT("시선"),
    FACIAL_EXPRESSION("표정"),
    GESTURE("제스처"),
    SPEECH_SPEED("말하기 속도"),
    SILENCE("침묵"),
    FILLER_WORD("필러 표현"),
    VOICE_VOLUME("목소리 크기"),
    TIMELINE("구간별 피드백"),
    PRACTICE("연습 방법");

    private final String description;

    FeedbackCategory(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}