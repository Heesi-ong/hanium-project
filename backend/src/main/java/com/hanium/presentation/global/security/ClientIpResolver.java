package com.hanium.presentation.global.security;

import com.hanium.presentation.global.properties.ClientIpProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final ClientIpProperties clientIpProperties;

    public ClientIpResolver(ClientIpProperties clientIpProperties) {
        this.clientIpProperties = clientIpProperties;
    }

    public String resolve(HttpServletRequest request) {
        if (clientIpProperties.isTrustForwardedHeaders()) {
            String forwardedClientIp = resolveForwardedClientIp(request);
            if (!forwardedClientIp.isBlank()) {
                return forwardedClientIp;
            }
        }

        return request.getRemoteAddr();
    }

    private String resolveForwardedClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader(X_FORWARDED_FOR);
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return "";
        }

        for (String candidate : forwardedFor.split(",")) {
            String clientIp = candidate.trim();
            if (!clientIp.isBlank()) {
                return clientIp;
            }
        }

        return "";
    }
}
