package com.hanium.presentation.application.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.properties.StorageProperties;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BasicAnalysisStepReaderTest {

    private static final String JOB_ID = "20260101120000-abcdef12";

    @TempDir
    Path storageRoot;

    private FilePathGenerator filePathGenerator;
    private BasicAnalysisStepReader reader;

    @BeforeEach
    void setUp() {
        StorageProperties storageProperties = new StorageProperties(
                storageRoot.toString(),
                storageRoot.resolve("uploads").toString(),
                storageRoot.resolve("results").toString(),
                storageRoot.resolve("temp").toString(),
                storageRoot.resolve("logs").toString(),
                1024L
        );
        filePathGenerator = new FilePathGenerator(storageProperties);
        reader = new BasicAnalysisStepReader(filePathGenerator, new ObjectMapper());
    }

    @Test
    void readsProgressJsonWhenPresent() throws Exception {
        Path progressPath = filePathGenerator.generateBasicProgressPath(JOB_ID);
        Files.createDirectories(progressPath.getParent());
        Files.writeString(progressPath, """
                {"jobId":"%s","phase":"BASIC_ANALYSIS","stepNo":5,"totalSteps":9,
                 "stepKey":"pose_gesture","label":"자세와 제스처를 분석하는 중..."}
                """.formatted(JOB_ID));

        Map<String, Object> progress = reader.read(JOB_ID).orElseThrow();

        assertThat(progress).containsEntry("stepNo", 5).containsEntry("totalSteps", 9);
        assertThat(progress.get("label").toString()).startsWith("자세와 제스처");
    }

    @Test
    void returnsEmptyWhenFileMissing() {
        assertThat(reader.read(JOB_ID)).isEmpty();
    }

    @Test
    void returnsEmptyWhenFileIsCorrupt() throws Exception {
        Path progressPath = filePathGenerator.generateBasicProgressPath(JOB_ID);
        Files.createDirectories(progressPath.getParent());
        Files.writeString(progressPath, "{ not json");

        assertThat(reader.read(JOB_ID)).isEmpty();
    }
}
