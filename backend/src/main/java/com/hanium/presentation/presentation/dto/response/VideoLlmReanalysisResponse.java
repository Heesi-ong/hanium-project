package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;

public record VideoLlmReanalysisResponse(
        String sourceJobId,
        String reanalysisJobId,
        AnalysisStatus status,
        boolean reused
) {

    public static VideoLlmReanalysisResponse created(
            String sourceJobId,
            AnalysisStatusResponse status
    ) {
        return new VideoLlmReanalysisResponse(
                sourceJobId,
                status.jobId(),
                status.status(),
                false
        );
    }

    public static VideoLlmReanalysisResponse reused(AnalysisJob existingJob) {
        return new VideoLlmReanalysisResponse(
                existingJob.getSourceJobId(),
                existingJob.getJobId(),
                existingJob.getStatus(),
                true
        );
    }
}
