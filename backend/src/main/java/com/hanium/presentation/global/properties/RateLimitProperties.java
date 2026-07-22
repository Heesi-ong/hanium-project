package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        Limit upload,
        Limit analysis,
        Limit login,
        Limit loginIp,
        Limit signup,
        Limit passwordResetRequest,
        Limit passwordResetConfirm,
        Limit passwordChange,
        Limit openaiMonthly,
        Limit videoLlmMonthly,
        Limit videoLlmDaily,
        Limit resultsQuery,
        Limit videoAccessToken,
        Limit jobStatusPoll,
        Limit coachDaily
) {

    public record Limit(
            int capacity,
            long refillMinutes
    ) {
    }
}
