package com.hanium.presentation.application.result;

import com.hanium.presentation.application.storage.StorageDeletionTaskService;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.storage.type.StorageDeletionReason;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.infrastructure.storage.LocalFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResultCommandServiceDeleteGuardTest {

    private static final String JOB_ID = "20260714120000-delete01";
    private static final Long OWNER_ID = 1L;

    private final ResultMergeService resultMergeService = mock(ResultMergeService.class);
    private final AnalysisCompactor analysisCompactor = mock(AnalysisCompactor.class);
    private final FilePathGenerator filePathGenerator = mock(FilePathGenerator.class);
    private final JsonFileStorage jsonFileStorage = mock(JsonFileStorage.class);
    private final LocalFileStorage localFileStorage = mock(LocalFileStorage.class);
    private final AnalysisFrameOverlayStorage analysisFrameOverlayStorage = mock(AnalysisFrameOverlayStorage.class);
    private final StorageDeletionTaskService storageDeletionTaskService = mock(StorageDeletionTaskService.class);
    private final AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
    private final UploadedVideoRepository uploadedVideoRepository = mock(UploadedVideoRepository.class);

    private final ResultCommandService resultCommandService = new ResultCommandService(
            resultMergeService,
            analysisCompactor,
            filePathGenerator,
            jsonFileStorage,
            localFileStorage,
            analysisFrameOverlayStorage,
            storageDeletionTaskService,
            analysisJobRepository,
            uploadedVideoRepository
    );

    @ParameterizedTest
    @EnumSource(value = AnalysisStatus.class, names = {"QUEUED", "BASIC_ANALYZING"})
    void rejectsDeletionWhileAnalysisIsQueuedOrRunning(AnalysisStatus status) {
        AnalysisJob analysisJob = createJobWithStatus(status);
        when(analysisJobRepository.findByJobIdForUpdate(JOB_ID)).thenReturn(Optional.of(analysisJob));

        assertThatThrownBy(() -> resultCommandService.deleteResult(JOB_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_DELETE_NOT_ALLOWED);

        verify(localFileStorage, never()).deleteDirectoryIfExists(org.mockito.ArgumentMatchers.any());
        verify(analysisJobRepository, never()).delete(analysisJob);
    }

    @ParameterizedTest
    @EnumSource(value = AnalysisStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void deletesFilesAndJobForTerminalStatus(AnalysisStatus status) {
        AnalysisJob analysisJob = createJobWithStatus(status);
        Path uploadDirectory = Path.of("uploads", JOB_ID);
        Path resultDirectory = Path.of("results", JOB_ID);

        when(analysisJobRepository.findByJobIdForUpdate(JOB_ID)).thenReturn(Optional.of(analysisJob));
        when(uploadedVideoRepository.findByJobIdForUpdate(JOB_ID)).thenReturn(Optional.empty());
        when(filePathGenerator.generateUploadDirectory(JOB_ID)).thenReturn(uploadDirectory);
        when(filePathGenerator.generateResultDirectory(JOB_ID)).thenReturn(resultDirectory);

        resultCommandService.deleteResult(JOB_ID, OWNER_ID);

        verify(localFileStorage, times(1)).deleteDirectoryIfExists(uploadDirectory);
        verify(localFileStorage, times(1)).deleteDirectoryIfExists(resultDirectory);
        verify(storageDeletionTaskService, times(1))
                .enqueue(JOB_ID, "uploads/" + JOB_ID + "/", StorageDeletionReason.RESULT_DELETE);
        verify(storageDeletionTaskService, times(1))
                .enqueue(JOB_ID, "results/" + JOB_ID + "/", StorageDeletionReason.RESULT_DELETE);
        verify(analysisJobRepository, times(1)).delete(analysisJob);
    }

    @Test
    void deletesOnlyResultWhenAnotherJobStillReferencesTheVideoAsset() {
        String assetStorageJobId = "20260714115959-source01";
        Long videoAssetId = 99L;
        AnalysisJob analysisJob = createJobWithStatus(AnalysisStatus.COMPLETED);
        analysisJob.linkVideoAsset(videoAssetId);
        UploadedVideo videoAsset = UploadedVideo.create(
                assetStorageJobId,
                "original.mp4",
                "uploads/" + assetStorageJobId + "/original.mp4",
                VideoFileType.MP4,
                10L
        );
        ReflectionTestUtils.setField(videoAsset, "id", videoAssetId);
        Path resultDirectory = Path.of("results", JOB_ID);

        when(analysisJobRepository.findByJobIdForUpdate(JOB_ID)).thenReturn(Optional.of(analysisJob));
        when(uploadedVideoRepository.findByIdForUpdate(videoAssetId)).thenReturn(Optional.of(videoAsset));
        // 현재 작업을 flush로 제거한 뒤에도 재분석 작업 하나가 같은 asset을 참조하는 상황입니다.
        when(analysisJobRepository.countByVideoAssetId(videoAssetId)).thenReturn(1L);
        when(filePathGenerator.generateResultDirectory(JOB_ID)).thenReturn(resultDirectory);

        resultCommandService.deleteResult(JOB_ID, OWNER_ID);

        verify(analysisJobRepository).delete(analysisJob);
        verify(analysisJobRepository).flush();
        verify(uploadedVideoRepository, never()).delete(videoAsset);
        verify(filePathGenerator, never()).generateUploadDirectory(assetStorageJobId);
        verify(localFileStorage, times(1)).deleteDirectoryIfExists(resultDirectory);
        verify(storageDeletionTaskService, never()).enqueue(
                assetStorageJobId,
                "uploads/" + assetStorageJobId + "/",
                StorageDeletionReason.RESULT_DELETE
        );
        verify(storageDeletionTaskService, times(1)).enqueue(
                JOB_ID,
                "results/" + JOB_ID + "/",
                StorageDeletionReason.RESULT_DELETE
        );
    }

    @Test
    void rejectsDeletingSourceWhileReanalysisChildStillExists() {
        AnalysisJob sourceJob = createJobWithStatus(AnalysisStatus.COMPLETED);
        when(analysisJobRepository.findByJobIdForUpdate(JOB_ID)).thenReturn(Optional.of(sourceJob));
        when(analysisJobRepository.existsBySourceJobId(JOB_ID)).thenReturn(true);

        assertThatThrownBy(() -> resultCommandService.deleteResult(JOB_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException businessException = (BusinessException) exception;
                    org.assertj.core.api.Assertions.assertThat(businessException.getErrorCode())
                            .isEqualTo(ErrorCode.ANALYSIS_DELETE_NOT_ALLOWED);
                    org.assertj.core.api.Assertions.assertThat(businessException.getMessage())
                            .contains("재분석 결과를 먼저 삭제");
                });

        verify(analysisJobRepository, never()).delete(sourceJob);
        verify(uploadedVideoRepository, never()).delete(any());
        verify(storageDeletionTaskService, never()).enqueue(any(), any(), any());
        verify(localFileStorage, never()).deleteDirectoryIfExists(any());
    }

    // MinIO 삭제는 더 이상 이 메서드 안에서 직접 시도하지 않고, outbox 행을 만드는 것으로
    // 대신한다. 이 outbox 생성은 업무 데이터 삭제와 원자적으로 커밋돼야 하므로(같은 트랜잭션),
    // enqueue 자체가 실패하면 전체 삭제도 실패해야 한다(이전처럼 조용히 넘어가면 안 된다).
    @Test
    void deleteResultPropagatesFailureWhenEnqueueingTheDeletionTaskFails() {
        AnalysisJob analysisJob = createJobWithStatus(AnalysisStatus.COMPLETED);
        Path uploadDirectory = Path.of("uploads", JOB_ID);
        Path resultDirectory = Path.of("results", JOB_ID);

        when(analysisJobRepository.findByJobIdForUpdate(JOB_ID)).thenReturn(Optional.of(analysisJob));
        when(uploadedVideoRepository.findByJobIdForUpdate(JOB_ID)).thenReturn(Optional.empty());
        when(filePathGenerator.generateUploadDirectory(JOB_ID)).thenReturn(uploadDirectory);
        when(filePathGenerator.generateResultDirectory(JOB_ID)).thenReturn(resultDirectory);
        doThrow(new RuntimeException("db down"))
                .when(storageDeletionTaskService)
                .enqueue(any(), any(), any());

        assertThatThrownBy(() -> resultCommandService.deleteResult(JOB_ID, OWNER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");
    }

    private AnalysisJob createJobWithStatus(AnalysisStatus status) {
        AnalysisJob analysisJob = AnalysisJob.create(JOB_ID, OWNER_ID);

        switch (status) {
            case QUEUED -> analysisJob.enqueue(true, true);
            case BASIC_ANALYZING -> analysisJob.startBasicAnalysis();
            case COMPLETED -> analysisJob.complete();
            case FAILED -> analysisJob.fail("test failure");
            case CANCELLED -> analysisJob.markCancelled();
            default -> throw new IllegalArgumentException("Unsupported test status: " + status);
        }

        return analysisJob;
    }
}
