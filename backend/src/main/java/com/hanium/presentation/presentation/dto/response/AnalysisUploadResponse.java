package com.hanium.presentation.presentation.dto.response;

import com.hanium.presentation.domain.analysis.type.AnalysisStatus;

public record AnalysisUploadResponse(
        String jobId,
        AnalysisStatus status,
        String statusDescription,
        String originalFileName,
        String storedFilePath,
        Long fileSize
) {

    public static AnalysisUploadResponse of(
            String jobId,
            AnalysisStatus status,
            String originalFileName,
            String storedFilePath,
            Long fileSize
    ) {
        return new AnalysisUploadResponse(
                jobId,
                status,
                status.getDescription(),
                originalFileName,
                storedFilePath,
                fileSize
        );
    }
}