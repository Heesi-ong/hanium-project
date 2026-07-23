package com.hanium.presentation.application.result;

import com.hanium.presentation.application.video.VideoFileCommandService;
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
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class VideoStreamingService {

    private final AnalysisJobRepository analysisJobRepository;
    private final UploadedVideoRepository uploadedVideoRepository;
    private final VideoAccessTokenProvider videoAccessTokenProvider;
    private final VideoFileCommandService videoFileCommandService;

    public VideoStreamingService(
            AnalysisJobRepository analysisJobRepository,
            UploadedVideoRepository uploadedVideoRepository,
            VideoAccessTokenProvider videoAccessTokenProvider,
            VideoFileCommandService videoFileCommandService
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.uploadedVideoRepository = uploadedVideoRepository;
        this.videoAccessTokenProvider = videoAccessTokenProvider;
        this.videoFileCommandService = videoFileCommandService;
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

        Optional<UploadedVideo> linkedAsset = analysisJobRepository.findByJobId(jobId)
                .map(AnalysisJob::getVideoAssetId)
                .flatMap(uploadedVideoRepository::findById);
        UploadedVideo uploadedVideo = linkedAsset
                .or(() -> uploadedVideoRepository.findByJobId(jobId))
                .orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));

        if (!Files.exists(Path.of(uploadedVideo.getStoredFilePath()))) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }

        return uploadedVideo;
    }

    /**
     * MinIO에 미러링된 오브젝트가 있으면 브라우저가 바로 그쪽으로 재생하도록 presigned URL을
     * 반환합니다. 아직 미러링되지 않았거나 MinIO가 응답하지 않으면 null을 반환해 호출부(컨트롤러)가
     * 기존처럼 로컬 디스크 기반 Range 스트리밍으로 계속 동작하게 합니다.
     */
    public String resolvePresignedStreamingUrl(String jobId, UploadedVideo uploadedVideo) {
        return videoFileCommandService.resolveStreamingUrl(
                uploadedVideo.getJobId(),
                uploadedVideo.getStoredFilePath()
        );
    }
}
