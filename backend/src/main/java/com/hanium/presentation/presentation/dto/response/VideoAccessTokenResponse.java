package com.hanium.presentation.presentation.dto.response;

public record VideoAccessTokenResponse(
        String token,
        long expiresInSeconds
) {
}
