package com.hanium.presentation.application.result;

import com.hanium.presentation.common.util.JsonMapSupport;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
import com.hanium.presentation.domain.analysis.type.VideoLlmGenerationMode;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.JsonFileStorage;
import com.hanium.presentation.presentation.dto.response.AnalysisResultResponse;
import com.hanium.presentation.presentation.dto.response.FeedbackSummary;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        when(jsonFileStorage.readObjectMap(any(Path.class)))
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
    void getResultSummariesResolvesSharedVideoAssetByLinkedIdWithoutJobIdFallback() {
        Long ownerId = 1L;
        PageRequest pageRequest = PageRequest.of(0, 1);
        AnalysisJob reanalysisJob = AnalysisJob.create("20260703090020-rean0001", ownerId);
        reanalysisJob.linkVideoAsset(99L);
        UploadedVideo sharedAsset = mock(UploadedVideo.class);

        when(sharedAsset.getId()).thenReturn(99L);
        when(sharedAsset.getOriginalFileName()).thenReturn("shared-source.mp4");
        when(sharedAsset.getFileSize()).thenReturn(1024L);
        when(analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(reanalysisJob), pageRequest, 1));
        when(uploadedVideoRepository.findAllById(Set.of(99L)))
                .thenReturn(List.of(sharedAsset));
        when(filePathGenerator.generateFinalResultPath(reanalysisJob.getJobId()))
                .thenReturn(Path.of("results", reanalysisJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readObjectMap(any(Path.class)))
                .thenReturn(Map.of());

        Page<ResultSummaryResponse> response = resultQueryService.getResultSummaries(ownerId, pageRequest);

        assertThat(response.getContent()).singleElement()
                .extracting(ResultSummaryResponse::originalFileName)
                .isEqualTo("shared-source.mp4");
        verify(uploadedVideoRepository).findAllById(Set.of(99L));
        verify(uploadedVideoRepository, never()).findAllByJobIdIn(any());
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
        when(jsonFileStorage.readObjectMap(any(Path.class)))
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
        when(jsonFileStorage.readObjectMap(any(Path.class)))
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
        when(jsonFileStorage.readObjectMap(any(Path.class)))
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
        when(jsonFileStorage.readObjectMap(any(Path.class)))
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
        when(jsonFileStorage.readObjectMap(any(Path.class)))
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
        when(jsonFileStorage.readObjectMap(any(Path.class)))
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
        when(jsonFileStorage.readObjectMap(any(Path.class)))
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
        assertThat(summary.feedback().generationMode()).isEqualTo("REAL");
        assertThat(summary.feedback().model()).isEqualTo("gpt-4.1-mini");
        assertThat(summary.feedback().realApiUsed()).isTrue();
        assertThat(summary.feedback().fallbackReason()).isEqualTo("-");
        assertThat(summary.feedback().overall()).isEqualTo("피드백");
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
        when(jsonFileStorage.readObjectMap(any(Path.class)))
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
        assertThat(summary.feedback().generationMode()).isEqualTo("REAL");
        assertThat(summary.feedback().model()).isEqualTo("gpt-4.1-mini");
        assertThat(summary.dataIssue()).isEqualTo("RESULT_DATA_INCOMPLETE");
        assertThat(dataIssueCount("list", "RESULT_DATA_INCOMPLETE")).isEqualTo(1.0);
    }

    // ResultMergeService.createFeedback()이 저장한 strengths/improvements가 이전에는 응답
    // 정규화 과정에서 조용히 빠져, 실제로 생성됐어도 결과 화면에는 항상 "표시할 강점/개선점이
    // 없습니다"로만 보였다(2026-07-23 발견).
    @Test
    void getResultSummariesIncludesStrengthsAndImprovementsFromStoredFeedback() {
        Long ownerId = 1L;
        PageRequest pageRequest = PageRequest.of(0, 1);
        AnalysisJob completedJob = AnalysisJob.create("20260703090013-nnnnnnnn", ownerId);
        completedJob.complete();

        when(analysisJobRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(completedJob), pageRequest, 1));
        when(uploadedVideoRepository.findAllByJobIdIn(List.of(completedJob.getJobId())))
                .thenReturn(List.of(createUploadedVideo(completedJob.getJobId(), "strengths.mp4")));
        when(filePathGenerator.generateFinalResultPath(completedJob.getJobId()))
                .thenReturn(Path.of("results", completedJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readObjectMap(any(Path.class)))
                .thenReturn(Map.of(
                        "scoreSummary", Map.of("totalScore", 88, "level", "A"),
                        "feedback", Map.of(
                                "generationMode", "REAL",
                                "model", "gpt-4.1-mini",
                                "realApiUsed", true,
                                "fallbackReason", "-",
                                "overall", "피드백",
                                "strengths", List.of("자세가 안정적입니다."),
                                "improvements", List.of("시선 처리를 개선하세요.")
                        )
                ));

        Page<ResultSummaryResponse> response = resultQueryService.getResultSummaries(ownerId, pageRequest);

        ResultSummaryResponse summary = response.getContent().get(0);
        assertThat(summary.feedback().strengths()).containsExactly("자세가 안정적입니다.");
        assertThat(summary.feedback().improvements()).containsExactly("시선 처리를 개선하세요.");
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
        when(jsonFileStorage.readObjectMap(any(Path.class)))
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
    void getFinalResultMarksCompletedJobWithMissingResultFileAsUnavailable() {
        Long ownerId = 1L;
        AnalysisJob completedJob = AnalysisJob.create("20260703090014-oooooooo", ownerId);
        completedJob.complete();

        when(analysisJobRepository.findByJobId(completedJob.getJobId()))
                .thenReturn(java.util.Optional.of(completedJob));
        when(filePathGenerator.generateFinalResultPath(completedJob.getJobId()))
                .thenReturn(Path.of("results", completedJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readObjectMap(any(Path.class)))
                .thenThrow(new com.hanium.presentation.global.exception.BusinessException(
                        com.hanium.presentation.global.exception.ErrorCode.FILE_NOT_FOUND
                ));

        AnalysisResultResponse response = resultQueryService.getFinalResult(completedJob.getJobId(), ownerId);

        assertThat(response.dataIssue()).isEqualTo("RESULT_DATA_UNAVAILABLE");
        assertThat(response.dataIssueDescription()).contains("결과 파일");
        assertThat(response.result()).containsKeys("scoreSummary", "feedback", "visualAnalysis", "pipeline");
        assertThat(dataIssueCount("detail", "RESULT_DATA_UNAVAILABLE")).isEqualTo(1.0);
    }

    @Test
    void getFinalResultMarksCompletedJobWithEmptyResultAsUnavailable() {
        Long ownerId = 1L;
        AnalysisJob completedJob = AnalysisJob.create("20260703090015-pppppppp", ownerId);
        completedJob.complete();

        when(analysisJobRepository.findByJobId(completedJob.getJobId()))
                .thenReturn(java.util.Optional.of(completedJob));
        when(filePathGenerator.generateFinalResultPath(completedJob.getJobId()))
                .thenReturn(Path.of("results", completedJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readObjectMap(any(Path.class)))
                .thenReturn(Map.of());

        AnalysisResultResponse response = resultQueryService.getFinalResult(completedJob.getJobId(), ownerId);

        assertThat(response.dataIssue()).isEqualTo("RESULT_DATA_UNAVAILABLE");
        assertThat(response.dataIssueDescription()).contains("결과 파일");
        assertThat(dataIssueCount("detail", "RESULT_DATA_UNAVAILABLE")).isEqualTo(1.0);
    }

    @Test
    void getFinalResultReturnsStatusShellWhenQueuedJobHasNoResultFileYet() {
        Long ownerId = 1L;
        AnalysisJob queuedJob = AnalysisJob.create("20260703090016-qqqqqqqq", ownerId);
        queuedJob.enqueue(false, false);

        when(analysisJobRepository.findByJobId(queuedJob.getJobId()))
                .thenReturn(java.util.Optional.of(queuedJob));
        when(filePathGenerator.generateFinalResultPath(queuedJob.getJobId()))
                .thenReturn(Path.of("results", queuedJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readObjectMap(any(Path.class)))
                .thenThrow(new com.hanium.presentation.global.exception.BusinessException(
                        com.hanium.presentation.global.exception.ErrorCode.FILE_NOT_FOUND
                ));

        AnalysisResultResponse response = resultQueryService.getFinalResult(queuedJob.getJobId(), ownerId);

        assertThat(response.result())
                .containsEntry("status", "QUEUED")
                .containsEntry("failReason", null);
        assertThat(response.analysisKind()).isEqualTo(AnalysisKind.STANDARD);
        assertThat(response.dataIssue()).isNull();
    }

    @Test
    void getFinalResultExposesStoredGenerationModeAndLatestReanalysisLineage() {
        Long ownerId = 1L;
        AnalysisJob sourceJob = AnalysisJob.create("20260703090017-rrrrrrrr", ownerId);
        sourceJob.linkVideoAsset(77L);
        sourceJob.recordVideoLlmGenerationMode(VideoLlmGenerationMode.FALLBACK);
        sourceJob.complete();
        AnalysisJob reanalysisJob = AnalysisJob.createVideoLlmReanalysis(
                "20260703090018-ssssssss",
                sourceJob,
                "a".repeat(64)
        );

        when(analysisJobRepository.findByJobId(sourceJob.getJobId()))
                .thenReturn(java.util.Optional.of(sourceJob));
        when(analysisJobRepository.findFirstBySourceJobIdAndAnalysisKindOrderByCreatedAtDesc(
                sourceJob.getJobId(),
                AnalysisKind.VIDEO_LLM_REANALYSIS
        )).thenReturn(java.util.Optional.of(reanalysisJob));
        when(filePathGenerator.generateFinalResultPath(sourceJob.getJobId()))
                .thenReturn(Path.of("results", sourceJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readObjectMap(any(Path.class)))
                .thenReturn(Map.of(
                        "status", "COMPLETED",
                        "scoreSummary", Map.of("level", "GOOD"),
                        "feedback", Map.of("generationMode", "REAL", "overall", "피드백")
                ));

        AnalysisResultResponse response = resultQueryService.getFinalResult(sourceJob.getJobId(), ownerId);

        assertThat(response.analysisKind()).isEqualTo(AnalysisKind.STANDARD);
        assertThat(response.sourceJobId()).isNull();
        assertThat(response.latestReanalysisJobId()).isEqualTo(reanalysisJob.getJobId());
        assertThat(response.videoLlmGenerationMode()).isEqualTo(VideoLlmGenerationMode.FALLBACK);
    }

    @Test
    void getFinalResultNormalizesGenerationMetadataWithPipelineFallbacks() {
        Long ownerId = 1L;
        AnalysisJob completedJob = AnalysisJob.create("20260703090013-nnnnnnnn", ownerId);
        completedJob.complete();

        when(analysisJobRepository.findByJobId(completedJob.getJobId()))
                .thenReturn(java.util.Optional.of(completedJob));
        when(filePathGenerator.generateFinalResultPath(completedJob.getJobId()))
                .thenReturn(Path.of("results", completedJob.getJobId(), "final-result.json"));
        when(jsonFileStorage.readObjectMap(any(Path.class)))
                .thenReturn(Map.of(
                        "status", "COMPLETED",
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
                        "visualAnalysis", Map.of(
                                "model", Map.of(
                                        "name", "-",
                                        "generationMode", "UNKNOWN"
                                ),
                                "observations", Map.of(
                                        "eyeContact", List.of(Map.of("label", "sample"))
                                )
                        ),
                        "pipeline", Map.of(
                                "openAiGenerationMode", "REAL",
                                "openAiModel", "gpt-4.1-mini",
                                "openAiRealApiUsed", true,
                                "openAiFallbackReason", "-",
                                "videoLlmAnalysis", "video-llm-engine fallback mock",
                                "videoLlmGenerationMode", "FALLBACK"
                        )
                ));

        AnalysisResultResponse response = resultQueryService.getFinalResult(completedJob.getJobId(), ownerId);

        FeedbackSummary feedback = response.result().get("feedback") instanceof FeedbackSummary typed
                ? typed
                : FeedbackSummary.unknown();
        Map<String, Object> visualAnalysis = JsonMapSupport.copyStringKeyedMap(
                response.result().get("visualAnalysis")
        );
        Map<String, Object> visualModel = JsonMapSupport.copyStringKeyedMap(
                visualAnalysis.get("model")
        );

        assertThat(feedback.generationMode()).isEqualTo("REAL");
        assertThat(feedback.model()).isEqualTo("gpt-4.1-mini");
        assertThat(feedback.realApiUsed()).isTrue();
        assertThat(feedback.overall()).isEqualTo("피드백");
        assertThat(visualModel)
                .containsEntry("name", "video-llm-engine fallback mock")
                .containsEntry("generationMode", "FALLBACK");
        assertThat(visualAnalysis).containsKey("observations");
        assertThat(response.dataIssue()).isNull();
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
        when(jsonFileStorage.readObjectMap(any(Path.class)))
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
