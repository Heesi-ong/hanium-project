package com.hanium.presentation.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "잘못된 요청 값입니다."),

    FILE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "파일 업로드에 실패했습니다."),
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다."),
    FILE_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "파일 삭제에 실패했습니다."),
    UNSUPPORTED_FILE_CONTENT(HttpStatus.BAD_REQUEST, "영상 파일 내용이 확장자와 일치하지 않습니다."),

    ANALYSIS_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 작업을 찾을 수 없습니다."),
    ANALYSIS_JOB_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인이 소유한 분석 작업이 아닙니다."),
    ANALYSIS_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 분석이 진행 중입니다."),
    ANALYSIS_ALREADY_COMPLETED(HttpStatus.CONFLICT, "이미 완료된 분석 작업입니다."),
    ANALYSIS_RETRY_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "재시도 가능 횟수를 초과했습니다."),

    ANALYSIS_ENGINE_ERROR(HttpStatus.BAD_GATEWAY, "기본 분석 엔진 호출 중 오류가 발생했습니다."),
    VIDEO_LLM_ENGINE_ERROR(HttpStatus.BAD_GATEWAY, "Video LLM 엔진 호출 중 오류가 발생했습니다."),
    OPENAI_API_ERROR(HttpStatus.BAD_GATEWAY, "OpenAI API 호출 중 오류가 발생했습니다."),

    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),

    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
