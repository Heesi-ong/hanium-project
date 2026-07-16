package com.hanium.presentation.infrastructure.client.analysis.dto;

public record AnalysisEngineRequest(
        String jobId,
        String videoPath,
        String videoDownloadUrl
) {

    public AnalysisEngineRequest(String jobId, String videoPath) {
        this(jobId, videoPath, null);
    }
}
