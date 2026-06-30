package com.hanium.presentation.global.response;

import java.time.LocalDateTime;

public record ApiResponse<T>(
        boolean success,
        String message,
        T data,
        LocalDateTime timestamp
) {

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(
                true,
                message,
                data,
                LocalDateTime.now()
        );
    }

    public static <T> ApiResponse<T> success(T data) {
        return success("요청이 성공적으로 처리되었습니다.", data);
    }

    public static ApiResponse<Void> success(String message) {
        return new ApiResponse<>(
                true,
                message,
                null,
                LocalDateTime.now()
        );
    }

    public static <T> ApiResponse<T> fail(String message, T data) {
        return new ApiResponse<>(
                false,
                message,
                data,
                LocalDateTime.now()
        );
    }

    public static ApiResponse<Void> fail(String message) {
        return new ApiResponse<>(
                false,
                message,
                null,
                LocalDateTime.now()
        );
    }
}