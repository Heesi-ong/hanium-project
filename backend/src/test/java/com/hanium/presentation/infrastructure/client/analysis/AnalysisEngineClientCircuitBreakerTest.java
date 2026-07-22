package com.hanium.presentation.infrastructure.client.analysis;

import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.client.analysis.dto.AnalysisEngineRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class AnalysisEngineClientCircuitBreakerTest {

    @Autowired
    private AnalysisEngineClient analysisEngineClient;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @AfterEach
    void resetCircuitBreaker() {
        circuitBreakerRegistry.circuitBreaker("analysis-engine").reset();
    }

    @Test
    void analyzeFailsFastWhenCircuitBreakerIsOpen(CapturedOutput output) {
        circuitBreakerRegistry.circuitBreaker("analysis-engine")
                .transitionToOpenState();

        assertThat(output.getOut())
                .contains("engine circuit breaker state transition")
                .contains("name=analysis-engine")
                .contains("from=CLOSED")
                .contains("to=OPEN");

        BusinessException exception = catchThrowableOfType(
                () -> analysisEngineClient.analyze(
                        new AnalysisEngineRequest("job-1", "/tmp/video.mp4")
                ),
                BusinessException.class
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ANALYSIS_ENGINE_ERROR);
        assertThat(exception)
                .hasMessage("기본 분석 엔진이 반복적으로 실패해 일시적으로 호출을 차단했습니다. 잠시 후 다시 시도해주세요.");
        assertThat(output.getOut())
                .contains("engine circuit breaker rejected call")
                .contains("name=analysis-engine")
                .contains("state=OPEN");
    }

    @Test
    void slowCallThresholdAllowsExpectedLongRunningAnalysis() {
        var config = circuitBreakerRegistry.circuitBreaker("analysis-engine").getCircuitBreakerConfig();

        assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.getSlowCallRateThreshold()).isEqualTo(100.0f);
    }
}
