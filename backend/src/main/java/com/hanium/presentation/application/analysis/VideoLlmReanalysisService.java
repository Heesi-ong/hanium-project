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
import com.hanium.presentation.global.config.UserRateLimiter;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.presentation.dto.response.AnalysisStatusResponse;
import com.hanium.presentation.presentation.dto.response.VideoLlmReanalysisResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.List;

@Service
public class VideoLlmReanalysisService {

    private static final List<AnalysisStatus> ACTIVE_STATUSES = List.of(
            AnalysisStatus.UPLOADED,
            AnalysisStatus.QUEUED,
            AnalysisStatus.BASIC_ANALYZING,
            AnalysisStatus.VIDEO_LLM_ANALYZING,
            AnalysisStatus.COMPACTING,
            AnalysisStatus.OPENAI_GENERATING,
            AnalysisStatus.MERGING_RESULT
    );

    private final AnalysisJobRepository analysisJobRepository;
    private final UploadedVideoRepository uploadedVideoRepository;
    private final VideoFileCommandService videoFileCommandService;
    private final AnalysisCommandService analysisCommandService;
    private final UserRateLimiter userRateLimiter;
    private final JobIdGenerator jobIdGenerator;

    public VideoLlmReanalysisService(
            AnalysisJobRepository analysisJobRepository,
            UploadedVideoRepository uploadedVideoRepository,
            VideoFileCommandService videoFileCommandService,
            AnalysisCommandService analysisCommandService,
            UserRateLimiter userRateLimiter,
            JobIdGenerator jobIdGenerator
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
        this.videoFileCommandService = videoFileCommandService;
        this.analysisCommandService = analysisCommandService;
        this.userRateLimiter = userRateLimiter;
        this.jobIdGenerator = jobIdGenerator;
    }

    @Transactional
    public VideoLlmReanalysisResponse requestReanalysis(
            String sourceJobId,
            Long ownerId,
            String idempotencyKey,
            boolean useOpenAi
    ) {
        AnalysisJob sourceJob = analysisJobRepository.findByJobIdForUpdate(sourceJobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        if (!ownerId.equals(sourceJob.getOwnerId())) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
        }

        String idempotencyKeyHash = sha256Hex(idempotencyKey);
        var existingRequest = analysisJobRepository
                .findByOwnerIdAndSourceJobIdAndAnalysisKindAndReanalysisIdempotencyKeyHash(
                        ownerId,
                        sourceJobId,
                        AnalysisKind.VIDEO_LLM_REANALYSIS,
                        idempotencyKeyHash
                );
        if (existingRequest.isPresent()) {
            return VideoLlmReanalysisResponse.reused(existingRequest.get());
        }

        validateSource(sourceJob);

        UploadedVideo videoAsset = uploadedVideoRepository.findByIdForUpdate(sourceJob.getVideoAssetId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VIDEO_SOURCE_EXPIRED));
        if (!videoFileCommandService.sourceExists(
                videoAsset.getJobId(),
                videoAsset.getStoredFilePath()
        )) {
            throw new BusinessException(ErrorCode.VIDEO_SOURCE_EXPIRED);
        }

        boolean activeReanalysisExists = analysisJobRepository
                .findFirstBySourceJobIdAndAnalysisKindAndStatusInOrderByCreatedAtDesc(
                        sourceJobId,
                        AnalysisKind.VIDEO_LLM_REANALYSIS,
                        ACTIVE_STATUSES
                )
                .isPresent();
        if (activeReanalysisExists) {
            throw new BusinessException(ErrorCode.VIDEO_LLM_REANALYSIS_ALREADY_ACTIVE);
        }

        validateBudgetAvailable(ownerId);

        AnalysisJob reanalysisJob = AnalysisJob.createVideoLlmReanalysis(
                jobIdGenerator.generate(),
                sourceJob,
                idempotencyKeyHash
        );
        AnalysisStatusResponse accepted = analysisCommandService.acceptVideoLlmReanalysis(
                reanalysisJob,
                useOpenAi
        );
        return VideoLlmReanalysisResponse.created(sourceJobId, accepted);
    }

    private void validateSource(AnalysisJob sourceJob) {
        if (sourceJob.getAnalysisKind() != AnalysisKind.STANDARD
                || !sourceJob.isCompleted()
                || sourceJob.getVideoLlmGenerationMode() != VideoLlmGenerationMode.FALLBACK) {
            throw new BusinessException(
                    ErrorCode.VIDEO_LLM_REANALYSIS_NOT_ALLOWED,
                    "완료된 STANDARD FALLBACK 결과만 실제 Video LLM으로 재분석할 수 있습니다."
            );
        }
        if (sourceJob.getVideoAssetId() == null) {
            throw new BusinessException(ErrorCode.VIDEO_SOURCE_EXPIRED);
        }
    }

    private void validateBudgetAvailable(Long ownerId) {
        if (!userRateLimiter.wouldAllow("video-llm-daily", ownerId)
                || !userRateLimiter.wouldAllow("video-llm-monthly", YearMonth.now().toString())) {
            throw new BusinessException(ErrorCode.VIDEO_LLM_USAGE_LIMIT_EXCEEDED);
        }
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
