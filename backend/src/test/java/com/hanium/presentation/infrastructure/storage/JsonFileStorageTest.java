package com.hanium.presentation.infrastructure.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JsonFileStorageTest {

    @TempDir
    private Path tempDir;

    private ObjectStorage objectStorage;
    private JsonFileStorage jsonFileStorage;

    @BeforeEach
    void setUp() {
        objectStorage = mock(ObjectStorage.class);
        jsonFileStorage = new JsonFileStorage(new ObjectMapper(), objectStorage);
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
    void readJsonStillWorksUnchanged() {
        Path path = tempDir.resolve("results").resolve("job-3").resolve("final-result.json");
        jsonFileStorage.saveJson(path, Map.of("level", "우수"));

        Map<?, ?> loaded = jsonFileStorage.readJson(path, Map.class);

        assertThat(loaded.get("level")).isEqualTo("우수");
    }
}
