package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.coach.entity.CoachMessage;

import java.time.LocalDateTime;

public record CoachMessageResponse(
        String role,
        String content,
        String generationMode,
        LocalDateTime createdAt
) {
    public static CoachMessageResponse from(CoachMessage message) {
        return new CoachMessageResponse(
                message.getRole().name(),
                message.getContent(),
                message.getGenerationMode(),
                message.getCreatedAt()
        );
    }
}
