package com.hanium.presentation.domain.result.type;

public enum ResultStatus {

    CREATED("결과 생성됨"),
    SAVED("결과 저장됨"),
    FAILED("결과 생성 실패");

    private final String description;

    ResultStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}