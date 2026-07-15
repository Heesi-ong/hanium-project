package com.hanium.presentation.infrastructure.client.openai;

import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.RateLimitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiUsageMetricsTest {

    @Test
    void registersCurrentMonthlyOpenAiUsageGauge() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        UserRateLimiter userRateLimiter = mock(UserRateLimiter.class);
        when(userRateLimiter.getCurrentCount(eq("openai-monthly"), anyString())).thenReturn(42L);
        RateLimitProperties rateLimitProperties = new RateLimitProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                new RateLimitProperties.Limit(1000, 44640),
                null,
                null,
                null,
                null,
                null
        );

        new OpenAiUsageMetrics(meterRegistry, userRateLimiter, rateLimitProperties);

        assertThat(meterRegistry.get("openai.monthly.usage").gauge().value())
                .isEqualTo(42.0);
        assertThat(meterRegistry.get("openai.monthly.budget.capacity").gauge().value())
                .isEqualTo(1000.0);
    }
}
