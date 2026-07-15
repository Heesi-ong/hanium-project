package com.hanium.presentation.application.video;

import com.hanium.presentation.application.video.dto.StoredVideoInfo;
import com.hanium.presentation.application.video.dto.VideoUploadCommand;
import com.hanium.presentation.domain.video.type.VideoFileType;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.StorageProperties;
import com.hanium.presentation.global.properties.VideoProperties;
import com.hanium.presentation.infrastructure.storage.FilePathGenerator;
import com.hanium.presentation.infrastructure.storage.LocalFileStorage;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
import com.hanium.presentation.infrastructure.video.VideoDurationProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

@Service
public class VideoFileCommandService {

    private static final Logger log = LoggerFactory.getLogger(VideoFileCommandService.class);

    private final LocalFileStorage localFileStorage;
    private final FilePathGenerator filePathGenerator;
    private final StorageProperties storageProperties;
    private final VideoDurationProbe videoDurationProbe;
    private final VideoProperties videoProperties;
    private final ObjectStorage objectStorage;

    public VideoFileCommandService(
            LocalFileStorage localFileStorage,
            FilePathGenerator filePathGenerator,
            StorageProperties storageProperties,
            VideoDurationProbe videoDurationProbe,
            VideoProperties videoProperties,
            ObjectStorage objectStorage
    ) {
        this.localFileStorage = localFileStorage;
        this.filePathGenerator = filePathGenerator;
        this.storageProperties = storageProperties;
        this.videoDurationProbe = videoDurationProbe;
        this.videoProperties = videoProperties;
        this.objectStorage = objectStorage;
    }

    public StoredVideoInfo store(VideoUploadCommand command) {
        MultipartFile file = command.file();

        validateFile(file);

        String originalFileName = file.getOriginalFilename();
        String extension = extractExtension(originalFileName);
        VideoFileType fileType = VideoFileType.fromExtension(extension);

        Path storedPath = filePathGenerator.generateOriginalVideoPath(
                command.jobId(),
                extension
        );

        localFileStorage.saveFile(file, storedPath);
        validateVideoDuration(storedPath);
        mirrorToObjectStorage(command.jobId(), extension, storedPath, file);

        return new StoredVideoInfo(
                originalFileName,
                storedPath.toString(),
                fileType,
                file.getSize()
        );
    }

    /**
     * 로컬 디스크에 저장된 원본 영상을 오브젝트 스토리지(MinIO)에도 미러링합니다.
     * 아직 어떤 서비스도 이 사본을 실제로 읽지 않으므로(Phase C에서 전환 예정),
     * 이 미러링은 best-effort입니다 - 실패해도 업로드 자체는 그대로 성공 처리합니다.
     */
    private void mirrorToObjectStorage(String jobId, String extension, Path storedPath, MultipartFile file) {
        String objectKey = "uploads/" + jobId + "/original" + extension;
        String contentType = Objects.requireNonNullElse(file.getContentType(), "application/octet-stream");

        try (InputStream inputStream = Files.newInputStream(storedPath)) {
            objectStorage.putObject(objectKey, inputStream, file.getSize(), contentType);
        } catch (IOException | RuntimeException exception) {
            log.warn(
                    "OBJECT_STORAGE_MIRROR_FAILED jobId={} objectKey={} reason={}",
                    jobId,
                    objectKey,
                    exception.toString()
            );
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "업로드할 영상 파일이 없습니다."
            );
        }

        String originalFileName = file.getOriginalFilename();

        if (originalFileName == null || originalFileName.isBlank()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "파일 이름이 올바르지 않습니다."
            );
        }

        String extension = extractExtension(originalFileName);

        if (!VideoFileType.isSupported(extension)) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "지원하지 않는 영상 파일 형식입니다. mp4, mov, avi, mkv 파일만 업로드할 수 있습니다."
            );
        }

        VideoFileType fileType = VideoFileType.fromExtension(extension);
        validateFileSignature(file, fileType);
        validateAvailableStorageSpace(file);
    }

    private void validateVideoDuration(Path storedPath) {
        videoDurationProbe.probe(storedPath).ifPresent(duration -> {
            long maxDurationSeconds = videoProperties.maxDurationMinutes() * 60L;

            if (duration.toSeconds() > maxDurationSeconds) {
                localFileStorage.deleteFileIfExists(storedPath);
                throw new BusinessException(
                        ErrorCode.VIDEO_DURATION_EXCEEDED,
                        "허용된 최대 재생 시간(" + videoProperties.maxDurationMinutes() + "분)을 초과했습니다."
                );
            }
        });
    }

    private void validateAvailableStorageSpace(MultipartFile file) {
        try {
            Path rootPath = Path.of(storageProperties.rootPath());
            long usableBytes = Files.getFileStore(rootPath).getUsableSpace();
            long minFreeBytes = storageProperties.minFreeSpaceMb() * 1024L * 1024L;

            if (usableBytes - file.getSize() < minFreeBytes) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_STORAGE_SPACE);
            }
        } catch (IOException e) {
            throw new BusinessException(
                    ErrorCode.FILE_UPLOAD_FAILED,
                    "저장 공간 확인에 실패했습니다."
            );
        }
    }

    private void validateFileSignature(MultipartFile file, VideoFileType fileType) {
        byte[] header = readHeader(file);

        if (!VideoSignatureValidator.matches(fileType, header)) {
            throw new BusinessException(
                    ErrorCode.UNSUPPORTED_FILE_CONTENT,
                    "파일 내용이 확장자와 일치하지 않습니다."
            );
        }
    }

    private byte[] readHeader(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return inputStream.readNBytes(VideoSignatureValidator.HEADER_BYTES_TO_READ);
        } catch (IOException e) {
            throw new BusinessException(
                    ErrorCode.FILE_UPLOAD_FAILED,
                    "파일 내용을 확인할 수 없습니다."
            );
        }
    }

    private String extractExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf(".");

        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "파일 확장자가 없습니다."
            );
        }

        return fileName.substring(lastDotIndex).toLowerCase();
    }
}
