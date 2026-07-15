package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.coach.entity.CoachMessage;

import java.util.List;

public record CoachConversationResponse(
        List<CoachMessageResponse> messages
) {
    public static CoachConversationResponse from(List<CoachMessage> messages) {
        return new CoachConversationResponse(
                messages.stream().map(CoachMessageResponse::from).toList()
        );
    }
}
