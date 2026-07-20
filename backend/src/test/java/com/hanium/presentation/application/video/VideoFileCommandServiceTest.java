package com.hanium.presentation.application.video;

import com.hanium.presentation.application.video.dto.StoredVideoInfo;
import com.hanium.presentation.application.video.dto.VideoUploadCommand;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.StorageProperties;
import com.hanium.presentation.global.properties.ObjectStoragePolicyProperties;
import com.hanium.presentation.global.properties.VideoProperties;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.LocalFileStorage;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
import com.hanium.presentation.infrastructure.video.VideoDurationProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoFileCommandServiceTest {

    @TempDir
    private Path tempDir;

    private VideoFileCommandService videoFileCommandService;
    private ObjectStorage objectStorage;

    @BeforeEach
    void setUp() {
        objectStorage = mock(ObjectStorage.class);
        videoFileCommandService = createService(0L);
    }

    private VideoFileCommandService createService(Long minFreeSpaceMb) {
        return createService(minFreeSpaceMb, videoPath -> Optional.of(Duration.ofMinutes(1)));
    }

    private VideoFileCommandService createService(Long minFreeSpaceMb, VideoDurationProbe videoDurationProbe) {
        return createService(minFreeSpaceMb, videoDurationProbe, false);
    }

    private VideoFileCommandService createService(
            Long minFreeSpaceMb,
            VideoDurationProbe videoDurationProbe,
            boolean objectStorageWriteRequired
    ) {
        StorageProperties storageProperties = new StorageProperties(
                tempDir.toString(),
                tempDir.resolve("uploads").toString(),
                tempDir.resolve("results").toString(),
                tempDir.resolve("temp").toString(),
                tempDir.resolve("logs").toString(),
                minFreeSpaceMb
        );
        VideoProperties videoProperties = new VideoProperties(30L, false);

        return new VideoFileCommandService(
                new LocalFileStorage(),
                new FilePathGenerator(storageProperties),
                storageProperties,
                videoDurationProbe,
                videoProperties,
                objectStorage,
                new ObjectStoragePolicyProperties(objectStorageWriteRequired, false)
        );
    }

    @Test
    void storeAllowsSupportedVideoSignaturesAndSavesFileAfterHeaderRead() {
        assertValidUpload("job-mp4", "video.mp4", VideoFileType.MP4, mp4Header());
        assertValidUpload("job-mov", "video.mov", VideoFileType.MOV, movHeader());
        assertValidUpload("job-avi", "video.avi", VideoFileType.AVI, aviHeader());
        assertValidUpload("job-mkv", "video.mkv", VideoFileType.MKV, mkvHeader());
    }

    @Test
    void storeRejectsTextFileRenamedToMp4BeforeSaving() {
        assertInvalidUpload(
                "job-fake-mp4",
                "fake.mp4",
                "plain text, not a video".getBytes(StandardCharsets.UTF_8)
        );
    }

    @Test
    void storeSucceedsWhenAvailableStorageSpaceIsSufficient() {
        videoFileCommandService = createService(0L);

        assertValidUpload("job-space-ok", "video.mp4", VideoFileType.MP4, mp4Header());
    }

    @Test
    void storeRejectsUploadWhenAvailableStorageSpaceIsInsufficient() {
        // 1 PB(페타바이트) 여유 공간을 요구해 어떤 디스크에서도 '공간 부족' 상황을 재현합니다.
        videoFileCommandService = createService(1024L * 1024L * 1024L);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "video.mp4",
                "video/mp4",
                mp4Header()
        );

        assertThatThrownBy(() -> videoFileCommandService.store(new VideoUploadCommand("job-no-space", file)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INSUFFICIENT_STORAGE_SPACE);
        assertThat(Files.exists(tempDir.resolve("uploads").resolve("job-no-space"))).isFalse();
    }

    @Test
    void storeRejectsInvalidAviAndMkvSignaturesBeforeSaving() {
        assertInvalidUpload("job-fake-avi", "fake.avi", mp4Header());
        assertInvalidUpload("job-fake-mkv", "fake.mkv", aviHeader());
    }

    @Test
    void storeRejectsVideoWhenDurationExceedsLimitAndDeletesSavedFile() {
        videoFileCommandService = createService(0L, videoPath -> Optional.of(Duration.ofHours(2)));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "video.mp4",
                "video/mp4",
                mp4Header()
        );

        assertThatThrownBy(() -> videoFileCommandService.store(new VideoUploadCommand("job-too-long", file)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.VIDEO_DURATION_EXCEEDED);

        Path storedPath = tempDir.resolve("uploads").resolve("job-too-long").resolve("original.mp4");
        assertThat(Files.exists(storedPath)).isFalse();
    }

    @Test
    void storeAllowsUploadWhenDurationProbeFailsOpen() {
        videoFileCommandService = createService(0L, videoPath -> Optional.empty());

        assertValidUpload("job-probe-failed", "video.mp4", VideoFileType.MP4, mp4Header());
    }

    @Test
    void storeRejectsUploadWhenDurationProbeIsRequiredAndFails() {
        StorageProperties storageProperties = new StorageProperties(
                tempDir.toString(),
                tempDir.resolve("uploads").toString(),
                tempDir.resolve("results").toString(),
                tempDir.resolve("temp").toString(),
                tempDir.resolve("logs").toString(),
                0L
        );
        videoFileCommandService = new VideoFileCommandService(
                new LocalFileStorage(),
                new FilePathGenerator(storageProperties),
                storageProperties,
                videoPath -> Optional.empty(),
                new VideoProperties(30L, true),
                objectStorage,
                new ObjectStoragePolicyProperties(false, false)
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "video.mp4",
                "video/mp4",
                mp4Header()
        );

        assertThatThrownBy(() -> videoFileCommandService.store(new VideoUploadCommand("job-probe-required", file)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_UPLOAD_FAILED);

        assertThat(Files.exists(tempDir.resolve("uploads/job-probe-required/original.mp4"))).isFalse();
        verify(objectStorage, never()).putObject(any(), any(), anyLong(), any());
    }

    @Test
    void storeMirrorsUploadedVideoToObjectStorage() {
        byte[] content = mp4Header();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "video.mp4",
                "video/mp4",
                content
        );

        videoFileCommandService.store(new VideoUploadCommand("job-mirror", file));

        verify(objectStorage).putObject(
                eq("uploads/job-mirror/original.mp4"),
                any(InputStream.class),
                eq((long) content.length),
                eq("video/mp4")
        );
    }

    @Test
    void storeSucceedsEvenWhenObjectStorageMirrorFails() {
        doThrow(new RuntimeException("minio down"))
                .when(objectStorage)
                .putObject(any(), any(), anyLong(), any());

        StoredVideoInfo storedVideoInfo = assertValidUpload(
                "job-mirror-fail",
                "video.mp4",
                VideoFileType.MP4,
                mp4Header()
        );

        assertThat(storedVideoInfo).isNotNull();
    }

    @Test
    void storeFailsAndDeletesLocalFileWhenObjectStorageWriteIsRequired() {
        videoFileCommandService = createService(
                0L,
                videoPath -> Optional.of(Duration.ofMinutes(1)),
                true
        );
        doThrow(new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "minio down"))
                .when(objectStorage)
                .putObject(any(), any(), anyLong(), any());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "video.mp4",
                "video/mp4",
                mp4Header()
        );

        assertThatThrownBy(() -> videoFileCommandService.store(new VideoUploadCommand("job-strict", file)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_UPLOAD_FAILED);

        assertThat(Files.exists(tempDir.resolve("uploads/job-strict/original.mp4"))).isFalse();
    }

    @Test
    void resolveDownloadUrlReturnsPresignedUrlFromObjectStorage() {
        when(objectStorage.exists("uploads/job-download/original.mp4")).thenReturn(true);
        when(objectStorage.generatePresignedUrl(eq("uploads/job-download/original.mp4"), any()))
                .thenReturn("https://minio.local/hanium-storage/uploads/job-download/original.mp4?X-Amz-Signature=abc");

        String url = videoFileCommandService.resolveDownloadUrl(
                "job-download",
                tempDir.resolve("uploads").resolve("job-download").resolve("original.mp4").toString()
        );

        assertThat(url).isEqualTo("https://minio.local/hanium-storage/uploads/job-download/original.mp4?X-Amz-Signature=abc");
    }

    @Test
    void resolveDownloadUrlReturnsNullWithoutGeneratingUrlWhenObjectDoesNotExist() {
        when(objectStorage.exists("uploads/job-download-missing/original.mp4")).thenReturn(false);

        String url = videoFileCommandService.resolveDownloadUrl(
                "job-download-missing",
                tempDir.resolve("uploads").resolve("job-download-missing").resolve("original.mp4").toString()
        );

        assertThat(url).isNull();
        verify(objectStorage, never()).generatePresignedUrl(any(), any());
    }

    @Test
    void resolveDownloadUrlReturnsNullWhenObjectStorageFails() {
        when(objectStorage.exists("uploads/job-download-fail/original.mp4")).thenReturn(true);
        when(objectStorage.generatePresignedUrl(any(), any()))
                .thenThrow(new RuntimeException("minio down"));

        String url = videoFileCommandService.resolveDownloadUrl(
                "job-download-fail",
                tempDir.resolve("uploads").resolve("job-download-fail").resolve("original.mp4").toString()
        );

        assertThat(url).isNull();
    }

    @Test
    void resolveStreamingUrlReturnsPresignedUrlWhenObjectExists() {
        when(objectStorage.exists("uploads/job-stream/original.mp4")).thenReturn(true);
        when(objectStorage.generatePublicPresignedUrl(eq("uploads/job-stream/original.mp4"), any()))
                .thenReturn("https://minio.local/hanium-storage/uploads/job-stream/original.mp4?X-Amz-Signature=abc");

        String url = videoFileCommandService.resolveStreamingUrl(
                "job-stream",
                tempDir.resolve("uploads").resolve("job-stream").resolve("original.mp4").toString()
        );

        assertThat(url).isEqualTo("https://minio.local/hanium-storage/uploads/job-stream/original.mp4?X-Amz-Signature=abc");
    }

    // 재생 URL 만료는 고정값이 아니라 "허용된 최대 영상 길이(VideoProperties.maxDurationMinutes) +
    // 재생 여유분"으로 계산됩니다. 이 테스트 설정의 maxDurationMinutes=30이므로 30+30=60분이
    // 기대값입니다 — 하드코딩된 1시간으로 되돌아가는 회귀를 잡기 위한 테스트입니다.
    @Test
    void resolveStreamingUrlExpirationScalesWithConfiguredMaxVideoDuration() {
        when(objectStorage.exists("uploads/job-stream-expiry/original.mp4")).thenReturn(true);
        when(objectStorage.generatePublicPresignedUrl(eq("uploads/job-stream-expiry/original.mp4"), any()))
                .thenReturn("https://minio.local/presigned");

        videoFileCommandService.resolveStreamingUrl(
                "job-stream-expiry",
                tempDir.resolve("uploads").resolve("job-stream-expiry").resolve("original.mp4").toString()
        );

        verify(objectStorage).generatePublicPresignedUrl(
                eq("uploads/job-stream-expiry/original.mp4"),
                eq(Duration.ofMinutes(60))
        );
    }

    @Test
    void resolveStreamingUrlReturnsNullWhenObjectDoesNotExist() {
        when(objectStorage.exists("uploads/job-stream-missing/original.mp4")).thenReturn(false);

        String url = videoFileCommandService.resolveStreamingUrl(
                "job-stream-missing",
                tempDir.resolve("uploads").resolve("job-stream-missing").resolve("original.mp4").toString()
        );

        assertThat(url).isNull();
        verify(objectStorage, never()).generatePublicPresignedUrl(any(), any());
    }

    @Test
    void resolveStreamingUrlReturnsNullWhenExistsCheckFails() {
        when(objectStorage.exists(any())).thenThrow(new RuntimeException("minio down"));

        String url = videoFileCommandService.resolveStreamingUrl(
                "job-stream-error",
                tempDir.resolve("uploads").resolve("job-stream-error").resolve("original.mp4").toString()
        );

        assertThat(url).isNull();
    }

    private StoredVideoInfo assertValidUpload(
            String jobId,
            String fileName,
            VideoFileType expectedFileType,
            byte[] content
    ) {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                fileName,
                "video/" + expectedFileType.name().toLowerCase(),
                content
        );

        StoredVideoInfo storedVideoInfo = videoFileCommandService.store(new VideoUploadCommand(jobId, file));

        Path storedPath = Path.of(storedVideoInfo.storedFilePath());
        assertThat(storedVideoInfo.fileType()).isEqualTo(expectedFileType);
        assertThat(storedVideoInfo.fileSize()).isEqualTo((long) content.length);
        assertThat(Files.exists(storedPath)).isTrue();
        assertThat(Files.exists(tempDir.resolve("uploads").resolve(jobId))).isTrue();

        return storedVideoInfo;
    }

    private void assertInvalidUpload(
            String jobId,
            String fileName,
            byte[] content
    ) {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                fileName,
                "video/mp4",
                content
        );

        assertThatThrownBy(() -> videoFileCommandService.store(new VideoUploadCommand(jobId, file)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_CONTENT);
        assertThat(Files.exists(tempDir.resolve("uploads").resolve(jobId))).isFalse();
    }

    private byte[] mp4Header() {
        return new byte[]{
                0x00, 0x00, 0x00, 0x18,
                'f', 't', 'y', 'p',
                'i', 's', 'o', 'm',
                0x00, 0x00, 0x00, 0x00
        };
    }

    private byte[] movHeader() {
        return new byte[]{
                0x00, 0x00, 0x00, 0x14,
                'f', 't', 'y', 'p',
                'q', 't', ' ', ' ',
                0x00, 0x00, 0x00, 0x00
        };
    }

    private byte[] aviHeader() {
        return new byte[]{
                'R', 'I', 'F', 'F',
                0x24, 0x00, 0x00, 0x00,
                'A', 'V', 'I', ' ',
                0x00, 0x00, 0x00, 0x00
        };
    }

    private byte[] mkvHeader() {
        return new byte[]{
                0x1A, 0x45, (byte) 0xDF, (byte) 0xA3,
                0x42, (byte) 0x86, (byte) 0x81, 0x01,
                0x42, (byte) 0xF7, (byte) 0x81, 0x01
        };
    }
}
