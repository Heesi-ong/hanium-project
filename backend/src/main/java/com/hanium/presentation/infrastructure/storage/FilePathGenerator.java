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

    public Path generateUploadRootDirectory() {
        return Path.of(storageProperties.uploadPath());
    }

    public Path generateOriginalVideoPath(String jobId, String extension) {
        return generateUploadDirectory(jobId)
                .resolve("original" + extension);
    }

    public Path generateResultDirectory(String jobId) {
        return Path.of(storageProperties.resultPath(), jobId);
    }

    public Path generateResultRootDirectory() {
        return Path.of(storageProperties.resultPath());
    }

    public Path generateTempRootDirectory() {
        return Path.of(storageProperties.tempPath());
    }

    /** 분석 엔진이 기본 분석 진행 중 현재 단계를 기록하는 파일입니다(분석 종료 시 temp와 함께 삭제). */
    public Path generateBasicProgressPath(String jobId) {
        return Path.of(storageProperties.tempPath(), jobId, "progress.json");
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

    public Path generateCompactAnalysisPath(String jobId) {
        return generateResultDirectory(jobId)
                .resolve("compact-analysis.json");
    }

    public Path generateOpenAiFeedbackPath(String jobId) {
        return generateResultDirectory(jobId)
                .resolve("openai-feedback.json");
    }

    public Path generateFinalResultPath(String jobId) {
        return generateResultDirectory(jobId)
                .resolve("final-result.json");
    }

    public Path generateResultFramesDirectory(String jobId) {
        return generateResultDirectory(jobId)
                .resolve("frames");
    }

    public Path generateResultFramePath(String jobId, String fileName) {
        return generateResultFramesDirectory(jobId)
                .resolve(fileName);
    }
}
