package com.hanium.presentation.infrastructure.client.videollm;

import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.RateLimitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoLlmUsageMetricsTest {

    @Test
    void registersCurrentMonthlyVideoLlmUsageGauge() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        UserRateLimiter userRateLimiter = mock(UserRateLimiter.class);
        when(userRateLimiter.getCurrentCount(eq("video-llm-monthly"), anyString())).thenReturn(17L);
        RateLimitProperties rateLimitProperties = new RateLimitProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new RateLimitProperties.Limit(500, 44640),
                null,
                null,
                null,
                null,
                null
        );

        new VideoLlmUsageMetrics(meterRegistry, userRateLimiter, rateLimitProperties);

        assertThat(meterRegistry.get("video_llm.monthly.usage").gauge().value())
                .isEqualTo(17.0);
        assertThat(meterRegistry.get("video_llm.monthly.budget.capacity").gauge().value())
                .isEqualTo(500.0);
    }
}
