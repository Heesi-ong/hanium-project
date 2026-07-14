package com.hanium.presentation.global.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void returnsUnsupportedMediaTypeForUnsupportedContentType() {
        ResponseEntity<ErrorResponse> response = handler.handleHttpMediaTypeNotSupportedException();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void returnsBadRequestForUnreadableRequestBody() {
        ResponseEntity<ErrorResponse> response = handler.handleHttpMessageNotReadableException();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
