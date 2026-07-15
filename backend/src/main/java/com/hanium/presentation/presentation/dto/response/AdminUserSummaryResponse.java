package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.type.UserRole;
import com.hanium.presentation.domain.user.type.UserStatus;

import java.time.LocalDateTime;

public record AdminUserSummaryResponse(
        Long id,
        String email,
        UserRole role,
        UserStatus status,
        LocalDateTime createdAt,
        boolean onboardingCompleted,
        long analysisJobCount
) {

    public static AdminUserSummaryResponse of(User user, long analysisJobCount) {
        return new AdminUserSummaryResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getCreatedAt(),
                user.getOnboardingCompletedAt() != null,
                analysisJobCount
        );
    }
}
