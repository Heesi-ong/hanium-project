package com.hanium.presentation.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 로그아웃된 access token의 SHA-256 해시만 저장하는 DB 최종 원장이다.
 *
 * <p>Redis는 빠른 양성 캐시로만 사용하며, Redis 장애나 재시작으로 키가 사라져도 이
 * 행이 JWT 원래 만료 시각까지 강제 무효화를 보장한다. raw JWT는 저장하지 않는다.</p>
 */
@Entity
@Table(name = "revoked_access_tokens")
public class RevokedAccessToken {

    @Id
    @Column(
            name = "token_hash",
            length = 64,
            nullable = false,
            updatable = false,
            columnDefinition = "CHAR(64)"
    )
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RevokedAccessToken() {
    }

    private RevokedAccessToken(String tokenHash, Instant expiresAt, Instant createdAt) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public static RevokedAccessToken create(String tokenHash, Instant expiresAt) {
        return new RevokedAccessToken(tokenHash, expiresAt, Instant.now());
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
