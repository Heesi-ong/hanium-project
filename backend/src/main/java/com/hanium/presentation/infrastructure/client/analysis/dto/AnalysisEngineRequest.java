package com.hanium.presentation.infrastructure.client.analysis.dto;

public record AnalysisEngineRequest(
        String jobId,
        String videoPath
) {
}