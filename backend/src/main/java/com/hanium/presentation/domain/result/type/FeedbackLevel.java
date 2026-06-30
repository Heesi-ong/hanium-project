package com.hanium.presentation.domain.result.type;

public enum FeedbackLevel {

    EXCELLENT("매우 우수"),
    GOOD("우수"),
    NORMAL("보통"),
    NEEDS_IMPROVEMENT("개선 필요"),
    POOR("미흡");

    private final String description;

    FeedbackLevel(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}