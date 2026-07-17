package com.hanium.presentation.infrastructure.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.ObjectStoragePolicyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JsonFileStorageTest {

    @TempDir
    private Path tempDir;

    private ObjectStorage objectStorage;
    private JsonFileStorage jsonFileStorage;

    @BeforeEach
    void setUp() {
        objectStorage = mock(ObjectStorage.class);
        jsonFileStorage = createStorage(false, false);
    }

    private JsonFileStorage createStorage(boolean writeRequired, boolean readPreferred) {
        return new JsonFileStorage(
                new ObjectMapper(),
                objectStorage,
                new ObjectStoragePolicyProperties(writeRequired, readPreferred)
        );
    }

    @Test
    void saveJsonWritesLocallyAndMirrorsToObjectStorage() {
        Path path = tempDir.resolve("results").resolve("job-1").resolve("final-result.json");

        jsonFileStorage.saveJson(path, Map.of("score", 88));

        Map<?, ?> loaded = jsonFileStorage.readJson(path, Map.class);
        assertThat(loaded.get("score")).isEqualTo(88);

        verify(objectStorage).putObject(
                eq("results/job-1/final-result.json"),
                any(),
                anyLong(),
                eq("application/json")
        );
    }

    @Test
    void saveJsonSucceedsEvenWhenObjectStorageMirrorFails() {
        doThrow(new RuntimeException("minio down"))
                .when(objectStorage)
                .putObject(any(), any(), anyLong(), any());

        Path path = tempDir.resolve("results").resolve("job-2").resolve("final-result.json");

        assertThatCode(() -> jsonFileStorage.saveJson(path, Map.of("score", 70)))
                .doesNotThrowAnyException();

        Map<?, ?> loaded = jsonFileStorage.readJson(path, Map.class);
        assertThat(loaded.get("score")).isEqualTo(70);
    }

    @Test
    void saveJsonFailsAndDeletesLocalFileWhenObjectStorageWriteIsRequired() {
        jsonFileStorage = createStorage(true, true);
        doThrow(new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "minio down"))
                .when(objectStorage)
                .putObject(any(), any(), anyLong(), any());

        Path path = tempDir.resolve("results").resolve("job-strict").resolve("final-result.json");

        assertThatThrownBy(() -> jsonFileStorage.saveJson(path, Map.of("score", 65)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_UPLOAD_FAILED);
        assertThat(Files.exists(path)).isFalse();
    }

    @Test
    void readJsonPrefersObjectStorageOverStaleLocalFile() throws Exception {
        jsonFileStorage = createStorage(false, true);
        Path path = tempDir.resolve("results").resolve("job-object-first").resolve("final-result.json");
        Files.createDirectories(path.getParent());
        new ObjectMapper().writeValue(path.toFile(), Map.of("score", 10));
        when(objectStorage.getObject("results/job-object-first/final-result.json"))
                .thenReturn(new ByteArrayInputStream("{\"score\":95}".getBytes(StandardCharsets.UTF_8)));

        Map<?, ?> loaded = jsonFileStorage.readJson(path, Map.class);

        assertThat(loaded.get("score")).isEqualTo(95);
    }

    @Test
    void readJsonFallsBackToLocalFileWhenObjectStorageReadFails() throws Exception {
        jsonFileStorage = createStorage(false, true);
        Path path = tempDir.resolve("results").resolve("job-read-fallback").resolve("final-result.json");
        Files.createDirectories(path.getParent());
        new ObjectMapper().writeValue(path.toFile(), Map.of("score", 77));
        when(objectStorage.getObject("results/job-read-fallback/final-result.json"))
                .thenThrow(new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "minio down"));

        Map<?, ?> loaded = jsonFileStorage.readJson(path, Map.class);

        assertThat(loaded.get("score")).isEqualTo(77);
    }

    @Test
    void readJsonStillWorksUnchanged() {
        Path path = tempDir.resolve("results").resolve("job-3").resolve("final-result.json");
        jsonFileStorage.saveJson(path, Map.of("level", "우수"));

        Map<?, ?> loaded = jsonFileStorage.readJson(path, Map.class);

        assertThat(loaded.get("level")).isEqualTo("우수");
    }
}
