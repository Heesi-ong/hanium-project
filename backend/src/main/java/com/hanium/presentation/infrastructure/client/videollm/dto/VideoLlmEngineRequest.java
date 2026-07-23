package com.hanium.presentation.infrastructure.client.videollm.dto;

public record VideoLlmEngineRequest(
        String jobId,
        String videoPath,
        Integer sampleFps,
        Integer maxFrames,
        Double durationSec,
        String videoDownloadUrl,
        boolean requireReal
) {

    public static VideoLlmEngineRequest defaultOption(
            String jobId,
            String videoPath
    ) {
        return new VideoLlmEngineRequest(
                jobId,
                videoPath,
                1,
                90,
                null,
                null,
                false
        );
    }

    public static VideoLlmEngineRequest defaultOption(
            String jobId,
            String videoPath,
            Double durationSec
    ) {
        return new VideoLlmEngineRequest(
                jobId,
                videoPath,
                1,
                90,
                durationSec,
                null,
                false
        );
    }

    public static VideoLlmEngineRequest defaultOption(
            String jobId,
            String videoPath,
            Double durationSec,
            String videoDownloadUrl
    ) {
        return defaultOption(jobId, videoPath, durationSec, videoDownloadUrl, false);
    }

    public static VideoLlmEngineRequest defaultOption(
            String jobId,
            String videoPath,
            Double durationSec,
            String videoDownloadUrl,
            boolean requireReal
    ) {
        return new VideoLlmEngineRequest(
                jobId,
                videoPath,
                1,
                90,
                durationSec,
                videoDownloadUrl,
                requireReal
        );
    }
}
