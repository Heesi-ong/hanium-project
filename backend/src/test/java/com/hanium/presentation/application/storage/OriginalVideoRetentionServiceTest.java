package com.hanium.presentation.application.storage;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "storage.upload-path=${user.dir}/build/test-storage/original-video-retention/uploads",
        "storage.result-path=${user.dir}/build/test-storage/original-video-retention/results",
        "storage.retention.original-video-days=30"
})
class OriginalVideoRetentionServiceTest {

    private static final String OLD_COMPLETED_JOB_ID = "20260704010000-aaaaaaaa";
    private static final String RECENT_COMPLETED_JOB_ID = "20260704010001-bbbbbbbb";
    private static final String OLD_FAILED_JOB_ID = "20260704010002-cccccccc";
    private static final String OLD_CANCELLED_JOB_ID = "20260704010003-dddddddd";
    private static final String LOCKED_JOB_ID = "20260704010004-eeeeeeee";
    private static final Path TEST_STORAGE_ROOT = Path.of("build", "test-storage", "original-video-retention");

    @Autowired
    private OriginalVideoRetentionService originalVideoRetentionService;

    @Autowired
    private AnalysisJobRepository analysisJobRepository;

    @Autowired
    private UploadedVideoRepository uploadedVideoRepository;

    @Autowired
    private FilePathGenerator filePathGenerator;

    @MockBean
    private SchedulerDistributedLock schedulerDistributedLock;

    @MockBean
    private ObjectStorage objectStorage;

    @BeforeEach
    void setUp() throws IOException {
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
        deleteRecursively(TEST_STORAGE_ROOT);
        when(schedulerDistributedLock.tryLock(eq("original-video-retention"), any(Duration.class))).thenReturn(true);
    }

    @Test
    void cleanupExpiredOriginalVideosDeletesOnlyOldCompletedJobUploadDirectory() throws IOException {
        createJobWithFiles(completedJob(OLD_COMPLETED_JOB_ID, LocalDateTime.now().minusDays(31)));
        createJobWithFiles(completedJob(RECENT_COMPLETED_JOB_ID, LocalDateTime.now().minusDays(5)));
        createJobWithFiles(failedJob(OLD_FAILED_JOB_ID, LocalDateTime.now().minusDays(31)));
        createJobWithFiles(cancelledJob(OLD_CANCELLED_JOB_ID, LocalDateTime.now().minusDays(31)));

        originalVideoRetentionService.cleanupExpiredOriginalVideos();

        assertThat(filePathGenerator.generateUploadDirectory(OLD_COMPLETED_JOB_ID)).doesNotExist();
        assertThat(filePathGenerator.generateResultDirectory(OLD_COMPLETED_JOB_ID)).exists();
        assertThat(uploadedVideoRepository.existsByJobId(OLD_COMPLETED_JOB_ID)).isFalse();

        assertUploadAndResultRemain(RECENT_COMPLETED_JOB_ID);
        assertUploadAndResultRemain(OLD_FAILED_JOB_ID);
        assertUploadAndResultRemain(OLD_CANCELLED_JOB_ID);
        assertThat(uploadedVideoRepository.existsByJobId(RECENT_COMPLETED_JOB_ID)).isTrue();
        assertThat(uploadedVideoRepository.existsByJobId(OLD_FAILED_JOB_ID)).isTrue();
        assertThat(uploadedVideoRepository.existsByJobId(OLD_CANCELLED_JOB_ID)).isTrue();
        assertThat(findJob(OLD_COMPLETED_JOB_ID).getStatus()).isEqualTo(AnalysisStatus.COMPLETED);

        verify(objectStorage).deleteObjectsWithPrefix("uploads/" + OLD_COMPLETED_JOB_ID + "/");
        verify(objectStorage, org.mockito.Mockito.never()).deleteObjectsWithPrefix("uploads/" + RECENT_COMPLETED_JOB_ID + "/");
        verify(objectStorage, org.mockito.Mockito.never()).deleteObjectsWithPrefix("uploads/" + OLD_FAILED_JOB_ID + "/");
        verify(objectStorage, org.mockito.Mockito.never()).deleteObjectsWithPrefix("uploads/" + OLD_CANCELLED_JOB_ID + "/");
    }

