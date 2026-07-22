package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.coach.entity.CoachMessage;

import java.util.List;

public record CoachConversationResponse(
        List<CoachMessageResponse> messages,
        DailyUsageResponse dailyUsage
) {
    public static CoachConversationResponse from(
            List<CoachMessage> messages,
            long dailyUsageUsed,
            long dailyUsageCapacity
    ) {
        return new CoachConversationResponse(
                messages.stream().map(CoachMessageResponse::from).toList(),
                DailyUsageResponse.of(dailyUsageUsed, dailyUsageCapacity)
        );
    }

    public record DailyUsageResponse(
            long used,
            long capacity,
            long remaining
    ) {
        public static DailyUsageResponse of(long used, long capacity) {
            return new DailyUsageResponse(used, capacity, Math.max(0, capacity - used));
        }
    }
}
