package com.hanium.presentation.application.result;

import com.hanium.presentation.domain.analysis.entity.AnalysisJob;
import com.hanium.presentation.domain.analysis.repository.AnalysisJobRepository;
import com.hanium.presentation.domain.video.entity.UploadedVideo;
import com.hanium.presentation.domain.video.repository.UploadedVideoRepository;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.config.VideoAccessTokenProvider;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoStreamingServiceTest {

    private static final String JOB_ID = "20260707091000-aaaaaaaa";
    private static final Long OWNER_ID = 1L;
    private static final Long OTHER_OWNER_ID = 2L;

    private final AnalysisJobRepository analysisJobRepository = mock(AnalysisJobRepository.class);
    private final UploadedVideoRepository uploadedVideoRepository = mock(UploadedVideoRepository.class);
    private final VideoAccessTokenProvider videoAccessTokenProvider = mock(VideoAccessTokenProvider.class);

    private final VideoStreamingService videoStreamingService = new VideoStreamingService(
            analysisJobRepository,
            uploadedVideoRepository,
            videoAccessTokenProvider
    );

    @TempDir
    Path tempDir;

    @Test
    void issueAccessTokenRejectsNonOwner() {
        when(analysisJobRepository.findByJobId(JOB_ID))
                .thenReturn(Optional.of(AnalysisJob.create(JOB_ID, OWNER_ID)));

        assertThatThrownBy(() -> videoStreamingService.issueAccessToken(JOB_ID, OTHER_OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
    }

    @Test
    void issueAccessTokenRejectsUnknownJobId() {
        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> videoStreamingService.issueAccessToken(JOB_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ANALYSIS_JOB_NOT_FOUND);
    }

    @Test
    void resolveVideoRejectsInvalidAccessToken() {
        when(videoAccessTokenProvider.validate("invalid-token", JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> videoStreamingService.resolveVideoForStreaming(JOB_ID, "invalid-token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ANALYSIS_JOB_ACCESS_DENIED);
    }

    @Test
    void resolveVideoRejectsMissingUploadedVideoRecord() {
        when(videoAccessTokenProvider.validate("valid-token", JOB_ID))
                .thenReturn(Optional.of(new VideoAccessTokenProvider.VideoAccessClaims(JOB_ID, OWNER_ID)));
        when(uploadedVideoRepository.findByJobId(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> videoStreamingService.resolveVideoForStreaming(JOB_ID, "valid-token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);
    }

    @Test
    void resolveVideoRejectsDeletedVideoFile() {
        UploadedVideo uploadedVideo = UploadedVideo.create(
                JOB_ID,
                "original.mp4",
                tempDir.resolve("deleted.mp4").toString(),
                VideoFileType.MP4,
                1024L
        );

        when(videoAccessTokenProvider.validate("valid-token", JOB_ID))
                .thenReturn(Optional.of(new VideoAccessTokenProvider.VideoAccessClaims(JOB_ID, OWNER_ID)));
        when(uploadedVideoRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(uploadedVideo));

        assertThatThrownBy(() -> videoStreamingService.resolveVideoForStreaming(JOB_ID, "valid-token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);
    }

    @Test
    void resolveVideoReturnsExistingUploadedVideo() throws Exception {
        Path videoPath = tempDir.resolve("original.mp4");
        java.nio.file.Files.writeString(videoPath, "video-bytes");
        UploadedVideo uploadedVideo = UploadedVideo.create(
                JOB_ID,
                "original.mp4",
                videoPath.toString(),
                VideoFileType.MP4,
                10L
        );

        when(videoAccessTokenProvider.validate("valid-token", JOB_ID))
                .thenReturn(Optional.of(new VideoAccessTokenProvider.VideoAccessClaims(JOB_ID, OWNER_ID)));
        when(uploadedVideoRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(uploadedVideo));

        assertThat(videoStreamingService.resolveVideoForStreaming(JOB_ID, "valid-token"))
                .isSameAs(uploadedVideo);
    }
}
