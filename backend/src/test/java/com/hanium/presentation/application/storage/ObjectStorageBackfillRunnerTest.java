package com.hanium.presentation.application.storage;

import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ObjectStorageBackfillRunnerTest {

    @TempDir
    private Path tempDir;

    @Test
    void backfillRootDirectoryUploadsNewFilesAndSkipsAlreadyMirroredFiles() throws IOException {
        Path uploadRoot = tempDir.resolve("uploads");
        createFile(uploadRoot.resolve("job1").resolve("original.mp4"), "job1 video");
        createFile(uploadRoot.resolve("job2").resolve("original.mov"), "job2 video");

        ObjectStorage objectStorage = mock(ObjectStorage.class);
        when(objectStorage.exists("uploads/job1/original.mp4")).thenReturn(true);
        when(objectStorage.exists("uploads/job2/original.mov")).thenReturn(false);

        ObjectStorageBackfillRunner runner = new ObjectStorageBackfillRunner(
                mock(FilePathGenerator.class),
                objectStorage,
                mock(ConfigurableApplicationContext.class)
        );

        ObjectStorageBackfillRunner.BackfillResult result = runner.backfillRootDirectory(uploadRoot, "uploads/");

        assertThat(result.scanned).isEqualTo(2);
        assertThat(result.uploaded).isEqualTo(1);
        assertThat(result.skipped).isEqualTo(1);
        assertThat(result.failed).isEqualTo(0);

        verify(objectStorage, never()).putObject(eq("uploads/job1/original.mp4"), any(), anyLong(), any());
        verify(objectStorage, times(1)).putObject(eq("uploads/job2/original.mov"), any(), anyLong(), eq("video/quicktime"));
    }

    @Test
    void backfillRootDirectoryContinuesAfterPutObjectFailureAndCountsAsFailed() throws IOException {
        Path resultRoot = tempDir.resolve("results");
        createFile(resultRoot.resolve("job1").resolve("final-result.json"), "{}");
        createFile(resultRoot.resolve("job1").resolve("basic-analysis.json"), "{}");

        ObjectStorage objectStorage = mock(ObjectStorage.class);
        when(objectStorage.exists(any())).thenReturn(false);
        doThrow(new RuntimeException("minio down"))
                .when(objectStorage)
                .putObject(eq("results/job1/final-result.json"), any(), anyLong(), any());

        ObjectStorageBackfillRunner runner = new ObjectStorageBackfillRunner(
                mock(FilePathGenerator.class),
                objectStorage,
                mock(ConfigurableApplicationContext.class)
        );

        ObjectStorageBackfillRunner.BackfillResult result = runner.backfillRootDirectory(resultRoot, "results/");

        assertThat(result.scanned).isEqualTo(2);
        assertThat(result.uploaded).isEqualTo(1);
        assertThat(result.failed).isEqualTo(1);
        assertThat(result.skipped).isEqualTo(0);
    }

    @Test
    void backfillRootDirectoryReturnsEmptyResultWhenRootDirectoryMissing() {
        ObjectStorage objectStorage = mock(ObjectStorage.class);
        ObjectStorageBackfillRunner runner = new ObjectStorageBackfillRunner(
                mock(FilePathGenerator.class),
                objectStorage,
                mock(ConfigurableApplicationContext.class)
        );

        ObjectStorageBackfillRunner.BackfillResult result =
                runner.backfillRootDirectory(tempDir.resolve("does-not-exist"), "uploads/");

        assertThat(result.scanned).isEqualTo(0);
        assertThat(result.uploaded).isEqualTo(0);
        assertThat(result.skipped).isEqualTo(0);
        assertThat(result.failed).isEqualTo(0);
        verifyNoInteractions(objectStorage);
    }

    @Test
    void determineExitCodeFailsWhenAnyBackfillResultHasFailures() {
        ObjectStorageBackfillRunner.BackfillResult successful = new ObjectStorageBackfillRunner.BackfillResult();
        successful.uploaded = 2;
        ObjectStorageBackfillRunner.BackfillResult failed = new ObjectStorageBackfillRunner.BackfillResult();
        failed.failed = 1;

        assertThat(ObjectStorageBackfillRunner.determineExitCode(successful, failed)).isEqualTo(1);
        assertThat(ObjectStorageBackfillRunner.determineExitCode(successful)).isZero();
    }

    private void createFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
