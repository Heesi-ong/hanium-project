package com.hanium.presentation.infrastructure.client.videollm;

import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.properties.RateLimitProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

@Component
public class VideoLlmUsageMetrics {

    private static final String BUCKET_NAME = "video-llm-monthly";

    public VideoLlmUsageMetrics(
            MeterRegistry meterRegistry,
            UserRateLimiter userRateLimiter,
            RateLimitProperties rateLimitProperties
    ) {
        Gauge.builder(
                        "video_llm.monthly.usage",
                        userRateLimiter,
                        limiter -> limiter.getCurrentCount(BUCKET_NAME, YearMonth.now().toString())
                )
                .description("이번 달 Video LLM 실제 API 호출 누적 횟수")
                .register(meterRegistry);

        Gauge.builder(
                        "video_llm.monthly.budget.capacity",
                        rateLimitProperties,
                        properties -> properties.videoLlmMonthly().capacity()
                )
                .description("설정된 Video LLM 월간 호출 상한")
                .register(meterRegistry);
    }
}
