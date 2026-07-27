package com.hanium.presentation.global.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class RedisTimeoutStartupValidatorTest {

    @Test
    void acceptsPositiveTimeouts() {
        RedisTimeoutStartupValidator validator = new RedisTimeoutStartupValidator(
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)
        );

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsZeroConnectTimeout() {
        RedisTimeoutStartupValidator validator = new RedisTimeoutStartupValidator(
                Duration.ZERO,
                Duration.ofSeconds(2)
        );

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("spring.data.redis.connect-timeout");
    }

    @Test
    void rejectsNegativeCommandTimeout() {
        RedisTimeoutStartupValidator validator = new RedisTimeoutStartupValidator(
                Duration.ofSeconds(1),
                Duration.ofMillis(-1)
        );

        assertThatIllegalStateException()
                .isThrownBy(validator::validate)
                .withMessageContaining("spring.data.redis.timeout");
    }
}
