package com.hanium.presentation.application.result;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.infrastructure.storage.LocalFileStorage;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private final ObjectStorage objectStorage = mock(ObjectStorage.class);
    private final AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
    private final UploadedVideoRepository uploadedVideoRepository = mock(UploadedVideoRepository.class);

    private final ResultCommandService resultCommandService = new ResultCommandService(
            resultMergeService,
            analysisCompactor,
            filePathGenerator,
            jsonFileStorage,
            localFileStorage,
            objectStorage,
            analysisJobRepository,
            uploadedVideoRepository
    );

    @ParameterizedTest
    @EnumSource(value = AnalysisStatus.class, names = {"QUEUED", "BASIC_ANALYZING"})
    void rejectsDeletionWhileAnalysisIsQueuedOrRunning(AnalysisStatus status) {
        AnalysisJob analysisJob = createJobWithStatus(status);
        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(analysisJob));

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

        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(analysisJob));
        when(uploadedVideoRepository.findByJobId(JOB_ID)).thenReturn(Optional.empty());
        when(filePathGenerator.generateUploadDirectory(JOB_ID)).thenReturn(uploadDirectory);
        when(filePathGenerator.generateResultDirectory(JOB_ID)).thenReturn(resultDirectory);

        resultCommandService.deleteResult(JOB_ID, OWNER_ID);

        verify(localFileStorage, times(1)).deleteDirectoryIfExists(uploadDirectory);
        verify(localFileStorage, times(1)).deleteDirectoryIfExists(resultDirectory);
        verify(objectStorage, times(1)).deleteObjectsWithPrefix("uploads/" + JOB_ID + "/");
        verify(objectStorage, times(1)).deleteObjectsWithPrefix("results/" + JOB_ID + "/");
        verify(analysisJobRepository, times(1)).delete(analysisJob);
    }

    @ParameterizedTest
    @EnumSource(value = AnalysisStatus.class, names = {"COMPLETED"})
    void stillDeletesJobWhenObjectStorageCleanupFails(AnalysisStatus status) {
        AnalysisJob analysisJob = createJobWithStatus(status);
        Path uploadDirectory = Path.of("uploads", JOB_ID);
        Path resultDirectory = Path.of("results", JOB_ID);

        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(analysisJob));
        when(uploadedVideoRepository.findByJobId(JOB_ID)).thenReturn(Optional.empty());
        when(filePathGenerator.generateUploadDirectory(JOB_ID)).thenReturn(uploadDirectory);
        when(filePathGenerator.generateResultDirectory(JOB_ID)).thenReturn(resultDirectory);
        org.mockito.Mockito.doThrow(new RuntimeException("MinIO down"))
                .when(objectStorage).deleteObjectsWithPrefix(org.mockito.ArgumentMatchers.anyString());

        resultCommandService.deleteResult(JOB_ID, OWNER_ID);

        verify(analysisJobRepository, times(1)).delete(analysisJob);
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
