package com.hanium.presentation.global.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

// rate-limit.*.capacity/refill-minutes에 @Min(1)을 건 이유를 검증한다: 이전에는 이런
// 잘못된 값이 그 버킷의 "첫 요청"이 들어올 때(UserRateLimiter.validateLimit)만 뒤늦게
// 발견됐다. 이제는 @ConfigurationProperties 바인딩 시점(=기동 시점)에 실패해야 한다
// (2026-07-23 코드 리뷰 P1-05).
class RateLimitPropertiesValidationTest {

    private static final String[] VALID_BASE_PROPERTIES = {
            "rate-limit.upload.capacity=5",
            "rate-limit.upload.refill-minutes=10",
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class TestConfig {
    }

    @Test
    void startsSuccessfullyWhenConfiguredBucketIsValid() {
        contextRunner
                .withPropertyValues(VALID_BASE_PROPERTIES)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsToStartWhenABucketCapacityIsZero() {
        contextRunner
                .withPropertyValues(
                        "rate-limit.upload.capacity=0",
                        "rate-limit.upload.refill-minutes=10"
                )
                .run(context -> assertBindingFailed(context, "upload.capacity"));
    }

    @Test
    void failsToStartWhenABucketRefillMinutesIsNegative() {
        contextRunner
                .withPropertyValues(
                        "rate-limit.upload.capacity=5",
                        "rate-limit.upload.refill-minutes=-1"
                )
                .run(context -> assertBindingFailed(context, "upload.refillMinutes"));
    }

    private void assertBindingFailed(AssertableApplicationContext context, String expectedRejectedField) {
        assertThat(context).hasFailed();

        Throwable rootCause = context.getStartupFailure();
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        // 검증 메시지는 로케일에 따라 달라지므로(한국어 환경에서는 "1 이상이어야 합니다"),
        // 문구 대신 어떤 필드가 거부됐는지(대상 필드명)만 확인한다.
        assertThat(rootCause.getMessage()).contains(expectedRejectedField);
    }
}
