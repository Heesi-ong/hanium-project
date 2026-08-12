package com.hanium.presentation.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class JwtCookieSupport {

    public static final String ACCESS_TOKEN_COOKIE_NAME = "access_token";

    private final boolean cookieSecure;

    public JwtCookieSupport(@Value("${security.jwt.cookie-secure:true}") boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public ResponseCookie createAccessTokenCookie(String token, Duration maxAge) {
        return baseCookie(ACCESS_TOKEN_COOKIE_NAME, token)
                .httpOnly(true)
                .maxAge(maxAge)
                .build();
    }

    public ResponseCookie expireAccessTokenCookie() {
        return baseCookie(ACCESS_TOKEN_COOKIE_NAME, "")
                .httpOnly(true)
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String name, String value) {
        return ResponseCookie.from(name, value)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/");
    }
}
