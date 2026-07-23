package com.hanium.presentation.global.properties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// @Validated + 각 필드의 @Valid는 이 프로퍼티가 바인딩되는 기동 시점에 15개 버킷 전부의
// capacity/refillMinutes를 한 번에 검증한다. 이전에는 UserRateLimiter.validateLimit()이
// 각 버킷의 "첫 요청"에서만 뒤늦게 검증해, 실제 그 API가 호출되기 전까지는 잘못된 설정
// (capacity=0 등)이 배포 후에도 드러나지 않았다(2026-07-23 코드 리뷰 P1-05).
@ConfigurationProperties(prefix = "rate-limit")
@Validated
public record RateLimitProperties(
        @Valid Limit upload,
        @Valid Limit analysis,
        @Valid Limit login,
        @Valid Limit loginIp,
        @Valid Limit signup,
        @Valid Limit passwordResetRequest,
        @Valid Limit passwordResetConfirm,
        @Valid Limit passwordChange,
        @Valid Limit openaiMonthly,
        @Valid Limit videoLlmMonthly,
        @Valid Limit videoLlmDaily,
        @Valid Limit resultsQuery,
        @Valid Limit videoAccessToken,
        @Valid Limit jobStatusPoll,
        @Valid Limit coachDaily
) {

    public record Limit(
            @Min(1) int capacity,
            @Min(1) long refillMinutes
    ) {
    }
}
