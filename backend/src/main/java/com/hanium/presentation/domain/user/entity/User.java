package com.hanium.presentation.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "terms_agreed_at")
    private LocalDateTime termsAgreedAt;

    @Column(name = "terms_version", length = 20)
    private String termsVersion;

    protected User() {
    }

    private User(
            String email,
            String passwordHash,
            LocalDateTime termsAgreedAt,
            String termsVersion
    ) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = LocalDateTime.now();
        this.termsAgreedAt = termsAgreedAt;
        this.termsVersion = termsVersion;
    }

    public static User create(String email, String passwordHash) {
        return new User(email, passwordHash, null, null);
    }

    public static User create(
            String email,
            String passwordHash,
            LocalDateTime termsAgreedAt,
            String termsVersion
    ) {
        return new User(email, passwordHash, termsAgreedAt, termsVersion);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getTermsAgreedAt() {
        return termsAgreedAt;
    }

    public String getTermsVersion() {
        return termsVersion;
    }
}
