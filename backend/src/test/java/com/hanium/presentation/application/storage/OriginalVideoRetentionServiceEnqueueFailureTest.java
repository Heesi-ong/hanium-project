package com.hanium.presentation.application.storage;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

// 원본 영상 삭제 DB 처리(uploaded_videos 행 삭제)와 MinIO 정리 outbox 생성은 하나의
// 트랜잭션으로 묶여 있어야 한다. enqueue가 실패하면 그 트랜잭션 전체가 롤백되어 DB 행은
// 그대로 남아야 하며, 로컬 파일 삭제도 commit 뒤에만 실행해 함께 보존되어야 한다.
@SpringBootTest
@TestPropertySource(properties = {
        "storage.upload-path=${user.dir}/build/test-storage/original-video-retention-enqueue-failure/uploads",
        "storage.result-path=${user.dir}/build/test-storage/original-video-retention-enqueue-failure/results",
        "storage.retention.original-video-days=30"
})
class OriginalVideoRetentionServiceEnqueueFailureTest {

    private static final String JOB_ID = "20260704020000-ffffffff";
    private static final Path TEST_STORAGE_ROOT =
            Path.of("build", "test-storage", "original-video-retention-enqueue-failure");

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
    private StorageDeletionTaskService storageDeletionTaskService;

    @BeforeEach
    void setUp() throws IOException {
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
        deleteRecursively(TEST_STORAGE_ROOT);
        when(schedulerDistributedLock.tryLock(eq("original-video-retention"), any(Duration.class))).thenReturn(true);
        doThrow(new RuntimeException("db down")).when(storageDeletionTaskService).enqueue(any(), any(), any());
    }

    @AfterEach
    void tearDown() {
        uploadedVideoRepository.deleteAll();
        analysisJobRepository.deleteAll();
    }

    @Test
    void localFileAndDbRowSurviveWhenEnqueueingTheDeletionTaskFails() throws IOException {
        AnalysisJob analysisJob = AnalysisJob.create(JOB_ID, 1L);
        analysisJob.startBasicAnalysis();
        analysisJob.complete();
        ReflectionTestUtils.setField(analysisJob, "completedAt", LocalDateTime.now().minusDays(31));
        analysisJobRepository.saveAndFlush(analysisJob);

        Path uploadDirectory = filePathGenerator.generateUploadDirectory(JOB_ID);
        Files.createDirectories(uploadDirectory);
        Path originalVideoPath = uploadDirectory.resolve("original.mp4");
        Files.writeString(originalVideoPath, "fake mp4 content");
        uploadedVideoRepository.save(UploadedVideo.create(
                JOB_ID,
                JOB_ID + ".mp4",
                originalVideoPath.toString(),
                VideoFileType.MP4,
                Files.size(originalVideoPath)
        ));

        originalVideoRetentionService.cleanupExpiredOriginalVideos();

        // 로컬 삭제는 DB/outbox commit 뒤에만 실행하므로 enqueue 실패 때 시작되지 않는다.
        assertThat(uploadDirectory).exists();
        // DB 행 삭제는 outbox 생성과 같은 트랜잭션이므로 enqueue 실패로 함께 롤백된다.
        assertThat(uploadedVideoRepository.existsByJobId(JOB_ID)).isTrue();
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
