package com.hanium.presentation.infrastructure.client.openai;

import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.RateLimitProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

@Component
public class OpenAiUsageMetrics {

    private static final String BUCKET_NAME = "openai-monthly";

    public OpenAiUsageMetrics(
            MeterRegistry meterRegistry,
            UserRateLimiter userRateLimiter,
            RateLimitProperties rateLimitProperties
    ) {
        Gauge.builder(
                        "openai.monthly.usage",
                        userRateLimiter,
                        limiter -> limiter.getCurrentCount(BUCKET_NAME, YearMonth.now().toString())
                )
                .description("이번 달 OpenAI 실제 API 호출 누적 횟수")
                .register(meterRegistry);

        Gauge.builder(
                        "openai.monthly.budget.capacity",
                        rateLimitProperties,
                        properties -> properties.openaiMonthly().capacity()
                )
                .description("설정된 OpenAI 월간 호출 상한")
                .register(meterRegistry);
    }
}
