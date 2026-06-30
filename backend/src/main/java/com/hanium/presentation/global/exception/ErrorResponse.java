package com.hanium.presentation.global.exception;

import java.time.LocalDateTime;

public record ErrorResponse(
        boolean success,
        int status,
        String error,
        String message,
        LocalDateTime timestamp
) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(
                false,
                errorCode.getStatus().value(),
                errorCode.name(),
                errorCode.getMessage(),
                LocalDateTime.now()
        );
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(
                false,
                errorCode.getStatus().value(),
                errorCode.name(),
                message,
                LocalDateTime.now()
        );
    }
}