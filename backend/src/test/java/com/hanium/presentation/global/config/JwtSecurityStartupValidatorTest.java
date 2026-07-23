package com.hanium.presentation.global.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtSecurityStartupValidatorTest {

    @Test
    void acceptsPositiveJwtSecuritySettings() {
        new JwtSecurityStartupValidator(30, 10).validate();
    }

    @Test
    void rejectsNonPositiveTokenExpiration() {
        JwtSecurityStartupValidator validator = new JwtSecurityStartupValidator(0, 10);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.jwt.expiration-minutes");
    }

    @Test
    void rejectsNonPositiveCleanupLockTtl() {
        JwtSecurityStartupValidator validator = new JwtSecurityStartupValidator(30, -1);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduler.lock.jwt-revocation-cleanup-ttl-minutes");
    }
}
