package com.hanium.presentation.infrastructure.client.videollm;

import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.client.videollm.dto.VideoLlmEngineRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
class VideoLlmEngineClientCircuitBreakerTest {

    @Autowired
    private VideoLlmEngineClient videoLlmEngineClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @AfterEach
    void resetCircuitBreaker() {
        circuitBreakerRegistry.circuitBreaker("video-llm-engine").reset();
    }

    @Test
    void analyzeFailsFastWhenCircuitBreakerIsOpen() {
        circuitBreakerRegistry.circuitBreaker("video-llm-engine")
                .transitionToOpenState();

        BusinessException exception = catchThrowableOfType(
                () -> videoLlmEngineClient.analyze(
                        VideoLlmEngineRequest.defaultOption("job-1", "/tmp/video.mp4")
                ),
                BusinessException.class
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VIDEO_LLM_ENGINE_ERROR);
        assertThat(exception)
                .hasMessage("Video LLM 엔진이 반복적으로 실패해 일시적으로 호출을 차단했습니다. 잠시 후 다시 시도해주세요.");
    }
}
