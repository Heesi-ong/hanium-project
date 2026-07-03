package com.hanium.presentation.application.storage;

import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import com.hanium.presentation.global.properties.StorageProperties;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StorageCleanupServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void cleanupStorageDeletesOnlyOldTempDirectoriesAndOldOrphanUploadAndResultDirectories() throws IOException {
        Path uploadRoot = tempDir.resolve("uploads");
        Path resultRoot = tempDir.resolve("results");
        Path tempRoot = tempDir.resolve("temp");
        Files.createDirectories(uploadRoot);
        Files.createDirectories(resultRoot);
        Files.createDirectories(tempRoot);

        Path existingUpload = createDirectory(uploadRoot, "existing-job");
        Path oldOrphanUpload = createDirectory(uploadRoot, "old-orphan-upload");
        Path recentOrphanUpload = createDirectory(uploadRoot, "recent-orphan-upload");
        Path existingResult = createDirectory(resultRoot, "existing-job");
        Path oldOrphanResult = createDirectory(resultRoot, "old-orphan-result");
        Path recentOrphanResult = createDirectory(resultRoot, "recent-orphan-result");
        Path oldTemp = createDirectory(tempRoot, "old-temp");
        Path recentTemp = createDirectory(tempRoot, "recent-temp");

        markOld(existingUpload);
        markOld(oldOrphanUpload);
        markOld(existingResult);
        markOld(oldOrphanResult);
        markOld(oldTemp);

        AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
        when(analysisJobRepository.existsByJobId("existing-job")).thenReturn(true);
        when(analysisJobRepository.existsByJobId("old-orphan-upload")).thenReturn(false);
        when(analysisJobRepository.existsByJobId("recent-orphan-upload")).thenReturn(false);
        when(analysisJobRepository.existsByJobId("old-orphan-result")).thenReturn(false);
        when(analysisJobRepository.existsByJobId("recent-orphan-result")).thenReturn(false);
        SchedulerDistributedLock schedulerDistributedLock = mock(SchedulerDistributedLock.class);
        when(schedulerDistributedLock.tryLock(eq("storage-cleanup"), eq(Duration.ofMinutes(10)))).thenReturn(true);

        StorageCleanupService storageCleanupService = new StorageCleanupService(
                analysisJobRepository,
                new FilePathGenerator(new StorageProperties(
                        tempDir.toString(),
                        uploadRoot.toString(),
                        resultRoot.toString(),
                        tempRoot.toString(),
                        tempDir.resolve("logs").toString()
                )),
                schedulerDistributedLock,
                6,
                24,
                10
        );

        storageCleanupService.cleanupStorage();

        assertThat(existingUpload).exists();
        assertThat(existingResult).exists();
        assertThat(recentOrphanUpload).exists();
        assertThat(recentOrphanResult).exists();
        assertThat(recentTemp).exists();

        assertThat(oldOrphanUpload).doesNotExist();
        assertThat(oldOrphanResult).doesNotExist();
        assertThat(oldTemp).doesNotExist();
    }

    @Test
    void cleanupStorageSkipsWhenDistributedLockIsAlreadyHeld() throws IOException {
        Path uploadRoot = tempDir.resolve("uploads");
        Path resultRoot = tempDir.resolve("results");
        Path tempRoot = tempDir.resolve("temp");
        Files.createDirectories(uploadRoot);
        Files.createDirectories(resultRoot);
        Files.createDirectories(tempRoot);

        Path oldTemp = createDirectory(tempRoot, "old-temp");
        markOld(oldTemp);

        AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
        SchedulerDistributedLock schedulerDistributedLock = mock(SchedulerDistributedLock.class);
        when(schedulerDistributedLock.tryLock(eq("storage-cleanup"), eq(Duration.ofMinutes(10)))).thenReturn(false);

        StorageCleanupService storageCleanupService = new StorageCleanupService(
                analysisJobRepository,
                new FilePathGenerator(new StorageProperties(
                        tempDir.toString(),
                        uploadRoot.toString(),
                        resultRoot.toString(),
                        tempRoot.toString(),
                        tempDir.resolve("logs").toString()
                )),
                schedulerDistributedLock,
                6,
                24,
                10
        );

        storageCleanupService.cleanupStorage();

        assertThat(oldTemp).exists();
        verifyNoInteractions(analysisJobRepository);
    }

    private Path createDirectory(Path rootDirectory, String directoryName) throws IOException {
        Path directory = rootDirectory.resolve(directoryName);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("fixture.txt"), "fixture");
        return directory;
    }

    private void markOld(Path directory) throws IOException {
        FileTime oldTime = FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS));
        Files.setLastModifiedTime(directory, oldTime);
        Files.setLastModifiedTime(directory.resolve("fixture.txt"), oldTime);
    }
}
