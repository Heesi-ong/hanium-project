package com.hanium.presentation.application.result;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.global.config.VideoAccessTokenProvider;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
@Transactional(readOnly = true)
public class VideoStreamingService {

    private final AnalysisJobRepository analysisJobRepository;
    private final UploadedVideoRepository uploadedVideoRepository;
    private final VideoAccessTokenProvider videoAccessTokenProvider;

    public VideoStreamingService(
            AnalysisJobRepository analysisJobRepository,
            UploadedVideoRepository uploadedVideoRepository,
            VideoAccessTokenProvider videoAccessTokenProvider
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
        this.videoAccessTokenProvider = videoAccessTokenProvider;
    }

    public String issueAccessToken(String jobId, Long ownerId) {
        AnalysisJob analysisJob = analysisJobRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_JOB_NOT_FOUND));

        if (!ownerId.equals(analysisJob.getOwnerId())) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
        }

        return videoAccessTokenProvider.issueToken(jobId, ownerId);
    }

    public UploadedVideo resolveVideoForStreaming(String jobId, String accessToken) {
        if (videoAccessTokenProvider.validate(accessToken, jobId).isEmpty()) {
            throw new BusinessException(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
        }

        UploadedVideo uploadedVideo = uploadedVideoRepository.findByJobId(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));

        if (!Files.exists(Path.of(uploadedVideo.getStoredFilePath()))) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }

        return uploadedVideo;
    }
}
