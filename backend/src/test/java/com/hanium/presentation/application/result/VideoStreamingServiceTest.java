package com.hanium.presentation.application.result;

import com.hanium.presentation.application.video.VideoFileCommandService;
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
    private final VideoFileCommandService videoFileCommandService = mock(VideoFileCommandService.class);

    private final VideoStreamingService videoStreamingService = new VideoStreamingService(
            analysisJobRepository,
            uploadedVideoRepository,
            videoAccessTokenProvider,
            videoFileCommandService
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

    @Test
    void resolveVideoUsesLinkedAssetBeforeLegacyJobIdLookup() throws Exception {
        Path videoPath = tempDir.resolve("shared-original.mp4");
        java.nio.file.Files.writeString(videoPath, "video-bytes");
        AnalysisJob reanalysisJob = AnalysisJob.create(JOB_ID, OWNER_ID);
        reanalysisJob.linkVideoAsset(99L);
        UploadedVideo sharedAsset = UploadedVideo.create(
                "20260707090000-source01",
                "shared-original.mp4",
                videoPath.toString(),
                VideoFileType.MP4,
                10L
        );

        when(videoAccessTokenProvider.validate("valid-token", JOB_ID))
                .thenReturn(Optional.of(new VideoAccessTokenProvider.VideoAccessClaims(JOB_ID, OWNER_ID)));
        when(analysisJobRepository.findByJobId(JOB_ID)).thenReturn(Optional.of(reanalysisJob));
        when(uploadedVideoRepository.findById(99L)).thenReturn(Optional.of(sharedAsset));

        assertThat(videoStreamingService.resolveVideoForStreaming(JOB_ID, "valid-token"))
                .isSameAs(sharedAsset);
    }

    @Test
    void resolvePresignedStreamingUrlDelegatesToVideoFileCommandService() {
        UploadedVideo uploadedVideo = UploadedVideo.create(
                JOB_ID,
                "original.mp4",
                tempDir.resolve("original.mp4").toString(),
                VideoFileType.MP4,
                10L
        );

        when(videoFileCommandService.resolveStreamingUrl(JOB_ID, uploadedVideo.getStoredFilePath()))
                .thenReturn("https://minio.local/hanium-storage/uploads/" + JOB_ID + "/original.mp4?X-Amz-Signature=abc");

        String url = videoStreamingService.resolvePresignedStreamingUrl(JOB_ID, uploadedVideo);

        assertThat(url).isEqualTo("https://minio.local/hanium-storage/uploads/" + JOB_ID + "/original.mp4?X-Amz-Signature=abc");
    }

    @Test
    void resolvePresignedStreamingUrlReturnsNullWhenVideoFileCommandServiceReturnsNull() {
        UploadedVideo uploadedVideo = UploadedVideo.create(
                JOB_ID,
                "original.mp4",
                tempDir.resolve("original.mp4").toString(),
                VideoFileType.MP4,
                10L
        );

        when(videoFileCommandService.resolveStreamingUrl(JOB_ID, uploadedVideo.getStoredFilePath()))
                .thenReturn(null);

        String url = videoStreamingService.resolvePresignedStreamingUrl(JOB_ID, uploadedVideo);

        assertThat(url).isNull();
    }

    @Test
    void resolvePresignedStreamingUrlUsesAssetStorageNamespace() {
        String sourceJobId = "20260707090000-source01";
        UploadedVideo sharedAsset = UploadedVideo.create(
                sourceJobId,
                "original.mp4",
                tempDir.resolve("original.mp4").toString(),
                VideoFileType.MP4,
                10L
        );
        when(videoFileCommandService.resolveStreamingUrl(
                sourceJobId,
                sharedAsset.getStoredFilePath()
        )).thenReturn("https://minio.local/shared");

        String url = videoStreamingService.resolvePresignedStreamingUrl(JOB_ID, sharedAsset);

        assertThat(url).isEqualTo("https://minio.local/shared");
    }
}
