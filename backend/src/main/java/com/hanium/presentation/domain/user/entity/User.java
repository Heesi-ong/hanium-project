package com.hanium.presentation.domain.user.entity;

import com.hanium.presentation.domain.user.type.UserRole;
import com.hanium.presentation.domain.user.type.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    @Column(name = "purpose", length = 30)
    private String purpose;

    @Column(name = "experience_level", length = 30)
    private String experienceLevel;

    @Column(name = "improvement_goal", length = 30)
    private String improvementGoal;

    @Column(name = "onboarding_completed_at")
    private LocalDateTime onboardingCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

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
        this.role = UserRole.USER;
        this.status = UserStatus.ACTIVE;
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

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.passwordChangedAt = LocalDateTime.now();
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

    public LocalDateTime getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void completeOnboarding(String purpose, String experienceLevel, String improvementGoal) {
        this.purpose = purpose;
        this.experienceLevel = experienceLevel;
        this.improvementGoal = improvementGoal;
        this.onboardingCompletedAt = LocalDateTime.now();
    }

    public String getPurpose() {
        return purpose;
    }

    public String getExperienceLevel() {
        return experienceLevel;
    }

    public String getImprovementGoal() {
        return improvementGoal;
    }

    public LocalDateTime getOnboardingCompletedAt() {
        return onboardingCompletedAt;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    public void syncRole(UserRole role) {
        this.role = role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isSuspended() {
        return status == UserStatus.SUSPENDED;
    }

    public void suspend() {
        this.status = UserStatus.SUSPENDED;
    }

    public void activate() {
        this.status = UserStatus.ACTIVE;
    }
}
