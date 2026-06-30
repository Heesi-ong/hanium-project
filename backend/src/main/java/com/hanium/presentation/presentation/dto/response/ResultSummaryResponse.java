package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.video.entity.UploadedVideo;

import java.time.LocalDateTime;

public record ResultSummaryResponse(
        String jobId,
        AnalysisStatus status,
        String statusDescription,
        String originalFileName,
        Long fileSize,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {

    public static ResultSummaryResponse of(
            AnalysisJob analysisJob,
            UploadedVideo uploadedVideo
    ) {
        return new ResultSummaryResponse(
                analysisJob.getJobId(),
                analysisJob.getStatus(),
                analysisJob.getStatus().getDescription(),
                uploadedVideo.getOriginalFileName(),
                uploadedVideo.getFileSize(),
                analysisJob.getCreatedAt(),
                analysisJob.getCompletedAt()
        );
    }
}