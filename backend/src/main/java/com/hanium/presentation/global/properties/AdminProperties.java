package com.hanium.presentation.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "admin")
public record AdminProperties(
        String emails
) {

    public boolean isAdminEmail(String email) {
        if (email == null || emails == null || emails.isBlank()) {
            return false;
        }

        for (String adminEmail : emails.split("\\s*,\\s*")) {
            if (adminEmail.equalsIgnoreCase(email.trim())) {
                return true;
            }
        }

        return false;
    }
}
