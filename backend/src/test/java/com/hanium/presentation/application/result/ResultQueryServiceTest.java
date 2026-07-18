package com.hanium.presentation.application.result;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.presentation.dto.response.AnalysisResultResponse;
import com.hanium.presentation.presentation.dto.response.ResultSummaryResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final ResultQueryService resultQueryService = new ResultQueryService(
            analysisJobRepository,
            uploadedVideoRepository,
            filePathGenerator,
            jsonFileStorage,
            meterRegistry
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

    @Test
    void getResultSummariesMarksJobsWithoutUploadedVideoAsBrokenInsteadOfFailingTheWholePage() {
        Long ownerId = 1L;
        PageRequest pageRequest = PageRequest.of(0, 2);
        AnalysisJob healthyJob = AnalysisJob.create("20260703090002-cccccccc", ownerId);
        AnalysisJob brokenJob = AnalysisJob.create("20260703090003-dddddddd", ownerId);
        List<AnalysisJob> analysisJobs = List.of(healthyJob, brokenJob);
        List<String> jobIds = analysisJobs.stream()
                .map(AnalysisJob::getJobId)
                .toList();

        when(analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageRequest))
                .thenReturn(new PageImpl<>(analysisJobs, pageRequest, analysisJobs.size()));
        // brokenJob의 UploadedVideo 레코드는 의도적으로 반환하지 않아 데이터 정합성이 깨진
        // 상황(예: 업로드 실패 후 정리 누락)을 재현합니다.
        when(uploadedVideoRepository.findAllByJobIdIn(jobIds))
                .thenReturn(List.of(createUploadedVideo(healthyJob.getJobId(), "healthy.mp4")));
        when(filePathGenerator.generateFinalResultPath(any()))
                .thenReturn(Path.of("results", "final-result.json"));
        when(jsonFileStorage.readJson(any(Path.class), eq(Map.class)))
                .thenReturn(Map.of());

        Page<ResultSummaryResponse> response = resultQueryService.getResultSummaries(ownerId, pageRequest);

        assertThat(response.getContent())
                .extracting(ResultSummaryResponse::jobId)
                .containsExactly(healthyJob.getJobId(), brokenJob.getJobId());

        ResultSummaryResponse healthySummary = response.getContent().get(0);
        assertThat(healthySummary.dataIssue()).isNull();
        assertThat(healthySummary.originalFileName()).isEqualTo("healthy.mp4");

        ResultSummaryResponse brokenSummary = response.getContent().get(1);
        assertThat(brokenSummary.dataIssue()).isEqualTo("MISSING_VIDEO");
        assertThat(brokenSummary.dataIssueDescription()).isNotBlank();
        assertThat(dataIssueCount("list", "MISSING_VIDEO")).isEqualTo(1.0);
    }

    @Test
    void getResultSummariesMarksCompletedJobWithMissingResultFileAsBroken() {
        Long ownerId = 1L;
        PageRequest pageRequest = PageRequest.of(0, 1);
        AnalysisJob completedJob = AnalysisJob.create("20260703090004-eeeeeeee", ownerId);
        completedJob.complete();

        when(analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(completedJob), pageRequest, 1));
        when(uploadedVideoRepository.findAllByJobIdIn(List.of(completedJob.getJobId())))
                .thenReturn(List.of(createUploadedVideo(completedJob.getJobId(), "presentation.mp4")));
        when(filePathGenerator.generateFinalResultPath(completedJob.getJobId()))
                .thenReturn(Path.of("results", completedJob.getJobId(), "final-result.json"));
        // final_result.json이 없거나 읽기 실패한 상황을 흉내내, readFinalResultSafely가
        // 실제로 반환하는 빈 맵을 그대로 재현합니다.
        when(jsonFileStorage.readJson(any(Path.class), eq(Map.class)))
                .thenReturn(Map.of());

        Page<ResultSummaryResponse> response = resultQueryService.getResultSummaries(ownerId, pageRequest);

        ResultSummaryResponse summary = response.getContent().get(0);
        assertThat(summary.dataIssue()).isEqualTo("RESULT_DATA_UNAVAILABLE");
        assertThat(summary.dataIssueDescription()).isNotBlank();
        assertThat(summary.originalFileName()).isEqualTo("presentation.mp4");
        assertThat(dataIssueCount("list", "RESULT_DATA_UNAVAILABLE")).isEqualTo(1.0);
    }

    @Test
    void getResultSummariesDoesNotFlagQueuedJobWithoutResultFileYet() {
        Long ownerId = 1L;
        PageRequest pageRequest = PageRequest.of(0, 1);
        AnalysisJob queuedJob = AnalysisJob.create("20260703090005-ffffffff", ownerId);
        queuedJob.enqueue(true, true);

        when(analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(queuedJob), pageRequest, 1));
        when(uploadedVideoRepository.findAllByJobIdIn(List.of(queuedJob.getJobId())))
                .thenReturn(List.of(createUploadedVideo(queuedJob.getJobId(), "queued.mp4")));
        when(filePathGenerator.generateFinalResultPath(queuedJob.getJobId()))
                .thenReturn(Path.of("results", queuedJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readJson(any(Path.class), eq(Map.class)))
                .thenReturn(Map.of());

        Page<ResultSummaryResponse> response = resultQueryService.getResultSummaries(ownerId, pageRequest);

        assertThat(response.getContent().get(0).dataIssue()).isNull();
    }

    @Test
    void getResultSummariesMarksCompletedJobWithPlaceholderResultAsIncomplete() {
        Long ownerId = 1L;
        PageRequest pageRequest = PageRequest.of(0, 1);
        AnalysisJob completedJob = AnalysisJob.create("20260703090006-gggggggg", ownerId);
        completedJob.complete();

        when(analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(completedJob), pageRequest, 1));
        when(uploadedVideoRepository.findAllByJobIdIn(List.of(completedJob.getJobId())))
                .thenReturn(List.of(createUploadedVideo(completedJob.getJobId(), "placeholder.mp4")));
        when(filePathGenerator.generateFinalResultPath(completedJob.getJobId()))
                .thenReturn(Path.of("results", completedJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readJson(any(Path.class), eq(Map.class)))
                .thenReturn(Map.of(
                        "scoreSummary", Map.of(
                                "totalScore", 0,
                                "postureScore", 0,
                                "gazeScore", 0,
                                "speechScore", 0,
                                "gestureScore", 0,
                                "expressionScore", 0,
                                "level", "-"
                        ),
                        "feedback", Map.of(
                                "generationMode", "UNKNOWN",
                                "overall", ""
                        )
                ));

        Page<ResultSummaryResponse> response = resultQueryService.getResultSummaries(ownerId, pageRequest);

        ResultSummaryResponse summary = response.getContent().get(0);
        assertThat(summary.dataIssue()).isEqualTo("RESULT_DATA_INCOMPLETE");
        assertThat(summary.dataIssueDescription()).contains("불완전");
        assertThat(dataIssueCount("list", "RESULT_DATA_INCOMPLETE")).isEqualTo(1.0);
    }

    @Test
    void getResultSummariesIncludesVideoLlmGenerationMetadataForListCards() {
        Long ownerId = 1L;
        PageRequest pageRequest = PageRequest.of(0, 1);
        AnalysisJob completedJob = AnalysisJob.create("20260703090009-jjjjjjjj", ownerId);
        completedJob.complete();

        when(analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(completedJob), pageRequest, 1));
        when(uploadedVideoRepository.findAllByJobIdIn(List.of(completedJob.getJobId())))
                .thenReturn(List.of(createUploadedVideo(completedJob.getJobId(), "video-llm.mp4")));
        when(filePathGenerator.generateFinalResultPath(completedJob.getJobId()))
                .thenReturn(Path.of("results", completedJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readJson(any(Path.class), eq(Map.class)))
                .thenReturn(Map.of(
                        "scoreSummary", Map.of(
                                "totalScore", 91,
                                "level", "A"
                        ),
                        "feedback", Map.of(
                                "generationMode", "REAL",
                                "model", "gpt-4.1-mini",
                                "realApiUsed", true,
                                "overall", "피드백"
                        ),
                        "visualAnalysis", Map.of(
                                "model", Map.of(
                                        "name", "mock-video-llm",
                                        "version", "local-mock",
                                        "generationMode", "MOCK"
                                ),
                                "observations", Map.of(
                                        "eyeContact", List.of(Map.of("label", "sample"))
                                )
                        ),
                        "pipeline", Map.of(
                                "videoLlmAnalysis", "video-llm-engine mock",
                                "videoLlmGenerationMode", "MOCK",
                                "openAiGenerationMode", "REAL",
                                "openAiModel", "gpt-4.1-mini",
                                "openAiRealApiUsed", true,
                                "openAiFallbackReason", "-"
                        )
                ));

        Page<ResultSummaryResponse> response = resultQueryService.getResultSummaries(ownerId, pageRequest);

        ResultSummaryResponse summary = response.getContent().get(0);
        assertThat(summary.dataIssue()).isNull();
        assertThat(summary.visualAnalysis())
                .containsEntry("model", Map.of(
                        "name", "mock-video-llm",
                        "version", "local-mock",
                        "generationMode", "MOCK"
                ));
        assertThat(summary.visualAnalysis()).doesNotContainKey("observations");
        assertThat(summary.pipeline())
                .containsEntry("videoLlmAnalysis", "video-llm-engine mock")
                .containsEntry("videoLlmGenerationMode", "MOCK")
                .containsEntry("openAiGenerationMode", "REAL");
    }

    @Test
    void getResultSummariesUsesPipelineVideoLlmMetadataWhenVisualModelIsPlaceholder() {
        Long ownerId = 1L;
        PageRequest pageRequest = PageRequest.of(0, 1);
        AnalysisJob completedJob = AnalysisJob.create("20260703090010-kkkkkkkk", ownerId);
        completedJob.complete();

        when(analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(completedJob), pageRequest, 1));
        when(uploadedVideoRepository.findAllByJobIdIn(List.of(completedJob.getJobId())))
                .thenReturn(List.of(createUploadedVideo(completedJob.getJobId(), "pipeline-only.mp4")));
        when(filePathGenerator.generateFinalResultPath(completedJob.getJobId()))
                .thenReturn(Path.of("results", completedJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readJson(any(Path.class), eq(Map.class)))
                .thenReturn(Map.of(
                        "scoreSummary", Map.of(
                                "totalScore", 77,
                                "level", "B"
                        ),
                        "feedback", Map.of(
                                "generationMode", "MOCK",
                                "overall", "피드백"
                        ),
                        "visualAnalysis", Map.of(
                                "model", Map.of(
                                        "name", "-",
                                        "generationMode", "UNKNOWN"
                                )
                        ),
                        "pipeline", Map.of(
                                "videoLlmAnalysis", "video-llm-engine fallback mock",
                                "videoLlmGenerationMode", "FALLBACK"
                        )
                ));

        Page<ResultSummaryResponse> response = resultQueryService.getResultSummaries(ownerId, pageRequest);

        ResultSummaryResponse summary = response.getContent().get(0);
        assertThat(summary.visualAnalysis())
                .containsEntry("model", Map.of(
                        "name", "video-llm-engine fallback mock",
                        "version", "-",
                        "generationMode", "FALLBACK"
                ));
        assertThat(summary.pipeline())
                .containsEntry("videoLlmGenerationMode", "FALLBACK");
    }

    @Test
    void getResultSummariesUsesPipelineOpenAiMetadataWhenFeedbackIsPlaceholder() {
        Long ownerId = 1L;
        PageRequest pageRequest = PageRequest.of(0, 1);
        AnalysisJob completedJob = AnalysisJob.create("20260703090011-llllllll", ownerId);
        completedJob.complete();

        when(analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(completedJob), pageRequest, 1));
        when(uploadedVideoRepository.findAllByJobIdIn(List.of(completedJob.getJobId())))
                .thenReturn(List.of(createUploadedVideo(completedJob.getJobId(), "openai-pipeline.mp4")));
        when(filePathGenerator.generateFinalResultPath(completedJob.getJobId()))
                .thenReturn(Path.of("results", completedJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readJson(any(Path.class), eq(Map.class)))
                .thenReturn(Map.of(
                        "scoreSummary", Map.of(
                                "totalScore", 88,
                                "level", "A"
                        ),
                        "feedback", Map.of(
                                "generationMode", "UNKNOWN",
                                "model", "-",
                                "realApiUsed", false,
                                "fallbackReason", "-",
                                "overall", "피드백"
                        ),
                        "pipeline", Map.of(
                                "openAiGenerationMode", "REAL",
                                "openAiModel", "gpt-4.1-mini",
                                "openAiRealApiUsed", true,
                                "openAiFallbackReason", "-"
                        )
                ));

        Page<ResultSummaryResponse> response = resultQueryService.getResultSummaries(ownerId, pageRequest);

        ResultSummaryResponse summary = response.getContent().get(0);
        assertThat(summary.feedback())
                .containsEntry("generationMode", "REAL")
                .containsEntry("model", "gpt-4.1-mini")
                .containsEntry("realApiUsed", true)
                .containsEntry("fallbackReason", "-")
                .containsEntry("overall", "피드백");
        assertThat(summary.dataIssue()).isNull();
    }

    @Test
    void getResultSummariesStillFlagsMissingFeedbackTextEvenWhenPipelineOpenAiMetadataExists() {
        Long ownerId = 1L;
        PageRequest pageRequest = PageRequest.of(0, 1);
        AnalysisJob completedJob = AnalysisJob.create("20260703090012-mmmmmmmm", ownerId);
        completedJob.complete();

        when(analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(completedJob), pageRequest, 1));
        when(uploadedVideoRepository.findAllByJobIdIn(List.of(completedJob.getJobId())))
                .thenReturn(List.of(createUploadedVideo(completedJob.getJobId(), "missing-feedback.mp4")));
        when(filePathGenerator.generateFinalResultPath(completedJob.getJobId()))
                .thenReturn(Path.of("results", completedJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readJson(any(Path.class), eq(Map.class)))
                .thenReturn(Map.of(
                        "scoreSummary", Map.of(
                                "totalScore", 88,
                                "level", "A"
                        ),
                        "feedback", Map.of(
                                "generationMode", "UNKNOWN",
                                "overall", ""
                        ),
                        "pipeline", Map.of(
                                "openAiGenerationMode", "REAL",
                                "openAiModel", "gpt-4.1-mini",
                                "openAiRealApiUsed", true
                        )
                ));

        Page<ResultSummaryResponse> response = resultQueryService.getResultSummaries(ownerId, pageRequest);

        ResultSummaryResponse summary = response.getContent().get(0);
        assertThat(summary.feedback())
                .containsEntry("generationMode", "REAL")
                .containsEntry("model", "gpt-4.1-mini");
        assertThat(summary.dataIssue()).isEqualTo("RESULT_DATA_INCOMPLETE");
        assertThat(dataIssueCount("list", "RESULT_DATA_INCOMPLETE")).isEqualTo(1.0);
    }

    @Test
    void getFinalResultIncludesDataIssueWhenStoredResultIsIncomplete() {
        Long ownerId = 1L;
        AnalysisJob completedJob = AnalysisJob.create("20260703090007-hhhhhhhh", ownerId);
        completedJob.complete();

        when(analysisJobRepository.findByJobId(completedJob.getJobId()))
                .thenReturn(java.util.Optional.of(completedJob));
        when(filePathGenerator.generateFinalResultPath(completedJob.getJobId()))
                .thenReturn(Path.of("results", completedJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readJson(any(Path.class), eq(Map.class)))
                .thenReturn(Map.of(
                        "status", "COMPLETED",
                        "scoreSummary", Map.of("level", "-"),
                        "feedback", Map.of("generationMode", "UNKNOWN", "overall", "")
                ));

        AnalysisResultResponse response = resultQueryService.getFinalResult(completedJob.getJobId(), ownerId);

        assertThat(response.dataIssue()).isEqualTo("RESULT_DATA_INCOMPLETE");
        assertThat(response.dataIssueDescription()).contains("불완전");
        assertThat(dataIssueCount("detail", "RESULT_DATA_INCOMPLETE")).isEqualTo(1.0);
    }

    @Test
    void getFinalResultDoesNotExposeDataIssueForHealthyStoredResult() {
        Long ownerId = 1L;
        AnalysisJob completedJob = AnalysisJob.create("20260703090008-iiiiiiii", ownerId);
        completedJob.complete();

        when(analysisJobRepository.findByJobId(completedJob.getJobId()))
                .thenReturn(java.util.Optional.of(completedJob));
        when(filePathGenerator.generateFinalResultPath(completedJob.getJobId()))
                .thenReturn(Path.of("results", completedJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readJson(any(Path.class), eq(Map.class)))
                .thenReturn(Map.of(
                        "status", "COMPLETED",
                        "scoreSummary", Map.of("level", "GOOD"),
                        "feedback", Map.of("generationMode", "MOCK", "overall", "피드백")
                ));

        AnalysisResultResponse response = resultQueryService.getFinalResult(completedJob.getJobId(), ownerId);

        assertThat(response.dataIssue()).isNull();
        assertThat(response.dataIssueDescription()).isNull();
        verify(uploadedVideoRepository, never()).findByJobId(any(String.class));
    }

    private double dataIssueCount(String source, String issue) {
        return meterRegistry.get("result.data_issue")
                .tag("source", source)
                .tag("issue", issue)
                .counter()
                .count();
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
