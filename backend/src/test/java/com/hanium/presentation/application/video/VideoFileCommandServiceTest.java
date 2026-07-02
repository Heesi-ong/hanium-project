package com.hanium.presentation.application.video;

import com.hanium.presentation.application.video.dto.StoredVideoInfo;
import com.hanium.presentation.application.video.dto.VideoUploadCommand;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.StorageProperties;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.LocalFileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoFileCommandServiceTest {

    @TempDir
    private Path tempDir;

    private VideoFileCommandService videoFileCommandService;

    @BeforeEach
    void setUp() {
        StorageProperties storageProperties = new StorageProperties(
                tempDir.toString(),
                tempDir.resolve("uploads").toString(),
                tempDir.resolve("results").toString(),
                tempDir.resolve("temp").toString(),
                tempDir.resolve("logs").toString()
        );

        videoFileCommandService = new VideoFileCommandService(
                new LocalFileStorage(),
                new FilePathGenerator(storageProperties)
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
    void storeRejectsInvalidAviAndMkvSignaturesBeforeSaving() {
        assertInvalidUpload("job-fake-avi", "fake.avi", mp4Header());
        assertInvalidUpload("job-fake-mkv", "fake.mkv", aviHeader());
    }

    private void assertValidUpload(
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
