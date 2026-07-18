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

    @Test
    void returnsBadRequestForRequestBindingErrors() {
        ResponseEntity<ErrorResponse> response = handler.handleRequestBindingException();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("INVALID_INPUT_VALUE");
    }
}
