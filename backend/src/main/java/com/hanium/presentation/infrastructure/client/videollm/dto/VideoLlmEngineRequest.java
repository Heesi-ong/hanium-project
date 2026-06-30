package com.hanium.presentation.infrastructure.client.videollm.dto;

public record VideoLlmEngineRequest(
        String jobId,
        String videoPath,
        Integer sampleFps,
        Integer maxFrames
) {

    public static VideoLlmEngineRequest defaultOption(
            String jobId,
            String videoPath
    ) {
        return new VideoLlmEngineRequest(
                jobId,
                videoPath,
                1,
                90
        );
    }
}