    @Test
    void cleanupExpiredOriginalVideosSkipsWhenDistributedLockIsAlreadyHeld() throws IOException {
        createJobWithFiles(completedJob(LOCKED_JOB_ID, LocalDateTime.now().minusDays(31)));
        when(schedulerDistributedLock.tryLock(eq("original-video-retention"), any(Duration.class))).thenReturn(false);

        originalVideoRetentionService.cleanupExpiredOriginalVideos();

        assertUploadAndResultRemain(LOCKED_JOB_ID);
        assertThat(uploadedVideoRepository.existsByJobId(LOCKED_JOB_ID)).isTrue();
    }

    @Test
    void cleanupExpiredOriginalVideosStillDeletesLocalFilesWhenObjectStorageCleanupFails() throws IOException {
        createJobWithFiles(completedJob(OLD_COMPLETED_JOB_ID, LocalDateTime.now().minusDays(31)));
        org.mockito.Mockito.doThrow(new RuntimeException("minio down"))
                .when(objectStorage).deleteObjectsWithPrefix(any());

        originalVideoRetentionService.cleanupExpiredOriginalVideos();

        assertThat(filePathGenerator.generateUploadDirectory(OLD_COMPLETED_JOB_ID)).doesNotExist();
        assertThat(uploadedVideoRepository.existsByJobId(OLD_COMPLETED_JOB_ID)).isFalse();
    }

    private void createJobWithFiles(AnalysisJob analysisJob) throws IOException {
        String jobId = analysisJob.getJobId();
        Path uploadDirectory = filePathGenerator.generateUploadDirectory(jobId);
        Path resultDirectory = filePathGenerator.generateResultDirectory(jobId);
        Files.createDirectories(uploadDirectory);
        Files.createDirectories(resultDirectory);

        Path originalVideoPath = uploadDirectory.resolve("original.mp4");
        Files.writeString(originalVideoPath, "fake mp4 content");
        Files.writeString(resultDirectory.resolve("final-result.json"), "{}");

        analysisJobRepository.saveAndFlush(analysisJob);
        uploadedVideoRepository.save(UploadedVideo.create(
                jobId,
                jobId + ".mp4",
                originalVideoPath.toString(),
                VideoFileType.MP4,
                Files.size(originalVideoPath)
        ));
    }

    private AnalysisJob completedJob(String jobId, LocalDateTime completedAt) {
        AnalysisJob analysisJob = AnalysisJob.create(jobId, 1L);
        analysisJob.startBasicAnalysis();
        analysisJob.complete();
        ReflectionTestUtils.setField(analysisJob, "completedAt", completedAt);
        return analysisJob;
    }

    private AnalysisJob failedJob(String jobId, LocalDateTime completedAt) {
        AnalysisJob analysisJob = AnalysisJob.create(jobId, 1L);
        analysisJob.startBasicAnalysis();
        analysisJob.fail("테스트 실패 상태");
        ReflectionTestUtils.setField(analysisJob, "completedAt", completedAt);
        return analysisJob;
    }

    private AnalysisJob cancelledJob(String jobId, LocalDateTime completedAt) {
        AnalysisJob analysisJob = AnalysisJob.create(jobId, 1L);
        analysisJob.startBasicAnalysis();
        analysisJob.requestCancel();
        analysisJob.markCancelled();
        ReflectionTestUtils.setField(analysisJob, "completedAt", completedAt);
        return analysisJob;
    }

    private AnalysisJob findJob(String jobId) {
        return analysisJobRepository.findByJobId(jobId)
                .orElseThrow();
    }

    private void assertUploadAndResultRemain(String jobId) {
        assertThat(filePathGenerator.generateUploadDirectory(jobId)).exists();
        assertThat(filePathGenerator.generateResultDirectory(jobId)).exists();
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }

        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException("테스트 파일 삭제 실패: " + path, exception);
                        }
                    });
        }
    }
}
