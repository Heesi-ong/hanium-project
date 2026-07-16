package com.hanium.presentation.infrastructure.client.videollm.dto;

public record VideoLlmEngineRequest(
        String jobId,
        String videoPath,
        Integer sampleFps,
        Integer maxFrames,
        Double durationSec,
        String videoDownloadUrl
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
                null
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
                null
        );
    }

    public static VideoLlmEngineRequest defaultOption(
            String jobId,
            String videoPath,
            Double durationSec,
            String videoDownloadUrl
    ) {
        return new VideoLlmEngineRequest(
                jobId,
                videoPath,
                1,
                90,
                durationSec,
                videoDownloadUrl
        );
    }
}
