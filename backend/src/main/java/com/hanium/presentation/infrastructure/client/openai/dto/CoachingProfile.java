package com.hanium.presentation.infrastructure.client.openai.dto;

/** 외부 AI에 전달해도 되는 비식별 온보딩 코칭 컨텍스트입니다. */
public record CoachingProfile(
        String purpose,
        String experienceLevel,
        String improvementGoal
) {
    public static CoachingProfile of(
            String purpose,
            String experienceLevel,
            String improvementGoal
    ) {
        return new CoachingProfile(
                normalize(purpose),
                normalize(experienceLevel),
                normalize(improvementGoal)
        );
    }

    public static CoachingProfile empty() {
        return new CoachingProfile("UNSPECIFIED", "UNSPECIFIED", "UNSPECIFIED");
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "UNSPECIFIED" : value;
    }
}
