package com.hanium.presentation.presentation.dto.response;

public record AdminStatsResponse(
        long totalUsers,
        long adminUsers,
        long totalAnalysisJobs,
        long completedAnalysisJobs
) {
}
