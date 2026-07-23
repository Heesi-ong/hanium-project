package com.hanium.presentation.application.analysis;

import com.hanium.presentation.application.video.VideoFileCommandService;
import com.hanium.presentation.common.util.JobIdGenerator;
import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.analysis.type.AnalysisKind;
import com.hanium.presentation.domain.analysis.type.AnalysisStatus;
import com.hanium.presentation.domain.analysis.type.VideoLlmGenerationMode;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.presentation.dto.response.AnalysisStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoLlmReanalysisServiceTest {

    private static final String SOURCE_JOB_ID = "20260723120000-aaaaaaaa";
    private static final String CHILD_JOB_ID = "20260723120100-bbbbbbbb";
    private static final String IDEMPOTENCY_KEY = "reanalysis-request-0001";
    private static final Long OWNER_ID = 1L;
    private static final Long ASSET_ID = 10L;

    private AnalysisJobRepository analysisJobRepository;
    private UploadedVideoRepository uploadedVideoRepository;
    private VideoFileCommandService videoFileCommandService;
    private AnalysisCommandService analysisCommandService;
    private UserRateLimiter userRateLimiter;
    private VideoLlmReanalysisService service;
    private AnalysisJob sourceJob;
    private UploadedVideo videoAsset;

    @BeforeEach
    void setUp() {
        analysisJobRepository = mock(AnalysisJobRepository.class);
        uploadedVideoRepository = mock(UploadedVideoRepository.class);
        videoFileCommandService = mock(VideoFileCommandService.class);
        analysisCommandService = mock(AnalysisCommandService.class);
        userRateLimiter = mock(UserRateLimiter.class);
        JobIdGenerator jobIdGenerator = mock(JobIdGenerator.class);

        sourceJob = completedFallbackSource();
        videoAsset = UploadedVideo.create(
                SOURCE_JOB_ID,
                "original.mp4",
                "/storage/uploads/" + SOURCE_JOB_ID + "/original.mp4",
                VideoFileType.MP4,
                10L
        );
        ReflectionTestUtils.setField(videoAsset, "id", ASSET_ID);

        when(analysisJobRepository.findByJobIdForUpdate(SOURCE_JOB_ID))
                .thenReturn(Optional.of(sourceJob));
        when(analysisJobRepository
                .findByOwnerIdAndSourceJobIdAndAnalysisKindAndReanalysisIdempotencyKeyHash(
                        eq(OWNER_ID),
                        eq(SOURCE_JOB_ID),
                        eq(AnalysisKind.VIDEO_LLM_REANALYSIS),
                        anyString()
                ))
                .thenReturn(Optional.empty());
        when(analysisJobRepository
                .findFirstBySourceJobIdAndAnalysisKindAndStatusInOrderByCreatedAtDesc(
                        eq(SOURCE_JOB_ID),
                        eq(AnalysisKind.VIDEO_LLM_REANALYSIS),
                        any()
                ))
                .thenReturn(Optional.empty());
        when(uploadedVideoRepository.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(videoAsset));
        when(videoFileCommandService.sourceExists(SOURCE_JOB_ID, videoAsset.getStoredFilePath()))
                .thenReturn(true);
        when(userRateLimiter.wouldAllow(eq("video-llm-daily"), eq(OWNER_ID))).thenReturn(true);
        when(userRateLimiter.wouldAllow(eq("video-llm-monthly"), anyString())).thenReturn(true);
        when(jobIdGenerator.generate()).thenReturn(CHILD_JOB_ID);
        when(analysisCommandService.acceptVideoLlmReanalysis(any(AnalysisJob.class), eq(true)))
                .thenAnswer(invocation -> {
                    AnalysisJob child = invocation.getArgument(0);
                    child.enqueue(true, true);
                    return AnalysisStatusResponse.from(child);
                });

        service = new VideoLlmReanalysisService(
                analysisJobRepository,
                uploadedVideoRepository,
                videoFileCommandService,
                analysisCommandService,
                userRateLimiter,
                jobIdGenerator
        );
    }

    @Test
    void createsQueuedChildThatSharesTheSourceAsset() {
        var response = service.requestReanalysis(
                SOURCE_JOB_ID,
                OWNER_ID,
                IDEMPOTENCY_KEY,
                true
        );

        assertThat(response.sourceJobId()).isEqualTo(SOURCE_JOB_ID);
        assertThat(response.reanalysisJobId()).isEqualTo(CHILD_JOB_ID);
        assertThat(response.status()).isEqualTo(AnalysisStatus.QUEUED);
        assertThat(response.reused()).isFalse();
        verify(analysisCommandService).acceptVideoLlmReanalysis(
                org.mockito.ArgumentMatchers.argThat(child ->
                        child.getAnalysisKind() == AnalysisKind.VIDEO_LLM_REANALYSIS
                                && SOURCE_JOB_ID.equals(child.getSourceJobId())
                                && ASSET_ID.equals(child.getVideoAssetId())
                                && child.getReanalysisIdempotencyKeyHash().matches("[0-9a-f]{64}")
                ),
                eq(true)
        );
    }

    @Test
    void reusesExistingChildForTheSameIdempotencyKey() {
        AnalysisJob existingChild = AnalysisJob.createVideoLlmReanalysis(
                CHILD_JOB_ID,
                sourceJob,
                sha256Hex(IDEMPOTENCY_KEY)
        );
        existingChild.enqueue(true, true);
        when(analysisJobRepository
                .findByOwnerIdAndSourceJobIdAndAnalysisKindAndReanalysisIdempotencyKeyHash(
                        OWNER_ID,
                        SOURCE_JOB_ID,
                        AnalysisKind.VIDEO_LLM_REANALYSIS,
                        sha256Hex(IDEMPOTENCY_KEY)
                ))
                .thenReturn(Optional.of(existingChild));

        var response = service.requestReanalysis(
                SOURCE_JOB_ID,
                OWNER_ID,
                IDEMPOTENCY_KEY,
                true
        );

        assertThat(response.reused()).isTrue();
        assertThat(response.reanalysisJobId()).isEqualTo(CHILD_JOB_ID);
        verify(analysisCommandService, never()).acceptVideoLlmReanalysis(any(), anyBoolean());
        verify(uploadedVideoRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void rejectsNonOwnerBeforeRevealingReanalysisState() {
        assertBusinessError(
                () -> service.requestReanalysis(SOURCE_JOB_ID, 2L, IDEMPOTENCY_KEY, true),
                ErrorCode.ANALYSIS_JOB_ACCESS_DENIED
        );
        verify(uploadedVideoRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void rejectsSourceThatIsNotFallback() {
        sourceJob.recordVideoLlmGenerationMode(VideoLlmGenerationMode.REAL);

        assertBusinessError(
                () -> service.requestReanalysis(SOURCE_JOB_ID, OWNER_ID, IDEMPOTENCY_KEY, true),
                ErrorCode.VIDEO_LLM_REANALYSIS_NOT_ALLOWED
        );
    }

    @Test
    void returnsGoneWhenVideoAssetHasExpired() {
        when(uploadedVideoRepository.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.empty());

        assertBusinessError(
                () -> service.requestReanalysis(SOURCE_JOB_ID, OWNER_ID, IDEMPOTENCY_KEY, true),
                ErrorCode.VIDEO_SOURCE_EXPIRED
        );
    }

    @Test
    void rejectsWhenAnotherReanalysisIsActive() {
        AnalysisJob activeChild = AnalysisJob.createVideoLlmReanalysis(
                CHILD_JOB_ID,
                sourceJob,
                "b".repeat(64)
        );
        activeChild.enqueue(true, true);
        when(analysisJobRepository
                .findFirstBySourceJobIdAndAnalysisKindAndStatusInOrderByCreatedAtDesc(
                        eq(SOURCE_JOB_ID),
                        eq(AnalysisKind.VIDEO_LLM_REANALYSIS),
                        any()
                ))
                .thenReturn(Optional.of(activeChild));

        assertBusinessError(
                () -> service.requestReanalysis(SOURCE_JOB_ID, OWNER_ID, IDEMPOTENCY_KEY, true),
                ErrorCode.VIDEO_LLM_REANALYSIS_ALREADY_ACTIVE
        );
    }

    @Test
    void rejectsBeforeCreatingChildWhenDailyBudgetIsExhausted() {
        when(userRateLimiter.wouldAllow("video-llm-daily", OWNER_ID)).thenReturn(false);

        assertBusinessError(
                () -> service.requestReanalysis(SOURCE_JOB_ID, OWNER_ID, IDEMPOTENCY_KEY, true),
                ErrorCode.VIDEO_LLM_USAGE_LIMIT_EXCEEDED
        );
        verify(analysisCommandService, never()).acceptVideoLlmReanalysis(any(), anyBoolean());
    }

    private AnalysisJob completedFallbackSource() {
        AnalysisJob job = AnalysisJob.create(SOURCE_JOB_ID, OWNER_ID);
        job.linkVideoAsset(ASSET_ID);
        job.startBasicAnalysis();
        job.complete();
        job.recordVideoLlmGenerationMode(VideoLlmGenerationMode.FALLBACK);
        return job;
    }

    private void assertBusinessError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(errorCode);
    }

    private String sha256Hex(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
