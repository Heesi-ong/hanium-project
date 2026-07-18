package com.hanium.presentation.global.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
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

    @Test
    void logsUnhandledExceptionAndReturnsGenericServerError(CapturedOutput output) {
        RuntimeException exception = new RuntimeException("database connection lost");

        ResponseEntity<ErrorResponse> response = handler.handleException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().message()).isEqualTo("서버 내부 오류가 발생했습니다.");
        assertThat(output.getOut())
                .contains("UNHANDLED_EXCEPTION")
                .contains(RuntimeException.class.getName())
                .contains("database connection lost");
    }
}
