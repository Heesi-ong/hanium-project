package com.hanium.presentation.application.result;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.presentation.dto.response.ResultSummaryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResultQueryServiceTest {

    private final AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
    private final UploadedVideoRepository uploadedVideoRepository = mock(UploadedVideoRepository.class);
    private final FilePathGenerator filePathGenerator = mock(FilePathGenerator.class);
    private final JsonFileStorage jsonFileStorage = mock(JsonFileStorage.class);

    private final ResultQueryService resultQueryService = new ResultQueryService(
            analysisJobRepository,
            uploadedVideoRepository,
            filePathGenerator,
            jsonFileStorage
    );

    @Test
    void getResultSummariesLoadsUploadedVideosWithSingleBatchQuery() {
        Long ownerId = 1L;
        PageRequest pageRequest = PageRequest.of(0, 2);
        AnalysisJob firstJob = AnalysisJob.create("20260703090000-aaaaaaaa", ownerId);
        AnalysisJob secondJob = AnalysisJob.create("20260703090001-bbbbbbbb", ownerId);
        List<AnalysisJob> analysisJobs = List.of(firstJob, secondJob);
        List<String> jobIds = analysisJobs.stream()
                .map(AnalysisJob::getJobId)
                .toList();

        when(analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageRequest))
                .thenReturn(new PageImpl<>(analysisJobs, pageRequest, analysisJobs.size()));
        when(uploadedVideoRepository.findAllByJobIdIn(jobIds))
                .thenReturn(List.of(
                        createUploadedVideo(firstJob.getJobId(), "first.mp4"),
                        createUploadedVideo(secondJob.getJobId(), "second.mp4")
                ));
        when(filePathGenerator.generateFinalResultPath(firstJob.getJobId()))
                .thenReturn(Path.of("results", firstJob.getJobId(), "final-result.json"));
        when(filePathGenerator.generateFinalResultPath(secondJob.getJobId()))
                .thenReturn(Path.of("results", secondJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readJson(any(Path.class), eq(Map.class)))
                .thenReturn(Map.of());

        Page<ResultSummaryResponse> response = resultQueryService.getResultSummaries(ownerId, pageRequest);

        assertThat(response.getContent())
                .extracting(ResultSummaryResponse::jobId)
                .containsExactly(firstJob.getJobId(), secondJob.getJobId());
        assertThat(response.getContent())
                .extracting(ResultSummaryResponse::originalFileName)
                .containsExactly("first.mp4", "second.mp4");
        verify(uploadedVideoRepository).findAllByJobIdIn(jobIds);
        verify(uploadedVideoRepository, never()).findByJobId(any());
    }

    private UploadedVideo createUploadedVideo(String jobId, String originalFileName) {
        return UploadedVideo.create(
                jobId,
                originalFileName,
                Path.of("storage", "uploads", jobId, "original.mp4").toString(),
                VideoFileType.MP4,
                1024L
        );
    }
}
