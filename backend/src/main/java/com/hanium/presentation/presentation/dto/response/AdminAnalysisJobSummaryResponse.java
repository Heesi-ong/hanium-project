package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;

import java.time.LocalDateTime;

public record AdminAnalysisJobSummaryResponse(
        String jobId,
        Long ownerId,
        AnalysisStatus status,
        String statusDescription,
        String failReason,
        int retryCount,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {

    public static AdminAnalysisJobSummaryResponse from(AnalysisJob analysisJob) {
        return new AdminAnalysisJobSummaryResponse(
                analysisJob.getJobId(),
                analysisJob.getOwnerId(),
                analysisJob.getStatus(),
                analysisJob.getStatus().getDescription(),
                analysisJob.getFailReason(),
                analysisJob.getRetryCount(),
                analysisJob.getCreatedAt(),
                analysisJob.getCompletedAt()
        );
    }
}
