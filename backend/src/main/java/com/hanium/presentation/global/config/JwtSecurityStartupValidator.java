package com.hanium.presentation.global.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtSecurityStartupValidator {

    private final long expirationMinutes;
    private final long revocationCleanupLockTtlMinutes;

    public JwtSecurityStartupValidator(
            @Value("${security.jwt.expiration-minutes:30}") long expirationMinutes,
            @Value("${scheduler.lock.jwt-revocation-cleanup-ttl-minutes:10}")
            long revocationCleanupLockTtlMinutes
    ) {
        this.expirationMinutes = expirationMinutes;
        this.revocationCleanupLockTtlMinutes = revocationCleanupLockTtlMinutes;
    }

    @PostConstruct
    void validate() {
        requirePositive("security.jwt.expiration-minutes", expirationMinutes);
        requirePositive(
                "scheduler.lock.jwt-revocation-cleanup-ttl-minutes",
                revocationCleanupLockTtlMinutes
        );
    }

    private void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalStateException(name + " must be greater than 0.");
        }
    }
}
