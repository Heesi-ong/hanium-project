package com.hanium.presentation.infrastructure.storage;

import com.hanium.presentation.global.properties.StorageProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class FilePathGenerator {

    private final StorageProperties storageProperties;

    public FilePathGenerator(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    public Path generateUploadDirectory(String jobId) {
        return Path.of(storageProperties.uploadPath(), jobId);
    }

    public Path generateOriginalVideoPath(String jobId, String extension) {
        return generateUploadDirectory(jobId)
                .resolve("original" + extension);
    }

    public Path generateResultDirectory(String jobId) {
        return Path.of(storageProperties.resultPath(), jobId);
    }

    public Path generateUploadMetaPath(String jobId) {
        return generateResultDirectory(jobId)
                .resolve("upload-meta.json");
    }

    public Path generateBasicAnalysisPath(String jobId) {
        return generateResultDirectory(jobId)
                .resolve("basic-analysis.json");
    }

    public Path generateVideoLlmRawPath(String jobId) {
        return generateResultDirectory(jobId)
                .resolve("video-llm-raw.json");
    }

    public Path generateVideoLlmCompactPath(String jobId) {
        return generateResultDirectory(jobId)
                .resolve("video-llm-compact.json");
    }

    public Path generateOpenAiFeedbackPath(String jobId) {
        return generateResultDirectory(jobId)
                .resolve("openai-feedback.json");
    }

    public Path generateFinalResultPath(String jobId) {
        return generateResultDirectory(jobId)
                .resolve("final-result.json");
    }
}