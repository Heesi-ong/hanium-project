package com.hanium.presentation.global.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@Profile({"dev", "prod"})
public class JwtSecretStartupValidator {

    private static final Set<String> INSECURE_PLACEHOLDER_SECRETS = Set.of(
            "presentation-coaching-local-jwt-secret-change-me-2026",
            "change-me-to-a-strong-random-secret-in-production"
    );

    private final String jwtSecret;

    public JwtSecretStartupValidator(
            @Value("${security.jwt.secret:presentation-coaching-local-jwt-secret-change-me-2026}") String jwtSecret
    ) {
        this.jwtSecret = jwtSecret;
    }

    @PostConstruct
    public void validate() {
        if (INSECURE_PLACEHOLDER_SECRETS.contains(jwtSecret)) {
            throw new IllegalStateException(
                    "dev/prod 환경에서는 SECURITY_JWT_SECRET 환경변수를 반드시 실제 강력한 값으로 설정해야 합니다. "
                            + "현재 값은 코드/문서에 공개된 placeholder 기본값입니다."
            );
        }
    }
}
