package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;

import java.time.LocalDateTime;

public record AnalysisStatusResponse(
        String jobId,
        AnalysisStatus status,
        String statusDescription,
        String failReason,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {

    public static AnalysisStatusResponse from(AnalysisJob analysisJob) {
        return new AnalysisStatusResponse(
                analysisJob.getJobId(),
                analysisJob.getStatus(),
                analysisJob.getStatus().getDescription(),
                analysisJob.getFailReason(),
                analysisJob.getCreatedAt(),
                analysisJob.getStartedAt(),
                analysisJob.getCompletedAt()
        );
    }
}